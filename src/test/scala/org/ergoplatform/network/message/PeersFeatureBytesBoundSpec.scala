package org.ergoplatform.network.message

import org.ergoplatform.network.{ApplicationVersionSerializer, HandshakeSerializer, Version}
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import scorex.util.ByteArrayBuilder
import scorex.util.serialization.VLQByteBufferWriter

/**
  * S-PEERS  (regression spec that asserts a MISSING per-PeerSpec byte cap inside a `Peers` message).
  *
  * CLAIM:  When a `PeerSpec` is parsed as part of a handshake, it is bounded to `HandshakeSerializer.maxHandshakeSize`
  *         = 8096 bytes (the handshake reader is built over `candidate.take(8096)` and
  *         `require(r.remaining <= 8096)`, HandshakeSerializer.scala:31). But inside a `Peers` message
  *         (`PeersSpec.parse`, BasicMessagesRepo.scala:54-59) each of the (up to `peersLimit`) PeerSpecs is parsed by
  *         the very same `PeerSpecSerializer.parse` with NO 8096-byte limit applied -- a PeerSpec is bounded only by
  *         the enclosing message frame. A single PeerSpec may carry up to 127 features of up to 32767 bytes each.
  *
  * REVIEW NOTE (Review A, F-10): the feature here has an unknown id, so its bytes are copied and discarded and the
  *         node holds about 1x the wire bytes, briefly. A retained-size story needs repeated known features and
  *         overlaps KNOWN #2449. `candidate.take(8096)` is at PeerConnectionHandler.scala:109; HandshakeSerializer.scala:31
  *         is only the `require`. A per-feature cap at or below 8096 or a per-PeerSpec check turns this green; a
  *         whole-body cap on Peers does not.
  *
  * DESIRED BOUND: a PeerSpec carried inside a `Peers` message that exceeds the handshake size limit (8096 bytes)
  *         should be rejected the same way it would be in the handshake path.
  *
  * RED means (pin f758780e1): a `Peers` message carrying ONE PeerSpec whose single feature holds 8097 bytes (one
  *         past the 8096 handshake limit) parses successfully (`Success`) -> the assertion that it is rejected fails
  *         -> RED, proving no per-PeerSpec byte cap exists inside Peers.
  * GREEN means: the over-limit PeerSpec is rejected (`Failure`) -> the bound exists.
  *
  * PAYLOAD SIZE / TIME: one Peers message with one PeerSpec of 8097 feature bytes ~= 8 KB on the wire; just one
  *         byte past the 8096 limit, a single frame, not filled. Parse is trivial; well under 1 s / 1 GB.
  */
class PeersFeatureBytesBoundSpec extends AnyPropSpec with Matchers {

  property("A PeerSpec inside Peers carrying more than maxHandshakeSize feature bytes should be rejected (RED at pin)") {
    val limit = HandshakeSerializer.maxHandshakeSize // 8096
    val featureBytes = limit + 1 // 8097 : one byte past the handshake limit

    // Build the Peers body by hand: UInt peer count = 1, then one PeerSpec whose single (unknown-id) feature carries
    // `featureBytes` bytes. Field order matches PeerSpecSerializer.parse: appName, version(3), nodeName,
    // declaredAddress option tag, featuresCount, then (featId, UShort featLen, feat bytes).
    val w = new VLQByteBufferWriter(new ByteArrayBuilder())
    w.putUInt(1L) // one PeerSpec in the Peers message
    w.putShortString("cq") // agentName (must be non-empty)
    ApplicationVersionSerializer.serialize(Version.initial, w) // 3 protocol-version bytes
    w.putShortString("n") // nodeName
    w.put(0: Byte) // declaredAddress Option tag = None
    w.put(1: Byte) // featuresCount = 1
    w.put(0x7F: Byte) // featureId = 127 (unknown serializer -> copied then ignored, PeerSpec still parses)
    w.putUShort(featureBytes) // UShort feature length = 8097
    w.putBytes(new Array[Byte](featureBytes)) // 8097 zero bytes of feature payload
    val payload = w.result().toBytes

    val parsed = new PeersSpec(peersLimit = 64).parseBytesTry(payload)

    // DESIRED: a PeerSpec exceeding the 8096-byte handshake limit is rejected inside Peers too. This fails at the
    // pin, where the PeerSpec is bounded only by the frame and parses cleanly -> RED.
    assert(
      parsed.isFailure,
      s"RED at pin f758780e1: a Peers message carrying one PeerSpec with $featureBytes feature bytes " +
        s"(> maxHandshakeSize=$limit) parsed successfully (${parsed.map(_.size)} peer(s)). Inside Peers a PeerSpec " +
        s"is bounded only by the frame; the 8096-byte handshake limit is NOT applied per PeerSpec."
    )
  }

}
