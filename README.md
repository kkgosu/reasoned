# Reasoned

A standalone, Kotlin-first Android learning app inspired
by [LearnHarness](https://github.com/amosblomqvist/learnharness), with native Views and AppKit
navigation. Not an official LearnHarness or OpenAI app.

**Status: pre-release.** The offline regression suite passes on an Android API 36 emulator. This is
not yet broad device coverage or live-provider acceptance. PDF support is deliberately deferred.

## Included

- Probe → learner-approved learning plan → Teacher, with predictions, reading checks, free recall,
  stable choice identities and multiple selection.
- Persistent chats, drafts and answers; drawer long-press rename/delete. Renaming updates graph
  groups; deleting a chat removes its history but retains extracted knowledge.
- A persistent knowledge graph with concise proposition summaries and named source-chat links; a
  separate illustrative sample graph.
- Native Markdown/LaTeX, offline Mermaid/SVG previews and a model-assisted figure
  creation/inspection loop.
- One-problem-at-a-time Practice and an isolated, opt-in Researcher using Brave search and public
  HTTPS pages.
- OpenAI API-key connections and browser-based ChatGPT OAuth/PKCE connections. Account/model
  availability depends on the provider; billing never silently falls back to another connection.
- Local session history and long-context compaction without deleting the full transcript.

Teaching, extraction, visual generation, research and compaction can make **paid model requests**.
An extracted proposition or model verdict is not independently verified and does not certify
mastery.

## Build

Requirements: JDK 21 (validated), Android SDK Platform 36 and the SDK/build tools required by
Android Gradle Plugin. Minimum supported Android version: 8.0 / API 26. The Gradle wrapper is
included.

1. Set `JAVA_HOME` and `ANDROID_HOME`, or configure `sdk.dir` in an untracked `local.properties`.
2. Build:

   ```sh
   ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:lintDebug :app:lintRelease
   ```

   A first build downloads dependencies. Once cached, add `--offline` to prevent Gradle network
   access.
3. Outputs:
    - Debug: `app/build/outputs/apk/debug/app-debug.apk`
    - Test APK: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
    - **Unsigned** release: `app/build/outputs/apk/release/app-release-unsigned.apk`

Release signing is intentionally not configured. Keep signing material and provider credentials
outside the repository; distribute only a deliberately signed release with its matching source.

## Start learning

Install the debug APK, then open Settings → Connections to configure an API key or browser sign-in
and select an available model. Start a new learning or practice session from the drawer. Brave
search has a separate credential; research also requires per-session permission. The sample lesson
and graph need no account.

Sessions, answers, graph data and preferences are app-private but **not encrypted as a database**.
Credentials are separately encrypted with Android Keystore. App backup/transfer is disabled;
uninstalling or clearing app storage loses local data, and export/import is not implemented.
See [PRIVACY.md](PRIVACY.md).

## Offline tests

**Use a disposable emulator only.** The fixtures exercise database migrations, deletion and
credential reset. Do not run them against a personal installation. Avoid unscoped connected-device
test commands when a phone is attached.

After building both debug APKs, target the emulator explicitly (replace `emulator-5554` with its
serial):

```sh
adb -s emulator-5554 shell svc wifi disable
adb -s emulator-5554 shell svc data disable
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -r \
  com.kkgosu.learnharness.test/androidx.test.runner.AndroidJUnitRunner
```

Read the final `OK (… tests)` / `FAILURES!!!` result: an `adb` exit code alone does not establish
test success. Providers and web retrieval are fixtures; loopback OAuth/socket checks and the offline
WebView renderer run locally. No real credentials are needed.

Latest release-preparation result: **76/76 tests passed on API 36**.
See [docs/RELEASE_CHECKLIST.md](docs/RELEASE_CHECKLIST.md) for remaining gates and limitations.

## Scope and limitations

- No PDF import/search, generated HTML/JavaScript demos, background agents, sync or export/import.
- Model requests are foreground operations; reopening a saved session does not itself resume
  generation.
- Formula escape recovery is presentation-only and bounded, not a complete desktop content-reader
  port.
- API 26–35, TalkBack journeys, RTL, tablets/foldables and live billing/provider/research flows
  still need release acceptance.

Architecture and implementation
history: [ARCHITECTURE.md](ARCHITECTURE.md), [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md).
Security boundaries: [SECURITY.md](SECURITY.md).

## License and attribution

Application code is licensed **AGPL-3.0-only**; see [LICENSE](LICENSE). Third-party components
retain their own licenses and notices.

- LearnHarness: pinned, byte-preserved instruction/source inventory and native adaptations;
  see [provenance](app/src/main/assets/learnharness/NOTICE.md)
  and [source hashes](app/src/main/assets/learnharness/SOURCE.sha256).
- AppKit / LiteX and Mastodon-derived search motion, Markwon / JLaTeXMath, Mermaid, fonts and other
  dependencies: notices under [app/src/main/assets/licenses](app/src/main/assets/licenses) and the
  bundled font/diagram source directories.
- Additional runtime
  notices: [runtime_dependencies.txt](app/src/main/assets/licenses/runtime_dependencies.txt).

Preserve these notices and comply with corresponding-source obligations when distributing modified
sources or binaries. This repository has not yet been published and no public release is implied by
a successful build.
