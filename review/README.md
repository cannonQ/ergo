# Review of `weak-blocks` at 9a409ec75 (PR #2055)

- `REVIEW-subblocks-9a409ec75.md` — the report. Every anchor is a permalink to commit 9a409ec75.
- `evidence/RUN-SUMMARY.md` — what was run on 2026-11-03 and the eight probe assertions; `RUN-TARGETED-SUMMARY.md` — the four existing specs at the pin vs CI; `probe-*.log`, `run-*.log` — the sbt logs (the 7.4 GB CandidateGeneratorSpec log is kept as an excerpt); `probe-tests-v2.diff` — the probe tests as a patch; `ci-node-9a409ec75.txt` — CI facts at the pin; `mainnet-params-2026-11-03.txt` — live parameters read from a mainnet node; `pr-coverage-missed-7.md` — seven open PRs against the branch checked against the findings; `pr2504.diff` — the #2504 diff as read.
- `fieldwork/PROBE-REVIEW-v1.md` — the adversarial review of the probe tests before they were run.

The probe tests themselves are in this branch's `src/test` (properties named `PROBE C-…`). Run one spec at a time, for example:

    sbt -Denv=test "testOnly *InputBlockProcessorSpecification -- -z \"PROBE C-\""

Each probe is expected to FAIL at 9a409ec75; the failure message states the finding. Each checks its fixture first under a `setup:` clue, so a broken fixture fails with that label instead.

Not included (working papers; available on request): the per-workstream fieldwork and prosecutor passes, the per-finding falsification verdicts, the adjudication and the register of others' open issues and PRs. An earlier working note on #2504 read its cost check incorrectly; the report's text on #2504 supersedes it.

Prepared with Claude (Anthropic, Claude Fable 5.1 orchestrating; Claude Opus 5.5 for fieldwork and falsification; Claude Sonnet 5.5 for lookups) for cannonQ.
