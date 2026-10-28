# LearnHarness native v1 reference cases

These are **authored offline test data**, not personal conversations, real assessments,
provider responses, measured usage, or verified rendered artifacts. They are not app assets
and must not become learner evidence. The fake model ID is intentionally not usable.

Baseline: LearnHarness `main` at
[`3dd52110bbcbcba81645453ce0a8fdc230594c47`](https://github.com/amosblomqvist/learnharness/tree/3dd52110bbcbcba81645453ce0a8fdc230594c47).
`upstream.json` pins source hashes, native versions and the upstream TypeBox lock entry.
No upstream source or dependency is added to Reasoned's runtime.

## Files

- `data/chats/chat_contract_teach.json`: probe, submitted multi-select answer, successful
  assessment/handoff, plan review/reply, wrong prediction, teaching prose, free-recall answer
  with both thinking channels, and compaction retaining the complete archive.
- `data/chats/chat_contract_practice.json`: graph context, pose/ack, thinking, conclude/ack,
  explicit next, another pose, silent dismissal and its deferred synthetic conclude pair.
  Its absent version is intentional: native lazy v1.
- `data/chats/chat_contract_interrupted.json`: failed generation and aborted retry; their
  unfinished calls were never executed. Both failed attempts remain in the record.
- `data/chats/chat_contract_artifacts.json`: delivered research, HTML/JS demo, SVG, PDF brief,
  inline PNG, and a separate acknowledged PDF job whose result delivery is still pending.
- `data/graph/`: native ledgers, format stamp and notes-source map. Repeated node IDs and
  equivalent chain assertions are intentional, not duplicate identities to discard.
- `data/jobs/`: a terminal worker with pending delivery. Reading it must not replay it.
- `cases.json`: hand-authored checkpoint/answer expectations and graph/dependency facts.
  A checkpoint's `through` selects an inclusive native message prefix; it is not a new
  transfer format. Negative/append/read-write checks live in the check script.

The source PDF, notes file and external chat transcript are **deliberately absent**.
The completed brief/image/artifacts remain readable as data, but these paths do not make
source files portable. Do not try to resolve them on the host or fetch the example URL.

## Run

Requires Node.js 24+ and a separately prepared, byte-identical upstream checkout with its
locked dependencies. For example, one-time network setup **outside this repository**:

```sh
git -c core.autocrlf=false clone --no-checkout https://github.com/amosblomqvist/learnharness.git ../learnharness-contract
git -C ../learnharness-contract -c core.autocrlf=false checkout --detach 3dd52110bbcbcba81645453ce0a8fdc230594c47
npm --prefix ../learnharness-contract ci --ignore-scripts --no-audit --no-fund
```

Then, from Reasoned, the check itself is offline:

```sh
node scripts/check-learnharness-contract.mjs --upstream ../learnharness-contract
```

No build, emulator, credentials, provider or LearnHarness server is needed. The check imports
native TypeScript directly, verifies the pinned source hashes before executing it, overrides
`LH_DATA_DIR` with a new disposable directory **before** importing stores, and removes that
directory afterwards. Original fixtures and the checkout's learning data are never written.
Only native core/fs adapters and TypeBox 1.3.27 are needed by these imports; a full upstream
installation is not otherwise exercised. Preserve LF in reference sources: CRLF conversion
correctly fails the byte hash check rather than silently testing different source bytes.

The job-tool check uses its existing-job seam with a spawn callback that fails immediately;
it only verifies the local acknowledgment. It does not start a worker. HTML, SVG, images,
paths and URLs are inspected as data, never executed, rendered, opened or fetched.

Recorded validation: **45 checks passed** using Node.js 24.12.0, the hash-matching upstream
source snapshot and the integrity-verified TypeBox package from its lock. This establishes
native reader/fold behavior, not Android conversion, trust-boundary validation, cross-engine
continuation or a working export/import feature. See
[`docs/LEARNHARNESS_NATIVE_CONTRACT.md`](../../../docs/LEARNHARNESS_NATIVE_CONTRACT.md).

Protocol strings in these authored records come from LearnHarness by Amos Blomqvist and
contributors, under its GNU Affero General Public License v3; see the
[bundled upstream license](../../../app/src/main/assets/learnharness/LICENSE) and the pinned
[original source](https://github.com/amosblomqvist/learnharness/tree/3dd52110bbcbcba81645453ce0a8fdc230594c47).
