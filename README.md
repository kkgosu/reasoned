# Reasoned

**First things first: all the credit for the idea goes
to [Amos Blomqvist](https://github.com/amosblomqvist)
and [LearnHarness](https://github.com/amosblomqvist/learnharness).** That's the project that
inspired this app: its approach to learning, teaching instructions and visual direction. If you like
what you see here, go give the original some love (and a star). The laurels belong there; I just
wanted this experience on Android.

The Android architecture takes inspiration
from [Mastodon for Android](https://github.com/mastodon/mastodon-android). After writing about a
hundred apps with the “proper” architecture, I wanted to try something different: an app without
Architecture™ and the usual modern stack. So: Kotlin, native Views, platform fragments, AppKit/LiteX
and SQLite. No Compose, MVVM ceremony, DI framework or clean-architecture layer cake. Just an
Android app.

**Found a bug? If you can, please fix it and send a PR.** Small, focused fixes are very welcome. If
you can't fix it yourself, an issue with reproduction steps, Android version and screenshots still
helps a lot. Please leave credentials and private conversations out of reports.

This is an independent app, not an official LearnHarness, OpenAI or Mastodon app.

## What it does

Reasoned is a learning app, not just another chat box. Start with something you want to understand,
work through questions about what you already know, approve a learning plan, then learn by
explaining, predicting and recalling—not only reading.

- Guided lessons with understanding checks, free-text answers and multiple-choice questions.
- Practice, one problem at a time.
- Saved sessions, drafts and answers; long-press a session in the drawer to rename or delete it.
- A persistent knowledge graph with summaries and links back to source conversations.
- Markdown, math, Mermaid diagrams and SVG figures.
- Optional web research with per-session permission; Brave Search uses a separate key.

AI explanations, extracted knowledge and grading can be wrong. A graph node is not proof that you've
mastered something.

## Screenshots

Captured from the debug build on an Android emulator. The graph, lesson and answer feedback are
built-in samples, not generated learning results. Sample screens are debug-only and aren't included
in release builds.

<table>
  <tr>
    <th>Sample graph</th>
    <th>Correct answer & feedback</th>
    <th>Sample lesson</th>
  </tr>
  <tr>
    <td><img src="docs/screenshots/sample-graph.png" width="260" alt="Sample knowledge graph with connected topic groups"></td>
    <td><img src="docs/screenshots/sample-answer.png" width="260" alt="Correct answer to the sample prediction question with explanatory feedback"></td>
    <td><img src="docs/screenshots/sample-lesson.png" width="260" alt="Sample gradient descent lesson with formulas and an understanding check"></td>
  </tr>
</table>

To explore the samples yourself, install a debug build and open the graph screen's overflow menu → *
*Sample graph** or **Sample lesson**. No account needed.

## Connections and costs

**Currently, the only model provider is OpenAI: a ChatGPT subscription connection, or an OpenAI API
key. API-key connections are implemented but haven't been tested with live requests.** Other model
providers aren't supported yet. ChatGPT model availability and limits depend on your account and
plan.

ChatGPT subscription usage and API billing are separate. The app never silently switches to another
connection. Lessons, graph extraction, figure generation, research and automatic context summaries
can make additional model requests and consume your plan allowance or incur API charges. Brave
Search has its own key and billing.

## Install

**Requires Android 8.0 (API 26) or newer.** GitHub release APKs are coming later; for now, build
from source.

### Build from source

You'll need Git, JDK 21, and an Android SDK with Platform 36. The Gradle wrapper is included; the
first build needs internet access to download dependencies and any missing build tools.

```sh
git clone https://github.com/kkgosu/reasoned.git
cd reasoned
```

Open the project in Android Studio, let Gradle sync, select the `app` debug configuration and run it
on your device or emulator.

Or build from a terminal, with `JAVA_HOME` pointing to JDK 21 and `ANDROID_HOME` pointing to your
Android SDK (you can also set `sdk.dir` in an untracked `local.properties`):

```sh
./gradlew :app:assembleDebug
```

On Windows, use `gradlew.bat` instead of `./gradlew`.

The APK is at `app/build/outputs/apk/debug/app-debug.apk`. Copy it to your phone, open it and allow
installation from that source when Android asks. Or enable USB debugging, connect your device and
install with ADB:

```sh
adb devices
adb -s YOUR_DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug builds use a local development signing key. Future signed releases may require uninstalling a
debug build first; uninstalling deletes local app data.

### Updates

In **Settings → About & privacy**, tap the current version to open this repository’s GitHub
Releases, or **Check for updates** to check for a newer stable release. Checks happen only when you
press the button; there is no background updater or automatic installation. Until releases are
published, the check reports that none are available.

### First launch

1. Open the drawer → **Settings → Providers & models**.
2. Connect your ChatGPT account in the browser, or enter an OpenAI API key (live API-key use is
   untested).
3. Choose and save a model.
4. Optionally set your language, pace and explanation style under **Learning preferences**.
5. Open **New session**, enter a topic and start learning. Keep the app in the foreground while it
   generates.

## Your data and current limits

Sessions, answers, preferences and the knowledge graph stay in app-private storage on your device.
The database itself isn't encrypted; credentials are stored separately using Android Keystore-backed
encryption. Starting a model request sends the relevant learning context to OpenAI. Optional
research also sends queries to Brave and fetches public pages from your device.

There is no sync, export/import or app backup. **Clearing app storage or uninstalling loses your
local data.** PDF import and background agents aren't supported.

This is still pre-release software. Offline checks aren't the same as live-provider or broad device
testing. Known failures and remaining release checks are tracked
in [the release checklist](docs/RELEASE_CHECKLIST.md), not hidden behind a “ready for everything”
badge.

More details: [Privacy](PRIVACY.md) · [Security](SECURITY.md).

## Working on the app

Agents and human contributors: start with [AGENTS.md](AGENTS.md). It covers the project layout,
conventions and safe validation commands. [ARCHITECTURE.md](ARCHITECTURE.md) describes the
implementation; [DESIGN_SYSTEM.md](DESIGN_SYSTEM.md) documents the visual rules.

Please keep PRs focused. A bug fix doesn't need a new framework attached to it.

Every PR runs [GitHub Actions checks](.github/workflows/pr-checks.yml): debug/release compilation,
Android lint, pinned-source integrity, release sample removal and JVM unit tests. The JVM suite
covers version comparison, OAuth/PKCE and loopback callbacks, ChatGPT payload/error handling,
question contracts/grading, public-web restrictions and graph geometry. Run it locally with
`./gradlew :app:testDebugUnitTest`; no emulator or real provider credentials are needed.
Android-specific checks (UI, SQLite, Keystore and native rendering) remain local instrumentation
tests on a disposable emulator—not part of PR CI.
Build/test reports and a debug APK are retained as workflow artifacts for seven days, including
reports from failed runs. No provider credentials or release signing keys are needed.

## License and credits

Application code is **AGPL-3.0-only**; see [LICENSE](LICENSE). Third-party components keep their own
licenses. Preserve their notices and comply with corresponding-source obligations when distributing
modified sources or binaries.

- **[LearnHarness / Amos Blomqvist](https://github.com/amosblomqvist/learnharness)** — the original
  idea, teaching approach, visual inspiration and bundled instruction sources.
  See [provenance](app/src/main/assets/learnharness/NOTICE.md)
  and [source hashes](app/src/main/assets/learnharness/SOURCE.sha256).
- **[Mastodon for Android](https://github.com/mastodon/mastodon-android)** — inspiration for the
  Android structure and way of building a native app.
- **AppKit / LiteX, Markwon / JLaTeXMath, Mermaid, bundled fonts and other dependencies** —
  see [bundled notices](app/src/main/assets/licenses)
  and [runtime dependency notices](app/src/main/assets/licenses/runtime_dependencies.txt).
