# Implementation plan and phase 0–1 handoff

## Scope decision

The requested work is **phases 0–1**, not the complete product. Research the full experience so the foundation supports it, but do not implement the phase-2 mock classroom/dock/streaming or phase-3 Canvas graph yet. Do not introduce a model runtime before visual prototypes are approved.

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
- [x] Finite short transitions, respecting the system animator setting; preserve AppKit predictive-back cleanup.
- [x] Kotlin device checks for font binding, navigation and recreation with an unfinished draft; screenshot capture smoke test.
- [x] Build/run on an emulator, inspect screenshots against reference characteristics, document intentional differences and unfinished visual validation.

The style specimen is intentionally static XML in a ScrollView. LiteX is installed but has no fabricated list adapter merely to demonstrate a dependency; actual DisplayItems arrive with the lesson prototype.

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
Captured app-window images are collected under `app/build/outputs/connected_android_test_additional_output/debugAndroidTest/connected/<device>/`. Tests use AGP's `additionalTestOutputDir`; standalone instrumentation falls back to the app's external-files `screenshots/` directory. These captures demonstrate rendering, not approved pixel-diff tests.

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

Our own full-device captures are checked in:

- [Phase-1 home](docs/screenshots/phase1-home.png)
- [Phase-1 style reference](docs/screenshots/phase1-style-reference.png)

No copied upstream screenshots are packaged. The captures are **not** approved Android-sized golden images. A screen-capture smoke test cannot establish full visual fidelity, gesture correctness or accessibility.

Not yet verified: API-26 runtime/device-specific behavior, tablet/foldable composition, actual rotation/multi-window/RTL/TalkBack journeys, gesture-mode predictive-back completion/cancellation, or formal screenshot diffs. These remain validation work; successful activity recreation is not claimed as every configuration/device test.

For manual process-death retesting: open the style screen, scroll, enter a draft, press Home, then kill only the background app. Restore its launcher task with MAIN/LAUNCHER (or Recents). A bare explicit `am start -n` may create a new standard Activity instance, and force-stop is not a saved-task-restoration test.

## Remaining milestones and acceptance gates

| Phase | Minimum implementation | Gate before advancing |
|---|---|---|
| **2 — visual classroom** | Real mock-data home/session navigation, typed LiteX/AppKit DisplayItems, text/formulas/choice rows, persistent composer/question dock, mock incremental streaming | Prepare manually reviewed Android-sized adaptations of the original screenshots. Check draft/choice preservation, keyboard, font scaling, stable row IDs, partial updates and document-like presentation. Correct major visual mismatches before real AI. |
| **3 — graph prototype** | Kotlin `KnowledgeGraphView`, sample nodes/edges/clusters, separate layout snapshot, pan/pinch/selection/search/fit | Test camera/inverse picking math and gesture focus; compare density/labels/regions with the original graph; verify no idle redraw loop and usable detail navigation. |
| **4 — local runtime** | Maintained provider HTTP client, Keystore-backed BYOK, cancellable streaming/tool loop, Prober/Teacher, first session tables/checkpoints, original prompt assets | Deterministic fake-provider loop/tool/cancellation/error tests; malformed outputs rejected; interrupted state recoverable; no keys in APK/logs/exports/backups. Real-model smoke tests opt-in. |
| **5 — plans and knowledge** | Dependency validation, Extractor/Auditor/deterministic merger, SQLite graph/evidence/provenance, session continuity | Reject broken references/cycles where forbidden; transactional replay-safe merges; conservative identity resolution; precise source links; plan and persistent graph remain independent. |
| **6 — educational content** | Bounded PDF import/render/search, Mermaid, SVG Visual Maker with render/vision/revision workflow | Page-citation, cache/memory and malformed-file tests; unsafe markup denied; artifacts store truthful verification status; specialized WebViews have explicit capability limits. |
| **7 — practice and research** | Knowledge-aware exercises/attempts, Researcher search/fetch, citations and persistence | Reasoning/transfer checked; attempts survive interruption; retrieved text cannot grant tools or replace instructions. |
| **8 — generated demos** | Demo Builder generate/validate/test/repair loop, only after an acceptable execution strategy exists | Threat model, malicious HTML/JS fixtures, network/file/bridge restrictions, runtime-failure detection and release security review. **Keep arbitrary execution disabled if isolation is not adequate.** |
| **9 — optional features** | Officially supported ChatGPT sign-in if eligible, more providers, speech, import/export, advanced graph/background recovery | Separate requirements/evidence for each; no undocumented auth endpoints or dependency on optional sign-in. |

## Explicitly deferred / release decisions

- No runtime code, cloud calls, credentials UI, coroutines/Flow dependency, database schema or Markdown prompt scaffolding until those features exist.
- No full lesson, dock, DisplayItems, formula engine, graph view, PDF renderer or WebView in phase 1.
- No independent agent swarm, DI framework, generic repository layer, multi-module split or new navigation abstraction.
- First-party licensing is an owner decision. Before public release, audit the final dependency notices/security inventory and provide an appropriate licenses surface. Fonts and existing third-party notices already ship as assets.
- Update this record with actual phase-2 Android-sized baselines rather than declaring the foundation specimen a completed LearnHarness reproduction.
