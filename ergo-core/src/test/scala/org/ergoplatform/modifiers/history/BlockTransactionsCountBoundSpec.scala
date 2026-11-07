package org.ergoplatform.modifiers.history

import org.ergoplatform.settings.Constants
import org.ergoplatform.utils.ErgoCorePropertyTest
import scorex.util.ByteArrayBuilder
import scorex.util.serialization.VLQByteBufferWriter

/**
  * S-BT  (regression spec that asserts a MISSING numeric transaction-count cap on `BlockTransactionsSerializer`).
  *
  * CLAIM:  `BlockTransactionsSerializer.parse` (BlockTransactions.scala:163-205) reads `txCount` (:166/:181) and
  *         then builds `(1 to txCount).map { ErgoTransactionSerializer.parse(r) }` (:187). The constant
  *         `MaxTransactionsInBlock = 10,000,000` (:140) is used ONLY to disambiguate the version-prefix encoding
  *         (:179), never as a `require`. There is NO numeric cap on `txCount`; it is bounded only by the enclosing
  *         block-section byte buffer (fail-closed when the reader drains).
  *
  * DESIRED BOUND: `require(txCount <= MaxTransactionsInBlock, ...)` evaluated right after `txCount` is read, before
  *         the transaction loop. (`require` throws `IllegalArgumentException`.)
  *
  * RED means (pin f758780e1): with no cap, a fixture declaring `txCount = MaxTransactionsInBlock + 1` is NOT
  *         rejected on the count; the parser proceeds into the tx loop and fails only incidentally when the reader
  *         runs out of bytes, throwing a NON-`IllegalArgumentException` (buffer underflow). The assertion that the
  *         failure is an `IllegalArgumentException` (the require) therefore fails -> RED.
  * GREEN means: parse rejects the over-ceiling count with an `IllegalArgumentException` before touching the tx
  *         bytes -> the guard exists.
  *
  * PAYLOAD SIZE / TIME: the serialized payload is tiny (32-byte header id + two VLQ ints, no tx bodies). The
  *         declared count is exactly one past the existing `MaxTransactionsInBlock` constant (not a huge literal),
  *         and no large block is allocated: the tx loop throws on its first iteration. Well under 1 s / 1 GB.
  */
class BlockTransactionsCountBoundSpec extends ErgoCorePropertyTest {
  import org.ergoplatform.utils.generators.ErgoCoreTransactionGenerators._

  property("BlockTransactions parser should reject a tx count above MaxTransactionsInBlock (RED at pin: no count cap)") {
    // --- characterization: a real, modest BlockTransactions round-trips through the serializer ---
    val bt = invalidBlockTransactionsGen.sample.get
    val roundTripped = BlockTransactionsSerializer.parseBytes(BlockTransactionsSerializer.toBytes(bt))
    roundTripped.txs.size shouldBe bt.txs.size

    // --- declared-count fixture: header id, then the new-format version prefix (MaxTransactionsInBlock + version),
    //     then a txCount one past the MaxTransactionsInBlock ceiling, and NO transaction bodies. ---
    val max = BlockTransactionsSerializer.MaxTransactionsInBlock // 10_000_000
    val w = new VLQByteBufferWriter(new ByteArrayBuilder())
    w.putBytes(new Array[Byte](Constants.ModifierIdSize)) // 32-byte header id (value irrelevant to the count bound)
    w.putUInt(max.toLong + 2L) // verOrCount > MaxTransactionsInBlock => new encoding, blockVersion = 2
    w.putUInt(max.toLong + 1L) // txCount = MaxTransactionsInBlock + 1 : one past the proposed ceiling
    val payload = w.result().toBytes

    val parsed = BlockTransactionsSerializer.parseBytesTry(payload)

    // DESIRED: the over-ceiling count is rejected by a numeric guard (require -> IllegalArgumentException), before
    // any transaction is read. At the pin there is no such guard, so the failure (if any) comes from the drained
    // reader inside the tx loop and is NOT an IllegalArgumentException -> RED.
    assert(parsed.isFailure, "RED at pin: expected the over-ceiling txCount to be rejected, but parse succeeded")
    val ex = parsed.failed.get
    assert(
      ex.isInstanceOf[IllegalArgumentException],
      s"RED at pin f758780e1: BlockTransactionsSerializer.parse has no numeric txCount cap. A declared txCount of " +
        s"${max + 1} (> MaxTransactionsInBlock=$max) was not rejected on the count; parse instead failed inside the " +
        s"transaction loop with ${ex.getClass.getName}: ${ex.getMessage}. A `require(txCount <= MaxTransactionsInBlock)` " +
        s"guard (IllegalArgumentException) is MISSING."
    )
  }

}
