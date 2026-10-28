# LearnHarness interoperability and selected-lesson sync

## Status

This is an implementation plan and compatibility investigation, **not an implemented
export/import feature**. No round-trip or cross-engine continuation has been verified.
Reasoned currently remains a standalone Android app. Its existing privacy/export statements
remain accurate until the feature is implemented.

Stage 1 now has a [frozen native-contract inventory and executable reference matrix](LEARNHARNESS_NATIVE_CONTRACT.md)
with [authored offline fixtures](../fixtures/learnharness/v1/README.md). The 45 checks pass against
hash-pinned LearnHarness readers/folds. Stage 2 has started with native persistence and pure
Kotlin restoration/projection checks (details below), not a native execution adapter. Cross-engine
continuation remains unverified; stages 3–6 remain unimplemented.

LearnHarness is the source of truth for the learning-data format and learning-runtime semantics;
Reasoned adapts to it, not the other way around. The LearnHarness library is the canonical home
of the learning data. Phone-authored progress becomes part of that library only after an explicit,
validated import there. Do not require an Android-specific lesson schema in LearnHarness: keep
transfer bookkeeping separate from its native learning records.

Authority does not mean silently discarding phone progress or automatically choosing the desktop
version in a conflict. The agreed manual resolution still applies. Native Android UI, SQLite,
coroutine lifecycle and provider adapters can remain; Electron, Node.js and Pi are not prerequisites
for implementing the same persisted learning contracts.

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

Implement and validate one stage at a time; this plan does not authorize live-provider calls.
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
45 native checks passed offline; setup, exact scope and limitations are recorded with the fixtures.
That reference work did not change the Android engine/store or implement a transfer feature.

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
- Until the native execution adapter exists, `SessionRuntime.open` explicitly refuses native-backed
  sessions before legacy handoff/migration/publication. It must not interpret their local checkpoint
  as authority or run the legacy engine against them. There is no production import action yet.

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

**Remaining within stage 2:** native-to-UI/provider projections and actual native event appends;
question normalization/shuffling/confidence and submitted-answer details; native input-cause/
foreground-permission gates; native probe/plan handoff and plan authoring; practice dismissal/deferred conclusion; compaction identity capture;
and offline fake-provider continuation/restoration tests through the actual runtime. Keep native
execution blocked until those seams work. Do not move to conversion or claim unfinished-lesson
portability merely because the reference folds or lossless storage pass.

### 3. Implement pure conversion and selected-subgraph export

Add bounded native-file parsing and LearnHarness-compatible export, preserving unknown data and
IDs. Select graph assertions by lesson provenance and include required endpoints without pulling
in other transcripts. Validate actual file dependencies. Keep package validation and conversion
separate from store mutation. Put only selection/version/origin/receipt bookkeeping around the
native payload; do not define an alternative educational schema for the desktop to adopt.

**Deliverable:** a validated transfer payload in either direction, with a completeness report.
**Gate:** unchanged native round trips preserve content; authored phone progress can be read by
LearnHarness; external references, unsupported versions and legacy limitations are tested.

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
