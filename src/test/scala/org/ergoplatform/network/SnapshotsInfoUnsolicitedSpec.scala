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
import scorex.crypto.authds.avltree.batch.VersionedLDBAVLStorage.splitDigest
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
  * Fix shape (Review A, F-6): a bootstrap gate alone (`utxoBootstrap && fullBlockHeight == 0`) turns only case B
  * green; case A runs with utxoBootstrap on and a headers-only history, so it stays RED. The fix needs a per-peer
  * request marker set in `requestSnapshotsInfo` (:667-677) and checked first in `processSnapshotsInfo`.
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

  /*
   * 3-1 SnapshotsInfo growth variant (Review A F-4, register candidate SNAP-2), v6.0.11 @ f758780e1.
   *
   * Claim: a node that is not bootstrapping (utxoBootstrap off) and sent no GetSnapshotsInfo still records the
   * offers of an unsolicited SnapshotsInfo. In `processSnapshotsInfo` the comparison is
   * `hr.bestHeaderAtHeight(height).map(_.stateRoot).map(splitDigest(_)._1)` against the offered id (:1023-1024;
   * `splitDigest` = `digest.dropRight(1) -> digest.last`, VersionedLDBAVLStorage.scala:249-250, so the id is the
   * first 32 bytes of the 33-byte state root). On a match it logs INFO "Found new manifest <id> for height <h> at
   * <peer>" (:1029) and puts the offer into `availableManifests` (:1030). That loop is not gated on utxoBootstrap
   * (only `checkUtxoSetManifests`, :1039/:1575, is), and the map is cleared only at :1067 on a bootstrapping node.
   *
   * Input: one SnapshotsInfo, 8 pairs, heights 1..8, ids derived from the fixture history with the handler's own
   * expression (so they match). Time cap: the probe wait is 4 s max per message, well under 10 s.
   * Observable: "Found new manifest" INFO lines for the sender on the ListAppender (`availableManifests` is a
   * `private val` of the actor, created with system.actorOf, so it is not readable from the test; the log is the signal).
   * Anti-vacuous control in the same property: zero "Got wrong manifest id" lines for this message. If a derived id
   * did not match, the handler would log an error for it and the property fails on the control, not on the claim.
   *
   * RED at pin: Found-new-manifest lines > 0, the offers were recorded on a non-bootstrapping node.
   * GREEN: zero Found lines and zero wrong-id lines, the offers were not recorded (dropped, or never compared).
   */
  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  private def runGrowthCase(appender: ListAppender[ILoggingEvent]): (Int, Int, Int, Int, Int) = {
    val f = new Fixture(utxoBootstrap = false)
    try {
      import f._
      val peerAddr = peer.connectionId.remoteAddress
      val manifests: Map[Int, ManifestId] = (1 to Pairs).map { h =>
        h -> (Digest32 @@ splitDigest(updHistory.bestHeaderAtHeight(h).get.stateRoot)._1)
      }.toMap
      val distinctIds = manifests.values.map(id => scorex.util.encode.Base16.encode(id)).toSet.size
      val bytes = SnapshotsInfoSpec.toBytes(new SnapshotsInfo(manifests))

      appender.list.clear()
      synchronizerRef ! Message(SnapshotsInfoSpec, Left(bytes), Some(peer))
      val sent = ncProbe.receiveWhile(max = 4.seconds, idle = 1500.millis) { case m => m }
      val getInfoSent = sent.collect { case s: SendToNetwork => s }
        .count(_.message.spec.messageCode == GetSnapshotsInfoSpec.messageCode)

      def count(level: Level, text: String): Int = appender.list.asScala.count { e =>
        val m = e.getFormattedMessage
        e.getLevel == level && m.contains(text) && m.contains(peerAddr.toString)
      }
      (getInfoSent, distinctIds, count(Level.INFO, "Found new manifest"),
        count(Level.WARN, "Double manifest declaration"), count(Level.ERROR, "Got wrong manifest id"))
    } finally {
      Await.result(f.system.terminate(), Duration.Inf)
    }
  }

  property("3-1 growth: unsolicited SnapshotsInfo with matching ids is not recorded on a non-bootstrapping node " +
    "(utxoBootstrap off; RED at pin: offers recorded)") {
    val logger = LoggerFactory.getLogger(classOf[ErgoNodeViewSynchronizer]).asInstanceOf[Logger]
    val previousLevel = logger.getLevel
    val appender = new ListAppender[ILoggingEvent]
    appender.start()
    logger.addAppender(appender)
    logger.setLevel(Level.INFO)
    val (getInfoSent, distinctIds, foundLines, doubleLines, wrongLines) = try {
      runGrowthCase(appender)
    } finally {
      logger.detachAppender(appender)
      logger.setLevel(previousLevel)
      appender.stop()
    }
    val summary = s"[growth: utxoBootstrap=off, no GetSnapshotsInfo sent] pairs=$Pairs, distinct ids=$distinctIds, " +
      s"GetSnapshotsInfo sent by node=$getInfoSent, 'Found new manifest' lines=$foundLines, " +
      s"'Double manifest declaration' lines=$doubleLines, 'Got wrong manifest id' lines=$wrongLines"

    withClue(s"precondition/control: $summary\n") {
      getInfoSent shouldBe 0
      wrongLines shouldBe 0 // control: every derived id matched the handler's comparison (:1023-1024)
    }
    withClue(s"$summary\nAn unsolicited SnapshotsInfo with matching ids was recorded (availableManifests.put, :1030) " +
      "on a node with utxoBootstrap off: a bootstrap gate / request check before the loop is MISSING.\n") {
      foundLines shouldBe 0
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
