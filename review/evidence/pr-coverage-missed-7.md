# PR coverage: the seven open weak-blocks PRs missing from the register (2026-11-03)

Read-only. Fetched with `gh pr view` and `gh pr diff` on 2026-11-03; diffs saved next to this file as `pr<N>.diff`.
The diffs were read against the pinned tree `ws-subblocks` @ `9a409ec75` and the anchors in `../ADJUDICATION.md`.
Nothing was compiled or run. `git apply --check` (a dry run that changes no files) was used only to confirm that
#2369, #2569 and #2833 apply cleanly to the probe tree `ws-subblocks-probe` @ `83d3ab457`. #2807 and #2623 apply cleanly
to the pin. #2645 and #2646 do not apply to the pin: they are stacked on #2563's head `86cf6c336`, so they conflict at
`ENVS:2490` and in `ExtraIndexerTestActor`. The tree was clean before and after.

Base note: every one of these PRs targets `weak-blocks@214d09cba`. The only change between `214d09cba` and the pin is
#2370, which moves `IBP:963-975` and adds 19 test lines. None of the hunks below overlap it, so the line numbers in each
diff's `-` side are the pin's line numbers.

LOW ids: `L-n` below are ADJUDICATION ids. The report renumbered them after C-13 was moved to LOW (report L-2 = C-13;
report L-(n+1) = ADJ L-n for n >= 2). Both are given where they differ.

## 1. Table

| PR | Author | Head | State | Scope (one line) | Findings touched | Verdict, with hunk evidence |
|---|---|---|---|---|---|---|
| #2369 | a-shannon | `4d90ba606` | OPEN, draft (since 2026-06-09) | Sibling completion folds over the accumulating tree and over every id that passed application (linear and switch branches) | **C-6**, C-7 (shared-prefix sub-case), C-2 (keeps `costDelta = 0`) | **C-6: FULL-FIX.** It removes both bad lines, `-  updTree = new InputBlocksTree(forks.updated(idx, ibc))` (old IBP:628 and :658). In their place, `registerSharedCompletions(new InputBlocksTree(forks.updated(bestIndex, r._1)), r._2)` folds with `completed.foldLeft(tree) { (updatedTree, id) => updatedTree.forks.map { fork => if (fork.firstToComplete().contains(id)) fork.registerCompletion(id, costDelta = 0) ...` (new IBP:538-558, :640-642, :657-659). This goes beyond our prescribed fix. Ours registers only `ib.id`. This one also registers the cached descendants in `r._2`, which is the residual FALSIFY-C-6 named ("siblings are registered only for `ib`, not for continuation blocks"). **C-7: PARTIAL.** It closes the shared-prefix sub-case, which FALSIFY-C-7 says exists only through C-6. It leaves `IBP:590` `if(commonIdx == -1 \|\| ...) Seq.empty` untouched, so the root-sibling case stays open; #2502 owns that case. **C-2: TOUCHES.** It keeps the `// todo: pass real cost ... costDelta = 0` that FALSIFY-C-2 cites. All other findings: UNRELATED. |
| #2569 | a-shannon | `c8e654a62` | OPEN, draft | #2369's commit, plus `disconnectedWaitlist` changed from `mutable.Set` to `mutable.LinkedHashSet` so equal-length sibling recovery follows arrival order (#2568) | C-6, C-7 (through its #2369 commit); none of its own | Main-code hunks are #2369's three hunks, byte-identical, plus `-  ... disconnectedWaitlist = mutable.Set[InputBlockAnnouncement]()` / `+ ... mutable.LinkedHashSet[...]` (IBP:726). **C-6: FULL-FIX (carried from #2369). C-7: PARTIAL (same as #2369; IBP:590 untouched).** The waitlist change affects only recovery order (`IBP:383`, `:413`). It does not touch C-8's `bestIndex == -1` fallback (IBP:539-543) or `switchNeeded` (`d > bestDepth`, IBP:573), so **C-8: UNRELATED**. The register's issue #2568 now has a PR. |
| #2833 | ocakciomursel-pixel | `059c2fa13` | OPEN, ready | `prune()` also removes `orderingBlockTransactions` entries behind the best height ("Fixes #2614") | L-1, C-13 / report L-2, KNOWN #2614 | **L-1: TOUCHES (does not fix).** The only hunk is inside `private def prune()` (IBP:784-797): `+ val orderingTxBlockIdsToRemove = orderingBlockTransactions.keys.filter { orderingId => bestHeight > historyReader.heightOf(orderingId).getOrElse(0) }` / `+ orderingBlockTransactions.remove(id)`. `prune()` is reached only through `resetState()` (IBP:847). That is called from NVH:252-253, which never fires (L-1), and from the height-jump branch IBP:886-892, which cannot be reached remotely (WS-2 passes). NVH:252 is not in the diff. The new test calls `invokePrune(h)` by reflection and `h.saveOrderingBlockTransactions` directly, so it is green while a running node still never prunes. **#2614 / C-13: TOUCHES.** Once L-1 is fixed, this bounds the map that C-13's junk lands in, but stays dead code until then. It does not move the save before the Merkle check (NVH:456 vs :466-470); the C-13 fix was "save after the root matches, or delete the map", and the PR offers deletion as "Option 1". |
| #2807 | ocakciomursel-pixel | `2f77a827b` | OPEN, ready | Genesis ordering announcement on an empty history is applied instead of requesting header `000…0`; `GenesisParentId` guards at ENVS:477, :1636, :2364 ("Fixes #2604") | C-13 / report L-2 lines (re-indented only); C-9: none | **C-9: UNRELATED.** NVH hunk `@@ -431,77 +431,81 @@` covers only `processOrderingBlock`. It replaces `history().typedModifierById[Header](parentId) match { case Some(_) => ...` with `val canApply = (header.isGenesis && history().bestHeaderOpt.isEmpty && parentId == Header.GenesisParentId) \|\| history().typedModifierById[Header](parentId).isDefined` and `} else if (parentId != Header.GenesisParentId) {`. C-9's anchors NVH:405-409, :600-609 and :251-255 are outside the hunk. **C-13 / report L-2: TOUCHES.** The re-indented body keeps `history().saveOrderingBlockTransactions(headerId, orderingBlockTransactions)` before the `merkleRootCorrect` check, the same order as NVH:456 vs :466-470. The ENVS hunks (:474 older-peer header request, :1633 unknown-ordering request on the +2 path, :2361 ParentHeaderNotFoundError) are not on any C-n anchor; they sit next to, but do not touch, C-12's ENVS:1575. All findings: UNRELATED, except that C-13 is TOUCHES. Register: issue #2604 now has a PR. |
| #2646 | arkadianet | `6bc21c5c6` | OPEN, ready | Stacked on #2563 (`86cf6c336`). Adds a `replayInvalid` counter, an empty-store early return and removal of the fairness filter in the +2 pending-announcement store ("Fixes #2644") | C-14 (side, -123 only); C-3, C-8: none | **C-3: UNRELATED.** No hunk in `processInputBlockTransactions` (ENVS:1799-1816, the code-104 path with `// todo: check if not spam`) and no hunk in IBP at all, so IBP:961-975 is unchanged. **C-8: UNRELATED** (no IBP change). The ENVS hunks are in `processInputBlock`, the code-100 announcement path (ENVS:1475-1605): `+ private def processInputBlockOutcome(...)`, `+ if (pendingInputAnnouncements.add(inputBlockInfo, remote)) {` replacing `- // todo: save input block?`, and on the invalid path `+ clearDeliveryIfFromSupplier(subBlockId, InputBlockTypeId.value, remote)` then `+ return InputBlockInvalid`. That last call clears `Received`/`Requested` for type -123 (input block). **C-14 concerns -122** (InputBlockTransactionIds, `setReceivedIfRequested` at ENVS:1735, which is not in the diff), so the verdict is UNRELATED (an adjacent -123 cleanup only). C-12: the hunk passes `.map(_.stateContext.currentParameters)` (ENVS:1575) as context, unchanged, so UNRELATED. Most of the diff (+261 `PendingInputAnnouncements.scala`) is #2563's. |
| #2645 | arkadianet | `f9958f923` | OPEN, ready | Stacked on #2563 (`86cf6c336`). Adds the switch `ergo.node.matrix.pendingAnnouncements.enabled` (default true); when false, a +2 announcement takes the stock drop path ("Fixes #2643") | none | **C-3: UNRELATED, C-8: UNRELATED**, for the same reason as #2646: the main-code hunks are in the code-100 `processInputBlock`, the stats collector, settings and the new store; there is no 104 path and no IBP. All findings: UNRELATED. Register: issue #2643 now has a PR. |
| #2623 | a-shannon | `4cb8efbb3` | OPEN, draft; needs #2502 and #2526 first (body) | NVH sends `ReconcileInputBlockChain(bestInputBlocksChain().reverse, mempool)` after every input-block selection change. The wallet rebuilds its off-chain registry from `OffChainRegistry.init(state.registry)`, then the selected chain, then the dependency-ordered final pool, and sets `inputBlockIds = selectedInputBlockIds` ("Fixes #2622") | L-9 / report L-10, L-8 / report L-9, C-9 (wallet half), C-7 (wallet half), C-6 (wallet mirrors the tree), KNOWN #2622 | **L-9 / report L-10: FULL-FIX (traced).** Rollback still drops the outputs of a tx that is back in the pool (`OCR:133-135`). The new NVH block `+ if (newBestInputBlocks.nonEmpty \|\| rollbackInputBlocks.nonEmpty) { ... vault().reconcileInputBlockChain(selectedInputBlockIds, memoryPool().getReader)` is placed after the rollback (NVH:392-401) and scan (NVH:405-414) messages, so it is the last wallet message. The handler then runs `applyOffChainTransactions(selectedRegistry, orderedPoolTransactions, state)` and re-applies that tx's outputs. **L-8 / report L-9: PARTIAL.** `inputBlockIds = selectedInputBlockIds` and the fresh `OffChainRegistry.init` (`inputBlockDiffs = Map.empty`, OCR:185) bound both structures to the current selected chain on every change. `ScanInputBlock` still appends (`state.inputBlockIds :+ inputBlockId`, EWA:265), and every input-block change now replays the whole selected chain and the whole pool ("no measured performance bound", PR body). **C-9: PARTIAL, wallet half only.** After an ordering block that omits input-chain tx T, the next input-block change rebuilds from the confirmed registry; T is neither selected nor pooled, so its phantom change output goes away. The overstatement lasts until that next input-block change, not until restart. The mempool half, NVH:405-409 removing T for good, is untouched ("Generic mempool-change handling is unchanged"). **C-7: PARTIAL, wallet half only.** After a root-sibling switch with an empty rollback list, the reconcile drops a and b's effects from the wallet because they are not on the selected chain. Their txs still are not returned to the pool, and IBP:590 is unchanged. **C-6: TOUCHES.** The wallet follows `bestInputBlocksChain()`, so it matches whatever tree C-6 leaves; it does not repair the tree. I-1 (txs missing from listings): UNRELATED. |

### Probe traces (read, not run)

- **PROBE C-6 (IBPS:3411) under #2369, and under #2569: would turn GREEN (traced).** In the setup, a, b (prev a) and b' (prev a) are announced, which gives forks `[a,b]` (index 0, from `fork()` → `Seq(this, forkedChain)`, IBP:133) and `[a,b']` (index 1), both at processedIndex -1. b's body comes first and is stored. When a's body arrives, `bestIndex == -1` so the fallback picks `longestIndex` = 0 (first strict maximum, IBP:272-283). `applicationStep` runs a, then b, so `r = ([a,b] at processedIndex 1, Seq(a, b))`; the setup clue already asserts this return. With the fold, `id = a` leaves fork 0 alone (`firstToComplete` = None) and registers a on fork 1 (processedIndex 0); `id = b` changes nothing, since fork 1's next is b'. The stored tree is fork 0 at processedIndex 1 and fork 1 at processedIndex 0, which passes the first two asserts. `bestIndex` (strict `>`, IBP:296-305) is 0, `bestChain` is `[a,b]`, and `bestInputBlocksChain()` is `Seq(b, a)`, which passes the third assert. At the pin, the second registration rebuilt the tree from the original `forks` and reset fork 0 to -1, which is the red message on record.
- **PROBE C-7 (IBPS:3446) under #2369 or #2569: stays RED (traced).** The switch to `[x,y,z]` finds no common index (`commonIdx = -1`), and IBP:590 still returns `Seq.empty`. The fold over `Seq(x, y, z)` leaves `[a,b]` alone (`firstToComplete` = None). Only #2502, already registered, changes IBP:590.
- **PROBE C-3 (IBPS:3370):** none of the seven touches IBP:961-975 or the 104 path, so it **stays RED** under each of them.
- None of the seven fixes C-1, C-2, C-4, C-5b, C-10, C-11 or C-12. None touches `CandidateGenerator.scala`, `UtxoState.scala` or `ErgoMemPool.scala`; the file lists are in `pr<N>.diff`.

## 2. Register corrections

### Rows to add to REGISTER §A (change the heading from 19 to 26)

| PR | Author | Title | State | Pending fix for |
|---|---|---|---|---|
| #2369 | a-shannon | Preserve input-block fork completion updates | OPEN, draft, head `4d90ba606` | **C-6 (full)**; C-7 shared-prefix sub-case; must land before #2569 |
| #2569 | a-shannon | Preserve arrival order when recovering two input-block sibling branches | OPEN, draft, head `c8e654a62` (contains #2369) | issue #2568; C-6 through #2369 |
| #2623 | a-shannon | Reconcile Matrix wallet state after selected input-chain changes | OPEN, draft, head `4cb8efbb3`; depends on #2502 and #2526 | issue #2622; **L-9 / report L-10 (full)**; L-8 / report L-9 (partial); wallet half of C-9 and C-7 (partial) |
| #2645 | arkadianet | Add an off switch for the pending input-announcement store | OPEN, head `f9958f923`; stacked on #2563 | issue #2643; no review finding |
| #2646 | arkadianet | Count invalid pending replays separately and tighten replay handling | OPEN, head `6bc21c5c6`; stacked on #2563 | issue #2644; no review finding |
| #2807 | ocakciomursel-pixel | Fix #2604: do not request Header.GenesisParentId when processing genesis ordering block | OPEN, head `2f77a827b` | issue #2604; no review finding |
| #2833 | ocakciomursel-pixel | Prune outdated orderingBlockTransactions in InputBlocksProcessor | OPEN, head `059c2fa13` | issue #2614; touches C-13 / report L-2; **dead code until L-1 is fixed** |

### REGISTER §B status-note updates

#2568 → PR #2569 (after #2369). #2604 → PR #2807. #2614 → PR #2833 (does not run until L-1 is fixed). #2622 → PR #2623 (draft).
#2643 → PR #2645. #2644 → PR #2646. Add #2891 (cafebedouin, 2026-11-03, "Mining specs: a test that fails before its
terminate() leaves its actor system running"), OPEN, fix PR #2892 (base `v6.0.11`, test-only); see §3.

### Finding labels to change

- **C-6: new → KNOWN-FIX-PENDING #2369** (also carried in #2569). #2369 was opened on 2026-06-09, long before this review. The
  report's line "#2502 does not touch these lines" is still true, but the finding is not new. Note in the report that
  #2369 registers every completed id, not just `ib.id`, so it also covers the continuation-block residual in FALSIFY-C-6.
  Our one-line fix is narrower than #2369's.
- **C-7: keep KNOWN-FIX-PENDING #2502** for the root-sibling case. Add "shared-prefix sub-case closed by #2369 (C-6)".
- **ADJ L-9 / report L-10: → KNOWN-FIX-PENDING #2623** (draft; needs #2502/#2526 to merge first).
- **ADJ L-8 / report L-9: → partial KNOWN-FIX-PENDING #2623** (growth bounded; re-walk cost not addressed, replay cost added).
- **C-9: stays new.** Add "wallet half partly mitigated by #2623 (corrected at the next input-block change); mempool half
  unaddressed".
- **L-1: stays new.** Add "#2833 extends `prune()` but is dead code until L-1 is fixed; its test calls prune by reflection".
- **C-13 / report L-2:** add "#2833 would bound the map once L-1 is fixed; save-before-Merkle order unchanged (also in #2807)".
- **C-3, C-8:** unchanged ("none"). #2645 and #2646 do not touch the 104 path or the processor.
- The public draft (`DRAFT-public-comment-2055.md`, not posted) must not present C-6 as new.

## 3. #2891 vs the CandidateGeneratorSpec restart loop (RUN-SUMMARY §3)

Our run matches the case jozanek reproduced in #2891 (comment 2026-11-03T19:58Z). In our excerpt, "should let multiple miners
compete" fails first. The cause is a duplicate `LocalBlockApplied` where `SolvedBlocksCount` was expected
(RUN-TARGETED-SUMMARY row, CandidateGeneratorSpec.scala:263), which is #2783's root cause on weak-blocks. That test's actor system
is never terminated, and it keeps three miners and a node view running on the shared `defaultSettings` data directory. The
next test, "should cache candidate until newly mined block is applied", then fails, and the log fills with
`collectEmission` → `UtxoState.emissionBoxOpt` → `NoSuchElementException` traces and "Attempted candidate generator restart
due to null". jozanek's comment gives the same sequence for `v6.0.11`, with 20,634 trace lines in 18 s. The other 12 failures
after it are consistent with leftover systems piling up. That part is inferred, not separated by a run.

So the loop is mostly a test-harness leak (#2891, fixed by #2892: a test-only `TestActorSystems` trait, base `v6.0.11`)
that starts from #2783's failure. One production half is real at the pin as well: `CandidateGenerator.scala:194-202` calls
`generateCandidate` outside a `Try`, so a throwing state read leaves `receive`. The supervisor restarts the actor
(`preRestart`, CG:65-67) with the same readers and it throws again, without end. #2886 (jozanek, base `v6.0.11`, head
`b0325e8f6`) wraps that call. Neither #2886 nor #2892 targets `weak-blocks`. Both need a forward merge, and #2892's body
lists two weak-blocks-only tests that would need `testSystem()`. For our report, add these to the CI section: "restart
loop = #2891 (harness, PR #2892) triggered by #2783; generator has no Try around generateCandidate, PR #2886 (v6.0.11)".
This is not a new finding.
