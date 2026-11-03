# Probe review v1 (adversarial-reviewer, Opus 5.5, read-only, no sbt) — 2026-11-03

Reviewed diff: evidence/probe-tests-v1.diff (probe worktree ws-subblocks-probe at 9a409ec75).

## Verdicts
- C-1 (UtxoStateSpecification:632): KEEP. Compiles. Red at pin: stateContext.currentHeight = 1 after genesis (ESC:89-90,109); Utxo:316 validates with that context so ETx:188 fails "2 <= 1 is not true". Green under the falsifier's h+1 preheader helper. No trivial pass.
- C-2 (CandidateGeneratorPropSpec:753): KEEP, miner half only. Compiles. Red at pin: limit 100000, safeGap 0; prefix ~86k + pool ~86k; correctLimits (CG:1099) sees only new txs. Writer's gap CONFIRMED: CG:882 commits previous ++ orderingTxs and orderingTxs is empty for TRUE txs, so the kickoff's check would pass at pin. Optional C-2b (follower half via hist.applyInputBlockTransactions on a second announcement) is red at pin (fwd == Seq(ib2.id)).
- C-3 (InputBlockProcessorSpecification:3370): KEEP. Compiles. Red at pin: proof excluded from tx id (sigma Input.scala:50); InputBlockFields.empty has no proof indices so IBP:961 passes; IBP:969/974 overwrite cache. Deviation: reads getInputBlockTransactions (peer path) not CG:691 miner path. Optional extra assert on getBestOrderingCollectedInputBlocksTransactions (lazy collectedTransactions IBP:153 not yet memoized) — TRAP: never call the getter before the re-delivery.
- C-6 (IBPS:3409): KEEP. Red at pin: registration loop rebuilds from original forks (IBP:658) → forkA.processedIndex −1 not 1. bestInputBlocksChain is tip-first so Seq(b,a) is right.
- C-7 (IBPS:3444): KEEP. Red at pin: commonIdx −1 → IBP:590 returns Seq.empty. Green under falsifier's IBP:590 change or #2502's commonPrefixLength = 0. Independent of C-6 fix.
- C-12 (ErgoStateContextSpec:192): FIX. Compiles; red at pin (65 vs 64, votingLength 128 in test conf). Stays RED under the adjudicated fix (CG:879 → state.stateContext.currentParameters) because it measures ESC:134 (pre-existing on master, falsifier side note). Prescribed: rename to "PROBE C-12-ESC …" (root/side-note probe), add a CG-level "PROBE C-12" gate in CandidateGeneratorPropSpec (anonymous UtxoState subclass overriding stateContext with a VotingData-seeded context; assert candidate.parameters.subBlocksPerBlock == voted.currentParameters.subBlocksPerBlock). Red at pin (65 vs 64), green with the CG:879 one-liner.

## Existing tests encoding C-7 (CONFIRMED and extended)
- IBPS:3214 "processInputBlockTransactions should switch to a longer competing fork", assert :3240 result._2 shouldBe Seq.empty — asserts the bug.
- IBPS:350 "input block - fork switching - disjoint forks", assert :397 (Seq(ib2.id, ib3.id) -> Seq.empty) — same structure; writer missed it; goes red under the C-7 fix.
- Not affected: IBPS:1500, :2513, :2891-2896, :3006.

## Cross-cutting
- Isolation: each probe builds own history/state/temp dir; CG.extraIndexSynced global stays false.
- Run cost: no forAll; C-2 ~200 single-tx validations, cheap.
- Name filter: -z "PROBE C-" is substring; "PROBE C-1" would also match C-12 → scope by spec in testOnly.
- C-6 and C-7 probes independent. C-6 fix breaks no existing test found (IBPS:1399 asserts only non-empty).
- C-1 closeStorage after assert leaks storage on red; harmless under fork := true.

## Attacked and found clean
Header-id uniqueness across genChain calls; bestInputBlocksChain order; proof bytes excluded from tx id; test voting length and parameter defaults; C-2 safeGap/limit wiring, rent/extra-index throws, cost parity with IBP stored cost (ES:197-210 vs UtxoStateReader:58); C-1 rule interference; all withClue setup checks true at pin.

(Full reviewer text incl. C-2b and C-12 code sketches was passed verbatim to the fix-pass agent; see evidence/probe-tests-v2.diff for what landed.)
