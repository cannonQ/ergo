package scorex.core.network

// Settles F-1 / N-1(a) of REVIEW-C1, both trees. The tree is detected by whether PeerConnectionHandler's
// InboundMessageProcessed exists. Merged: 17 header replies arrive in one Tcp.Received, the handler forwards 16 and
// pauses, the silent view holder never acks, so item 17 stays Requested and must be re-requested from peer B after
// the 1 s delivery timeout. Pin: all 17 are read at once, item 17 is Received and nothing is re-requested.
// GREEN on a tree = the review's prediction holds there; RED = F-1 is wrong (or the harness did not reach the state).
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.io.Tcp
import akka.testkit.{TestActorRef, TestProbe}
import akka.util.ByteString
import org.ergoplatform.modifiers.history.header.Header
import org.ergoplatform.network.ErgoNodeViewSynchronizerMessages.{ChangedHistory, ChangedMempool, HandshakedPeer}
import org.ergoplatform.network.message._
import org.ergoplatform.network.peer.PeerInfo
import org.ergoplatform.network.{ErgoNodeViewSynchronizer, ErgoSyncTracker, Handshake, HandshakeSerializer, ModePeerFeature}
import org.ergoplatform.nodeView.ErgoNodeViewHolder.ReceivableMessages.GetNodeViewChanges
import org.ergoplatform.nodeView.history.ErgoSyncInfoMessageSpec
import org.ergoplatform.nodeView.mempool.ErgoMemPool
import org.ergoplatform.nodeView.state.StateType
import org.ergoplatform.settings.ErgoSettings
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import scorex.core.app.ScorexContext
import scorex.core.network.NetworkController.ReceivableMessages.SendToNetwork
import scorex.testkit.utils.AkkaFixture
import scorex.util.ModifierId

import java.net.InetSocketAddress
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Try

object ReadPauseDeliveryTimeoutSpec {
  final case class RequestHeaders(ids: Seq[ModifierId], peer: ConnectedPeer)

  class RequestingSynchronizer(nc: ActorRef,
                               vh: ActorRef,
                               settings: ErgoSettings,
                               syncTracker: ErgoSyncTracker,
                               deliveryTracker: DeliveryTracker)
                              (implicit ec: scala.concurrent.ExecutionContext)
    extends ErgoNodeViewSynchronizer(nc, vh, ErgoSyncInfoMessageSpec, settings, syncTracker, deliveryTracker)(ec) {

    // lets the test make the real request path (requestBlockSection) run inside the actor
    override protected def processDataFromPeer(msgHandlers: PartialFunction[(MessageSpec[_], _, ConnectedPeer), Unit]): Receive = {
      val requests: Receive = {
        case RequestHeaders(ids, peer) => ids.foreach(id => requestBlockSection(Header.modifierTypeId, Seq(id), peer))
      }
      requests orElse super.processDataFromPeer(msgHandlers)
    }
  }
}

class ReadPauseDeliveryTimeoutSpec extends AnyPropSpec with Matchers {
  import ReadPauseDeliveryTimeoutSpec._
  import org.ergoplatform.network.peer.PeerManager.ReceivableMessages._
  import org.ergoplatform.utils.ErgoNodeTestConstants._
  import org.ergoplatform.utils.generators.ConnectedPeerGenerators._
  import org.ergoplatform.utils.generators.ChainGenerator._
  import org.ergoplatform.utils.HistoryTestHelpers._

  private val treeHasInboundCredits: Boolean = Try(
    Class.forName("scorex.core.network.PeerConnectionHandler$ReceivableMessages$InboundMessageProcessed")
  ).isSuccess

  property("a read pause does not stop delivery timeouts: item 17 of 17 queued replies") {
    val fixture = new AkkaFixture {}
    try {
      implicit val system: ActorSystem = fixture.system
      implicit val ec: scala.concurrent.ExecutionContextExecutor = system.dispatcher

      val testSettings = settings.copy(
        scorexSettings = settings.scorexSettings.copy(
          network = settings.scorexSettings.network.copy(deliveryTimeout = 1.second)
        )
      )
      val netCfg = testSettings.scorexSettings.network

      val ncProbe = TestProbe("SynchronizerNC")  // the synchronizer's networkControllerRef: sees its requests
      val vhProbe = TestProbe("SilentViewHolder")
      val syncTracker = ErgoSyncTracker(netCfg)
      val tracker = DeliveryTracker.empty(testSettings)

      val h = generateHistory(verifyTransactions = true, StateType.Utxo, PoPoWBootstrap = false, blocksToKeep = -1)
      val headers = genHeaderChain(17, h, diffBitsOpt = None, useRealTs = false).headers
      val mp = ErgoMemPool.empty(testSettings)

      val sync = system.actorOf(Props(new RequestingSynchronizer(ncProbe.ref, vhProbe.ref, testSettings, syncTracker, tracker)))
      vhProbe.expectMsgType[GetNodeViewChanges]
      sync ! ChangedHistory(h)
      sync ! ChangedMempool(mp)

      // real NetworkController; the synchronizer is the handler of Modifiers messages
      val peerManager = TestProbe("PeerManager")
      val tcpManager = TestProbe("TcpManager")
      val controller = TestActorRef(new NetworkController(
        testSettings,
        peerManager.ref,
        ScorexContext(Seq(ModifiersSpec), None, None),
        tcpManager.ref,
        _ => Map(ModifiersSpec.messageCode -> sync)
      ))
      tcpManager.expectMsgType[Tcp.Bind]
      controller ! Tcp.Bound(netCfg.bindAddress)

      // peer A: a real PeerConnectionHandler on a probe connection
      val events = TestProbe("Handshakes")
      system.eventStream.subscribe(events.ref, classOf[HandshakedPeer])
      val remoteA = new InetSocketAddress("203.0.113.60", 9060)
      val connectionA = TestProbe("ConnectionA")
      connectionA.send(controller, Tcp.Connected(remoteA, netCfg.bindAddress))
      peerManager.expectMsgPF(3.seconds) {
        case ConfirmConnection(_, handlerRef) =>
          controller ! ConnectionConfirmed(ConnectionId(remoteA, netCfg.bindAddress, Incoming), handlerRef)
      }
      val handlerA = connectionA.expectMsgType[Tcp.Register].handler
      connectionA.receiveWhile(500.millis) { case m => m }
      val handshake = HandshakeSerializer.toBytes(
        Handshake(defaultPeerSpec.copy(declaredAddress = Some(remoteA)), System.currentTimeMillis())
      )
      connectionA.send(handlerA, Tcp.Received(ByteString(handshake)))
      val peerA = events.expectMsgType[HandshakedPeer](3.seconds).remote
      peerA.handlerRef shouldBe handlerA
      connectionA.receiveWhile(500.millis) { case m => m }

      // peer B: the only Older peer able to serve headers, so a re-request has nowhere else to go
      val pchB = TestProbe("PCH-B")
      val specB = defaultPeerSpec.copy(features = Seq(
        ModePeerFeature(StateType.Utxo, true, None, ModePeerFeature.AllBlocksKept)
      ))
      val peerB = ConnectedPeer(connectionIdGen.sample.get, pchB.ref, Some(PeerInfo(specB, System.currentTimeMillis())))
      syncTracker.updateStatus(peerB, org.ergoplatform.consensus.Older, Some(1000))

      // 17 single-header requests to A, then drain the synchronizer's outgoing request messages
      sync ! RequestHeaders(headers.map(_.id), peerA)
      ncProbe.receiveWhile(400.millis) { case m => m }
      headers.foreach(hd => tracker.status(hd.id, Header.modifierTypeId, Seq.empty) shouldBe ModifiersStatus.Requested)

      // A answers all 17 requests in a single TCP read
      val serializer = new MessageSerializer(Seq(ModifiersSpec), netCfg.magicBytes)
      val frames = headers.map { hd =>
        serializer.serialize(Message(
          ModifiersSpec,
          Right(ModifiersData(Header.modifierTypeId, Map(hd.id -> hd.bytes))),
          None
        ))
      }.reduce(_ ++ _)
      connectionA.send(handlerA, Tcp.Received(frames))

      // observe for 1 s timeout + margin
      val sentAt = System.nanoTime()
      val deadline = sentAt + 3500.millis.toNanos
      val timed = Iterator.continually(ncProbe.receiveOne(100.millis)).takeWhile(_ => System.nanoTime() < deadline)
        .collect { case m if m != null => ((System.nanoTime() - sentAt) / 1000000, m) }.toList
      val timedRequests = timed.collect {
        case (at, SendToNetwork(msg, SendToPeer(to))) if msg.spec.messageCode == RequestModifierSpec.messageCode =>
          msg.input match {
            case Right(d: InvData) => (at, to, d.ids)
            case _ => (at, to, Seq.empty[ModifierId])
          }
      }
      val reRequests = timedRequests.map { case (_, to, ids) => (to, ids) }
      val firstReRequestMs = timedRequests.headOption.map(_._1)
      val connectionMsgs = connectionA.receiveWhile(300.millis) { case m => m }
      val item17 = headers.last.id
      val status17 = tracker.status(item17, Header.modifierTypeId, Seq.empty)
      info(s"observed: tree=${if (treeHasInboundCredits) "merged" else "pin"} status(item17)=$status17 " +
        s"reRequests=${reRequests.map { case (to, ids) => (if (to == peerB) "B" else if (to == peerA) "A" else "?") -> ids.size }} " +
        s"item17ReRequestedTo=${reRequests.filter(_._2.contains(item17)).map { case (to, _) => if (to == peerB) "B" else "other" }} " +
        s"firstReRequestMs=$firstReRequestMs allReRequestMs=${timedRequests.map(_._1)} " +
        s"connectionA=${connectionMsgs.map(_.getClass.getSimpleName)}")

      if (treeHasInboundCredits) {
        reRequests.exists { case (to, ids) => to == peerB && ids == Seq(item17) } shouldBe true
        reRequests.flatMap(_._2).toSet shouldBe Set(item17)
        // not before the 1 s delivery timeout of the request, and not long after it
        firstReRequestMs.exists(ms => ms >= 500 && ms <= 2000) shouldBe true
      } else {
        status17 shouldBe ModifiersStatus.Received
        reRequests shouldBe empty
      }
    } finally {
      Await.result(fixture.system.terminate(), Duration.Inf)
    }
  }
}
