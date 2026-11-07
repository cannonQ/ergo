package org.ergoplatform.network

import akka.actor.{ActorRef, Props}
import akka.testkit.TestProbe
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{Level, Logger}
import ch.qos.logback.core.read.ListAppender
import org.ergoplatform.network.ErgoNodeViewSynchronizerMessages._
import org.ergoplatform.network.message.{GetSnapshotsInfoSpec, Message, SnapshotsInfoSpec}
import org.ergoplatform.network.peer.PeerInfo
import org.ergoplatform.nodeView.ErgoNodeViewHolder
import org.ergoplatform.nodeView.history.{ErgoHistory, ErgoSyncInfoMessageSpec}
import org.ergoplatform.nodeView.mempool.ErgoMemPool
import org.ergoplatform.nodeView.state.{SnapshotsInfo, StateType, UtxoState}
import org.ergoplatform.nodeView.state.UtxoState.ManifestId
import org.ergoplatform.settings.{ErgoSettings, UtxoSettings}
import org.ergoplatform.wallet.utils.FileUtils
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import org.slf4j.LoggerFactory
import scorex.core.network.NetworkController.ReceivableMessages.{PenalizePeer, SendToNetwork}
import scorex.core.network.{ConnectedPeer, DeliveryTracker}
import scorex.crypto.hash.Digest32
import scorex.testkit.utils.AkkaFixture

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutor}

/**
  * Phase 2b-1 (hardening group, PASS-A CF-2) regression spec, v6.0.11 @ f758780e1.
  *
  * `processSnapshotsInfo` (ErgoNodeViewSynchronizer.scala:1018-1040) is reached from the message handler
  * (:1806-1807) for a `SnapshotsInfo` from any peer. It has no request check (there is no "requested" state for
  * snapshots info: `requestSnapshotsInfo` at :667-677 only sends) and is not gated on `utxoBootstrap` (only
  * `checkUtxoSetManifests`, called after the loop at :1039, is gated at :1575). For every (height, manifest id)
  * pair it does `hr.bestHeaderAtHeight(height)` (:1023) and, on mismatch, `log.error` (:1035), with no penalty.
  *
  * Desired behaviour asserted here: a `SnapshotsInfo` that the node did not ask for (no `GetSnapshotsInfo` sent),
  * in case A with `utxoBootstrap` on and in case B with it off, is either dropped before any header lookup
  * (no per-pair "Got wrong manifest id" log line) or answered with a `PenalizePeer` for the sender.
  *
  * Observables: (1) messages the synchronizer sends to the network-controller probe (PenalizePeer, SendToNetwork);
  * (2) whether the handler ran, read from a logback ListAppender on the synchronizer's logger, counting the
  * per-pair error line of :1035 (one line per mismatching pair means one lookup per pair). The fixture exposes no
  * mismatch counter, so the log is the handler-ran signal. Cost is not measured.
  *
  * RED at the pin means: no PenalizePeer and one error line per pair, in both cases.
  */
class SnapshotsInfoUnsolicitedSpec extends AnyPropSpec with Matchers with FileUtils {
  import SnapshotsInfoUnsolicitedSpec.Observed
  import org.ergoplatform.utils.ErgoNodeTestConstants._
  import org.ergoplatform.utils.generators.ConnectedPeerGenerators._
  import org.ergoplatform.utils.generators.ChainGenerator._
  import org.ergoplatform.utils.HistoryTestHelpers._

  private val Pairs = 8 // small message: 8 pairs, heights 1..8, all inside the 10-header test history

  // Same shape as ErgoNodeViewSynchronizerSpecification.InjectedReadersNodeViewHolder: answers the synchronizer's
  // start-up view request with the fixture's history and mempool.
  class InjectedReadersNodeViewHolder(injectedHistory: ErgoHistory, injectedMempool: ErgoMemPool)
    extends ErgoNodeViewHolder[UtxoState](settings) {
    override protected def getNodeViewChanges: Receive = {
      case request: ErgoNodeViewHolder.ReceivableMessages.GetNodeViewChanges =>
        if (request.history) sender() ! ChangedHistory(injectedHistory)
        super.getNodeViewChanges(request.copy(history = false, mempool = false))
        if (request.mempool) sender() ! ChangedMempool(injectedMempool)
    }
  }

  // Same shape as ErgoNodeViewSynchronizerSpecification.UtxoBootstrapSynchronizerFixture (headers-only history,
  // utxoBootstrap on or off). The synchronizer is the production class itself, not a subclass, so its logger is
  // the one named by classOf[ErgoNodeViewSynchronizer].
  class Fixture(utxoBootstrap: Boolean) extends AkkaFixture {
    implicit val ec: ExecutionContextExecutor = system.dispatcher
    val ncProbe = TestProbe("NetworkControllerProbe")
    val pchProbe = TestProbe("PeerHandlerProbe")
    val syncTracker = ErgoSyncTracker(settings.scorexSettings.network)
    val deliveryTracker: DeliveryTracker = DeliveryTracker.empty(settings)

    val synchronizerSettings: ErgoSettings = settings.copy(
      nodeSettings = settings.nodeSettings.copy(utxoSettings = UtxoSettings(utxoBootstrap, 0, 2))
    )

    val history: ErgoHistory = generateHistory(verifyTransactions = true, StateType.Utxo, PoPoWBootstrap = false,
      blocksToKeep = -1, utxoBootstrap = utxoBootstrap)
    val chain = genHeaderChain(BlocksInChain, history, diffBitsOpt = None, useRealTs = false)
    val updHistory: ErgoHistory = applyHeaderChain(history, chain)
    val mempool: ErgoMemPool = ErgoMemPool.empty(synchronizerSettings)

    deleteRecursive(ErgoHistory.historyDir(synchronizerSettings))

    val nodeViewHolderRef: ActorRef =
      system.actorOf(Props(new InjectedReadersNodeViewHolder(updHistory, mempool)))

    val synchronizerRef: ActorRef = system.actorOf(Props(
      new ErgoNodeViewSynchronizer(ncProbe.ref, nodeViewHolderRef, ErgoSyncInfoMessageSpec,
        synchronizerSettings, syncTracker, deliveryTracker)
    ))

    synchronizerRef ! ChangedHistory(updHistory)
    synchronizerRef ! ChangedMempool(mempool)

    @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
    val peer: ConnectedPeer = ConnectedPeer(connectionIdGen.sample.get, pchProbe.ref,
      Some(PeerInfo(defaultPeerSpec, System.currentTimeMillis())))
  }

  private def runCase(caseName: String, utxoBootstrap: Boolean, appender: ListAppender[ILoggingEvent]): Observed = {
    val f = new Fixture(utxoBootstrap)
    try {
      import f._
      val peerAddr = peer.connectionId.remoteAddress

      // Heights within the test history, random 32-byte ids (a match with our own state root is negligible).
      val manifests: Map[Int, ManifestId] = (1 to Pairs).map { h =>
        h -> (Digest32 @@ scorex.utils.Random.randomBytes(32))
      }.toMap
      require(manifests.keys.forall(h => h <= updHistory.headersHeight), "heights must be inside the test history")
      val bytes = SnapshotsInfoSpec.toBytes(new SnapshotsInfo(manifests))

      appender.list.clear()
      synchronizerRef ! Message(SnapshotsInfoSpec, Left(bytes), Some(peer))

      val sent = ncProbe.receiveWhile(max = 4.seconds, idle = 1500.millis) { case m => m }
      val penalties = sent.collect { case p: PenalizePeer if p.address == peerAddr => p }
      val stns = sent.collect { case s: SendToNetwork => s }
      val getInfoSent = stns.count(_.message.spec.messageCode == GetSnapshotsInfoSpec.messageCode)

      val wrongLines = appender.list.asScala.count { e =>
        val m = e.getFormattedMessage
        e.getLevel == Level.ERROR && m.contains("Got wrong manifest id") && m.contains(peerAddr.toString)
      }
      Observed(caseName, Pairs, getInfoSent, penalties,
        stns.map(s => s.message.spec.messageName), wrongLines)
    } finally {
      Await.result(f.system.terminate(), Duration.Inf)
    }
  }

  property("Unsolicited SnapshotsInfo is dropped before any header lookup or penalized " +
    "(utxoBootstrap on and off; RED at pin: no request check, no penalty)") {
    val logger = LoggerFactory.getLogger(classOf[ErgoNodeViewSynchronizer]).asInstanceOf[Logger]
    val previousLevel = logger.getLevel
    val appender = new ListAppender[ILoggingEvent]
    appender.start()
    logger.addAppender(appender)
    logger.setLevel(Level.INFO)
    val results = try {
      Seq(
        runCase("A: utxoBootstrap=on, no GetSnapshotsInfo sent", utxoBootstrap = true, appender),
        runCase("B: utxoBootstrap=off", utxoBootstrap = false, appender)
      )
    } finally {
      logger.detachAppender(appender)
      logger.setLevel(previousLevel)
      appender.stop()
    }

    // Characterization preconditions (must pass): the node never asked for snapshots info, so the message is
    // unsolicited; and the message was delivered and parsed (either a penalty or the handler's log is observed).
    results.foreach { r =>
      withClue(s"precondition: $r\n") {
        r.getSnapshotsInfoSent shouldBe 0
        (r.penalties.nonEmpty || r.wrongManifestLogLines > 0) shouldBe true
      }
    }

    // Desired behaviour (RED at pin).
    withClue(results.mkString("\n", "\n",
      "\nAn unsolicited SnapshotsInfo ran one bestHeaderAtHeight lookup + one error line per pair and got no " +
        "PenalizePeer: a request check (or utxoBootstrap gate) before the loop, or a penalty, is MISSING.\n")) {
      results.forall(_.desired) shouldBe true
    }
  }
}

object SnapshotsInfoUnsolicitedSpec {
  final case class Observed(caseName: String,
                            pairs: Int,
                            getSnapshotsInfoSent: Int,
                            penalties: Seq[PenalizePeer],
                            sendToNetwork: Seq[String],
                            wrongManifestLogLines: Int) {
    def desired: Boolean = penalties.nonEmpty || wrongManifestLogLines == 0
    override def toString: String =
      s"[$caseName] pairs=$pairs, GetSnapshotsInfo sent by node=$getSnapshotsInfoSent, " +
        s"PenalizePeer for sender=${penalties.size} ${penalties.map(_.penaltyType).mkString("(", ",", ")")}, " +
        s"SendToNetwork=${sendToNetwork.mkString("[", ",", "]")}, " +
        s"'Got wrong manifest id' error lines for sender=$wrongManifestLogLines"
  }
}
