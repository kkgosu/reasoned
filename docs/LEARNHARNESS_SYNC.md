# LearnHarness interoperability and selected-lesson sync

## Status

This is an implementation plan and compatibility investigation, **not an implemented
export/import feature**. No transfer round trip has been verified; an authored
Android → LearnHarness → Android runtime cycle now passes offline with fake providers,
not through a transfer installer.
Reasoned currently remains a standalone Android app. Its existing privacy/export statements
remain accurate until the feature is implemented.

Stage 1 now has a [frozen native-contract inventory and executable reference matrix](LEARNHARNESS_NATIVE_CONTRACT.md)
with [authored offline fixtures](../fixtures/learnharness/v1/README.md). The expanded reader/fold
suite passes 87 checks including six Android snapshots; a separate native-runtime runner passes
9 checks with 14 faux requests. Stage 2 has native persistence, pure Kotlin
restoration, question/answer appends, gated probe → plan → teaching and practice UI/provider paths (details below).
A bounded desktop web-UI smoke test also passes (5 checks, 6 faux requests). Full native runtime
adaptation, broader cross-engine continuation and Electron/full UI acceptance remain unverified.
Stage 3 now has a pure native-subgraph selection foundation; package validation/assembly, legacy
conversion and stages 4–6 remain unimplemented.

LearnHarness is the source of truth for the learning-data format and learning-runtime semantics;
Reasoned adapts to it, not the other way around. The LearnHarness library is the canonical home
of the learning data. Phone-authored progress becomes part of that library only after an explicit,
validated import there. Do not require an Android-specific lesson schema in LearnHarness: keep
transfer bookkeeping separate from its native learning records.

Authority does not mean silently discarding phone progress or automatically choosing the desktop
version in a conflict. The agreed manual resolution still applies. Native Android UI, SQLite,
coroutine lifecycle and provider adapters can remain; Electron, Node.js and Pi are not prerequisites
for implementing the same persisted learning contracts.

## Agreed engine direction and next milestones

The agreed direction is **an Android/Kotlin executor of LearnHarness learning contracts**, not an
embedded Node/Electron engine and not two permanently independent teaching semantics. Extend the
existing native runtime rather than rewrite it. Android keeps its Views, SQLite, foreground-only
coroutine lifecycle, permission checks and explicit connection/billing ownership. LearnHarness stays
unchanged except for a future separately implemented transfer integration.

1. First deliver explicit export/import for the supported native subset. Start with pure selected-
   lesson/subgraph selection and bounded validation; then preview, conflict-safe installation and
   document UI. Preserve unsupported content and report blockers; never advertise full continuation
   just because a record round-trips. Do not wait for every stage-2 parity feature to start this work.
2. After that subset passes transfer and continuation gates, make the existing native path the main
   path for newly created Reasoned lessons. Validate creation, model/billing capture, restoration and
   provider/tool behavior before changing the default. This is planned, not implemented now.
3. Keep the legacy runner for existing lessons. Legacy conversion is a separate explicit operation
   with an honest missing-history report; no automatic migration, fabricated native past or silent
   model change. Retire legacy execution only after a separately verified migration path exists.

Bounded native learner-text steering is now implemented; broader in-run inputs, Electron shell and
tool/artifact parity remain tracked gaps, not reasons to keep expanding stage 2 indefinitely before
a bounded transfer implementation. Safety, hidden-
answer protection, provenance and dependency validation are still mandatory gates for that subset.

## Agreed scope

- Compatibility baseline: LearnHarness's default branch, `main`, at
  [`3dd52110bbcbcba81645453ce0a8fdc230594c47`](https://github.com/amosblomqvist/learnharness/tree/3dd52110bbcbcba81645453ce0a8fdc230594c47).
  The repository has no `master` branch at the time of investigation. Do not follow a moving
  branch silently after implementing against this baseline.
- Begin with explicit local export/import. A later **Sync** action runs the same conversion
  and validation in an explicit, cancellable job; network transport must not define another
  learning-data format.
- Every operation has an explicit source, destination and lesson selection: LearnHarness → phone
  or phone → LearnHarness. Do not start with an automatic two-way merge or infer direction from
  timestamps. Show the actual desktop library/destination, not just a generic device label.
- Select lessons rather than synchronizing the entire live store. Each selection includes its
  related knowledge subgraph, not the entire graph or other lessons' transcripts.
- Drafts, unsubmitted option selections and other unfinished answer input stay local in the initial
  implementation. This does not exclude an unanswered persisted question, submitted answers or
  the durable state needed to resume a lesson.
- Conversion works in both directions. LearnHarness's contracts are authoritative, but that
  does **not** mean desktop edits automatically win a conflict.
- Deletions are local only. Absence from a package is not a deletion; neither app emits remote
  deletion instructions. Reimporting a locally deleted lesson requires an explicit decision.
- Independently edited versions require manual resolution. No overwrite before confirmation;
  preserve the other version as a recoverable snapshot. Do not merge learner answers or
  competing lesson continuations automatically.
- Resume support is a requirement, not just readable history. Align concrete runtime contracts
  before claiming unfinished lessons can move between engines.

## What the current code actually stores

| Area | LearnHarness baseline | Reasoned today | Required work |
| --- | --- | --- | --- |
| Lesson identity and metadata | `data/chats/<id>.json`: stable string ID, title, creation/update dates, optional `kind`, native messages | SQLite `sessions`, separate title, latest persistence time and JSON checkpoint; UUID session IDs | Preserve native metadata and IDs independently of SQLite row identities. Do not infer activity solely from autosave time. |
| Conversation | Native `user`, `assistant`, `toolResult`, and `custom` messages; IDs, timestamps, provider stamps and content blocks | Responses API items in `RuntimeSession.protocol`, plus separate numeric display rows | Keep the canonical native record losslessly; project provider input and UI from it. A provider request is not a complete export record. |
| Learner answers | A question's native `toolResult`; full `QuestionAnswerData` in `details`, compact learner-only JSON in `content` | Separate `questions`/`answers` tables and `answerCards`; different answer JSON also placed in provider history | Map stable selected values, displayed labels/order, free text, reasoning, reading-thinking, don't-know, correctness and confidence without inventing evidence. Preserve answer kind, tool, timestamp and dismissal. |
| Probe completion | Successful `probe_done` call/result; native phase fold checks call/result identity and success | `finish_probe`, structured assessment and local `handoff()` | Align the event and assessment contracts, including refusal/error paths. Renaming the tool alone is insufficient. |
| Planning and phases | Probe → plan → teach derived from events; teach begins at a node-tagged `quiz`/`explain` call | `phase="teacher"`, checkpoint status and explicit plan approval state | Distinguish provider-agent phase, displayed phase and local UI state. Preserve pending review and submitted revision requests; do not manufacture learner approval during import. Unsubmitted input stays local. |
| Practice | Problem pose/result, thinking/next/dismissal custom markers, successful conclude activation | `problems`, `practiceReady`, ordinary provider user messages for several transitions | Align problem ownership, immutable hidden solution, conclude/dismissal and next-problem permission. Merely displaying an imported problem does not prove it can safely continue. |
| Compaction | Full archive plus native compaction marker referencing a kept message ID | Full protocol plus separate `ContextSummary` and numeric cut | Preserve the full archive and reference identities. Never export only the compacted model context. |
| Knowledge | Append-only native node/chain ledgers, provenance, coverage/checkpoints and source metadata | SQLite nodes/chains, source foreign keys to numeric display rows, different coverage fingerprints | Preserve native IDs, ledger semantics and external provenance. Do not mark native coverage as valid for a different extraction view. |
| Usage and model settings | Native provider/model stamps plus parallel `turns` and `extractions` logs; per-chat overrides | Captured connection/model/effort and aggregated counters, with separate extraction-stage rows | Keep original native fields. Do not fabricate historical per-turn usage, cost, cache buckets or provider signatures from totals. |
| Artifacts and jobs | Delivered custom steers retain inline research/demo/SVG/PDF-brief results; native image results retain bytes. Pending jobs and host source files are separate | Android research and figure workflows have their own persisted forms | Map the inventoried inline payloads and disclose missing source files/unsupported renderers. Preserve unsupported records; do not execute imported jobs, scripts or active SVG. |

Relevant Android implementation:

- `app/src/main/java/com/kkgosu/reasoned/model/RuntimeSession.kt`
- `app/src/main/java/com/kkgosu/reasoned/model/RuntimeQuestion.kt`
- `app/src/main/java/com/kkgosu/reasoned/controllers/SessionRuntime.kt`
- `app/src/main/java/com/kkgosu/reasoned/controllers/SessionState.kt`
- `app/src/main/java/com/kkgosu/reasoned/controllers/ToolSchemas.kt`
- `app/src/main/java/com/kkgosu/reasoned/storage/SessionStore.kt`
- `app/src/main/java/com/kkgosu/reasoned/storage/KnowledgeStore.kt`

Existing Android records do not contain every native historical field. Engine alignment cannot
recover missing message timestamps, private provider signatures or per-call accounting after
the fact. A legacy conversion must report unavailable information honestly; it must not claim a
lossless native history was reconstructed. Newly captured records can satisfy the stronger
contract, and imported native records must retain their original information.

## Portable data and SQLite

Use LearnHarness's native `ChatRecord`, `LhMessage`, plan and graph contracts as the data inside
the transfer. The enclosing selection/transport metadata needs its own version, separate from
native chat version 1 and graph format version 1. Do not disguise a Reasoned checkpoint as a
LearnHarness chat file.

Retain original native JSON fields, including fields Android does not render. Keep custom
messages as custom messages: scaffolding must not become learner-authored speech. Preserve
provider-specific blocks and signatures without trying to execute them. Unsupported newer
versions must be refused before mutation, not rewritten as this build's version.

SQLite can remain the physical store. Its numeric display-message IDs are local projection
identities, not replacements for native string message IDs. Maintain an explicit persistent
mapping for provenance. Imported graph references must never be redirected to an arbitrary
numeric row or synthetic teacher passage.

An exported native chat should be usable as the corresponding LearnHarness chat record without
embedding Android database internals. Installing it and its graph contribution into an existing
LearnHarness store needs a validated import path there; copying over live files is not a safe
sync protocol.

## Direction, destination and user flow

An operation is a **one-way transfer of a selected set**, not permission to make both libraries
identical. The user decides where the data comes from, where it goes and which lessons it contains.
The source's learning data is read-only during transfer; the receiver validates and applies
changes to its own store. Acknowledgment may update transfer bookkeeping, not source lesson content. No receiver writes directly to the other app's working directory.

### Initial file-based workflow

| Direction | Sender | Receiver and final approval |
| --- | --- | --- |
| LearnHarness → phone | In LearnHarness, select lessons and export their native records plus related subgraph. | In Reasoned, choose the package, review changes against the phone's current data, resolve conflicts and confirm import. |
| Phone → LearnHarness | In Reasoned, select lessons and export in the LearnHarness-compatible format. | In LearnHarness, choose the package and destination library, review changes, resolve conflicts and confirm import. |

The initial workflow needs no network discovery or remote lesson list. Selecting a file is not
the same as importing it. A sender-side preview cannot authorize replacing receiver data that
may have changed since export. File creation reports **exported**, not **synchronized**.

A phone-created lesson keeps its stable identity when first imported into LearnHarness. An
existing LearnHarness lesson continued on the phone returns under its original identity: it is
an update candidate, not a new duplicate. Imported lessons may contain progress from either app;
origin labels must not imply all their messages were authored on the exporting device.

### Later connected Sync workflow

The entry screen presents two unambiguous actions, **Send to LearnHarness** and **Receive from
LearnHarness**. LearnHarness can expose the corresponding send/receive actions. Each operation:

1. Select the paired desktop and the specific LearnHarness library. A connection address is not
   a substitute for library identity; switching libraries requires a new explicit selection.
2. Show a persistent `source → destination` heading. Choose lessons from the source: locally for
   send, or from an authenticated remote inventory for receive. Shared graph dependencies are
   calculated automatically and disclosed; do not ask users to hand-select individual edges.
3. Show the proposed additions, updates, unchanged records, conflicts and unsupported dependencies.
   Before sending, disclose what leaves the source. Before applying, obtain the destination
   user's approval of the actual changes; pairing alone is not overwrite authorization. On a
   connected transfer, the approval UI may be presented to the initiating user, but must refer
   to a fresh receiver-generated preview and be revalidated by that receiver.
4. Transfer, validate, resolve conflicts and commit through the same import path used for local
   files. Reversing direction starts a separate operation; it is never an implicit side effect.
5. Return an import receipt identifying the operation, destination and accepted record hashes.
   Report success only for committed records. A dropped connection after commit permits safe
   retry/receipt retrieval, not a second append or a false rollback claim.

Use existing native navigation, styles and accessible controls. Keep direction and device/library
names visible in the preview and final confirmation. Do not use ambiguous conflict buttons such
as "local" and "remote" when the initiating screen can be on either device.

## Attachments and local-only state

Ordinary Markdown, LaTeX, Mermaid, saved research text/excerpts and Reasoned's persisted SVG
source/notes are record content, not a reason to build a general attachment service. Transfer
them with the native data and regenerate local previews without inference. Render caches,
scroll/zoom state, drafts and unsubmitted answers are not part of the initial exchange.

The [pinned dependency inventory](LEARNHARNESS_NATIVE_CONTRACT.md#real-artifact-and-external-file-dependencies)
confirms delivered research briefs, demo HTML/JS, SVG and PDF briefs live inside native custom
messages; native read-image results carry inline bytes. These do not require a separate artifact
file merely to preserve their data. Original PDFs, notes and other host paths are different:
a path or pending job ID alone does not transfer the referenced data. Include required portable
resources and validate references, or explicitly block the affected
lesson as incomplete; never silently omit a dependency and report a complete transfer. Do not
fetch arbitrary linked web pages, add a PDF engine or execute HTML/JavaScript just to make file
transfer possible. Preserving an artifact's data and supporting its Android renderer are separate
capabilities, both disclosed before claiming that lesson can continue correctly.

## Selected-lesson subgraph

Define selection by provenance, not by a graph traversal that can pull in the whole library:

1. Select proposition assertions and reasoning-chain assertions attributed to the selected
   lesson IDs.
2. Include every premise and conclusion needed by those selected chains, even when a required
   proposition originated in another lesson. Preserve stable native identities and the original
   defining node record; a later link-append is not a replacement definition.
3. Do not recursively include every chain incident to those required nodes. Do not export
   derived implication nodes or canvas arrows as stored propositions/chains.
4. Preserve references to other contributing chats/notes as external provenance. Do not copy
   their transcripts or pretend those source messages are present. Source views must distinguish
   available local evidence from an external reference.
5. Include only applicable coverage/checkpoints. Android and desktop coverage fingerprints are
   not interchangeable until their extraction views are equivalent and tested. Missing coverage
   must not automatically start a paid extraction job.

A shared node can already exist on the destination. Same ID with conflicting defining content
is a conflict, not permission to replace it. No fuzzy identity matching, AI-assisted re-extraction
or automatic provenance union across conflicting versions is part of file transfer.

## Explicit import and conflict handling

1. Read a bounded package/file set off the main thread. Validate schema versions, structure,
   IDs, duplicate identities, chain endpoints and provenance before changing stored data.
   Treat all content and filenames as untrusted. Reject an invalid package as a whole rather
   than silently discarding records.
2. Produce a preview: selected lesson, included subgraph, dependencies, unsupported features,
   destination changes and conflicts. Importing is not permission to contact a provider.
3. Compare with the last mutually confirmed baseline using content hashes, not timestamps.
   Apply the decision table below. A selected direction never authorizes replacing a destination
   that has newer or independently changed content. With no common baseline, different records
   with the same ID require an explicit decision.
4. For a conflict, name the actual versions: choose the LearnHarness version, choose the phone
   version, or skip/cancel. These labels must identify the chosen snapshots even when direction reverses.
   Preserve the displaced complete version and its related graph/source state before replacement,
   outside the active lesson/graph stores. A competing continuation remains a recoverable snapshot;
   it is not spliced into live history or exposed as duplicate learner evidence. Shared graph
   identity conflicts must be visible too; resolution must respect native graph-ledger semantics.
5. Apply the confirmed selection and its dependencies in one SQLite transaction on Android.
   Respect existing foreign keys and source anchors: `INSERT OR REPLACE` on parents is unsafe.
   LearnHarness's multiple files do not provide an equivalent database transaction: inspect and
   reuse its existing mutation/write primitives first, then provide serialized, recoverable
   installation with staged validation and preserved originals. Do not claim a sequence of file
   writes is atomic. On either destination, recheck the revision after the preview; intervening
   edits invalidate approval, and concurrent generation/extraction cannot race the commit.
6. Reimporting an identical package is a no-op. Do not duplicate messages, answers, graph
   assertions or usage entries. Do not delete destination-only records.
7. Record a new common baseline only when the exchange has actually been acknowledged on both
   sides, and only for the versions actually accepted. A skipped/conflicted record is not marked
   synchronized. Writing an export file alone does not prove the other app imported it. In the
   initial manual-file workflow, absent an imported receipt, retain the conservative no-common-
   baseline behavior rather than inventing acknowledgment. A file receipt can later carry the
   same confirmation used by connected Sync without changing native lesson records.

| Source/destination state | Behavior in the chosen direction |
| --- | --- |
| Same content | No-op; no duplicate history, graph assertions or usage. |
| Destination has no record | Preview a new import. A locally deleted identity still requires an explicit restore decision. |
| Only source changed since the common baseline | Preview an update; apply only after confirmation. |
| Only destination changed since the common baseline | Do not overwrite it with the older source snapshot. Offer skip or a separate reverse transfer; forced replacement requires explicit version selection and preservation of the displaced version. |
| Both changed differently | Manual conflict resolution; retain both complete versions. |
| Same ID, different content, no common baseline | Manual conflict resolution; timestamps do not establish authority. |
| Record absent from the source/package | No deletion or mutation of destination-only data. |

A typical round trip is LearnHarness → phone, phone study, then phone → LearnHarness. If the
desktop lesson was unchanged, import the phone continuation after review. If it was also edited,
show both versions. After choosing one, it becomes the canonical LearnHarness version; bringing
that accepted version back to the phone is a separate explicit receive operation. Do not overwrite
the phone merely because its export was accepted on the desktop.

An active lesson must first reach a durable stopped checkpoint before export/import can alter
its state. Coordinate with the existing runtime and extraction writer rather than introducing
an independent writer. Cancellation must not leave half-imported lessons or advance the
baseline for an exchange that did not complete.

Credentials, OAuth tokens, API keys, account state, global settings and signing material are
never learning-data payloads. Imported model settings cannot silently change the captured
connection or billing source. If a provider/model or artifact is unsupported, show the blocker;
require an explicit model decision before any continuation request. Opening an imported lesson
must never resume generation or background jobs automatically.

## Implementation order and deliverables

Implement and validate one bounded milestone at a time; the agreed native-subset transfer may
proceed with explicit stage-2 capability gaps. This plan does not authorize live-provider calls.
Reasoned changes happen in this repository. LearnHarness integration needs a separate checkout
of the pinned reference; do not edit the byte-preserved vendored files in Android assets.

### 1. Freeze the native contract and offline reference cases

Inventory the pinned chat/plan/graph/tool contracts, coverage rules and real artifact dependencies.
Create authored reference cases for probe, plan review, teach, practice, interruption and compaction,
including shared graph nodes and external source references. Record which legacy Android fields
are unavailable rather than synthesizing historical evidence.

**Deliverable:** an executable compatibility matrix and native reference cases; no new transfer UI.
**Gate:** cases are accepted by the pinned LearnHarness readers/folds, not merely by an Android
parser or this document's description.

**Reference delivered:** [native inventory/matrix](LEARNHARNESS_NATIVE_CONTRACT.md),
[`fixtures/learnharness/v1/`](../fixtures/learnharness/v1/README.md) and
[`scripts/check-learnharness-contract.mjs`](../scripts/check-learnharness-contract.mjs).
The original 45 native checks, expanded to 62 for question/answer construction, pass offline;
setup, exact scope and limitations are recorded with the fixtures. Reference work itself does
not change the Android engine/store or implement a transfer feature.

### 2. Adapt Android persistence and runtime to LearnHarness

Add lossless native-record persistence and stable native-to-display identity mapping in the
existing session store. Keep provider input and UI as projections. Align answer details/model
content, probe completion, plan/phase derivation, pending-question gates, practice markers and
compaction against the reference. Preserve existing sessions and local draft behavior without
transferring drafts. Use the separate Android compatibility layer where required; do not rewrite
vendored prompts or replace the native architecture.

**Deliverable:** supported lessons can be represented and continued under the native contracts.
**Gate:** offline continuation/restoration cases pass; importing/opening never invokes a provider,
changes billing source, reveals a hidden answer or runs an imported job.

**In progress — persistence/restoration foundation (2a), not stage-2 completion:**

- `NativeChat.kt` keeps the original JSON string immutable, including unknown fields, opaque
  signatures and inline artifacts. Mutable JSON snapshots are isolated. It checks storage bounds,
  native version and message identities/roles; it is **not** the stage-3 external-package validator.
- SQLite v4 adds nullable `sessions.native_record` and `native_display_ids`, mapping native
  message/ordered-block identities to locally allocated numeric row IDs without string hashes.
  Record, checkpoint, mappings and learner evidence save in the same transaction. Native records
  use bounded SQL reads for CursorWindow safety. Deleting a session also erases its native data;
  a stale legacy save cannot discard a native archive. Local row IDs never enter native JSON.
- v1/v2/v3 migrations leave legacy checkpoint/evidence untouched and native history absent.
  Existing and newly created lessons still use the existing runner; no native past is invented.
- Pure native folds cover phase, pending question, plan position, practice activation/dismissal,
  continuation and retry drops. Native model projection preserves the full archive, applies the
  compaction cut, filters/flattens markers and makes interrupted results ephemeral. This returns
  native pi-shaped messages, **not** a Responses payload. Full answers stay separate from the
  learner-only compact answer projection. None of these functions renders or executes artifacts.
- The foundation initially refused all native-backed sessions before legacy handoff/migration.
  The teaching adapter in 2c below now opens a supported subset locally; unsupported records stay
  blocked. Native archives never run through the legacy engine or take their local checkpoint as
  transcript authority. There is no production import action yet.

Validation: the seven `NativeChatTest` JVM tests pass, including all **24 authored native state
checkpoints and seven answer cases**, plus refusal, immutability and model-projection checks.
The complete existing JVM suite also passes. Android Studio analysis reports no errors in the
changed production files. Four `NativeSessionStoreTest` instrumentation tests cover v1–v3
migration, raw/Unicode round trips, stable multipart IDs, transaction rollback/deletion, stale
legacy writes and no-provider opening. On the user-selected running emulator (API 36), these four
and `SessionManagementTest#versionTwoMigrationPreservesHistoryAndSupportsRenaming` passed:
**`OK (5 tests)`**. Debug and test APK assembly also passed. The run used separate temporary
fixture databases, no app-data clearing and no real provider; temporary databases were deleted
and the emulator's Wi-Fi/mobile-data settings restored afterward. This validates the persistence
foundation on API 36, not cross-engine continuation or the full stage-2 gate.

Run the JVM reference checks with:

```sh
gw ./gradlew :app:testDebugUnitTest --tests com.kkgosu.reasoned.NativeChatTest --offline
```

On Windows use `gw ./gradlew.bat ...`. Use the repository's disposable-emulator procedure for
instrumentation; never target a personal device. The focused instrumented run was:

```sh
adb -s emulator-5554 shell am instrument -w -r \
  -e class 'com.kkgosu.reasoned.NativeSessionStoreTest,com.kkgosu.reasoned.SessionManagementTest#versionTwoMigrationPreservesHistoryAndSupportsRenaming' \
  com.kkgosu.reasoned.debug.test/androidx.test.runner.AndroidJUnitRunner
```

**Question/answer foundation (2b), not stage-2 completion:**

- `NativeQuestion.kt` projects the five native question tools onto existing `RuntimeQuestion`
  data/submit validation. Its bounded active-question parser mirrors native value normalization,
  deterministic FNV-1a/Mulberry32 shuffle over UTF-16, option order, answer-key resolution and
  missing/legacy optional fields without changing archived arguments or the legacy parser.
- The client projection contains no grade, answer key, explanation or committed teacher answer.
  Native reveal rules remain distinct from submission. The native confidence stops/default are
  recorded; the live legacy UI has **not** been switched to them.
- Explicit `NativeChat.answerPending`/`dismissQuestion` pure actions check the current call ID,
  build native full `details` and learner-only compact `content`, and return a new archive with
  one result. Existing IDs/opaque fields/history survive; absent version is stamped on this new
  write, not on reads. Dismissal is a pause, not an incorrect answer. Duplicate/stale targets,
  failed/aborted generations, forged graded input and invalid/over-limit actions are refused.
  No action saves itself, grants foreground permission, invokes a provider or runs a job.
- Shared [`questions.json`](../fixtures/learnharness/v1/questions.json) vectors cover **16 question
  projections, 29 submissions and eight refused calls**, checked against the pinned native
  functions/dock construction and Kotlin. The full JVM suite passes, including seven new
  `NativeQuestionTest` tests for projection/append/dismissal, hidden-answer safety and refusal
  without archive mutation. The reference checker now passes **62 checks**.
- Debug/test APK assembly passed. The same focused API-36 emulator command above now passes
  **`OK (6 tests)`**: the five native store tests plus the v2 migration regression. The added case
  persists/reopens a submitted native answer with confidence 90, full teacher commitment, compact
  learner-only content and unchanged earlier message/display identities. Temporary databases were
  deleted; Wi-Fi/mobile-data settings were restored; there were no real provider calls.

To run both pure Kotlin reference suites, add
`--tests com.kkgosu.reasoned.NativeQuestionTest` to the JVM command above.
The active-question parser uses Android limits (32 options, bounded strings; local answer text
2,000 characters). This is not proof of every TypeBox coercion or every historical call being
executable. Unsupported active calls must remain archived and be disclosed before enabling
continuation; do not validate/rewrite the complete historical transcript through this parser.

**Gated teaching runtime (2c), not stage-2 completion:**

- `NativeTeaching.kt` projects an existing native teach-phase lesson and saved plan into the shared
  transcript, answered cards, question dock and plan page. Native identities drive display rows and
  plan positions. Historical probe/plan/questions and delivered `send_to_user` prose are supported;
  saved answer snapshots, not a new grading pass, supply revealed commitments. Out-of-subset
  historical cards remain archived but block this UI path rather than being coerced. Pending graded
  questions hide both answer fields and their owning generation's reasoning summary. Failed text
  is labeled interrupted and excluded from local teacher-prose evidence.
- `SessionRuntime.open` performs only local restoration. Explicit answer submission commits native
  full/compact results, cleared submitted drafts and display identities transactionally **before**
  the request. Native confidence uses `[0, 30, 50, 70, 90, 100]`; legacy confidence is unchanged.
  New user messages, successful assistant turns, errors and cancellations append without rewriting
  the earlier native record. Stopped turns do not resume on reopen. Eligible retry omits failed
  attempts only from provider input and does not resubmit the learner answer.
- `NativeResponses.kt` bridges native messages to the existing Responses provider. Signature/call-ID
  conventions follow the upstream lockfile's pi-ai 1.4.0 transport. Completed response JSON stays
  in `reasonedResponses`, including opaque fields; same-model reasoning signatures are replayed
  without being rendered. Reported token/cache/reasoning counts are preserved; absent usage/cost
  fields are not invented. This is **not yet proof that newly appended messages satisfy every
  desktop usage/accounting consumer**: partial metadata, native turn/extraction logs and failed
  stream metadata still need acceptance before export can be enabled.
- At the initial 2c checkpoint, only the selected question mechanic (`quiz` or `explain`) and
  `ask_user_question` were advertised. Section 2d extends this to probe/plan execution. Existing
  namespace, replay, argument, plan-reference and output limits still apply. Section 2e adds native
  practice. Jobs/artifacts and unknown historical tools remain blocked, with the original archive retained and no legacy
  fallback. Section 2f adds native compaction creation, never a legacy index-based cut.
  Native research controls are unavailable. Newly created sessions still
  use the legacy path.
- Offline `NativeResponsesTest` covers restoration, saved commitments, response/signature/usage
  mapping, wire-only retry and permission guards. `NativeRuntimeTest` exercises the actual runtime
  and SQLite with fake providers: commit-before-request, next-question restoration, hidden-answer
  safety, durable failure/retry, cancellation, don't-know thinking, denied research and transactional
  refusal. The account fixture also checks routing without changing the selected connection.
  These are authored cases,
  **not** a transfer or a continuation test against the installed desktop app. Its files/lesson and
  real providers have not been touched.

Validation for 2c: the full JVM suite, debug APK and test APK build pass. The latest API-36
instrumentation run on 2026-10-29 passed **`OK (11 tests)`**: four native runtime cases, five native
store cases, the v2 migration and the legacy fake-provider regression. An earlier expanded run
failed the account fixture because it emitted foreign namespace `functions`; the gate correctly
denied it. The corrected fixture uses the advertised `RuntimeTools.NAMESPACE`, and the full
11-case rerun now passes. The JVM suite also verifies the declared account namespace and rejects
`functions`.
The 62 pinned upstream reference checks still pass separately. IDE analysis found no errors in
the new transport/projection or changed runtime/fragment files (deprecation/style warnings remain).
Emulator networking was disabled for instrumentation and restored afterward; temporary fixture
DBs were deleted, with no app-data clearing or personal-device use. No live model/OAuth/Brave
requests, installed desktop access, full UI journey or cross-engine acceptance was run.

```sh
gw ./gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --offline
adb -s emulator-5554 shell am instrument -w -r \
  -e class 'com.kkgosu.reasoned.NativeRuntimeTest,com.kkgosu.reasoned.NativeSessionStoreTest,com.kkgosu.reasoned.SessionManagementTest#versionTwoMigrationPreservesHistoryAndSupportsRenaming,com.kkgosu.reasoned.RuntimeTest#fakeProviderRunsProbeRouteTeacherAndDurableAnswers' \
  com.kkgosu.reasoned.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Use the installation/network-isolation procedure above and in the root contributor instructions;
these commands do not select or prepare an emulator automatically.

**Native probe → plan → teaching (2d), not stage-2 completion:**

- The native adapter also restores probe and plan phases locally. Probe questions use `probe` or
  `recall`; `probe_done` preserves the assessment arguments, slim native acknowledgment and one
  deterministic `probe-handoff` marker. The validated call/result/marker save atomically before
  the teacher request. Only newly returned, validated local calls execute; opening an archive
  never dispatches historical calls or requests inference.
- `NativePlan.kt` implements the pinned Mermaid authoring subset and reuses `StudyPlan` DAG/size
  validation (1–24 nodes, at most 80 edges, 16,000 source characters). A successful `plan_init`
  replaces the native tree and retains the full tree only in result details; model content carries
  node IDs and the pinned reminder. Invalid maps return a durable refusal without replacing the
  old tree; repairs share an eight-step/180-second foreground turn bound.
- Presenting the initial/revised planning map stops for a natural learner reply. There is no
  legacy route approval or invented verdict event. Phase remains `plan` until a node-tagged
  `quiz`/`explain` call; mid-teaching rewrites do not create another approval barrier. Reopening
  and empty Retry at the presented-plan barrier do not request a model. Assessment and plan rows
  use existing display roles and remain outside teacher-prose extraction.
- `plans.json` supplies nine shared success/refusal vectors, verified by both Kotlin and the real
  upstream parser/tree constructor. The pinned checker now passes **71 checks**. `NativePlanTest`
  also checks acknowledgment/details parity, phase boundaries, immutability and refusal rollback.
- The full JVM suite and debug/test APK assembly pass. The latest API-36 offline selection passes
  **`OK (12 tests)`**: five native runtime cases, five native store cases, migration and legacy
  runtime regression. The new fake-provider flow covers probe answer → handoff → invalid-map
  repair → plan persistence/reopen → explicit natural reply → hidden teaching question. An initial
  test fixture incorrectly emitted an account namespace for API-key mode; correcting that fixture,
  not the permission gate, produced the passing run. Emulator networking was restored afterward.
  No real provider, installed desktop data or full UI/desktop acceptance was used.

**Native practice lifecycle (2e), not stage-2 completion:**

- `NativePractice.kt` adapts native `problem`/`conclude` to the existing runtime and practice
  screen. Only clean acknowledgments activate/free the slot. The original problem call commits
  the hidden solution; conclude reveals that exact text, never a re-authored solution. Practice
  reasoning summaries remain hidden. Verdicts are model judgments, not mastery evidence.
- Each active-problem send commits a native `practice-thinking` marker with the exact problem
  call ID before requesting a response. Multiple submissions, prose hints, failures and wire-only
  retry preserve the same problem and do not duplicate learner evidence. Reopening is local-only.
- Conclude accepts native nullable rung/missClass and does not impose the legacy minimum-attempt
  gate. Its acknowledgment permits one closing-prose response; all further tools in that turn
  are denied, including on retry. A new problem requires later explicit learner input. The Next
  action writes `practice-next`; ordinary follow-up text remains ordinary learner text.
- Dismissal appends only UI-only `problem-dismissed`, preserves the unsent draft and neither
  reveals the solution nor invokes a provider. The next explicit text/Next input atomically
  prepends the pinned synthetic conclude/ack pair once. The marker never enters provider input.
- Native targets are optional; new empty target arrays canonicalize to absent while raw provider
  JSON is retained. Newly posed target IDs validate against the whole current Android graph,
  not only selected material. Name-to-ID healing and other coercions are not implemented; use
  canonical IDs. Graph-context snapshots remain source context, not imported graph assertions.
  Android bounds remain 24 problems, 40 submissions each, eight targets, 2,000 submission,
  4,000 statement and 6,000 solution characters. Extra tools/jobs/artifacts remain blocked.
- `NativePracticeTest` checks authored checkpoints, exact marker/ack/deferred-pair parity,
  hidden/revealed commitments, optional targets, nullable judgments, limits and permission gates.
  The native checker passes **72 checks**, including exact practice input builders. A proposed
  extra upstream practice-tool schema execution check could not load `ls.ts` in the partial
  source snapshot; it is not part of the reported count or claimed tool-factory acceptance.
- The full JVM suite and debug/test APK assembly pass. API-36 offline instrumentation passes
  **`OK (14 tests)`**: seven native runtime, five native store, migration and legacy regression.
  New fake-provider cases cover practice pose → durable thinking → failure/retry → another
  attempt → conclude/reveal → closing prose → reopen/pause → Next → silent dismissal/deferred
  conclusion, plus denial of automatic re-pose after conclude and safe closing-only retry.
  Emulator networking was restored. No real provider, desktop data or full UI journey was used.

**Native compaction (2f), not stage-2 completion:**

- `NativeCompaction.kt` follows the pinned native content-only token sizing, usage anchor,
  20,000-token kept-tail walk and stable `firstKeptMessageId` boundary. Aborted/error/all-zero
  usage cannot anchor the estimate; a later marker invalidates older usage. JSON scaffolding
  and opaque reasoning ciphertext are not counted as text tokens. Seven shared `compaction.json`
  vectors pass in Kotlin and the real upstream functions; the native checker passes **79 checks**.
- The existing foreground command/mutex gates a tool-free summary request before native provider
  continuation and at successful turn settle. It uses the same captured model, effort and
  connection, the existing compaction prompt, the prior summary plus newly dropped span, and
  exact plan nodes/edges/statuses. Learner input remains write-ahead durable, including before
  turn-start summarization. Android keeps this work foreground-only and serialized: it does not
  add the desktop background registry or permit simultaneous drafting/sending during compaction.
  Opening a saved lesson never starts a summary.
- A valid nonempty completed response atomically appends the native marker and its `turns` usage
  entry, retaining raw provider JSON and all earlier transcript/opaque fields/display identities.
  Missing cache/cost/reasoning fields remain absent. The provider alone receives summary + exact
  retained tail. Pending question commitments and full learner transcript remain untouched;
  summaries do not become teacher prose, answer cards or graph evidence. Chained summaries
  advance the previous boundary; no-progress cuts do not spend another summary call.
- Failure, empty/incomplete output, denied tools and cancellation cannot replace the prior cut.
  A failed settle summary does not append a failed teacher or steal the pending question; the
  next explicit input can retry the gate. Completed reported usage survives rejection in local
  totals/run accounting. Section 2g adds native parallel-log accounting for these failures.
  The fixed native tail can itself exceed a small window; an unshrinkable request is blocked,
  never silently truncated. Compaction-divider UI remains unimplemented.
- Full JVM tests and debug/test APK builds pass. API-36 offline instrumentation passes
  **`OK (17 tests)`**: ten native runtime, five native store, migration and legacy regression.
  Three new runtime cases cover successful settle/reopen with the same hidden question,
  incomplete summary → durable answer → turn-start summary → continuation, and cancellation
  with no failed teacher or parallel paid turn. The first run was interrupted by another
  instrumentation process. Two fixture failures were then corrected by selecting the advertised
  recall mechanic/account namespace, without relaxing tool guards; the complete rerun passes.
  Emulator Wi-Fi/mobile data were already off and were left off. No real provider, installed
  desktop files or full UI/desktop acceptance was used.

**Native accounting and authored desktop-reader acceptance (2g), not stage-2 completion:**

- `NativeResponses` now shares validated usage normalization between executable assistant output
  and the money channel. Each foreground conversation invocation appends one native `TurnUsage`
  with one `CallUsage` per provider call, including local tool repair/handoff/closing steps. Entry
  IDs are idempotent and independent of message provenance: retries are new invocations, not
  duplicate appends. Existing native logs and unknown fields are never rewritten. The final
  assistant/failure owns the entry; model/provider stamps follow the explicitly selected connection.
- Reported usage is captured before tool/output validation. Denied tools, incomplete output and
  malformed authoring cannot erase a valid report or charge it twice. Failed/cancelled compaction
  appends a parallel entry linked to the existing transcript, with `reasonedPurpose=compaction`
  and `reasonedStatus`; it creates neither a compaction marker nor a failed teacher. Successful
  summaries retain their marker provenance and atomic marker/usage save. Conversation logs
  settle in the existing non-cancellable `finally` checkpoint, not a new job or writer.
- SSE terminal failures carry their bounded response to the native runner without leaking its
  diagnostic text into error strings/UI/logs. Completed finished-item execution stays strict;
  differing terminal-only output metadata is retained as `reasonedTerminalOutput`. Raw terminal
  metadata/signatures remain archival data, not executable fallback. Failed assistant records
  retain valid usage and bounded terminal JSON; rejected summary JSON rides its usage entry.
- Fresh input/cache-read/cache-write stay disjoint, with reasoning a subset of output. Counts
  must be bounded nonnegative integers; invalid usage is retained only in opaque raw response,
  never trusted as counters. Missing fields stay absent, including cache, reasoning and cost.
  An attempted call with no trustworthy report contributes `{}` to `steps`, **not zero spend**.
  Android totals accumulate known reported counts only; they are not a complete invoice. No
  catalog price, cache-retention bucket, billing source or charge is invented/switched.
- The complete JVM suite and debug/test APK builds pass. API-36 offline instrumentation passes
  **`OK (18 tests)`**: eleven native runtime, five store, migration and legacy regression. Added
  coverage checks multi-step grouping, cache splits, denied tool → terminal failure → network
  failure → invalid usage → successful retry, unknown vs zero counts, summary failure/cancellation
  and reopen/no duplicate request. Emulator networking was already disabled and remains disabled.
- Four unmodified `SessionStore` snapshots from authored Android fake-provider tests (teaching,
  practice, compaction and failures) pass the pinned LearnHarness `chatStore` read, native
  view/model/phase/pending/problem folds, idempotent usage append and resave. Token sums, unknown
  steps, per-invocation grouping, provenance, opaque fields and original snapshot bytes are checked.
  This is **83 checks with `--android-records` (79 baseline + 4 Android cases)**, not a hand-authored
  imitation of Android output. Snapshot files live only in the debug test cache/ignored build
  output; no learner data is pulled. Reproduction is in the fixture README.
- Remaining limits: no desktop server/agent/provider execution, desktop UI or transfer installer
  was exercised. At the 2g checkpoint, abrupt death before the final turn-log append was not
  recovery-tested; section 2h below adds bounded recovery without inferring missing usage.
  Unreported spend after EOF/network loss/cancellation is unknowable. This is terminal response
  capture, not a byte-for-byte SSE event journal or acceptance of every provider/artifact shape.
  Installed desktop data, credentials and real models remain untouched.

**Native accounting recovery after process death (2h), still not stage-2 completion:**

- A local `checkpoint.nativeTurn` stores pending call state, accumulated normalized steps and
  bounded receipt metadata alongside known counters. It is not inserted into portable native
  history or model input. Receipts checkpoint before tool/output acceptance; the final append
  and checkpoint clearing commit atomically. No schema migration, new writer or background job.
- Startup recovery is one SQLite transaction: finalize an interrupted native turn once, retain
  `{}` for a possibly in-flight call with unknown usage, reconcile local run counts/status, and
  clear the journal. Already committed final entries also repair a stale `running` row. A failed
  transaction retains the original checkpoint/archive/run state for a later safe retry.
- Unexpected conversation death is a retryable native error, not intentional `aborted` Stop.
  Opening remains local-only; only explicit Retry can continue. A killed summarizer retains the
  old cut, pending hidden question and display IDs, without a failed teacher or automatic request.
- Offline JVM tests and debug/test APK builds pass. The API-36 ordinary run has **19 passing
  checks plus two opt-in harness skips** (`OK (21 tests)`). Five synthetic checkpoint boundaries
  cover in-flight, receipt-before-acceptance, accepted output, compaction and final-log/run-status
  ordering, including cache splits, rollback and repeated recovery.
- Two separate real process-kill fixtures pass their fresh reader assertions (`OK (1 test)` each):
  kill during a second teaching request after a committed local tool step, and kill during settle
  compaction after a newly committed hidden question. Writer `Process crashed` output is expected,
  not itself a passing test. Ordinary suites cannot kill their runner without explicit opt-in.
- With both recovery snapshots, the pinned reader/fold/resave checker passes **85 checks**
  (79 baseline + 4 original Android + 2 recovery). Snapshots stay synthetic, local and ignored.
  This does not cover death before a receipt reaches SQLite, every SSE boundary, task/UI
  restoration, or desktop execution/transfer. Emulator networking remains off; no real models
  or installed desktop data were used. Reproduction is in the fixture README.

**Offline Android → LearnHarness runtime continuation (2i), still not stage-2 completion:**

- `scripts/check-learnharness-runtime.mjs` opens the six unchanged authored Android snapshots
  in scratch and drives the pinned real desktop chat handler, turn registry/SSE settlement,
  teacher/practice Agent loop, native tools and fs persistence with the existing faux provider.
  **7 checks pass, 10 faux requests, zero runtime network attempts.**
- Covers write-ahead quiz answers and practice thinking, native plan refusal/repair, duplicate
  call-ID reminting, original practice solution reveal and explicit Next, existing compaction
  summary/tail projection, retained failed attempts/costs, and explicit process-death Retry
  without answer duplication. Original transcript/turn prefixes and opaque fields remain intact.
- All 146 scoped upstream source/prompt bytes and lock metadata are pinned. Data/home/credential
  environment are isolated; runtime fetch/sockets are denied. The synthetic catalog pin/dummy
  key and every write are scratch-only. No installed lessons, keys or real models are used;
  no worker runs. Reproduction and exact limits are in the fixture README.
- This is narrow one-direction runtime continuation, not desktop Electron/UI, live-model,
  reverse Android continuation, graph/artifact execution or transfer-installation acceptance.
  Upstream's test gate disables automatic extraction/compaction; no desktop summary is generated.

**Desktop continuation → Android offline runtime (2j), still not stage-2 completion:**

- The real desktop runner can save all six post-settlement fs records to a new test-output
  directory only after its assertions pass; it refuses to overwrite an existing destination.
  These are unchanged store bytes, not a production transfer package or installer.
- An opt-in `NativeRuntimeTest` reads only bounded authored debug-cache files and restores them
  through existing `SessionStore.saveNative`/`NativeTeaching`/`SessionRuntime`. The API-36 run
  passes **`OK (1 test)` with all six cases**, using a fake Responses provider and networking off.
  No production Android runtime change was necessary.
- Covers desktop question identity/hidden commitments and write-ahead graded answers with both
  thinking channels, practice restoration and exact solution reveal, and existing compaction
  projection. All previous message/usage-turn prefixes and opaque fields survive Android
  continuation; new usage is separate. Display IDs persist through local reopen without inference.
- The test explicitly selects its fake local model/connection. Local counters start at zero;
  it preserves archived usage rather than claiming production import/accounting reconstruction.
  Source cache files stay unchanged and temporary databases are deleted. Real lessons, credentials
  and models remain untouched. Reproduction is in the fixture README.
- Debug/test APK builds pass. The ordinary native runtime class reports **11 passes and three
  safe opt-in skips** (`OK (14 tests)`). This is a narrow authored Android → LearnHarness → Android
  runtime cycle, not UI, live-provider, graph/artifact, transfer validation or installer acceptance.

**Pinned desktop web UI smoke acceptance (2k), still not stage-2 completion:**

- `scripts/check-learnharness-ui.mjs` drives the real Next pages/components/routes in a fresh,
  isolated Chromium profile against the six authored desktop continuations. Only `POST /api/chat`
  delegates to the real handler with its existing faux-engine factory; no renderer/tool/store is
  replaced. **5 checks pass, 6 faux requests, zero real provider requests.**
- Covers local opening without generation; quiz reveal, native option order, confidence and both
  thinking channels durable before inference; Why disclosure after grading; practice's exact saved
  solution reveal, explicit Next and silent acknowledged discard; full history/compaction dividers
  without displaying the summary as teacher prose; and failed-turn Retry without erasing prior
  usage/history. Existing native prefixes and opaque fields survive.
- `ui-upstream.json` pins 313 source/config/asset files and upstream dependency lock metadata.
  Data/home/auth are scratch-only; browser external requests and runtime public sockets are denied.
  One Next npm update check was blocked, not allowed; missing dictation keys return the expected
  local 503 (dictation is outside scope). This run made no font-compilation connections.
- Evidence includes six authored screenshots and `result.json` in ignored output. The runner uses
  a two-minute deadline, saves failure diagnostics, verifies/restores only Next's generated type
  include in the disposable checkout, and closes the owned server/browser. Initial setup/test
  races were corrected before the passing run; no production Android or upstream UI code changed.
- This is browser-rendered desktop UI, **not the Electron shell**, live providers, graph/artifact
  execution, production transfer/install or exhaustive restoration/permission coverage. See the
  fixture README for reproduction. Installed LearnHarness, its lessons and keys stayed untouched.

**Native question dismissal and negative permission acceptance (2l), still bounded:**

- Android's native question overflow action uses the existing native dismissal append and atomic
  projection/save. It rejects stale call IDs, does not grade/reveal the question or request inference,
  and preserves unsent drafts. Reopen, repeated dismissal and Retry stay local; explicit text resumes.
  Public reasoning attached to a dismissed question remains hidden, without deleting it from history.
- Ten fake-provider cases reject research, figures, spawning, file reads, permission-grant attempts,
  wrong-phase tools, foreign/API namespaces, replayed call IDs and multiple calls. Rejected calls never
  enter executable history; reported usage is still saved once. Active commands do not queue another
  Send/Retry/dismissal; Stop remains cancellation, not an automatic continuation.
- Focused JVM tests and debug/test APK builds pass. API-36 `NativeRuntimeTest`: **13 passed,
  three opt-in skips** (`OK (16 tests)`), with emulator networking off. The new Android menu is
  compiled, not a device-click/UI acceptance claim. No real provider or installed desktop data used.

**Native parallel-question batches (2m), still bounded:**

- The native teaching runtime accepts question-only batches within existing output/size bounds;
  every call is validated before any enters the archive. Duplicate/replayed wire IDs, delimiter
  ambiguity, foreign namespaces, malformed later questions and mixed local/job tool batches are
  rejected. Legacy, practice and other validator callers remain single-call by default.
- The existing dock advances in authored order. Each answer is saved locally; siblings defer the
  provider wake until the last answer. Results remain adjacent to their original assistant owner.
  Dismissals never wake a provider; discarding the whole batch waits for explicit later text.
  Hidden generation summaries remain suppressed even when an ungraded ask precedes graded siblings.
- Three fake-provider scenarios (all answered, mixed answer/dismissal, all dismissed) verify order,
  reopen/Retry without requests, exact history/opaque-field preservation and one reaction/usage turn.
  Twelve negative envelope cases pass. The JVM suite/debug/test APK builds pass; API-36 native
  runtime: **14 passed and three opt-in skips** (`OK (17 tests)`), networking off. The pinned native
  checker adds the same ordered-answer/dismissal law: **86 checks** (80 baseline + six saved Android
  reader cases). This does not re-establish desktop agent/UI or transfer round-trip acceptance.

**Historical graph reads, private-copy continuation and stream boundaries (2n):**

- Completed historical `list_sessions`, `list_propositions` and `find` calls now replay as passive
  model history. Each nonfailed read requires one matching later result; missing, duplicated,
  out-of-order or mismatched results remain blocked. Results are not teacher prose or learner
  evidence. These tools are **not** exposed for new Android execution; all three new-call attempts
  remain denied alongside the existing permission tests (15 negative cases total).
- With the owner's explicit permission, one existing, settled desktop teaching lesson was read
  locally without logging its content and tested on a disposable offline API-36 emulator. Opening
  and Retry sent nothing. A deliberately test-routed fake provider then posed an authored quiz on
  the saved native plan; the answer was committed, followed by fake teacher prose. Both requests
  stayed local. Reopen kept the hidden generation hidden and made no duplicate request. The complete
  original message prefix and other native root fields survived; the original desktop file's SHA-256
  was unchanged. This is **copy-based runtime acceptance**, not a production import, live teaching
  quality check, real account/model-routing acceptance or transfer round trip. Private cache copies
  and the temporary database were deleted; no private record joined the authored fixtures/repository.
- Six fake-provider cases now feed the real Responses SSE parser through the native runtime:
  EOF after a delta, malformed JSON, failed/incomplete terminal responses, an unfinished tool item,
  and cancellation during a delta. No partial tool executes, answers are not duplicated, known
  terminal usage survives, and missing usage stays unknown. Reopen stays local; deliberate Stop
  does not turn Retry into another request. These use an in-memory stream, not real HTTP sockets.
- Fresh checks: complete JVM suite **43 passed, one private-record opt-in skip**; debug/test APKs
  and `lintDebug` pass. Combined native-runtime/store/migration instrumentation reports **22 passed,
  four opt-in skips** (`OK (26 tests)`). The private-copy and six-desktop-record opt-ins separately
  each pass (`OK (1 test)`). Both genuine process-death boundaries were rerun: each writer deliberately
  kills its process, and each subsequent recovery reader passes (`OK (1 test)`). Networking stayed off.
  The pinned reference suite passes **87 checks**, and the desktop runtime runner passes **7 checks,
  10 faux requests, zero network attempts**. Earlier desktop UI evidence was not rerun in this slice.

**Native learner-text steering at response boundaries (2o):**

- The native session overflow menu accepts plain learner text during a live conversation, separately
  from the disabled answer/composer controls. Up to eight messages of 2,000 characters each queue
  locally; they neither answer a question nor grant tools/change model or billing. The dialog warns
  that delivery can add model usage. Compaction, legacy sessions and settled runs reject queueing.
- Queued messages join together after a completed non-stopping response, before the next request in
  the same run. Questions, presented plans, practice problems, errors and Stop park them until later
  explicit input/eligible Retry. Opening never drains or requests anything. Delivery appends ordinary
  native user messages with stable steer IDs; archive append, display projection and queue removal
  share one SQLite transaction. Stale checkpoints cannot erase queued messages or requeue delivered
  IDs. Unlike the pinned desktop's memory-only pending queue, Android retains undelivered text across
  process death in its local checkpoint; it is not fabricated as already-delivered native history.
- Fake-provider tests cover prose, plan repair, questions, errors, Stop, a response arriving after
  cancellation, presented plans and practice. They check validation/capacity, ordering, one delivery,
  known usage, unchanged hidden commitments and local reopen. The conversation process-death reader
  also checks a persisted steer survives, stays unsent on open and reaches the next explicit retry.
- API-36 UI acceptance opens the real overflow action/dialog, queues text, displays the pending count
  and retains an unsent draft when the run settles before Send. This caught a stale disabled menu:
  invalidation now includes native-turn readiness and compaction, not status alone. It uses an isolated
  fixture database and fake provider; the application's session library is unchanged.
- Fresh complete JVM/build/debug-lint checks pass (**43 JVM passes, one opt-in skip**). Combined
  native runtime/store/migration instrumentation: **24 passed, four opt-in skips** (`OK (28 tests)`).
  Both process-death recovery readers separately pass after intentional writer-process termination.
  The pinned reference suite stays at **87 checks**. Two real LearnHarness Agent/handler checks now
  exercise co-delivery and question-parked steers: **9 checks, 14 faux requests, zero network attempts**;
  the six desktop fixture outputs stay unchanged in scope. No live model, private lesson or Electron
  UI was used in this slice.

**Remaining within stage 2:** broader in-run input/permission cases (including answers/dismissals
while generating); provider/stream and process-death cases beyond the explicit boundaries above;
Electron shell/broader UI coverage;
and broader cross-engine/offline continuation/restoration. The bounded native subset is green,
not full native adaptation. Keep unsupported execution blocked.
The agreed bounded-transfer milestone may proceed for the verified native subset while these gaps
remain explicit. Block unsupported continuation and incomplete dependencies; do not claim full
unfinished-lesson portability or switch new-session defaults merely because these slices pass.

### 3. Implement pure conversion and selected-subgraph export

Add bounded native-file parsing and LearnHarness-compatible export, preserving unknown data and
IDs. Select graph assertions by lesson provenance and include required endpoints without pulling
in other transcripts. Validate actual file dependencies. Keep package validation and conversion
separate from store mutation. Put only selection/version/origin/receipt bookkeeping around the
native payload; do not define an alternative educational schema for the desktop to adopt.

The selected file format is **ZIP with `manifest.json`**, containing original LearnHarness JSON/
JSONL payloads rather than a new lesson schema. The manifest carries transfer bookkeeping and a
completeness/dependency report. Assembly/validation is still pending; it must not mutate stores.

**Deliverable:** a validated transfer payload in either direction, with a completeness report.
**Gate:** unchanged native round trips preserve content; authored phone progress can be read by
LearnHarness; external references, unsupported versions and legacy limitations are tested.

**Pure native-graph selection foundation (3a), not a transfer package:**

- `NativeGraphSelection.kt` selects raw node/chain assertions by selected native-chat provenance.
  It retains the first defining node assertion plus selected link-appends; selected chains keep their
  required endpoints and the first equivalent argument's canonical ID. It does not traverse incident
  chains, copy external transcripts, normalize unknown fields or rewrite original assertion lines.
- It reports external `(chatId, messageId)` anchors, verifies selected-chat anchors, refuses unknown
  graph versions, dangling endpoints and conflicting chain IDs, and bounds snapshot/line/count sizes.
  It consumes local native snapshots: this is **not** an untrusted-package validator, a store reader,
  or a claim that source files, artifacts, checkpoints or extraction coverage are portable.
- Four JVM tests pass. The shared authored selection vector folds through pinned LearnHarness
  `getGraph`/`deriveView` without importing unrelated chains; the reference checker now passes
  **87 checks** (81 baseline + six saved Android reader cases). No live provider, installed desktop
  data, device test or transfer installation was involved in this increment. Android Studio reports
  no errors; the selector is not yet connected to a production export action.

Next: assemble selected native records and applicable graph/source metadata into a bounded payload,
report missing dependencies/capabilities, then implement preview and conflict-safe installation.
Do not substitute the legacy SQLite renderer graph or reuse its coverage as native graph evidence.

### 4. Implement receiver-side preview, conflicts and safe installation in both apps

Add the explicit comparison/decision table, preserved alternate snapshots, idempotent import and
revision recheck. Coordinate with each app's real writers. Use SQLite transactions on Android and
recoverable native-file installation in LearnHarness. Inspect native store helpers before adding
custom write/recovery machinery. Keep credentials and sync bookkeeping out of native lesson data.

**Deliverable:** the receiver can safely accept or reject a package into an existing library.
**Gate:** failure/cancellation, stale previews, duplicate imports, locally deleted identities,
shared-node conflicts and interrupted multi-file writes cannot silently lose data or report a
false completed import. Test both directions, not just an empty destination.

### 5. Expose manual export/import with explicit direction

Use native document selection/creation and existing Android navigation/styles. Add lesson
selection, destination/source labels, dependency/change preview and version-named conflict
choices. Add matching LearnHarness export/import actions using its existing UI conventions.
Report file creation and successful destination installation as different events.

**Deliverable:** a usable offline LearnHarness → phone → LearnHarness workflow, without pairing.
**Gate:** a user can choose what moves and where, inspect changes, resolve a conflict and return
an accepted lesson to the phone without automatic generation, data deletion or hidden replacement.

### 6. Add connected Sync as transport, not another data pipeline

Add QR/authenticated pairing, explicit desktop/library selection and source inventory. Reuse the
same exporter, receiver preview, conflict decisions, installer and import receipt. Run through the
existing application-owned coroutine lifecycle; interruption is visible and retry is idempotent.
Keep operations one-way and user initiated. Do not add automatic merging, deletion propagation,
a custom cloud backend or Syncthing integration for the live stores.

**Deliverable:** Send/Receive actions replace manual file handoff without changing data semantics.
**Gate:** wrong destinations, stale approval, unauthenticated peers, cancellation and disconnect
before/after commit are covered. Pairing grants neither provider usage nor blanket overwrite
permission. Validate current Android local-network requirements when implementing the transport.

Until stage 5 passes, do not describe export/import or unfinished-lesson portability as a shipped
feature in README, privacy documentation or release notes. Connected Sync remains unimplemented
until stage 6 independently passes.

## Acceptance gates

Use authored offline fixtures, not personal conversations or live model calls:

- Native LearnHarness → Reasoned → LearnHarness preserves the original native record, unknown
  fields, message/call IDs, ordered content, tool results, plan, usage and provenance. Test
  semantic record equality; preserve opaque original payloads where normalization would lose
  information.
- A subsequent learner action appends correctly without rewriting imported history. Pending
  question, answered-but-not-continued, plan review, interrupted generation, practice
  pose/thinking/conclude/dismissal/next and compaction checkpoints all need coverage.
- Learner thinking and confidence stay learner evidence; committed model answers never become
  learner answers. Hidden solutions remain hidden before submission/conclusion.
- Selected graph export includes required chain endpoints but excludes unrelated lessons and
  chains. External source references remain external. Shared-node conflicts do not mutate
  existing evidence without approval.
- Duplicate import is idempotent. Independent edits preserve both complete versions. Local
  deletion/absence never propagates. A stale preview cannot overwrite newer edits.
- Both directions name the actual source/destination and library. A phone-created lesson becomes
  a new native LearnHarness lesson; a phone continuation retains its existing lesson identity.
  Destination-only edits cannot be overwritten just by choosing Send. Skipped records and
  unacknowledged exports do not advance the common baseline.
- Drafts and unsubmitted answer input stay local; persisted pending questions and submitted
  answers do transfer. Saved diagram sources need no render-cache transfer or provider call.
  Missing external artifacts are reported as incomplete, not silently dropped.
- Oversized, malformed, unsupported-version or unsafe artifact input fails before mutation.
  Cancellation/failure leaves the prior database and common baseline intact.
- Opening/restoring/importing causes no provider, research, extraction or job replay. Unsupported
  model settings cannot switch the connection or spend money.
- Legacy Reasoned → LearnHarness export clearly distinguishes preserved data from unavailable
  historical metadata. Successful JSON parsing alone is not engine-compatibility acceptance.

## Pinned reference sources

All links below refer to the investigated commit, not moving `main`:

- [Chat storage](https://github.com/amosblomqvist/learnharness/blob/3dd52110bbcbcba81645453ce0a8fdc230594c47/src/store/chats.ts)
- [Chat and marker contracts](https://github.com/amosblomqvist/learnharness/blob/3dd52110bbcbcba81645453ce0a8fdc230594c47/src/core/contracts/chat.ts)
- [Native records and continuation folds](https://github.com/amosblomqvist/learnharness/blob/3dd52110bbcbcba81645453ce0a8fdc230594c47/src/core/native-messages.ts)
- [Questions and answer records](https://github.com/amosblomqvist/learnharness/blob/3dd52110bbcbcba81645453ce0a8fdc230594c47/src/core/question-core.ts)
- [Phase derivation](https://github.com/amosblomqvist/learnharness/blob/3dd52110bbcbcba81645453ce0a8fdc230594c47/src/core/phases.ts)
- [Graph and ledger contracts](https://github.com/amosblomqvist/learnharness/blob/3dd52110bbcbcba81645453ce0a8fdc230594c47/src/core/contracts/graph.ts)
- [Data-format reference](https://github.com/amosblomqvist/learnharness/blob/3dd52110bbcbcba81645453ce0a8fdc230594c47/skills/learnharness/references/data-format.md)
