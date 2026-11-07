package org.ergoplatform.network

// Settles F-4 / N-2 of REVIEW-C1 (merged tree only): GREEN = F-4 CONFIRMED (after an ENVS restart a stale view-holder
// reply releases the NEXT peer's ack while that peer's batch is still unacked); RED (timeout) = F-4 FALSIFIED.
import akka.actor.{ActorRef, ActorSystem, Cancellable, Props}
import akka.testkit.TestProbe
import org.ergoplatform.modifiers.history.header.Header
import org.ergoplatform.network.ErgoNodeViewSynchronizerMessages._
import org.ergoplatform.network.message._
import org.ergoplatform.nodeView.ErgoNodeViewHolder.ReceivableMessages.{
  GetNodeViewChanges,
  ModifiersFromRemote,
  ModifiersFromRemoteProcessed
}
import org.ergoplatform.nodeView.history.ErgoSyncInfoMessageSpec
import org.ergoplatform.nodeView.mempool.ErgoMemPool
import org.ergoplatform.nodeView.state.StateType
import org.ergoplatform.network.peer.PeerInfo
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import scorex.core.network.NetworkController.ReceivableMessages.MessageProcessed
import scorex.core.network.{ConnectedPeer, DeliveryTracker}
import scorex.testkit.utils.AkkaFixture

import scala.concurrent.Await
import scala.concurrent.duration._

object EnvsRestartAckSpec {
  case object Boom

  class RestartableSynchronizer(nc: ActorRef,
                                vh: ActorRef,
                                settings: org.ergoplatform.settings.ErgoSettings,
                                syncTracker: ErgoSyncTracker,
                                deliveryTracker: DeliveryTracker)
                               (implicit ec: scala.concurrent.ExecutionContext)
    extends ErgoNodeViewSynchronizer(nc, vh, ErgoSyncInfoMessageSpec, settings, syncTracker, deliveryTracker)(ec) {

    // an exception escaping the message handler makes the default supervisor restart this actor
    override protected def processDataFromPeer(msgHandlers: PartialFunction[(MessageSpec[_], _, ConnectedPeer), Unit]): Receive = {
      val injected: Receive = {
        case Boom => throw new RuntimeException("injected failure to restart the synchronizer")
      }
      injected orElse super.processDataFromPeer(msgHandlers)
    }
  }
}

class EnvsRestartAckSpec extends AnyPropSpec with Matchers {
  import EnvsRestartAckSpec._
  import org.ergoplatform.utils.ErgoNodeTestConstants._
  import org.ergoplatform.utils.generators.ConnectedPeerGenerators._
  import org.ergoplatform.utils.generators.ChainGenerator._
  import org.ergoplatform.utils.HistoryTestHelpers._

  property("ENVS restart: a stale ModifiersFromRemoteProcessed releases the next peer's MessageProcessed early") {
    val fixture = new AkkaFixture {}
    try {
      implicit val system: ActorSystem = fixture.system
      implicit val ec: scala.concurrent.ExecutionContextExecutor = system.dispatcher
      val ncProbe = TestProbe("NCProbe")         // the synchronizer's networkControllerRef
      val ackSink = TestProbe("AckSink")         // plays the NetworkController that sends peer messages and gets the acks
      val vhProbe = TestProbe("VHProbe")
      val pchA = TestProbe("PCH-A")
      val pchB = TestProbe("PCH-B")
      val syncTracker = ErgoSyncTracker(settings.scorexSettings.network)
      val tracker = DeliveryTracker.empty(settings)

      val h = generateHistory(verifyTransactions = true, StateType.Utxo, PoPoWBootstrap = false, blocksToKeep = -1)
      val headers = genHeaderChain(3, h, diffBitsOpt = None, useRealTs = false).headers
      val mp = ErgoMemPool.empty(settings)

      val sync = system.actorOf(Props(new RestartableSynchronizer(ncProbe.ref, vhProbe.ref, settings, syncTracker, tracker)))
      vhProbe.expectMsgType[GetNodeViewChanges]
      sync ! ChangedHistory(h)
      sync ! ChangedMempool(mp)

      val info = PeerInfo(defaultPeerSpec, System.currentTimeMillis())
      val peerA = ConnectedPeer(connectionIdGen.sample.get, pchA.ref, Some(info))
      val peerB = ConnectedPeer(connectionIdGen.sample.get, pchB.ref, Some(info))

      def batchFrom(peer: ConnectedPeer, header: Header): Message[ModifiersData] = {
        tracker.setRequested(Header.modifierTypeId, header.id, peer)(_ => Cancellable.alreadyCancelled)
        val data = ModifiersData(Header.modifierTypeId, Map(header.id -> header.bytes))
        Message(ModifiersSpec, Left(ModifiersSpec.toBytes(data)), Some(peer))
      }

      // 1. peer A's requested header is dispatched to the view holder; the batch is left unacknowledged
      val msgA = batchFrom(peerA, headers(0))
      ackSink.send(sync, msgA)
      vhProbe.expectMsgType[ModifiersFromRemote]
      ackSink.expectNoMessage(300.millis)

      // 2. restart: preRestart releases A's parked ack (the replacement actor starts with an empty queue)
      sync ! Boom
      ackSink.expectMsg(3.seconds, MessageProcessed(peerA, msgA.messageLength))
      vhProbe.expectMsgType[GetNodeViewChanges](5.seconds)
      sync ! ChangedHistory(h)
      sync ! ChangedMempool(mp)

      // 3. peer B's batch is dispatched by the restarted actor and stays unacknowledged at the view holder
      val msgB = batchFrom(peerB, headers(1))
      ackSink.send(sync, msgB)
      vhProbe.expectMsgType[ModifiersFromRemote]
      ackSink.expectNoMessage(300.millis)

      // 4. the view holder's reply for A's PRE-restart batch arrives; B's batch is still being processed
      vhProbe.send(sync, ModifiersFromRemoteProcessed)
      // F-4 predicts that this releases B's credit already
      ackSink.expectMsg(3.seconds, MessageProcessed(peerB, msgB.messageLength))
    } finally {
      Await.result(fixture.system.terminate(), Duration.Inf)
    }
  }
}
