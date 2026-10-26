# Implementation plan and phase 0–5 handoff

## Scope decision

Phases **0–4** are implemented: research, Kotlin foundation, mock classroom, illustrative graph and the requested local model runtime. The owner requested proceeding after graph visual fixes, clearing the prior visual-review gate, and selected OpenAI as the first provider. The sample graph/lesson remain explicitly authored fixtures; Home now shows persistent knowledge from real sessions. Phase 5 is implemented as a build-only handoff: dependency plans plus the owner-selected hybrid Extractor/Auditor/transactional-merger pipeline. Its device/live acceptance checks are pending; no live request or device test was run for phase 5.

## Latest refinement — native UI and original instructions

- Settings title/subtitle entries are single full-width native buttons with edge-to-edge ripples. Navigation/radio rows have internal padding; filled actions and outlined input fields remain inset. Provider sign-out/reset actions are on the page, not in overflow; reset still requires confirmation.
- Fragment navigation now inherits Mastodon's AppKit 1.5.3 defaults: 300 ms enter, 200 ms exit, positional fade, native interpolator and predictive-back cleanup. Shared native AlertDialog styling adds rounded surfaces, proper padding, sans typography and mixed-case text actions without a new dependency.
- Owner selected **verbatim LearnHarness instructions plus a separate Android compatibility layer**, not a full runtime-feature port. All 36 source files under `assets/learnharness/` match revision `0795ef197cca04f6a8e7d50d10ed4dc71bd1a0cc`; original licenses/provenance and SHA-256 inventory are bundled. Active Prober/Teacher composition matches upstream ordering. Practice, specialist, compaction and learning-data skill sources are preserved, not executed.
- Independently written role prompts/reasoning-check skill were removed. New runs use `learnharness-0795ef197cca-v1`; legacy version-1 sessions adopt it on their next turn without losing transcript or billing identity. The Android appendix explicitly describes tool substitutions, unavailable features and renderer differences. Copyleft/corresponding-source obligations now apply; compatible first-party release licensing is still required.
- Screenshot generation, capture helpers and screenshot-only tests were removed at the owner's request. No replacement screenshots were produced after that request.

Verification: `:app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug` passed. The complete **30-test** Pixel_7/API-36 instrumentation suite passed in 49 seconds. Four focused navigation/input/provider/dialog checks also passed at **200% text with animator scale 0**, across class-filtered runs; original settings were restored. Dialog checks cover visible/unclipped positive/negative buttons, native list selection, typography/ripples and recovery confirmation. The tests also verify exact source bytes, upstream composition for all three roles, active runtime request instructions, legacy-session migration and inherited transition defaults. Native dialog accessibility lookup now waits for the actual window rather than assuming its tree is immediately ready.

No live model, ChatGPT authorization, paid request, broader-device visual comparison or full TalkBack journey was used to validate these refinements; model adherence to the full original doctrine remains a live evaluation task.

## Delivered

### Phase 0 — research and design extraction

- [x] Inspect LearnHarness product/methodology/design code and both original screenshots.
- [x] Inspect LiteX, AppKit and Mastodon Android engineering patterns.
- [x] Pin reference revisions and distinguish library reuse from visual/architecture inspiration: [REFERENCES.md](REFERENCES.md).
- [x] Document exact palette, font roles, typography, geometry, component anatomy, motion, mobile adaptations and known visual differences: [DESIGN_SYSTEM.md](DESIGN_SYSTEM.md).
- [x] Document the current native foundation and future responsibility boundaries: [ARCHITECTURE.md](ARCHITECTURE.md).
- [x] Verify AppKit's published Unlicense terms and font redistribution licenses; bundle dependency/font license texts.

### Phase 1 — Kotlin Android foundation

- [x] Keep one application module, all first-party application/tests in Kotlin.
- [x] Replace the template AppCompat/Material/ConstraintLayout stack with AppKit 1.5.3 and LiteX RecyclerView 1.2.1.1.
- [x] Use AGP's built-in Kotlin support, Java 17 compatibility, minimum API 26 and compile/target API 36. API 26 was selected for native bundled font XML; API 36 is the installed/verified baseline rather than the template's unavailable API 37.
- [x] `MainActivity : FragmentStackActivity`, public no-argument native fragments, `Nav.go`/`Nav.finish`, AppKit toolbar/back/inset behavior and restoration.
- [x] Dark resources, reusable palette/dimensions/text/widget appearances and separately licensed JetBrains Mono, Source Sans 3 and CMU Serif Bold.
- [x] Small native home empty state and style-reference screen; no pretend learning actions.
- [x] Inherited AppKit/Mastodon transitions, respecting the system animator setting and preserving predictive-back cleanup.
- [x] Kotlin device checks for font binding, navigation, dialogs and recreation with an unfinished draft. Screenshot-only checks have been removed.
- [x] Build/run on an emulator, inspect screenshots against reference characteristics, document intentional differences and unfinished visual validation.

The style specimen remains static XML in a ScrollView; actual DisplayItems now render the phase-2 lesson.

### Phase 2 — visual classroom

- [x] Home remains graph-oriented, with a quiet empty state and an actual sample-session route rather than inactive feature cards.
- [x] Kotlin `LearningSessionFragment`, typed `TextDisplayItem` / `FormulaDisplayItem` / `QuestionDisplayItem`, specialized AppKit/LiteX holders and stable authored/monotonically allocated IDs.
- [x] Independently authored gradient-descent lesson, basic native formulas with spoken alternatives, a prediction checkpoint, retry/reveal feedback and honest canned replies. No provider/network calls or fake reasoning evaluation.
- [x] Persistent composer/question shell; explicit check reveal, non-destructive collapse, native choices/confidence/reasoning, pinned submit and a bounded scrollable form.
- [x] Coroutines/StateFlow with AppKit visibility ownership, 60 ms coalesced mock increments, partial holder payloads, no full-list notifications and no forced scrolling away from an earlier reading position.
- [x] Stop/Resume, unfinished composer/answer preservation through dock changes, recreation, Home return/reopen and background process death of the existing task.
- [x] Native 150 ms dock transitions respecting disabled animators. IME-aware answer focus mode keeps a usable editor and submit above the keyboard, including at 200% type.
- [x] Manual phone design target and inspected Android review captures; see DESIGN_SYSTEM. These are not approved pixel-diff goldens.
- [x] Seven emulator tests pass at both normal settings and 200% type/disabled animations; build and lint pass. Added coroutine redistribution notice.

The mock is deliberately bounded to three answer attempts and eight notes (2,000 characters per input), so transient saved state stays small. Real sessions need SQLite/checkpoints; full Markdown/LaTeX, general open-response questions, practice, arbitrary goals, plans and actual assessment remain feature work, not simulated capabilities.

### Phase 3 — knowledge-graph prototype

- [x] Kotlin `KnowledgeGraphView`: 30 authored proposition nodes, 32 relationships, six muted sample clusters and zoom-gated, collision-suppressed labels.
- [x] Separate deterministic layout snapshot, computed off the main thread; cached Canvas drawing objects and no idle redraw loop.
- [x] Native pan/pinch/double-tap, inverse-transform picking, selection, accessible native zoom controls and fit-to-content.
- [x] Native word-prefix search with graph highlights, no-match feedback and Browse-all results; choosing a result centers/selects its node.
- [x] Native readable proposition/reasoning/source details, labeled incoming/outgoing relationships and relevant sample-lesson navigation. Clear illustrative-data/no-learner-evidence disclosure.
- [x] Parcelled camera center/relative zoom/selection, recreation checks and visibility-owned layout cancellation; no new dependencies or real knowledge store.
- [x] Camera/gesture/picking/search/detail checks and inspected review captures. See the phase-3 verification record below.

### Phase 4 — local OpenAI runtime

- [x] A real/fake `ModelProvider` boundary and direct OpenAI Responses SSE over OkHttp 5.4.0. No custom backend, Pi, Node.js, ChatGPT session-cookie access or remote agent process.
- [x] In-app key/model/goal setup, explicit data/billing disclosure, manual API model IDs and account-scoped ChatGPT model rows. Drawer → New session now uses one disclosed Start session action to create its checkpoint and request the first step; reopening/restoring saved sessions never starts generation. The underlying runtime create operation remains local. Settings → Providers & models configures accounts/models, with saved sessions listed separately in the drawer.
- [x] Keystore AES-GCM, atomic no-backup ciphertext, secure password window, disabled key view-state/autofill saving and explicit forget/reset. Keys never enter prompts, checkpoints or logs. Cloud backup and device transfer explicitly exclude app data.
- [x] A bounded, cancellable, foreground tool loop; role-specific strict schemas plus independent native validation, replay/step/context/output limits, sanitized errors and completed-response usage accounting. Stop closes blocked HTTP bodies; interrupted text is marked provisional and excluded from provider context. Retry is explicit and can incur another charge.
- [x] Independently authored v1 Prober/Teacher assets and reasoning-check skill, editable local AGENTS.md learning preferences snapshotted as user data, diagnostic questions and learner-approved provisional textual routes. Dependency validation remains phase 5.
- [x] Reuse the real native transcript/dock: stable row IDs, 100-ms coalesced streaming, choices/free response/reasoning/confidence, Teacher check reveal and IME focus mode. The phase-2 authored mock remains independent.
- [x] SQLite v1 sessions/messages/questions/answers/runs; transactional answer/checkpoint writes, stable IDs and prompt versions, draft preservation and interrupted-run recovery. Browse the newest 30 sessions; no long-history management or knowledge extraction yet.
- [x] Optional official Sign in with ChatGPT for the requested public open-source distribution: browser PKCE/loopback, verified identity, encrypted registrations, serialized token rotation/revocation, account-scoped model catalog and fixed per-session billing. API keys remain independent; live eligibility is not verified.
- [x] Eight deterministic runtime/transport/credential/native-UI instrumentation checks, including at 200% text and disabled animations. No network or paid-model calls are required. Existing foundation/classroom/graph checks remain in scope for regression validation.

## Reproduce locally

Prerequisites: JDK 17 or newer compatible with the included Gradle/AGP, Android SDK platform 36, Maven access for the first dependency resolution, and an API-26+ device/emulator for instrumentation. Configure the SDK through local machine settings/`local.properties`; do not commit local SDK paths or credentials.

```sh
./gradlew :app:assembleDebug :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

Install/launch with the Android CLI or Android Studio:

```sh
android run --device emulator-5554 \
  --apks app/build/outputs/apk/debug/app-debug.apk \
  --activity com.kkgosu.learnharness.MainActivity
```

Instrumentation reports: `app/build/reports/androidTests/connected/debug/index.html`.
Lint report: `app/build/reports/lint-results-debug.html`.
Captured device frames are collected under `app/build/outputs/connected_android_test_additional_output/debugAndroidTest/connected/<device>/`. Tests use AGP's `additionalTestOutputDir`; standalone instrumentation falls back to the app's external-files `screenshots/` directory. These captures demonstrate rendering, not approved pixel-diff tests.

## Verification record — 2026-10-25

Device: running **Pixel_7**, `emulator-5554`, **Android 16 / API 36**, 1080 × 2400 screenshots, default font scale 1.0, three-button system navigation.

| Check | Result / limitation |
|---|---|
| Debug APK compilation/packaging | Passed |
| Android lint | Passed with warnings; no baseline/suppression used to hide errors |
| `FoundationTest.navigationFontsAndDraftSurviveRecreation` | Passed: real AppKit navigation, prose/mono font resources, dark background, two-entry restored stack, draft retention and returning to a single root |
| `FoundationTest.captureFoundationScreens` | Passed: both app windows render and save PNGs |
| Enlarged type / reduced motion | Both instrumentation tests also passed with `font_scale=2.0` and `animator_duration_scale=0`; captures visually inspected. Original settings restored to 1.0 afterward. |
| Real process death | Manually passed: typed “Native state survives”, backgrounded, `am kill`, confirmed no remaining PID, restored the existing launcher task; style screen, draft, focus and scroll state returned. |
| Keyboard and navigation | Manually checked: editor visible above keyboard, system Back first dismisses the keyboard. Toolbar/return navigation and recreated-stack return are exercised through AppKit. |
| Runtime dependency inventory | No AppCompat, Compose, Navigation Component or Material Components in `debugRuntimeClasspath`; LiteX RecyclerView and expected AppKit transitives only, plus Kotlin/annotations |
| Visual comparison | Exact palette and three font roles inspected against the two original screenshots; caught/fixed a theme-level font override. See DESIGN_SYSTEM for deviations. |
| IDE inspection | Current IDE model reported unresolved Android SDK/AppKit symbols even though command-line builds and device tests pass. Re-sync/import the changed Gradle model before relying on IDE inspections; this check is not reported as clean. |

Nonblocking lint warnings include API/AGP/test-dependency updates, API-gated attributes ignored on older platforms, intentional design-ladder resources not used by the tiny specimen, and the opaque fragment background needed during transitions. Platform fragment deprecation warnings are expected from the explicitly selected AppKit architecture. Do not add AppCompat just to silence them.

Current phase-2 full-device review captures are checked in: [home](docs/screenshots/phase2-home.png), [reading](docs/screenshots/phase2-reading.png), [question](docs/screenshots/phase2-question.png), [thinking](docs/screenshots/phase2-thinking.png), [feedback](docs/screenshots/phase2-feedback.png).

No copied upstream screenshots are packaged. The captures are **not** approved Android-sized golden images. A screen-capture smoke test cannot establish full visual fidelity, gesture correctness or accessibility.

Not yet verified: API-26 runtime/device-specific behavior, tablet/foldable composition, actual rotation/multi-window/RTL/TalkBack journeys, gesture-mode predictive-back completion/cancellation, or formal screenshot diffs. These remain validation work; successful activity recreation is not claimed as every configuration/device test.

For manual process-death retesting: open the style screen, scroll, enter a draft, press Home, then kill only the background app. Restore its launcher task with MAIN/LAUNCHER (or Recents). A bare explicit `am start -n` may create a new standard Activity instance, and force-stop is not a saved-task-restoration test.

## Phase-2 verification — 2026-10-25

Same Pixel_7/API-36 emulator. `:app:assembleDebug`, `:app:lintDebug` and all **seven** `:app:connectedDebugAndroidTest` tests pass. The same seven tests also pass with `font_scale=2.0` and `animator_duration_scale=0`; original settings restored afterward. Platform fragment deprecations and nonblocking lint warnings remain; no lint baseline hides errors.

`LessonTest` checks targeted row payloads/stable IDs, parcelled partial-response state, explicit pause/resume, no duplicate/empty submission, repair and uncertainty feedback, input/turn bounds, saved-state size, composer/choice/confidence/reasoning restoration, Home reopening, reading-position retention through streaming/recreation, and real IME geometry. Foundation font/navigation checks remain passing. The capture test records five actual device frames using native UIAutomation, after animations settle.

Android Studio's session-file inspection still cannot resolve the generated `FragmentLearningSessionBinding` and consequently reports cascading type errors. Command-line compilation/lint/device execution resolve it correctly; refresh the IDE Gradle model/generated-source index before treating editor diagnostics as authoritative.

Manual checks: real keyboard input into reasoning; backgrounding, killing the now-background process, confirming an empty PID lookup, and restoring the existing launcher task recovered the session, expanded dock, reasoning text, focus and form scroll. The first immediate kill preceded background completion, so the kill was repeated after the activity was backgrounded; the verified empty PID is the process-death gate, not merely a launcher return. Selection/confidence recovery and the final IME focus-mode behavior are covered by instrumentation.

Validation caught two concrete issues: anonymous accessibility `labelFor` generated an ID colliding with AppKit containers (fixed with a stable resource ID), and 200%-type plus keyboard left too little answer space (fixed by temporarily assigning the reading pane’s height to the dock). The latter now has a real-IME regression check. Visual review also caught a whole-scene transition blanking transcript rows; bounds animation is now targeted only at the dock, verified in the final native screen capture.

Not claimed: API-26 device testing, tablet/foldable/RTL/TalkBack journeys, gesture predictive-back testing, actual AI, broad Markdown/LaTeX support, durable database persistence or automated pixel-diff fidelity. Owner review of the Android-sized captures is still required before real models.

## Phase-3 verification — 2026-10-25

Pixel_7/API 36. Build/lint and all **ten** connected instrumentation tests pass; the three new `GraphTest` checks also pass at **200% font scale with animations disabled**, with original device settings restored. Graph checks cover deterministic snapshots, invalid-edge rejection, fit bounds, inverse transforms/nearest picking, focus-preserving zoom/clamping, native one-/two-pointer gestures, a usable graph height, parcel round trips, recreation, empty-query Browse, no results, native accessibility-action result selection, related details and lesson navigation. Selection uses the real accessible dropdown row rather than a coordinate guessed from popup geometry; this is not a full TalkBack journey. No test-only dependency was added.

Review frames: [graph](docs/screenshots/phase3-graph.png), [search](docs/screenshots/phase3-search.png), [selection](docs/screenshots/phase3-selection.png), [proposition](docs/screenshots/phase3-proposition.png). Phase-2 screenshots remain historical classroom baselines; the current home now displays the explicitly illustrative sample graph. These are inspected device captures, not approved pixel-diff goldens.

Validation found and fixed native dropdown behaviors: filtering an empty query dismissed Browse after it was opened, and a root dropdown TextView was forced into horizontal scrolling. Browse now opens after native filtering/observer completion; a native container row allows full proposition wrapping. Label collision suppression also covers cluster captions when the viewport shrinks. Source inspection confirms no recurring invalidation, physics or per-frame layout work. Manual idle check: after resetting `dumpsys gfxinfo` with the selected graph visible and editor unfocused, zero app frames were rendered over approximately 25 seconds. This is an idle-redraw check, not a gesture-jank benchmark. Real process-death check also passed: select/focus Positive derivative, background the app, `am kill` and confirm no PID, then restore its existing launcher task. Native hierarchy and captured-frame pixel checks confirm the selected detail action and graph viewport remain. A fresh explicit activity intent is not a restoration test; use the same MAIN/LAUNCHER task identity (`-f 0x10200000`) as its original launch.

Limitations: fixed small-fixture geometry and circular soft regions, not the reference’s force-layout/organic cluster geometry; linear picking/O(n²) label decluttering are intentionally bounded to 30 nodes. No graph extraction, audited identity, database, actual learner evidence or assessed confidence. The native browse/detail alternative is implemented, but a full TalkBack journey, API-26 device, tablet/foldable/RTL/rotation matrix, large-graph benchmarks and formal visual diffs are not claimed. Android Studio’s previously stale generated-binding index remains a separate IDE sync limitation; CLI build/lint/device checks are authoritative here.

### Visual refinement after owner feedback

Graph labels/captions now scale with zoom. Bottom button rows are replaced by a compact, fading above-node tooltip whose chevron opens proposition details; zoom/Fit, sample lesson and style reference move to the toolbar overflow. Search text/hint are vertically centered. Native selection fades respect disabled animations; tooltip placement follows the camera and stays inside the viewport.

Build/lint and IDE error inspections of both changed Kotlin files pass. All **11** Pixel_7/API-36 instrumentation tests pass in scoped runs (seven foundation/classroom plus four graph checks). All four graph checks also pass at 200% text with animations disabled; original emulator settings are restored. The additional check records actual Canvas text sizes across zoom, exercises rapid selection/fade completion, checks tooltip placement/clearing, search gravity and menu actions. Existing navigation/recreation checks now use toolbar actions. Updated review captures include [scaled text](docs/screenshots/phase3-scaled-text.png). The subsequent owner go-ahead after the selection-blink fix cleared the gate to phase 4.

The initial unfiltered `./gradlew :app:connectedDebugAndroidTest --console=plain` exceeded the 30-second shell limit and reported an animation-timing assertion failure; it is not counted as a pass. Final scoped runs completed successfully after waiting on native animation completion and using a short, unellipsized label in the scaling check at 200% text. Reproduce with `ANDROID_SERIAL=emulator-5554` and `-Pandroid.testInstrumentationRunnerArguments.class=com.kkgosu.learnharness.GraphTest` or `com.kkgosu.learnharness.FoundationTest,com.kkgosu.learnharness.LessonTest`.

## Phase-4 verification record

On Pixel_7 / API 36, `:app:assembleDebug` and `:app:lintDebug` pass. All **19** instrumentation tests pass in completed class-filtered runs: eight RuntimeTest, five LessonTest, and six FoundationTest/GraphTest. All eight runtime tests also pass at 200% system font size with animator duration scale 0; original settings restore to 1.0 afterward.

Runtime checks cover: the fake-provider context-tool → Prober question → answer → provisional route → explicit approval → Teacher check → summary flow; usage totals; SQLite reopen/evidence; injected checkpoint-write failure preventing a provider call or lost draft; cancellation/partial-context exclusion; role/schema/replay/step-budget rejection; complete/incomplete/malformed/oversized SSE; fixed HTTP endpoint and sanitized authentication errors; a cancelled blocked HTTP body being closed; isolated Keystore ciphertext/reset; secure unsaved key entry; no generation on local session creation; native draft recreation and actual IME geometry at enlarged type. Initial testing caught an IOException racing coroutine cancellation and replaced main-thread-idle assumptions with a wait for actual keyboard insets.

Reproduce without credentials/network (each command can be scoped to stay within a 30-second shell window):

```sh
ANDROID_SERIAL=emulator-5554 ./gradlew :app:assembleDebug :app:lintDebug
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.kkgosu.learnharness.RuntimeTest
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.kkgosu.learnharness.LessonTest
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.kkgosu.learnharness.FoundationTest,com.kkgosu.learnharness.GraphTest
```

The combined older-regression command (FoundationTest, LessonTest and GraphTest together) exceeded the 30-second shell limit after 8/11 tests with no failure yet reported. It is **not counted as a pass**; no active instrumentation/app process remained before the successful smaller class runs. IDE inspections of credentials, SQLite, schemas, runtime and both fragments report no errors. The HTTP file's IDE model still resolves AppKit's old OkHttp API and reports stale companion/property errors, whereas the CLI dependency report resolves 5.4.0 and compilation/lint/transport execution pass: refresh Android Studio's Gradle model.

Manual process-death recovery also passed without a key: created “Local recovery check” using the non-secret placeholder model ID `local-validation-model`, entered a composer draft, backgrounded the app, killed it and confirmed no remaining PID, then restored the existing MAIN/LAUNCHER task. The session, focused draft and ready state returned without generation. This verifies an idle local checkpoint, not process death during an in-flight request or disk write.

Review captures use local fixtures, not live model output: [ready](docs/screenshots/phase4-ready.png), [question](docs/screenshots/phase4-question.png), [200% question](docs/screenshots/phase4-question-large-text.png). The installed APK launches successfully; the later navigation refinement below replaces the original Home overflow → Learning sessions entry. Real OpenAI smoke testing, educational-quality evaluation, API-26/other-device/TalkBack journeys, comprehensive vulnerability audit and arbitrary process-kill timing during disk writes remain unverified. Historical phase-2/3 screenshot links predate this change; their image files are absent from this checkout, and are not recreated as part of phase 4.

## Optional ChatGPT plan connection — verification

The requested public open-source flow is implemented alongside BYOK. Setup distinguishes ChatGPT-plan usage from API billing, supports separate registrations/accounts, external-browser PKCE sign-in, refreshed account-specific models, welcome acknowledgement, Manage usage and sign-out/reset. A session persists its selected registration and never silently switches to an API key. Tokens are Keystore-encrypted; no account, API key, conversation or paid inference was used to validate this addition.

Build, Android-test compilation and lint pass. After an authorized cold restart of the stalled Pixel_7/API-36 emulator, all **25** instrumentation tests pass in completed scoped runs: six ChatGptTest, eight RuntimeTest, five LessonTest and six FoundationTest/GraphTest. The new native billing UI check also passes at 200% text with animations disabled; original emulator settings were restored. IDE error inspections of `ChatGptAuth.kt` and `ProviderSettingsFragment.kt` report no errors.

The six new checks cover RFC PKCE, signed issuer/audience/nonce/expiry/signature rejection, loopback state/duplicate rejection and socket cancellation, dynamic registration/refresh/model listing/revocation/encrypted storage, durable billing isolation/no API fallback, preview-compatible plan tools/tool continuation/namespace rejection and native billing-choice persistence/no implicit browser login. These use local HTTP/token fixtures, **not a live OpenAI authorization**. Reproduce with the existing class-filtered Gradle command and `com.kkgosu.learnharness.ChatGptTest` (or `#methodName` for a smaller run).

Earlier all-class and UI-only runs hit the 30-second shell timeout while Android's activity service was stalled; they are not counted as passes. An initial loopback cancellation race was fixed by honoring cancellation around socket acceptance/read errors. The emulator's graceful reboot/shutdown also stalled; the owner authorized restart, and its stuck process was terminated before cold-starting the same saved AVD without a data wipe. Final split runs above completed normally.

Remaining: real Android browser authorization/callback behavior, live account/workspace eligibility/catalog/refresh/revocation, actual plan inference/limits and production educational quality. If Android kills the app during browser authorization, restart sign-in; credentials are saved only after validated exchange. First-party open-source license/release review is still an owner decision, not inferred from the intended distribution.

## Navigation and settings refinement — verification

Implemented the requested native drawer with the newest 30 saved sessions, New session, Knowledge graph and a pinned Settings entry. Settings is a category list opening Providers & models, Learning preferences and About & privacy. Provider setup no longer mixes account configuration with goal creation or a history dialog. Radio rows align, form gutters are consistent, descriptions are shorter, and disabled buttons use 40% alpha. The working name remains unchanged pending an owner-selected replacement.

`assembleDebug`, Android-test compilation and `lintDebug` pass. With owner approval for up to 90 seconds per emulator test command, the complete **26-test** instrumentation suite passed on Pixel_7/API 36 in 84 seconds. `NavigationTest` covers the hamburger, populated drawer, native Back dismissal, settings categories, preference persistence/recreation, saved-session opening/draft restoration and separate New session UI. Existing runtime and billing checks now exercise the separated pages, including password-state exclusion, no automatic generation, aligned radio rows, minimum targets and disabled alpha.

The first 200% provider check exceeded 30 seconds and was aborted, not passed. A later authorized 90-second run recorded the provider-controls test as **PASSED** at 200% text with animations disabled, but timed out during `NavigationTest`; the overall run and the enlarged-text navigation journey are **not** passes. Emulator font and animator scales were restored to 1.0. Default-text captures are in `docs/screenshots/navigation-{drawer,settings,new-session}.png`; provider screenshots remain protected by FLAG_SECURE. No live authorization/inference was attempted. Full TalkBack, API-26, RTL/tablet layouts and large history management remain unverified/deferred.

## Back, search, model list and ViewBinding removal — verification

Drawer destinations now keep Home underneath; Settings toolbar/system Back returns there, including after recreation. Home search opens a native full-screen list with Mastodon-style 700/300 ms expansion/collapse, automatic keyboard focus, API-30+ IME-inset animation, empty/no-match states and restored queries. Choosing a result waits for the restored graph's layout before applying focus. Provider radios are vertically centered; ChatGPT models load automatically as account-scoped selectable rows, while API connections retain manual model IDs. ViewBinding and its Gradle setting are removed app-wide in favor of `findViewById`.

All **31** Pixel_7/API-36 instrumentation tests pass in completed 13-test and 18-test class-filtered runs. Four targeted checks also pass at 200% text with animator scale 0: search/IME/query and graph restoration, Settings Back, fixture-backed automatic model loading/selection/recreation, and native billing controls including actual radio-glyph centering. Original emulator settings were restored. Model UI checks substitute a local encrypted OAuth/HTTP fixture, not a real account. No screenshots or live inference requests were made. Live catalog/authorization and API-26–29 keyboard behavior remain unverified.

## Live catalog and session-start polish — verification

The owner's model-list error was the application's 256 KiB JSON cap, not a subscription quota. Model catalogs now allow 2 MiB while token/discovery/revocation requests keep the 256 KiB bound. Fixture checks cover a multibyte catalog above the old cap, exactly 2 MiB accepted and one byte over rejected without losing the connection. On the owner's already-authorized Pixel 8 Pro, automatic catalog loading and explicit refresh both showed seven model choices. No lesson inference or fresh browser authorization was performed.

Provider billing/model indicators use native relative LayerDrawable insets (24 dp outer, 8 dp text gap), retaining full-width row ripples. Reset has an inline explanation; verified email labels no longer append opaque connection IDs. The shared composer centers its hint vertically and disables horizontal baseline alignment, preventing the action button from being shifted/clipped above its row. Start session now creates and requests the first step in one tap, after checkpoint loading, with its one-shot navigation flag consumed before recreation. Existing idle sessions still require an explicit Start.

Build, Android-test compilation and lint pass. All **22** relevant Runtime/ChatGPT/Lesson/Navigation tests pass in scoped emulator runs; **three** focused checks also pass at 200% text with animations disabled, and original emulator settings are restored. The fixture-backed start check makes exactly one request and none on recreation/reopening; the UI check confirms the entire Start button fits within its row. The updated APK was installed on Pixel 8 Pro without clearing data; native hierarchy inspection confirmed its existing session's input/action share the same full vertical bounds and the account-ID suffix is absent. The new goal screen has an enabled Start session button; the provider screen again loads seven live model choices and exposes the reset explanation after scrolling. No screenshots were taken.

## ChatGPT response-format compatibility — verification

The owner authorized exactly one live diagnostic request on Pixel 8 Pro for the new-session `protocol` error. An isolated copy of the failed session used its saved connection and the same runtime request construction. Safe metadata showed HTTP 200, an unexpected Content-Type label and 76 valid JSON SSE frames; the header-only gate rejected the response before SSE parsing. The original session was not modified, and temporary diagnostic source/test-package/database were removed. No credentials, goal or response content were logged.

The shared transport now validates SSE framing instead of the media-type label. Comments, standard event/id/retry fields, BOM/CRLF and empty data frames are supported; non-SSE JSON/HTML, malformed data, `[DONE]` without completion, incomplete streams and byte limits remain fail-closed. Only validated `response.completed` output can commit tools/context. Fixture transport checks cover six content-type variants (including absent) and HTML/JSON rejection. Build, test-APK assembly and lint pass; all **15 RuntimeTest/ChatGptTest tests pass** on Pixel_7/API 36. No second live request was made; the owner's next explicit retry remains the end-to-end live check.

## LearnHarness network-protocol alignment — build-only handoff

The ChatGPT path now ports upstream `src/agent/chatgpt-stream.ts` at the existing pinned revision: an allowlisted payload, the selected model, stateless history with system→developer conversion, and top-level `learnharness`-namespaced tools replace the custom `additional_tools` input item. API-key payloads stay unchanged. Plan validation admits that exact namespace, rejects foreign/API namespaces, preserves call/item IDs and opaque reasoning in history, and keeps legacy unnamespaced records usable. Top-level/nested stream error codes now reach existing safe recovery messages; response errors and missing/early terminal completion cannot succeed.

Fixture checks were updated for the upstream wire shape, opaque-history continuation, payload sanitization, namespace isolation and terminal/error handling. At the owner's request, no on-device/emulator tests or live requests are run for this change; only APK/test compilation and lint are used. Full tutoring/tool availability, Electron IPC and background admission are not ported by this network alignment; Android-specific credentials, lifecycle, safety budgets and per-session billing remain intact.

## Stream item assembly and format diagnostics — build-only handoff

Source comparison with LearnHarness's exact `@earendil-works/pi-ai` 1.4.0 dependency found a missed integration step: its `processResponsesStream` assembles finished output items and uses their final text, rather than requiring terminal-envelope output to equal provisional deltas. Android now collects indexed `response.output_item.done` items, preserves opaque reasoning with terminal-only encrypted-content backfill, rejects unfinished items, and waits for successful completion before validating/committing anything. Full terminal-output fallback remains for existing Responses fixtures. Missing message status is accepted only after successful completion; explicit non-completed message status still fails. A compiled fixture covers reconstruction, final-text replacement and unfinished-item rejection; it has not been run on-device.

At the owner's request, temporary `ReasonedRuntime` logs record only HTTP status, allowlisted event kinds, item/pending counts, text lengths, namespace/declared-tool booleans, argument/choice counts and safe failure codes. No raw headers, credentials, messages, learner answers or tool arguments are logged. Validation errors now distinguish shape/count/message/tool/argument/choice failures. Filter with `adb logcat -s ReasonedRuntime`; remove the tagged log calls to remove diagnostics. Build/lint only; no device or live request verification is claimed for this change, and the exact original failing guard remains unconfirmed without the owner's next run.

## Runtime history, Markdown and composer refinement — build-only handoff

The owner confirmed live teaching now works, then reported duplicate answers, unparsed questions, missing probe-question history and generation motion. Written responses now enter display/protocol once; accepted questions are saved as transcript rows, and legacy missing question rows are reconstructed from existing evidence. Native Markwon 4.6.2/commonmark/JLaTeXMath renders standard Markdown and inline/display math in runtime text, questions and choices. Confidence has six 20-percent positions. A progress indicator, question bounds/fade motion and API-30+ IME-frame resizing accompany the persistent rounded composer; the transcript stays visible while answering. The sample lesson retains its authored layout.

Functional composer controls are Send/Stop, session model, thinking level and reported-context snapshot only. Account/Manage usage is inside the context dialog rather than a top plan button. Model IDs/names come from the session connection's API; capability metadata comes from a reproducibly generated pi-ai 1.4.0 catalog (version/hash in `openai-models.json`, generator `scripts/update-model-catalog.mjs`). This mirrors LearnHarness's live account list + pi-ai capability join, without fabricating unknown model windows/levels. Explicit effort is persisted and sent as reasoning.effort; Auto omits it. Selecting settings never generates or switches billing. The ring is last completed request input usage/catalog capacity, not cumulative billing or a live tokenizer; the dialog explains unknowns and the independent local byte cap. Prompt revision v2 changes only the Android capability appendix; pinned upstream source bytes remain untouched.

APK/test compilation and lint pass. Existing instrumentation fixtures were adapted for history recovery, single answers, confidence, effort persistence/request shape, Markdown and the new dock; they are compiled but **not run** at the owner's request. No device/emulator tests, screenshots, installation or live requests were performed for this refinement. Keyboard/animation geometry, large-text behavior and live effort selection need the owner's manual check.

## Runtime follow-up — build-only handoff

Restored the question panel's existing rounded outline, bounded composer ripples to the button shape (transparent at rest to retain the context ring), and moved generation progress into an ephemeral last transcript row. Local model lists now persist per connection and are shared by Settings/session pickers; reopening does not fetch again, explicit Refresh models does. Failed refresh retains the cache; API credential changes and UI plan sign-out/reset invalidate their entries.

The missing reasoning/capacity cause was a generator bug: pi-ai's internal keys (`chat:gpt-6.1-sol`) were emitted instead of flattened API `model.id` values (`gpt-6.1-sol`). The generator/42-entry asset now use wire IDs and guard against typed keys. Metadata lookup for the owner's selected gpt-6.1-sol now supplies its catalog capacity and low/medium/high/xhigh/max efforts. Saved input/output usage is also shown in context details; a missing legacy last-request snapshot remains unknown, not guessed from accumulated billing usage. Compiled fixtures cover the exact ID, supported levels, cache reuse/explicit refresh and footer position during streaming. Build/lint only; no device tests, screenshots, installation or live requests for this follow-up.

## Phase 5 — plans and persistent knowledge, build-only handoff

- Added a validated session DAG with stable IDs/versioned rewrites and native dependency review. Duplicate/missing IDs, self-loops and cycles fail closed. Learner confirmation precedes teaching; question tags derive current/visited positions without a completion/mastery ledger. Plans remain separate from permanent knowledge.
- Added an additive SQLite v1→v2 migration, persistent propositions/aliases, joint-premise arguments, exact source-message anchors, fingerprint coverage and separate stage usage. Message checkpoint writes preserve foreign keys via UPDATE/INSERT. The deterministic merger uses only seen audited links or exact statement equality, preserves first identity text, validates resolved graphs, and atomically commits evidence plus coverage. Repeated local findings resolving to the same proposition/chain and message reuse one source anchor instead of rolling back. Replays do nothing; cancellation/errors leave the current batch uncovered.
- Owner chose the upstream-style hybrid: automatic dispatch after a completed turn at 4096 estimated uncovered tokens, plus a manual session-menu action. Only canonical completed assistant prose is eligible; answers/confidence/tools/plans/partials are excluded. Extractor sees the source; Auditor sees findings plus bounded graph reads. Pinned instructions are projected by scripts/update-extraction-prompts.py from the existing unmodified source. Both stages use this session's captured model/effort/connection, with additional usage disclosed. The channel is single-writer/foreground-only and independently cancellable; opening a saved session never generates.
- Home/search/details now read the real graph, including honest empty states, derived argument junctions, source deep links to exact messages and separate sample-graph navigation. Later sessions can read a bounded goal-relevant proposition preview via learning_context without treating it as mastery. Native session plan review and graph-stage progress/stop actions reuse existing surfaces.

Validation: APK assembly, instrumentation-APK compilation and lint pass. An offline check of the actual SQL DDL verifies additive schema creation, surviving source updates and foreign-key rejection of missing anchors; all 36 pinned files still match their source hashes. New KnowledgeTest fixtures cover migration, plan validation/positions, atomic/replay-safe identity merging, source privacy/billing, automatic dispatch and cancellation. Existing teaching/graph fixtures were adapted to explicit plan approval and separate sample navigation. **Fixtures were compiled, not run.** No installation, device/emulator test, screenshot or live inference was performed. Phase-5 acceptance remains pending those checks, especially extraction accuracy, source-link UI, process interruption and enlarged text.

## Answer-card and graph-menu refinement — build-only handoff

Matched the owner's LearnHarness reference with native answered cards, selected/correct option marks, confidence rails and matching six-color live sliders, learner bands, authorship dividers and independent learner/Why/agent-summary disclosures. New question calls precommit the hidden answer/explanation; selected listed options receive deterministic key comparison, while written answers reveal the reference without invented semantic grading. Existing evidence renders as cards without adding missing keys. Prompt appendix v4 preserves pinned upstream bytes. Public completed reasoning summaries are requested for known reasoning models and stored separately from extractable prose; encrypted reasoning is never rendered.

The perpetual Stop graph update item was stale UI: AppKit does not invoke platform onPrepareOptionsMenu for its toolbar. Both graph and session menus now derive state during onCreateOptionsMenu and use fragment invalidation. No pending prose gets a distinct explanation. An in-progress session/plan is not itself a graph node; automatic extraction still waits for about 4096 uncovered estimated tokens, while a manual session update can process less.

APK/test-APK compilation and lint pass. Regression fixtures cover answer-key validation, public-summary-only rendering, card/disclosure/confidence state, legacy cards and idle Stop visibility; they are compiled, not run. No device/emulator tests, installation, screenshot or live requests were performed. Actual extraction results, UI geometry and provider-summary availability still require the owner's check.

## Remaining milestones and acceptance gates

| Phase | Minimum implementation | Gate before advancing |
|---|---|---|
| **2 — visual classroom (implemented)** | Home/session navigation, typed DisplayItems, text/basic formulas/choice rows, persistent dock and incremental mock streaming | Technical checks and captures recorded; owner subsequently requested proceeding after visual refinements. |
| **3 — graph prototype (implemented)** | Kotlin Canvas, 30 sample propositions/six clusters, off-main snapshot, pan/pinch/selection/search/browse/fit and native details | Technical camera/picking/gesture/restoration/navigation checks pass; reference comparison/captures recorded. Owner-requested label/tooltip/search/fade fixes completed before phase 4. |
| **4 — local runtime (implemented)** | OpenAI Responses/OkHttp, Keystore BYOK, cancellable bounded loop, Prober/Teacher, SQLite checkpoints and original prompts | Eight deterministic runtime/transport/credential/UI checks pass, including malformed tools, cancellation and interruption recovery. End-to-end real-session smoke testing remains incomplete; the one authorized format diagnostic is recorded above. |
| **5 — plans and knowledge (implemented, build-only)** | Dependency validation, Extractor/Auditor/deterministic merger, SQLite graph/evidence/provenance, session continuity | Code/compiled fixtures cover reference/DAG rejection, atomic replay-safe merges, conservative identity resolution and precise sources. Device/live acceptance remains unverified; plan and persistent graph stay independent. |
| **6 — educational content** | Bounded PDF import/render/search, Mermaid, SVG Visual Maker with render/vision/revision workflow | Page-citation, cache/memory and malformed-file tests; unsafe markup denied; artifacts store truthful verification status; specialized WebViews have explicit capability limits. |
| **7 — practice and research** | Knowledge-aware exercises/attempts, Researcher search/fetch, citations and persistence | Reasoning/transfer checked; attempts survive interruption; retrieved text cannot grant tools or replace instructions. |
| **8 — generated demos** | Demo Builder generate/validate/test/repair loop, only after an acceptable execution strategy exists | Threat model, malicious HTML/JS fixtures, network/file/bridge restrictions, runtime-failure detection and release security review. **Keep arbitrary execution disabled if isolation is not adequate.** |
| **9 — optional features** | More providers, speech, import/export, advanced graph/background recovery (official OSS ChatGPT sign-in is implemented separately below) | Separate requirements/evidence for each; no undocumented auth endpoints or dependency on optional sign-in. |

## Explicitly deferred / release decisions

- Real runtime, credential UI and SQLite now exist. Live account/model discovery is verified, and one owner-authorized ChatGPT-plan request diagnosed response-format handling. The owner reports teaching now works; phase-5 extraction behavior and educational quality remain live-unverified. API-key sessions support manual setup IDs plus a discovered session picker joined to known Responses models; optional ChatGPT sessions use their own account catalog. Additional providers are later work.
- No PDF renderer or WebView. Runtime standard Markdown/math uses native spans; specialized rich content remains future work. Persistent extracted knowledge is now separate from the explicitly illustrative sample graph; neither is a mastery score.
- No independent agent swarm, DI framework, generic repository layer, multi-module split or new navigation abstraction.
- First-party licensing is an owner decision. Before public release, audit the final dependency notices/security inventory and provide an appropriate licenses surface. Fonts and existing third-party notices already ship as assets.
- Phase-2 Android-sized targets/captures are recorded; neither the foundation specimen nor the classroom mock is declared a complete LearnHarness reproduction.
