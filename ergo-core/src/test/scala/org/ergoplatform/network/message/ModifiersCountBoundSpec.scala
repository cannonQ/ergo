package org.ergoplatform.network.message

import org.ergoplatform.utils.ErgoCorePropertyTest
import scorex.util.ByteArrayBuilder
import scorex.util.serialization.VLQByteBufferWriter

/**
  * S-MOD  (regression spec that asserts a MISSING numeric element-count cap on the `Modifiers` message parser).
  *
  * CLAIM:  `ModifiersSpec.parse` (ModifiersSpec.scala:58-77) reads an entry `count` (:60) and then loops that many
  *         times, bounded only by the cumulative byte budget `maxMsgSizeWithReserve` (:69). There is NO numeric cap
  *         on the entry count itself. An honest peer only ever answers a `RequestModifier`, which is itself capped at
  *         `InvSpec.maxInvObjects` = 400 ids (InvSpec.scala:19,42 / RequestModifierSpec delegates to InvSpec). So a
  *         `Modifiers` message declaring more than 400 entries is never legitimate, yet the parser accepts it: with
  *         zero-length bodies up to ~256k entries fit under the 8 MB budget.
  *
  * REVIEW NOTE (Review A, F-5): unsolicited Modifiers entries of type 2 (transactions) draw no penalty
  *         (ErgoNodeViewSynchronizer.scala:939-946), so one connection can repeat this freely. The fix must be
  *         `require(count <= InvSpec.maxInvObjects)` only: a zero-count Modifiers message is a deliberate
  *         non-delivery signal (ModifiersSpec.scala:61-63) and must still parse, so do not copy InvSpec's
  *         `require(count > 0)`. Same loop as open PR #2795.
  *
  * DESIRED BOUND (what the one-line maintainer fix should add):
  *         `require(count <= InvSpec.maxInvObjects, ...)` (or an equivalent tight element cap) evaluated right after
  *         the count is read, before the entry loop.
  *
  * RED means (current code at pin f758780e1): `parseBytesTry` returns Success holding 401 entries -> the assertion
  *         below fails -> this spec is RED, proving the cap is absent.
  * GREEN means: the parser rejects the 401-entry message (Failure) -> the cap exists. This spec would then pass.
  *
  * PAYLOAD SIZE / TIME: one message, 401 distinct 32-byte ids with zero-length bodies ~= 13 KB on the wire; just
  *         one past the proposed 400 cap, never large. Parse is O(count); well under 1 s and far under 1 GB heap.
  */
class ModifiersCountBoundSpec extends ErgoCorePropertyTest {

  property("Modifiers parser should reject an entry count above InvSpec.maxInvObjects (RED at pin: no count cap)") {
    val typeId: Byte = 2 // any NetworkObjectTypeId byte; value is irrelevant to the count bound
    val count = InvSpec.maxInvObjects + 1 // 401 -- one past the only legitimate ceiling (the Inv/RequestModifier cap)

    // Build a structurally valid Modifiers body by hand: typeId, UInt count, then `count` (distinct id, zero-length body)
    // pairs. Distinct ids are required because the parser collects into a Map (duplicates would collapse).
    val w = new VLQByteBufferWriter(new ByteArrayBuilder())
    w.put(typeId)
    w.putUInt(count.toLong)
    (0 until count).foreach { i =>
      val id = new Array[Byte](32)
      id(0) = (i & 0xFF).toByte
      id(1) = ((i >> 8) & 0xFF).toByte
      w.putBytes(id)
      w.putUInt(0L) // zero-length modifier body
    }
    val payload = w.result().toBytes

    val parsed = ModifiersSpec.parseBytesTry(payload)

    // Sanity: the fixture is well within the byte budget, so at the pin it parses cleanly - that is exactly the point.
    // DESIRED: a declared count above InvSpec.maxInvObjects must be rejected. This fails at the pin (RED).
    assert(
      parsed.isFailure,
      s"RED at pin f758780e1: ModifiersSpec.parse accepted a Modifiers message declaring $count entries " +
        s"(got ${parsed.map(_.modifiers.size)} distinct entries), but the only legitimate source is a " +
        s"RequestModifier answer capped at InvSpec.maxInvObjects=${InvSpec.maxInvObjects}. " +
        s"A numeric element-count cap on ModifiersSpec.parse is MISSING."
    )
  }

}
