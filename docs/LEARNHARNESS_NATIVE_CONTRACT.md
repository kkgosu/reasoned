# LearnHarness native contract: frozen reference v1

## Scope and authority

Stage 1 of [the interoperability plan](LEARNHARNESS_SYNC.md): freeze the actual native
contract and establish authored, offline reference cases. **This reference does not establish
cross-engine continuation, conversion, import installation, transfer UI or network sync.**
The subsequent Android persistence/restoration foundation is recorded under stage 2 of the plan;
native execution adaptation is still incomplete.

The authority is LearnHarness `main` at
[`3dd52110bbcbcba81645453ce0a8fdc230594c47`](https://github.com/amosblomqvist/learnharness/tree/3dd52110bbcbcba81645453ce0a8fdc230594c47),
not a moving branch, this document, or Reasoned's existing checkpoint schema. The original
idea, teaching approach and visual direction are Amos Blomqvist's LearnHarness.

[`fixtures/learnharness/v1/upstream.json`](../fixtures/learnharness/v1/upstream.json) records
SHA-256 hashes of 41 reference source/config/license files, chat/graph version 1, and the
upstream-locked TypeBox 1.3.27 package/integrity. Reference sources stay in a separate checkout;
byte-preserved Android prompt assets are untouched. Changing the baseline requires an explicit
manifest, inventory and fixture review; do not merely update a commit label.

Source paths below are relative to that pinned LearnHarness tree. Where upstream comments
and executable behavior disagree, the readers/folds and their reference checks decide.

## Persistence inventory

| Native file / source | Persisted meaning and preservation rule |
| --- | --- |
| `data/chats/<id>.json`; `src/core/contracts/chat.ts`, `src/store/chats.ts` | Stable string `id`, title, ISO creation/update times, chronological `messages`, optional `tree`, `turns`, `extractions`, `settings`, `kind`, `version`. Absent `kind` means teach; practice is explicit. Absent version means v1. Native reads do not stamp/migrate files. Future chat versions read as refused (`null`), not permission to replace an existing file. |
| `src/core/native-messages.ts` | `LhMessage` is native `user`, `assistant`, `toolResult` or `custom`, plus record `id`. Preserve content order, call IDs, timestamps, provider/API/model stamps, stop/error information, usage, opaque blocks/signatures and unknown fields. A Responses API request/history is not this record. |
| `src/core/contracts/plan.ts`, `src/agent/tools/plan.ts`, `mermaid-plan.ts`, `src/core/plan-tree.ts` | `PlanTree` stores nodes, directed prerequisite edges and structural version. `plan_init` authors Mermaid; successful result `details.tree` retains the parsed tree while model-facing content is slim. Persisted `plan-block` markers retain presentation snapshots. There is no approval, current-node or done ledger in the native tree. |
| `data/graph/{nodes,chains,sessions,coverage}.jsonl`; `src/core/contracts/graph.ts`, `src/store/graph.ts`, `src/core/graph.ts` | Append-order assertions, not a flat mutable canvas. The first node line defines name/statement/creation time; later same-ID lines contribute phrasings/aliases and provenance. Chains merge by the set of premises plus conclusion, retaining the first chain ID and unioning provenance. Do not confuse a link-append with a conflicting definition. |
| `data/graph/sources.json` | Map of notes-source IDs to original path, import time and optional extraction history/legacy fingerprint. A source record/provenance point is not the original notes file's bytes. |
| `data/graph/format.json`; `src/store/graph-format.ts` | Version stamp for the graph family. Absent means v1; newer means reads throw. Native readers tolerate corrupt lines/sidecars in ways an untrusted transfer importer must not copy. `positions.json` is rebuildable and unversioned. Canvas edges and implication nodes are derived from chains, never stored as knowledge assertions. |
| `data/jobs/<id>.json`; `src/core/contracts/jobs.ts`, `src/store/jobs.ts`, `src/agent/tools/jobs.ts` | Worker status, dispatch input, result, usage and delivery state are separate from transcript history. `done` with pending delivery is not a delivered transcript artifact. A read/import must not start/replay jobs or deliver saved results automatically. |

Native `getChat` mainly parses JSON/casts; graph readers skip malformed ledger lines and drop
unrenderable dangling chains in derived views. These are **not sufficient package validators**.
Stage 3 still needs bounded parsing, reference/version validation and whole-package rejection
before mutation. Historical rejected/partial calls and native synthetic messages must remain
preservable: blindly validating every archived call against today's authoring schema would
incorrectly reject honest history.

## Runtime/event inventory

| Area / native sources | Contract pinned by the reference cases |
| --- | --- |
| Questions: `src/core/question-core.ts`, `tool-parts.ts`, `pending.ts` | Probe tools are `probe` (MC) and `recall` (free recall); teaching twins are `quiz` and `explain`; `ask_user_question` collects ungraded preferences. Teacher calls require `type: predict/check` and `nodeId`; probe calls do not. MC calls carry stable option values, explanation and correct answer; free-recall calls commit using **`correctAnswer`**, not an Android-specific key. |
| Answers: `QuestionAnswerData`, `newAnswerResult`, `compactAnswerForModel` | The submitted answer is the question's `toolResult`. Full `details` preserve tool/kind, question snapshot, displayed order/1-based indices, stable selected values, free text/Other, don't-know, question thinking, reading thinking, confidence, correctness/dismissal, teacher explanation/commitment and answer time. Model-facing `content` carries only the compact learner contribution. A committed teacher answer is not learner speech. Don't-know is not zero confidence; dismissal is not an incorrect answer. Legacy `note` is retained and projects as thinking. |
| Pending/continuation: `native-messages.ts`, `src/core/input-events.ts` | A persisted unanswered call has **no saved placeholder result**. Native/view scans find the pending question; an answer append targets that call once. An answered tail can owe continuation, but dismissal pauses it. Practice pose acknowledgments deliberately owe no continuation. These folds describe native behavior, not permission for import to run a model. |
| Probe completion: `src/core/contracts/probe.ts`, `src/agent/tools/probe.ts`, `src/core/phases.ts` | `probe_done` input is `{assessment:{strands:[{strand,assessment}],summary?}}`. Completion needs an earlier nonfailed call and a matching, clean result of the same tool, not `details.ok:false`. Older clean acknowledgments without explicit `ok:true` count. The `probe-handoff` marker is scaffolding, never fabricated learner approval. |
| Plan/teach: `src/core/phases.ts`, `src/core/contracts/plan.ts` | Phase is probe until successful probe completion, then plan until the first node-tagged `quiz`/`explain` call. Presenting a plan or a natural learner reply alone does not flip teach. Current/visited nodes derive from tagged calls, even before answering; wrong answers do not mark nodes done. Native and grouped-view folds agree on the checked checkpoints. |
| Practice: `src/agent/tools/practice.ts`, `src/core/native-messages.ts`, `src/core/input-events.ts` | A problem commits `statement`, `mode` (`recall/discrimination/composition`), optional graph target IDs and hidden `modelSolution`. Clean pose ack activates it; only clean conclude ack/dismissal frees it. Thinking is a `practice-thinking` marker. Conclude records outcome, verdict, rung, miss class and retest; reveal uses the original solution. Silent `problem-dismissed` is UI-only; the next input prepends a synthetic conclude pair with outcome dismissed. That deliberately slim synthetic call is not an ordinary newly authored conclude. `practice-next` is explicit learner permission, not automatic progression. |
| Interruption/retry: `native-messages.ts`, `input-events.ts` | Failed/aborted attempts stay in the archive. Wire projection strips record IDs, filters/flattens markers, and repairs unanswered calls from completed generations with ephemeral interrupted results. Error/aborted generations get no fabricated orphan results. Retry drops only eligible failed-tail IDs from temporary agent history, without resubmitting input or rewriting disk history. |
| Compaction: `src/core/compaction.ts`, `native-messages.ts` | Full transcript stays persisted. Newest `compaction` marker references `firstKeptMessageId`, summary/header, token count, time and optional model. Model input becomes summary plus retained tail; missing reference falls back to full history. Do not export only this projection. A trailing compaction marker is not itself an unconsumed result: the reference pins the native continuation fold's exact tail law. |
| Coverage: `src/core/coverage.ts` | Only learner-facing teacher prose is extracted: ordinary assistant text and successful `send_to_user` prose. Learner text, answers, questions, thinking blocks, other tools/results and custom markers are invisible. Fingerprints include native message identity. Editing only an answer does not reopen extraction. Some older contract comments describe broader coverage; this executable code is authoritative. |
| Usage: `src/core/contracts/chat.ts`, `contracts/jobs.ts` | Preserve native assistant stamps and parallel turn/extraction/job logs independently. Turn ID, not just message ID, is the billing-log idempotency key. Unreported token/cache/reasoning fields remain absent, never manufactured as zero. Authored fixture usage and signatures are explicitly fake test data. |

Custom markers may flatten to user-role text **only for model projection**. Preserve their
native custom types and details in storage; role flattening destroys input-cause distinctions.
System prompt/tool declarations are request chrome, not imported learner history.

## Real artifact and external-file dependencies

This baseline does not need an external bundle for every rendered artifact:

| Native value / source | What is already inside history | What is not transferred by that value |
| --- | --- | --- |
| Delivered `subagent-steer`; `src/core/contracts/jobs.ts`, `src/core/view.ts` | `details.result`: research brief and source title/URLs; demo title, complete HTML/JS, tested flag and notes; complete SVG and notes; PDF brief, original path/question and consulted/read page lists. The marker content is model-facing text; details preserve the artifact. | Web pages, original PDF bytes, and any files/URLs the authored artifact itself references. Job IDs alone do not contain pending artifacts. |
| `read` tool result; `src/agent/tools/read.ts`, `src/agent/tool-execute.ts` | Text slice, or native image content with MIME type/base64 bytes. These bytes are record content. | The rest of a truncated text file, the original source file, or a portable meaning for its host path. |
| PDF worker; `src/agent/subagents/pdf-reader.ts` | Final brief is the teacher-facing result. | PDF page renders remain inside the worker context; they are not lesson attachments. |
| Learner paths/notes provenance; `src/agent/tools/fs-shared.ts`, `contracts/graph.ts` | Original path and source identity/usage metadata. Relative tool paths resolve against the learner's home on desktop. | A phone-accessible document or permission to read either device's filesystem. Never resolve imported paths automatically. |

The reference includes inline research/demo/SVG/PDF/image data plus deliberately missing PDF,
notes and external transcript references. Keeping a completed brief is different from being able
to reread its source. Stage 3 must disclose missing/unsupported dependencies, without silently
omitting them or claiming complete portability. Arbitrary links are not an instruction to crawl
the web. Preserve HTML/JS as inert data; an Android renderer is a separate safety/capability gate,
not permission to execute imported scripts or active SVG.

## Executable compatibility matrix

Reference files and setup/run instructions:
[`fixtures/learnharness/v1/README.md`](../fixtures/learnharness/v1/README.md).
Run from Reasoned against the separately prepared pinned checkout:

```sh
node scripts/check-learnharness-contract.mjs --upstream ../learnharness-contract
```

| Reference | Executed native acceptance checks |
| --- | --- |
| Teach transcript, 12 checkpoints | Pending probe → answer → bare/clean completion → handoff → plan review/reply → tagged prediction/answer → free recall/answer → compaction. Phase, pending call, continuation, current/visited and native/view parity. |
| Practice transcript, 10 checkpoints | Bare/acked pose, thinking, bare/acked conclude, next, another pose, silent dismissal, deferred conclusion, next. Active problem, pending dismissal and exact continuation behavior. |
| Interruption transcript, 2 checkpoints | Failed request retry IDs, deliberate abort, no orphan wire results, archive unchanged. |
| Seven answer cases | Multi-select values/shuffled display order, wrong prediction/confidence, free recall/both thinking channels, don't-know, Other, legacy note and dismissal. Full details versus compact model content. |
| Shared graph/source ledgers | First-definition identity, later aliases/provenance, canonical equivalent-chain merge, required source metadata, external chat references, derived canvas geometry and native prose coverage. |
| Artifacts and pending job | Inline result/view projection and PNG content survive; delivered versus pending are distinct. Only a local existing-job acknowledgment is exercised, with spawning forbidden. |
| Guard/read-write cases | Matching-source hashes, lazy/unsupported versions, refusal without mutation, read-only bytes, native corrupt-line tolerance, opaque-field/full-archive preservation on native resave and idempotent usage append. |

Recorded result: **45 checks passed**, Node.js 24.12.0, hash-matching upstream source snapshot
plus integrity-verified locked TypeBox. All fs adapter writes are confined to disposable
`LH_DATA_DIR`; there are no live providers, browser execution, renderer tests or Android runs.
This is a native-contract acceptance matrix, **not** a LearnHarness → Reasoned → LearnHarness
round trip or proof of cross-engine continuation. The downstream stages remain gated.

The shared-graph case intentionally includes `n_shared` defined in an external chat, later linked
by the selected lesson, and `n_notes` required by `arg_selected`. A future selected-lesson export
must retain those definitions/references and `n_selected`, but must not traverse through the
shared node to include `arg_unrelated`, `n_unrelated` or the external transcript. This stage
pins native ledger behavior and these boundary examples; it does not implement a selector.

## Legacy Reasoned limitations: do not synthesize missing evidence

`RuntimeSession.kt` stores Responses history plus a checkpoint; `SessionStore.kt` and
`KnowledgeStore.kt` separately store display rows, answers, usage and provenance. Existing data
can preserve what was actually captured, but cannot retroactively become an exact native archive.

| Native requirement | Legacy constraint / next-stage rule |
| --- | --- |
| Message IDs/timestamps/provider metadata | Response/call IDs may exist, but native per-message identities, exact historical times, opaque blocks/signatures and stamps are not guaranteed. Do not manufacture them from display-row numbers or session persistence time. New local compatibility identities must be labeled as such. |
| Full answer snapshots | Reuse stored question/answer evidence where present. Never infer submitted confidence, thinking, displayed order, dismissal or learner text from a teacher commitment or unsubmitted draft. Report unavailable fields. |
| Native phase/plan/practice events | A checkpoint's phase/approval/problem flags are not proof those native calls/results/markers occurred. No fabricated learner approval, answer or solution. New Android capture/restoration must follow the frozen event contract. |
| Turn/call usage logs | Aggregated counters do not reconstruct missing per-request/per-step/cache/cost/signature history. Preserve available records without presenting guessed zeros or invented bills as provider reports. |
| Compaction reference | Numeric protocol cuts/local summaries are not native kept-message IDs. Preserve original archive and map only demonstrable identities. |
| Graph provenance/coverage | Numeric display-row anchors and Android fingerprints are not automatically native message provenance/coverage. Preserve source identities, establish an explicit mapping and leave unverifiable coverage unavailable; never trigger paid re-extraction on import. |
| Artifacts/host files | Android-specific saved research/figure forms require explicit mapping; desktop paths do not grant Android file access. Unsupported data stays preserved and disclosed, not executed or silently discarded. |

Drafts/unsubmitted answer input stay local as agreed. Credentials, account/billing state and
global preferences are not learning-data transfers. LearnHarness remains format/runtime authority;
phone progress still requires the explicit direction, preview, manual-conflict and recoverable-
snapshot rules in the main plan. Native reader acceptance does not waive any of those protections.
