# Targeted spec runs, weak-blocks 9a409ec75

- Date: 2026-11-03
- Commit: 9a409ec75 (branch weak-blocks, ergo PR #2055)
- Box run under a 6 GB memory cap; forked test JVM `-Xmx2g -Xss64m -XX:+ExitOnOutOfMemoryError` (from the `javaOptions` debug line in each log); one spec per sbt invocation.
- Wall times (run-targeted-driver.log, CDT):

| Spec | start | exit (code) | wall | sbt "Total time" |
|---|---|---|---|---|
| InputBlockWalletSpec | 14:49:29 | 14:51:16 (1) | 1m47s | 92 s |
| CandidateGeneratorSpec | 14:51:16 | 14:53:04 (1) | 1m48s | 102 s |
| ErgoMinerSpec | 14:53:04 | 14:58:47 (1) | 5m43s | 335 s |
| ErgoNodeViewHolderSpec | 14:58:47 | 15:07:12 (1) | 8m25s | 499 s |

CI comparison source: ci-node-9a409ec75.txt (run 41653486275; the "Runs node tests" step never finished, runner shutdown signal, no ScalaTest totals; it lists 13 FAILED lines). "CI-did-not-reach-it" below means CI's log has no FAILED line for that test and ended after the runner shutdown.

## InputBlockWalletSpec
`Tests: succeeded 10, failed 4, canceled 0, ignored 0, pending 0`

| Test | Result | Failure message | Anchor | Matches CI? |
|---|---|---|---|---|
| boxes created in input blocks can be spent in subsequent blocks | FAILED | `1 was not equal to 2` | InputBlockWalletSpec.scala:94 | yes |
| scanInputBlock with multiple transactions | FAILED | `The code passed to eventually never returned normally. Attempted 27 times over 5.128089868 seconds. Last failure message: 1 was not greater than or equal to 2.` | InputBlockWalletSpec.scala:174 | yes |
| LocallyGeneratedInputBlock updates wallet state | FAILED | `The code passed to eventually never returned normally. Attempted 30 times over 10.026900653999999 seconds. Last failure message: 1 was not greater than or equal to 2.` | InputBlockWalletSpec.scala:371 | yes |
| wallet tracks boxes from input block before ordering block confirmation | FAILED | `1 was not greater than or equal to 2` | InputBlockWalletSpec.scala:406 | yes |

Logged near each failure (WARN lines): "Input block transactions delivered for unknown input block test-input-block-N" and "No transactions found for input block test-input-block-N".

## ErgoMinerSpec
`Tests: succeeded 2, failed 4, canceled 0, ignored 0, pending 0`

| Test | Result | Failure message | Anchor | Matches CI? |
|---|---|---|---|---|
| should not include too costly transactions | FAILED | `1 was not equal to 0` | ErgoMinerSpec.scala:102 | yes |
| should not freeze while mempool is full | FAILED | `11 was not equal to 0` | ErgoMinerSpec.scala:212 | yes |
| should include only one transaction from 2 spending the same box | FAILED | `java.lang.AssertionError: assertion failed: fishForMessage() found unexpected message Error(Block already solved : None)` (frames: Predef.assert, TestKitBase.recv$1:511, fishForMessage:514) | ErgoMinerSpec.scala:273 | yes |
| should include mandatory transactions | FAILED | `0 was not equal to 1` | ErgoMinerSpec.scala:358 | yes |

Passing in this log (named): "should pass external ordering and input-block solutions to the candidate generator", "should prepare external candidate".

## ErgoNodeViewHolderSpec
`Tests: succeeded 148, failed 2, canceled 0, ignored 0, pending 0`

| Test | Result | Failure message | Anchor | Matches CI? |
|---|---|---|---|---|
| ProcessInputBlock uses pre-existing transactions from history - State: utxo, Verify Transactions: true, PoPoW Bootstrap: true, UTXO Bootstrap: false | FAILED | `java.lang.AssertionError: assertion failed: timeout (15 seconds) during fishForMessage, hint:` (frames: Predef.assert, TestKitBase.recv$1:510, fishForMessage:514) | ErgoNodeViewHolderSpec.scala:1321 (via $anonfun$t41$1, :1289 adapted; test registered at :1643) | yes |
| ProcessInputBlock uses pre-existing transactions from history - State: utxo, Verify Transactions: true, PoPoW Bootstrap: false, UTXO Bootstrap: false | FAILED | same text as above | ErgoNodeViewHolderSpec.scala:1321 | yes |

## CandidateGeneratorSpec
Source is the excerpt only (original 7,450,136,217 bytes / 88,487,357 lines, deleted). The excerpt does contain the summary:

```
[info] Run completed in 1 minute, 40 seconds.
[info] Total number of tests run: 22
[info] Suites: completed 1, aborted 0
[info] Tests: succeeded 8, failed 14, canceled 0, ignored 0, pending 0
[info] *** 14 TESTS FAILED ***
[error] Failed tests:
[error] 	org.ergoplatform.mining.CandidateGeneratorSpec
[error] (Test / testOnly) sbt.TestsFailedException: Tests unsuccessful
[error] Total time: 102 s (0:01:42.0), completed Nov 3, 2026, 2:53:04 PM
```

The excerpt keeps the first 40 FAILED matches with 12 context lines; for most tests the context holds a stack trace instead of the assertion text, so "message" is what the excerpt shows right after the FAILED line. Excerpt line numbers are the `NNN:` prefix; original-log line numbers are in the third column.

| Test | Result | Failure message (as shown in excerpt) | Anchor | Matches CI? |
|---|---|---|---|---|
| should let multiple miners compete (orig line 783) | FAILED | `java.lang.AssertionError: assertion failed: expected class org.ergoplatform.mining.ErgoMiningThread$SolvedBlocksCount, found class org.ergoplatform.network.ErgoNodeViewSynchronizerMessages$LocalBlockApplied (LocalBlockApplied(Header({"extensionId":"0ab4a5...` | CandidateGeneratorSpec.scala:263 (test body :236) | yes |
| should cache candidate until newly mined block is applied (3281763) | FAILED | `java.lang.AssertionError: assertion failed: timeout (9 seconds) during expectMsg:` | CandidateGeneratorSpec.scala:297 (test body :277) | yes |
| should accept solution for previous candidate after regeneration (11540911) | FAILED | assertion text not in excerpt; context shows `akka.actor.OneForOneStrategy ... 14:51:55.420 null / java.util.NoSuchElementException: null` at LDBVersionedStore.scala:81 | not in excerpt | yes |
| should pool transactions should be removed from pool when block is mined (22239707) | FAILED | assertion text not in excerpt; context: `Attempted candidate generator restart due to null / NoSuchElementException: null` (14:52:06.486) | not in excerpt | CI-did-not-reach-it |
| should 6.0 pool transactions should be added to 6.0 block (24186343) | FAILED | `java.lang.AssertionError: assertion failed: received unexpected message RealMessage(LocalBlockApplied(Header({"extensionId":"96d1a8...` | not in excerpt | CI-did-not-reach-it |
| should use custom miner public key when provided via optPk (32369372) | FAILED | assertion text not in excerpt; context: restart due to null / NoSuchElementException (14:52:17.848) | not in excerpt | CI-did-not-reach-it |
| should use default minerPk when optPk is None (42081349) | FAILED | assertion text not in excerpt; context: restart due to null / NoSuchElementException (14:52:26.901) | not in excerpt | CI-did-not-reach-it |
| should generate different candidates for different optPk values (53009982) | FAILED | assertion text not in excerpt; context: restart due to null (14:52:35.955) | not in excerpt | CI-did-not-reach-it |
| should handle optPk with empty transactions (64914828) | FAILED | assertion text not in excerpt; context: restart due to null (14:52:45.015) | not in excerpt | CI-did-not-reach-it |
| should preserve previous candidate when forced regeneration occurs (70329617) | FAILED | `java.lang.AssertionError: assertion failed: expected:  but got unexpected message LocalBlockApplied(Header({"extensionId":"ffc087...` (frames: Predef.assert, TestKitBase.expectMsgPF:478) | not in excerpt | CI-did-not-reach-it |
| should handle multiple consecutive forced regenerations correctly (72935092) | FAILED | assertion text not in excerpt; context: `OneForOneStrategy 14:52:51.497 null / NoSuchElementException: null` | not in excerpt | CI-did-not-reach-it |
| should accept solution for previous candidate after forced regeneration triggered by mempool (78512370) | FAILED | assertion text not in excerpt; context: restart due to null (14:52:55.984) | not in excerpt | CI-did-not-reach-it |
| should correctly complete input block from candidate and solution (81243630) | FAILED | assertion text not in excerpt; context: restart due to null (14:52:58.049) | not in excerpt | CI-did-not-reach-it |
| should recover with a filled candidate when proof generation fails once (87958655) | FAILED | assertion text not in excerpt; context: `OneForOneStrategy 14:53:03.852 null / NoSuchElementException: null` | not in excerpt | CI-did-not-reach-it |

Passing tests seen in the head: "should provider candidate to internal miner and verify and apply his solution", "should recover when locally mined block is invalidated by node view holder", "should recover when locally mined block is invalidated by full block id" (8 passed in total; the other 5 names are not in the excerpt). The head also shows a `MalformedModifierError: tx failed` trace at CandidateGeneratorSpec.scala:142 (14:51:29, "Locally mined block ... invalidated by the node view holder") and one at :213.

## CandidateGeneratorSpec restart loop

- Log line: `[ERROR] o.e.mining.CandidateGenerator >> Attempted candidate generator restart due to null`, followed by `java.util.NoSuchElementException: null`.
- Frames (sample, excerpt lines 626-631 and the repeated-shape table), top to bottom:
  - `scorex.db.LDBVersionedStore.$anonfun$apply$1(LDBVersionedStore.scala:81)` (Option.getOrElse, KVStoreReader.getOrElse:71)
  - `scorex.db.LDBVersionedStore.apply(LDBVersionedStore.scala:81)`
  - `VersionedLDBAVLStorage$.fetch(VersionedLDBAVLStorage.scala:183)`
  - `ProxyInternalProverNode.left(:27)` or `.right(:34)`
  - `BatchAVLProver.internalNodeFn$3(:391 / :393)`, `unauthenticatedLookup(BatchAVLProver.scala:400)`, `PersistentBatchAVLProver.unauthenticatedLookup(:21)`
  - `UtxoStateReader.emissionBoxOpt(UtxoStateReader.scala:124)`, `UtxoState.emissionBoxOpt(UtxoState.scala:36)`
  - `CandidateGenerator$.collectEmission(CandidateGenerator.scala:952)`, then `emissionTxOpt$lzycompute`, `emissionTxOpt`, `hasAnyMemPoolOrMinerTx$1(CandidateGenerator.scala:495)`, `generateCandidate(CandidateGenerator.scala:497)`, `initialized$1.applyOrElse(CandidateGenerator.scala:201)`, `aroundReceive(CandidateGenerator.scala:49)`
- Some occurrences are logged by `akka.actor.OneForOneStrategy` (message `null`) instead of the CandidateGenerator restart line; same exception.
- Frequency table (sample = original lines 1,000,000-1,200,000, timestamps stripped): each frame above appears 4874 times and `java.util.NoSuchElementException: null` 4873 times, i.e. about 4,870 traces per 200,000 lines (about one trace per 41 lines). Original log was 88,487,357 lines; at that sample rate the trace count is on the order of 2 million (arithmetic extrapolation, not counted).
- First occurrence in the excerpt (excerpt line 428, original line 3281772), printed 9 lines after the FAILED line of "should cache candidate until newly mined block is applied" (original 3281763):
  `[default-critical-dispatcher-5] >> [ERROR] o.e.mining.CandidateGenerator >> 14:51:44.324 Attempted candidate generator restart due to null`
  The test whose FAILED line immediately precedes it is "should cache candidate until newly mined block is applied" (CandidateGeneratorSpec.scala:277/297); the next FAILED line in the log, at 14:51:55 (OneForOneStrategy), belongs to "should accept solution for previous candidate after regeneration". Which of the two was executing at 14:51:44.324 is not stated in the log; the head -400 (up to 14:51:29) contains no restart line.
- The traces then continue through every following failed test (timestamps 14:51:44, 14:51:55, 14:52:06 ... 14:53:04) and through the tail; the last 300 lines are still the same trace, with `restart due to null` at 14:53:04.448 repeated by four different dispatcher threads.

## Resource notes

- grep for `OutOfMemory|Killed|ExitOnOutOfMemoryError|timed out` in all four run logs: the only hit in each is the JVM option line `javaOptions: Vector(-Xmx2g, -Xss64m, -XX:+ExitOnOutOfMemoryError)`. No OutOfMemoryError, no "Killed", no exit-on-OOM event. The driver log has no such marks.
- Test-level timeouts present (not process timeouts): ErgoNodeViewHolderSpec `timeout (15 seconds) during fishForMessage` x2; CandidateGeneratorSpec `timeout (9 seconds) during expectMsg`; InputBlockWalletSpec `eventually` gave up after 5.1 s and 10.0 s.
- Each sbt run ended with its own `[error] (Test / testOnly) sbt.TestsFailedException: Tests unsuccessful` and `[error] Total time: ...` line (sbt prints Total time under [error] when tests fail; no `[success]` line in any of the four). Total-time lines: InputBlockWalletSpec 92 s (2:51:16 PM), CandidateGeneratorSpec 102 s (2:53:04 PM, taken from the excerpt tail), ErgoMinerSpec 335 s (2:58:46 PM), ErgoNodeViewHolderSpec 499 s (3:07:12 PM). All four driver exit codes were 1; the driver logged "ALL DONE" at 15:07:12.
- CandidateGeneratorSpec raw log reached 7.45 GB / 88.5 M lines in 102 s of test time because of the restart loop, and was deleted after excerpting.
