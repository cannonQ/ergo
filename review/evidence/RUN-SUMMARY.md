# Live run summary — weak-blocks @ 9a409ec75 — 2026-11-03 (CDT)

Box: node and oracle stopped by the operator; every sbt invocation in its own `systemd-run --user --scope` with MemoryMax=6G, MemorySwapMax=0, forked test JVM `-Xmx2g -Xss64m -XX:+ExitOnOutOfMemoryError`, `Tags.ForkedTestGroup` limited to 1. No OOM, kill, or timeout in any run (see RUN-TARGETED-SUMMARY.md §Resource notes).

## 1. Sigma dependency bootstrap (DONE)
sigmastate-interpreter at 368a860be033af94aa14895381f42099b3646db6 built locally and published as `6.0.5-22-368a860b-SNAPSHOT` (publishLocal, exit 0, 106 s; `sigma-bootstrap.log`). No public repository serves this snapshot, so a fresh checkout of the branch cannot test without this step.

## 2. Test/compile of the branch at the pin (DONE)
`probe-testcompile.log`: `[success]`, 0 errors.

## 3. Existing specs at the pin, run in the pristine pinned worktree (RAN)
Detail: `RUN-TARGETED-SUMMARY.md`. Logs: `run-<Spec>.log` (CandidateGeneratorSpec kept as `run-CandidateGeneratorSpec.excerpt.log`; the raw 7.45 GB log was deleted after excerpting).

| Spec | Result | Matches CI at pin |
|---|---|---|
| InputBlockWalletSpec | 10 pass / 4 FAIL | yes, same 4 |
| ErgoMinerSpec | 2 pass / 4 FAIL | yes, same 4 |
| ErgoNodeViewHolderSpec | 148 pass / 2 FAIL (ProcessInputBlock uses pre-existing transactions from history, utxo+verify, PoPoW true/false) | yes, same 2 |
| CandidateGeneratorSpec | 8 pass / 14 FAIL | 3 match; 11 CI never reached (CI runner died in the same restart loop) |

CandidateGeneratorSpec restart loop (RAN): `java.util.NoSuchElementException` at `LDBVersionedStore.apply:81 ← VersionedLDBAVLStorage.fetch:183 ← BatchAVLProver.unauthenticatedLookup:400 ← UtxoState.emissionBoxOpt:36 ← CandidateGenerator.collectEmission:952 ← generateCandidate:497`, "Attempted candidate generator restart due to null", repeating ~4,900 traces per 200k log lines, first at 14:51:44.324 right after "should cache candidate until newly mined block is applied" FAILED. The actor supervisor restarts the generator in a tight loop against a closed/rolled-back store. This is the same trace CI's log ends in. (Not in the review's cluster list; recorded here as a RAN observation for the report's CI section.)

Conclusion: the branch's own targeted suites are red at the pin on this box exactly where CI is red. Nothing in the review was changed on the basis of these failures; they bound what the suites could have caught.

## 4. Probe tests (RAN, all RED at the pin for the finding's reason)
Probe worktree `ws-subblocks-probe`, branch `probe/subblocks-9a409ec75`, diff `probe-tests-v2.diff` (reviewed: `fieldwork/PROBE-REVIEW-v1.md`). Logs `probe-<Spec>.log`. Each probe checks its fixture under a `setup:` clue first; none failed under that clue.

| Probe | Spec:line | Assertion message at the pin | Finding |
|---|---|---|---|
| C-1 | UtxoStateSpecification:632 | `input block at height 2 rejected a tx whose output has creationHeight 2; applyInputBlock validated at stateContext.currentHeight = 1: … 2 <= 1 is not true` | C-1 HIGH (Utxo:316 / ETx:188) |
| C-2 | CandidateGeneratorPropSpec:755 | `input-chain prefix cost 85407 + new candidate txs cost 85407 = 170814 exceeds maxBlockCost 100000; collectTxs was budgeted without the prefix` | C-2 HIGH, miner half (CG:788/1099) |
| C-2b | CandidateGeneratorPropSpec:835 | `input chain accepted at cost 170814 > 100000` (forward list non-empty) | C-2 HIGH, follower half (IBP:201 / ES:197) |
| C-3 | InputBlockProcessorSpecification:3370 | `processed input block … now serves altered proof bytes 01 instead of the original for tx …` | C-3 HIGH (IBP:969-975) |
| C-6 | InputBlockProcessorSpecification:3411 | `fork [a, b] was applied up to b but is stored with processedIndex -1` | C-6 MEDIUM (IBP:658) |
| C-7 | InputBlockProcessorSpecification:3446 | `switch from [a, b] to [x, y, z] returned rollback list List(), expected Seq(a, b)` | C-7 MEDIUM (IBP:590; fix pending #2502) |
| C-12 | CandidateGeneratorPropSpec:802 | `miner's input PoW uses subBlocksPerBlock 65, followers use 64` | C-12 MEDIUM (CG:879 vs ENVS:1575) |
| C-12-ESC | ErgoStateContextSpec:193 | `at mid-epoch height 3 upcoming() gives subBlocksPerBlock 65, followers keep the epoch's 64` | root/side note: ESC:134 applies a passed vote mid-epoch (pre-existing on master); NOT the C-12 gate |

Review notes carried forward (PROBE-REVIEW-v1.md): existing tests IBPS:3214 (assert :3240) and IBPS:350 (assert :397) assert the C-7 behaviour (`rollback == Seq.empty`) and will go red when #2502 or any C-7 fix lands. C-12 turns green with the CG:879 one-liner; C-12-ESC only if `upcoming()` stops applying votes mid-epoch.

## 5. Full suite
Not run (optional per KICKOFF §5; needs ≥ 2 h of node downtime).

## 6. Not proven by this run
- NV-B (sigma pin lacks 6.0.7 deserialization commits → branch-vs-master acceptance split) and NV-C (codes 104/106 parsed without VersionContext → ban of honest peers): no probe written; still READ-level.
- C-4 (quadratic weak-id matching), C-5b, C-8..C-11, C-14: READ-level only.
- Probes show the defect at the pin; they have not been run against any fix PR, so "green after fix" is the reviewer's trace, not a run.
