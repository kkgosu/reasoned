# Working on Reasoned

Reasoned is a standalone native Android learning app inspired
by [Amos Blomqvist's LearnHarness](https://github.com/amosblomqvist/learnharness). Credit the
original for the idea, teaching approach and visual direction. The Android structure is inspired
by [Mastodon for Android](https://github.com/mastodon/mastodon-android).

## Keep it boring

The lack of a fashionable architecture is intentional. This is one application module with Kotlin,
native Views/XML, platform fragments, AppKit/LiteX, coroutines, OkHttp and SQLite.

- Follow the surrounding code. Reuse existing widgets, styles and helpers before adding anything.
- Don't introduce Compose, MVVM, repositories/use-case layers, DI frameworks, new modules or
  dependencies just to make things look “proper.”
- Make the smallest correct change. No unrelated cleanup, formatting sweeps or speculative
  abstractions.
- Trace the actual flow and callers before fixing a bug; fix the shared cause rather than one
  visible symptom.
- Preserve unrelated local edits. Don't commit, reset files or modify signing/release configuration
  unless asked.

## Where things live

Application sources: `app/src/main/java/com/kkgosu/learnharness/`.

| Path                     | Responsibility                                                                 |
|--------------------------|--------------------------------------------------------------------------------|
| `MainActivity.kt`        | AppKit navigation, drawer and session actions                                  |
| `ReasonedApplication.kt` | Application-owned dependencies and foreground lifecycle                        |
| `fragments/`             | Screens using AppKit and platform fragments                                    |
| `controllers/`           | Learning runtime, tool execution, extraction, research, figures and compaction |
| `api/`                   | OpenAI HTTP/SSE, ChatGPT OAuth and public-web retrieval                        |
| `model/`                 | Session, question, plan, graph and result values                               |
| `storage/`               | SQLite, encrypted credentials and model catalog                                |
| `ui/`                    | Adapters, custom Views, graph geometry, diagrams and text rendering            |
| `utils/`                 | Shared JSON helpers                                                            |

- `app/src/main/res/`: layouts, strings, fonts and shared visual tokens.
- `app/src/main/assets/learnharness/`: pinned upstream sources and their provenance. This is
  vendored content, not the location for new project instructions.
- `app/src/main/assets/agents/`: Android compatibility instructions and projected specialist
  prompts. These are runtime assets; this root `AGENTS.md` is contributor guidance, not a teaching
  prompt.
- `app/src/androidTest/`: instrumentation tests, including offline provider fixtures.
- `scripts/`: existing prompt-projection, catalog and asset maintenance tools.

Read the relevant part of [ARCHITECTURE.md](ARCHITECTURE.md) for runtime changes
and [DESIGN_SYSTEM.md](DESIGN_SYSTEM.md) for UI changes. These also contain implementation
history/future plans; confirm current behavior in code.
Use [docs/RELEASE_CHECKLIST.md](docs/RELEASE_CHECKLIST.md) for recorded release evidence and known
failures.

## Boundaries worth preserving

- Keep AppKit's fragment stack, restoration and inset ownership. Don't replace navigation for a
  local screen change.
- Keep shared palette, typography, dimensions and styles. The dark visual direction is intentional.
  Preserve accessibility, large-font behavior, RTL and native touch feedback.
- The sample lesson, sample graph and style-reference screens are debug-only. Never treat sample
  graph data as learner evidence or expose debug screens in release.
- Keep upstream files listed in `app/src/main/assets/learnharness/SOURCE.sha256` byte-preserved. Use
  existing projection scripts and the separate Android compatibility layer; don't silently rewrite
  vendored prompts. Preserve licenses and provenance.
- Model output and public web pages are untrusted input. Preserve bounded parsing, tool validation,
  permission checks, public-HTTPS restrictions and isolated offline diagram rendering. No arbitrary
  model-generated JavaScript or active SVG.
- Preserve cancellation, foreground-only work, durable checkpoints and SQLite transaction ordering.
  Don't resume generation merely because a saved session was opened.
- Never silently change a session's model, connection or billing source. Additional requests can
  cost money.
- Don't add credentials, tokens, signing keys, personal conversations, databases or device logs to
  the repository. Keep credentials Keystore-backed and backups disabled.
  See [PRIVACY.md](PRIVACY.md) and [SECURITY.md](SECURITY.md).

## Build and validate

Use JDK 21 and Android SDK Platform 36. Minimum supported device API is 26; Java source/target
compatibility is 17. Gradle has built-in Kotlin support; don't add a redundant Kotlin Android
plugin.

```sh
./gradlew :app:assembleDebug
./gradlew :app:lintDebug
```

Use `--offline` when dependencies are already cached. Run the narrowest relevant checks, and report
what actually ran. A successful build isn't a passing test suite or live-provider acceptance.

For release-specific changes:

```sh
./gradlew :app:assembleRelease :app:lintRelease
python3 scripts/check-release-samples.py
```

The sample check needs both debug and release outputs. Release signing is not configured:
`app/build/outputs/apk/release/app-release-unsigned.apk` is **not** a distributable signed release.

### Pull request CI

`.github/workflows/pr-checks.yml` runs on every PR, pushes to `master`, and manual dispatch.
`Build and lint` compiles debug/release, runs the available JVM test task, lints both variants,
checks the vendored hashes and verifies R8 removes debug samples. `Instrumentation (API 36)`
independently builds and runs the full suite on a disposable Linux x86_64 emulator with device
networking disabled. Reports and a debug APK are retained for seven days; Actions are commit-pinned,
PR caches are read-only, and no secrets/signing keys are used. Do not exclude failing tests or add
`continue-on-error` to hide a failure.

After the workflow has run on GitHub, the repository owner can require both checks in the `master`
branch ruleset before merging. Workflow configuration alone does not enable branch protection. Known
failures remain documented in `docs/RELEASE_CHECKLIST.md`; the first hosted run, not local YAML
validation, establishes the actual CI result.

### Instrumentation: disposable emulator only

Tests exercise deletion, migrations and credential reset. Never run fixture suites on a personal
phone or use an unscoped connected-device test task. Inspect `adb devices` and explicitly target a
disposable emulator. Don't clear app data or disable networking on a personal device.

Build the app and test APK, then install on the selected emulator (replace the serial below if
needed):

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s emulator-5554 shell svc wifi disable
adb -s emulator-5554 shell svc data disable
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -r \
  com.kkgosu.learnharness.test/androidx.test.runner.AndroidJUnitRunner
```

For a focused test, add `-e class com.kkgosu.learnharness.TestClass#methodName` before the runner.
Read the final `OK` / `FAILURES!!!` output; ADB's exit code alone doesn't prove success. Don't
weaken assertions or hide known failures to get a green result.

No live model, OAuth or Brave calls without explicit permission: they can send private context and
incur costs. API-key live use is currently untested; don't document it as verified.

## Publishing versions

About reads the installed version from `BuildConfig.VERSION_NAME`. Update `versionName` and
increment `versionCode` in `app/build.gradle` for each release. Publish a stable GitHub release in
`kkgosu/learnharness` with a matching numeric tag, preferably `vMAJOR.MINOR.PATCH` (for example
`v1.1.0`). Drafts and prereleases are not update candidates. About uses GitHub’s latest stable
release endpoint; keep its latest-release designation correct. The app opens GitHub Releases in the
browser and does not download or install updates itself.

## Documentation and PRs

Keep public documentation in English and the tone approachable. Encourage focused bug-fix PRs.
Update docs when behavior changes, but don't turn an unexecuted check into a success claim. README
screenshots belong in `docs/screenshots/`; capture actual debug sample screens without credentials
or private content, and label sample/test data honestly.
