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
- `questions.json`: 16 authored question projections, 29 raw learner requests/full answers/compact
  results and eight refused calls. Expected values come from the pinned native question functions
  and the `question-dock.tsx` construction, not Android output. Covers UTF-16 seeded shuffling,
  exact/debris/encoded answer keys, optional fields, confidence, both thinking channels and Other.
  Both the native checker and Kotlin `NativeQuestionTest` consume these same vectors.
- `plans.json`: nine authored Mermaid success/refusal vectors for native authoring and DAG creation.
  Both the native checker and Kotlin `NativePlanTest` consume them; Android additionally applies
  its existing bounded node/edge/title limits.
- `compaction.json`: seven authored cutoff/estimate vectors shared with `NativeCompactionTest`:
  oversized trailing tool results, cache-inclusive usage anchors, aborted/zero usage, chained
  markers, no-progress boundaries and adjacent UI-only metadata. Scaffolding/signatures do not
  contribute text tokens.

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

Recorded validation: **81 checks passed** using Node.js 24.12.0, the hash-matching upstream
source snapshot and the integrity-verified TypeBox package from its lock. This establishes
native reader/fold behavior, not Android conversion, trust-boundary validation, cross-engine
continuation or a working export/import feature. See
[`docs/LEARNHARNESS_NATIVE_CONTRACT.md`](../../../docs/LEARNHARNESS_NATIVE_CONTRACT.md).

## Pure selected-subgraph reference

`cases.json.graphSelection` supplies the same original assertion-line indices to
`NativeGraphSelectionTest` and the pinned native checker. Selecting the authored teaching chat keeps
four node lines (including an external defining assertion and a notes-sourced endpoint) and one
chain line. Native `getGraph`/`deriveView` retain definitions, aliases and required endpoints without
including the unrelated incident chain. External provenance stays external; no transcript or source
file is opened. Kotlin also checks later-selected equivalent chain identity, opaque/raw-line
preservation, empty selection results, missing anchors, invalid identities/versions and bounds.

Run `gw ./gradlew.bat :app:testDebugUnitTest --tests com.kkgosu.reasoned.NativeGraphSelectionTest --offline`
(on Unix use `./gradlew`). Recorded: **four tests passed**, and **87 native reference checks** with
all six saved Android snapshots. This is a pure selection foundation, not package validation,
source-file completeness, coverage compatibility, store installation or transfer acceptance.

## Authored Android runtime → desktop readers (optional)

`NativeRuntimeTest` also writes four **synthetic temporary-DB snapshots** to the debug test
app's `cache/native-acceptance/`: `teaching.json`, `practice.json`, `compaction.json`, `failures.json`.
These are unmodified `SessionStore` records after fake-provider execution, not installed learner
lessons or a transfer feature. Run the test class sequentially on a disposable emulator with
networking disabled as described in the root `AGENTS.md`; never use a personal phone. After an
`OK` result, copy only these authored files into ignored build output (Git Bash example):

```sh
mkdir -p build-logs/native-acceptance
set -o pipefail
adb -s emulator-5554 exec-out run-as com.kkgosu.reasoned.debug tar -C cache/native-acceptance -cf - \
  teaching.json practice.json compaction.json failures.json | tar -xf - -C build-logs/native-acceptance
node scripts/check-learnharness-contract.mjs --upstream ../learnharness-contract \
  --android-records build-logs/native-acceptance
```

The original four yield **85 checks (81 baseline + 4 Android-runtime cases)**. The latest ordinary
API-36 runtime/store/migration/regression run has **19 passing checks and two opt-in harness skips**
(`OK (21 tests)`), plus passing JVM tests/debug/test APK builds. The four cases verify native read,
projection folds, idempotent usage replay and resave, including original snapshot bytes/opaque
fields, turn/step grouping, token sums, unknown counts and message provenance. All store writes
stay in disposable scratch. No desktop server/agent/model/UI, graph conversion or import/export
installer runs; this is not full cross-engine continuation acceptance. Raw synthetic artifacts
remain local/ignored rather than being committed as a second set of golden fixtures.

### Opt-in accounting process-death harness

The following runs kill **only the fixture app process on a disposable emulator**. Use the same
selected emulator sequentially, with networking off and no other Reasoned instrumentation running.
The writer's `Process crashed` is expected; each fresh reader must finish `OK (1 test)`. Do not
count a writer crash as a successful assertion. Without these explicit arguments, both harness
methods are skipped and cannot kill an ordinary suite.

```sh
for boundary in conversation compaction; do
  adb -s emulator-5554 shell am instrument -w -r -e nativeProcessDeath prepare -e boundary "$boundary" \
    -e class com.kkgosu.reasoned.NativeRuntimeTest#nativeAccountingProcessDeathWriter \
    com.kkgosu.reasoned.debug.test/androidx.test.runner.AndroidJUnitRunner
  adb -s emulator-5554 shell am instrument -w -r -e nativeProcessDeath recover -e boundary "$boundary" \
    -e class com.kkgosu.reasoned.NativeRuntimeTest#nativeAccountingProcessDeathReader \
    com.kkgosu.reasoned.debug.test/androidx.test.runner.AndroidJUnitRunner
done
adb -s emulator-5554 exec-out run-as com.kkgosu.reasoned.debug tar -C cache/native-acceptance -cf - \
  teaching.json practice.json compaction.json failures.json recovery-conversation.json recovery-compaction.json \
  | tar -xf - -C build-logs/native-acceptance
```

Then rerun the checker command above. Recorded result: **87 checks (81 + 4 + 2 recovery)**.
Both fresh reader runs passed on API 36 after genuine process termination without coroutine
`finally`. They verify durable known usage, an unknown in-flight step, idempotent recovery,
local-only open, explicit conversation retry and the unchanged hidden compaction question. The
conversation writer also leaves one undelivered learner steer: recovery retains it in the local
checkpoint without sending it on open; explicit Retry delivers it exactly once.
Only fixed, synthetic `native-process-death-*.db` files are used/deleted, never `sessions.db`.
This is not task/UI restoration, exhaustive SSE-boundary recovery or desktop execution acceptance.

## Authored Android runtime → real LearnHarness handler/Agent (optional)

After producing **all six** snapshots above, use the same pinned checkout and locked dependencies:

```sh
node scripts/check-learnharness-runtime.mjs --upstream ../learnharness-contract \
  --android-records build-logs/native-acceptance
```

This separate runner exercises the real `chat-handler.ts`, turn registry/SSE settlement,
`createTeacherAgent`/`createPracticeAgent`, pi Agent loop, native tools and fs stores. Only the
provider engine is replaced with upstream's existing faux engine. Every expected response is
scripted; an extra model request fails. `NODE_ENV=test` intentionally disables upstream automatic
extraction/compaction; compaction acceptance here checks continuation from an existing Android
marker, not desktop summary generation.

`runtime-upstream.json` pins 146 files: `LICENSE`, `config.ts`, both package manifests, and
`.ts`/`.tsx`/`.md`/`.json` files under `src/{agent,core,server,store}`. The aggregate hashes sorted
POSIX relative paths, NUL, lowercase file SHA-256 and LF. Those bytes were also checked against
the official pinned Git blobs. Extra client/Electron files in a full checkout are not imported
or included in this runtime-only scope. Installed package versions/integrity metadata must match
the upstream lock, including pi-ai and pi-agent-core 1.4.0 and TypeBox 1.3.27. No upstream files
are patched and no dependency is added to Reasoned.

The runner accepts only bounded, explicitly authored snapshots; this is **not** an untrusted
transfer-package validator. It clears credential environment variables, redirects data/home to
scratch, and blocks fetch/socket access before importing upstream runtime modules. TSX's initial
local-parent IPC setup precedes that guard. The handler's catalog/key-presence gate uses an
explicit **scratch-only** `openai:gpt-4` model pin and a dummy key, never the unusable fixture model
or an installed account. All provider calls use the faux engine. Source snapshot bytes and
upstream source/prompt hashes are checked unchanged afterwards; scratch is deleted.

Recorded result: **9 checks passed, 14 faux requests, zero runtime network attempts**:

- All six records read/project and expose settled state without inference.
- Android quiz answer persists before inference; invalid native plan is refused, repaired, then a
  new question is posed. A deliberately reused Android call ID is reminted by the desktop runtime.
- Practice thinking persists first; conclude reveals the original Android commitment verbatim;
  only an explicit Next starts the next scripted problem.
- An Android compaction summary reaches the Agent once with the retained tail; full archive stays.
- Rejected Android generations and their costs remain intact through explicit continuation.
- Explicit Retry after conversation process death omits failed messages only from temporary Agent
  history, without duplicating the earlier answer.
- Interrupted compaction retains its hidden question and unknown accounting; its answer commits
  once and carries the original commitment.
- In an additional authored scratch chat, two learner steers join together after the current prose
  response in one live Agent run, in insertion order and exactly once.
- A question parks queued steers without archiving them; the next explicit answer run receives both.
  The settled run rejects new live delivery. This extra chat is not exported among the six fixtures.

Every continued record retains its original message/turn prefix, opaque fields and source bytes;
new turn/step counts and message provenance are checked. No worker is spawned. This demonstrates
bounded **Android → LearnHarness offline runtime continuation**, not Electron/UI, live provider,
reverse Android continuation, graph/artifact execution, transfer installation or a full round trip.
Stage 2 remains incomplete.

The Android `NativeRuntimeTest` suite also checks the corresponding checkpoint queue across prose,
plan repair, question/error/Stop, late cancellation responses, presented plans and practice boundaries;
validation/capacity, stale saves and reopen stay local. Its real native-menu/dialog test checks pending
status and a retained unsent draft when Send races settlement. Combined runtime/store/migration on
API 36 now reports **24 passed/four opt-in skips** (`OK (28 tests)`); both process-death readers pass
separately. These are fake-provider tests, not live-provider, broad UI or transfer acceptance.

## Desktop continuations → Android runtime (opt-in)

The runner can retain the **unmodified post-settlement fs-store bytes** after all its checks pass.
The destination must be new; it refuses to overwrite any existing directory. No files are installed
in a real LearnHarness library. With all six original Android snapshots already available:

```sh
node scripts/check-learnharness-runtime.mjs --upstream ../learnharness-contract \
  --android-records build-logs/native-acceptance --desktop-records build-logs/native-desktop-acceptance
adb -s emulator-5554 exec-out run-as com.kkgosu.reasoned.debug mkdir -p cache/native-desktop-acceptance
set -o pipefail
tar -C build-logs/native-desktop-acceptance -cf - teaching.json practice.json compaction.json \
  failures.json recovery-conversation.json recovery-compaction.json \
  | adb -s emulator-5554 exec-in run-as com.kkgosu.reasoned.debug tar -xf - -C cache/native-desktop-acceptance
adb -s emulator-5554 shell am instrument -w -r -e nativeDesktopRecords read \
  -e class com.kkgosu.reasoned.NativeRuntimeTest#desktopContinuationsRestoreAndContinueThroughAndroidWithoutRewritingHistory \
  com.kkgosu.reasoned.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Build/install the current debug and test APKs first. Use only the selected disposable emulator,
sequentially with other fixture runs, with networking disabled. This test requires explicit opt-in;
ordinary suites skip it rather than reading fixture files accidentally. The files must remain
bounded authored records, never personal lessons. They stay in ignored host output/debug cache.

Recorded API-36 result: **`OK (1 test)` covering all six desktop continuations**. Existing
`SessionStore.saveNative`, `NativeTeaching` and `SessionRuntime` restore and continue them with a
fake Responses provider; no production import API or runtime change was needed. The test-local
model/connection is explicit, with local counters initially zero; historical native usage is kept
in its original turns, not reconstructed as fresh spending. It verifies:

- Byte-identical native archive on initial save/open; opening and settled Retry make no request.
- Desktop quiz identities, hidden commitments and deterministic answers; submitted answer/thinking
  and reading-thinking are durable before inference, with no duplicate old answer.
- Desktop practice pose stays hidden; conclusion reveals its exact committed solution, alongside
  the correctly restored older Android-origin conclusion.
- Existing compaction summary/tail reaches Responses while old archive messages remain intact.
- Entire desktop message/turn prefix and opaque fields survive new Android turns; new usage steps
  are separate, with stable local display IDs through stop/reopen and no new request on Retry.
- Temporary fixture databases are deleted; source cache records are not rewritten.

The ordinary `NativeRuntimeTest` class now passes: **14 tests passed and three opt-in
methods skipped** (`OK (17 tests)`), including silent question dismissal, three question-batch
answer/dismissal scenarios and twelve negative permission/envelope cases. Debug/test APK builds
and the JVM suite pass. The native checker now includes ordered sibling resolution and deferred
continuation versus dismissal, plus selected graph assertions, for 81 baseline checks and 87 with
all six saved Android reader cases. The Android dismissal menu is compiled, not device-click/UI acceptance. This establishes a bounded authored
**Android → LearnHarness → Android runtime cycle** with fake providers, not a selected-lesson
transfer round trip, production import validation, Electron/UI, graph/artifact execution or
live-provider acceptance. Stage 2 remains incomplete.

## Pinned desktop web UI (opt-in, not Electron)

Use a **disposable full checkout** of the pinned revision, its locked `node_modules` (including
Puppeteer Core), Node.js 24+, and an installed Chromium executable. Never point at installed
LearnHarness, a working lesson library, personal records or credentials. No dependency is added
to Reasoned. First produce the six authored desktop continuations above, then:

```sh
node scripts/check-learnharness-ui.mjs --upstream ../learnharness-ui \
  --records build-logs/native-desktop-acceptance --browser /path/to/chrome \
  --output build-logs/native-ui-acceptance
```

The output directory must be new. `ui-upstream.json` pins 313 files under `app`, `components`,
`lib`, `src`, `public`, `electron`, plus listed root config/license/manifests, using the same sorted
path/NUL/file-hash/LF digest as the runtime checker. Installed versions/integrity metadata must
match the pinned lock. Next dev adds a generated-type include to `tsconfig.json`; the runner
accepts only that known edit, restores the exact original bytes, and verifies the whole source
pin after shutdown. Do not run another dev server against this disposable checkout concurrently.

The real pages, components and API routes run unchanged. Only `POST /api/chat` injects the
upstream handler's existing fake-engine factory; the real Agent/tools/native stores and settled
SSE route remain in use. The child environment is whitelisted, home/data/auth are temporary,
all browser external requests and runtime public sockets are denied. Public Google font downloads
are permitted only during font compilation if needed (none occurred in the recorded run).
Next's npm version check is blocked and recorded separately; dictation's expected no-key 503 is
not a passing dictation test. Automatic extraction/compaction are skipped by the upstream test
gate; the UI checks an existing compaction marker, not generating a desktop summary.

Recorded Windows/Chrome 156 result: **5 checks passed, 6 faux requests, zero real model requests**:

- All six desktop records render locally without a model POST on open.
- Quiz answer order, reveal gate, confidence, question-thinking and reading-thinking; the native
  answer is durable before fake inference, and Why reveals the saved explanation after grading.
- Practice commitment stays hidden until conclude, then reveals the exact stored solution;
  reopen does not advance, Next is explicit, and discard waits for its durable silent HTTP ack.
- Full transcript, compaction/cut dividers and hidden summary, without turning it into teacher prose.
- A scripted failure exposes Retry; reopen does not retry, explicit retry excludes failed wire
  input only and retains previous native history/usage. Every new turn preserves old prefixes
  and opaque fields, and no background job is spawned.

`result.json` and six screenshots are saved in ignored output; failures instead save diagnostics
and authored record snapshots. During preparation, browser setup, asynchronous test assertions
and shutdown issues were fixed; only the final complete passing run establishes these results.
The server and browser closed; their processes/listening ports were independently checked absent.
The runner has a two-minute hard deadline and exits its owned Next process after cleanup because
dev watcher handles otherwise keep it alive. A forced deadline exit is a failure, not acceptance;
inspect that run's processes/disposable checkout before retrying.

This tests the **desktop web UI in Chromium, not Electron chrome/windowing**, live providers,
Android UI, transfer/install, graph/artifact execution or exhaustive navigation/restoration.
Installed LearnHarness, personal lessons and keys remain untouched; stage 2 is still incomplete.

## Optional private local-record checks (not authored fixtures)

Only with the owner's explicit authorization, a settled native teaching record can be checked
locally without adding it to this directory or logging its content. Do not pass private records
to the authored runtime/UI scripts: their `[Authored offline ...]` guards remain mandatory.

Set `REASONED_NATIVE_LOCAL_RECORD` to an explicitly selected file outside the repository and run:

```sh
gw ./gradlew :app:testDebugUnitTest --tests com.kkgosu.reasoned.LocalNativeCompatibilityTest --offline
```

This opt-in JVM check projects native/UI/Responses input entirely in memory and verifies original
file bytes remain unchanged. Unset the variable afterward. Ordinary JVM suites skip this one check;
force the selected test task to rerun if rechecking a different file, since environment input is not
part of Gradle's test cache key.

For an owner-authorized **disposable offline emulator only**, the instrumentation opt-in reads
`cache/native-private-check.json` in the debug app sandbox:

```sh
adb -s emulator-5554 shell am instrument -w -r -e nativeLocalRecord read \
  -e class com.kkgosu.reasoned.NativeRuntimeTest#explicitlySelectedPrivateCopyRestoresAndContinuesWithoutRewritingHistory \
  com.kkgosu.reasoned.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Place only a copy there, outside tracked fixture paths; never copy credentials. The test requires a
settled teaching record and uses a deliberately test-routed fake provider for an authored quiz,
answer and teacher prose. It checks original prefixes/root fields, hidden generation and local-only
reopen/Retry. Its isolated database and cache copy are deleted in `finally`; independently verify
cleanup and the source file hash, including after a failed/interrupted setup. It does not test live
providers, original billing/model selection, actual learner progress or production import/transfer.
One owner-selected lesson passed both checks on API 36. No private lesson/title/ID/content is recorded
in this fixture set. Full native adaptation remains incomplete; see the plan's stage 2n for limits.

Protocol strings in these authored records come from LearnHarness by Amos Blomqvist and
contributors, under its GNU Affero General Public License v3; see the
[bundled upstream license](../../../app/src/main/assets/learnharness/LICENSE) and the pinned
[original source](https://github.com/amosblomqvist/learnharness/tree/3dd52110bbcbcba81645453ce0a8fdc230594c47).
