package org.ergoplatform.modifiers.history.popow

import org.ergoplatform.utils.ErgoCorePropertyTest
import scorex.util.ByteArrayBuilder
import scorex.util.serialization.VLQByteBufferWriter

/**
  * SUPERSEDED per review (Review A, F-1): the claim is KNOWN upstream. Open PR #2461 (a-shannon) already adds
  * `requireWithinLimit` on the prefix and suffix counts. The 100,000 fixture count is also above what the 2 MB
  * sub-frame can hold (about 7.5k elements), so a no-op guard would turn this spec green. The property is
  * `ignore`d; the original text follows unchanged.
  *
  * S-NIP  (regression spec that asserts a MISSING numeric element-count cap on `NipopowProofSerializer`).
  *
  * CLAIM:  `NipopowProofSerializer.parse` (NipopowProof.scala:218-234) reads `prefixSize` (and `suffixSize`) as a
  *         `getUInt().toIntExact` and then builds `(0 until prefixSize).map { ... }`. There is NO numeric cap on
  *         these element counts. The only bound is the stage-1 `NipopowProofSpec` sub-frame of 2,000,000 bytes
  *         (NipopowProofSpec.scala SizeLimit). No protocol constant for a maximum proof length / header count exists
  *         in the code, so the count is bounded only by that byte sub-frame (fail-closed on reader drain).
  *
  * DESIRED BOUND: a numeric cap on `prefixSize`/`suffixSize` tied to a sane maximum proof length, evaluated right
  *         after each count is read, before the element loop (a `require` -> `IllegalArgumentException`). A real
  *         nipopow proof carries O(m * polylog(chain)) headers (hundreds), orders of magnitude below the fixture's
  *         declared count below.
  *
  * PATH NOTE: this parser runs bootstrap-only -- `processNipopowProof` only parses while `hr.bestHeaderOpt.isEmpty`
  *         and at most once per connected peer (ErgoNodeViewSynchronizer.scala:1170-1184). A synced node ignores it.
  *
  * RED means (pin f758780e1): a fixture declaring a `prefixSize` far above any real proof is NOT rejected on the
  *         count; parse proceeds into the element loop and fails only incidentally when the reader drains, throwing
  *         a NON-`IllegalArgumentException` (buffer underflow). The assertion that the failure is an
  *         `IllegalArgumentException` therefore fails -> RED.
  * GREEN means: parse rejects the over-max count with an `IllegalArgumentException` before reading any element.
  *
  * PAYLOAD SIZE / TIME: the serialized payload is a handful of VLQ bytes (m, k, prefixSize) with no element bodies;
  *         the loop throws on its first iteration, so nothing large is allocated. Well under 1 s / 1 GB.
  */
class NipopowProofCountBoundSpec extends ErgoCorePropertyTest {
  import org.ergoplatform.utils.ErgoCoreTestConstants._
  import org.ergoplatform.utils.generators.CoreObjectGenerators._
  import org.ergoplatform.utils.generators.ErgoCoreGenerators._

  ignore("NipopowProof parser should reject an element count above a sane maximum (RED at pin: no count cap)") {
    val serializer = new NipopowProofSerializer(nipopowAlgos)

    // --- characterization: a small, structurally valid proof round-trips through the serializer ---
    val header = defaultHeaderGen.sample.get
    val interlinks = Seq(modifierIdGen.sample.get, modifierIdGen.sample.get)
    val interlinksProof = NipopowAlgos.proofForInterlinkVector(nipopowAlgos.interlinksToExtension(interlinks)).get
    val ph = PoPowHeader(header, interlinks, interlinksProof)
    val proof = NipopowProof(nipopowAlgos, m = 1, k = 1, prefix = Seq(ph), suffixHead = ph, suffixTail = Seq(header), continuous = false)
    val roundTripped = serializer.parseBytes(serializer.toBytes(proof))
    roundTripped.prefix.size shouldBe proof.prefix.size

    // --- declared-count fixture: m, k, then a prefixSize far above any real proof, and NO prefix element bodies. ---
    // No numeric cap exists in code; a proposed sane maximum would be a few thousand headers. Declare well past that.
    val declaredPrefixSize = 100000
    val w = new VLQByteBufferWriter(new ByteArrayBuilder())
    w.putUInt(1L) // m
    w.putUInt(1L) // k
    w.putUInt(declaredPrefixSize.toLong) // prefixSize : orders of magnitude above any legitimate proof
    val payload = w.result().toBytes

    val parsed = serializer.parseBytesTry(payload)

    // DESIRED: the over-max element count is rejected by a numeric guard (require -> IllegalArgumentException) before
    // any prefix element is read. At the pin there is no such guard, so the failure comes from the drained reader
    // inside the element loop and is NOT an IllegalArgumentException -> RED.
    assert(parsed.isFailure, "RED at pin: expected the over-max prefixSize to be rejected, but parse succeeded")
    val ex = parsed.failed.get
    assert(
      ex.isInstanceOf[IllegalArgumentException],
      s"RED at pin f758780e1: NipopowProofSerializer.parse has no numeric prefix/suffix element cap. A declared " +
        s"prefixSize of $declaredPrefixSize was not rejected on the count; parse instead failed inside the element " +
        s"loop with ${ex.getClass.getName}: ${ex.getMessage}. A numeric element-count cap is MISSING (only the " +
        s"2 MB stage-1 sub-frame bounds it)."
    )
  }

}
