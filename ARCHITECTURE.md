# Reasoned — Android architecture

## Scope and current implementation

This document separates **implemented phase-1 foundations** from **future feature design**. Only phases 0–1 are requested now. The fuller “first task” examples in the product specification (lesson streaming, question dock and graph) map to phases 2–3 and are intentionally not implemented yet.

The app is standalone, Kotlin-first, traditional Views. It is not a web wrapper, LearnHarness client, Mastodon skin, or Pi host. It will eventually execute its own Kotlin learning runtime locally and call cloud model APIs directly. No custom backend, Node.js or remote agent runtime is part of the design.

Current single Gradle application module:

```text
app/src/main/java/com/kkgosu/learnharness/
  MainActivity.kt             AppKit FragmentStackActivity, root and transitions
  HomeFragment.kt             Honest graph-oriented foundation empty state
  StyleReferenceFragment.kt   Static native type/surface/input specimen
app/src/main/res/
  values/                     Dark palette, dimensions, text/widget styles, strings
  font/                       Bundled licensed fonts and platform family resources
  layout/                     Shared AppKit toolbar shell, home, style specimen
  drawable/                   Minimal shapes, ripples and independent launcher mark
app/src/main/assets/licenses/  Dependency/font notices
app/src/androidTest/           Kotlin navigation/restoration/font checks and captures
```

- Minimum API **26**, selected for native XML font-family resources; compile/target API **36**, available locally and validated on Android 16.
- Java 17 compile compatibility; **all first-party application and test source is Kotlin**. AGP 9.3.3 supplies built-in Kotlin support; no redundant Kotlin Android plugin is added.
- AppKit **1.5.3**, LiteX RecyclerView **1.2.1.1**, platform widgets. No Compose, AppCompat, AndroidX Navigation, Hilt or Material Components. `Theme.AppKit` inherits Android's platform Material-era widget theme; that is not the Material Components dependency or a Material 3 visual design.
- No database, model client, agent prompts, fake lesson engine, generated-code renderer or placeholder screens for unimplemented features.
- No INTERNET permission or credentials. Cleartext is prohibited and backup disabled. Dependency networking capability alone does not enable a product network path.

## Navigation and lifecycle: implemented

`MainActivity` extends the actual AppKit `FragmentStackActivity`. It installs `HomeFragment` only when `savedInstanceState == null`; otherwise AppKit/platform fragment restoration owns the stack. Fragments have public no-argument construction. `Nav.go` opens the style reference; toolbar/system back and `Nav.finish` return.

Both screens use `ToolbarFragment` with a small shared XML shell that supplies AppKit's required `toolbar` and `appkit_content` IDs. The root is `FragmentRootLinearLayout`, leaving inset handling with the library instead of duplicating system-bar-height calculations. No custom back dispatcher or parallel navigation state exists. The manifest opts into modern back callbacks; AppKit handles API-specific predictive back. The short entry fade is customized; exit reuses AppKit's cleanup so predictive-back transforms are not left behind.

Normal activity recreation is **not** suppressed with `configChanges`. XML views have stable IDs; the platform preserves the sample EditText draft and ScrollView state. AppKit saves container IDs and restores its stack, including hidden previous screens. Tests exercise recreation with two entries and returning to one. Actual process-death recovery must be checked by backgrounding, killing the background process and restoring the existing task—not force-stop/data clearing, and not a new explicit standard-activity instance.

Future screen arguments contain stable domain IDs, not mutable sessions, bitmaps, API keys or full transcripts in Bundles. Durable lesson state belongs in SQLite/files; saved view state handles transient presentation.

## Future structure: add only as each feature arrives

Retain one module until a concrete build/ownership need requires more. Package by responsibility, not an up-front framework:

```text
UI (AppKit fragments, holders, Canvas views)
             ↓ state snapshots / explicit learner actions
Session controller + educational domain rules
             ↓                        ↓
Kotlin agent runner / tools       Concrete SQLite/file stores
             ↓
Provider HTTP adapter / approved local tools
```

- **UI:** renders state and sends learner actions. No provider calls, tool execution or graph merging in holders or Canvas drawing.
- **Educational domain:** learning phases, question/answer contracts, dependency-plan validation, evidence and deterministic graph validation. Keep these Kotlin rules free of Views and network I/O.
- **Session execution:** one owner coordinates a session's turns, cancellation, pending questions, usage, tool results and persistence. It publishes `StateFlow` snapshots/`Flow` events when introduced in phase 4.
- **Providers/tools:** narrow boundaries where substitution is real (fake provider versus network provider). Prefer a concrete store/controller and constructor parameters; no generic repository, plugin registry or DI container for one implementation.
- **Persistence:** SQLite transactions for related structured changes, app-private files for documents/artifacts. Each entity has one owning writer; derivation never silently overwrites evidence.

Do not reproduce the reference's HTTP routes, six-system framework, barrels or synchronous Node filesystem stores. Preserve their useful separation of rendering, execution and evidence ownership.

## Lesson rendering: phase 2

Mastodon's typed `StatusDisplayItem`/holder pattern is the engineering reference, not code to translate. Implement a small Kotlin lesson-item representation and a `UsableRecyclerView.Adapter` over LiteX RecyclerView. Start with mock text, formula and question/activity rows; add image, diagram, demo, PDF excerpt, thinking and progress types when their renderers exist.

- Stable IDs derive from persistent block/tool identities, never list positions or collision-prone string hashes alone.
- Project session events into display items separately from agent execution.
- Bind specialized holders; clear stale state/listeners when rebound. View state must not be the only copy of answers or tool status.
- Append/replace the changing block with targeted insert/change notifications and payloads. Coalesce streaming deltas; don't rebuild the transcript or reparse all Markdown on every token.
- Auto-follow only while the learner is already near the bottom. Preserve a reading position during streaming.
- Teacher output reads as a document in Source Sans 3; mono is UI/code, CMU is prominent titles. Formulas/diagrams are integrated content. Android-sized baselines gate the real runtime.

The question/composer dock is a **sibling of the transcript**, not a recycled transcript row. One persistent shell changes content between composer, choice/free-response/reasoning, practice and pending feedback. State is keyed to question identity: selected options, confidence and unfinished reasoning survive expansion, collapse, rotation and navigation. Probe questions open; teaching checks offer explicit reveal. Native multiline entry uses a touch submit control rather than desktop Enter-only assumptions. Handle IME/system insets once at the containing screen.

## Educational state and knowledge: phases 4–5, 7

The educational sequence is **Probe → Plan → Teach → Practice**:

1. Probe defines a concrete goal and what the learner already holds, including uncertainty and misconceptions.
2. Plan validates the minimal dependency route from current understanding to the goal. Reject invalid IDs, missing prerequisites and cycles; persist stable identities and edits.
3. Teach advances one reasoning step at a time. A check must land before building on that step; use the learner's reasoning to repair misunderstandings.
4. Practice checks independent application and transfer, preserving attempts and reasoning rather than only a score.

The **session plan** is temporary teaching scaffolding. The **persistent graph** holds propositions, reasoning relationships/chains, citations and message-level provenance across sessions. Never turn every plan node into a knowledge claim automatically, and never store learner confidence as objective truth.

Extraction reads bounded transcript evidence. An Auditor proposes identity matches conservatively. A deterministic merger validates IDs/references, rejects malformed changes, applies approved changes transactionally and preserves source evidence and user corrections. Deduplication, coverage checkpoints and replay protection must be testable without a real model. Partial sessions should contribute evidence; a “finished session” is not required.

## Agent runtime and prompts: phase 4 onward

Use coroutines, structured concurrency and Flow. A bounded multi-step loop yields model deltas, validated tool calls, results, usage and completion/errors. Provider and local tool outputs are untrusted inputs: enforce schemas, byte/token limits, allowed tools, timeouts and step budgets. Cancellation closes streaming responses and cancels child work; never convert cancellation into a successful answer.

- Prober and Teacher arrive first. Practice, Extractor, Auditor, Researcher, PDF Reader, Visual Maker and Demo Builder are specialized scoped roles later, not an always-running multi-agent swarm.
- Context selection is deterministic and budgeted: relevant session evidence, selected graph facts and role-allowed instructions/tools. Context management must retain references to source records.
- Version original prompt assets under `assets/agents/` and `assets/skills/<name>/SKILL.md`; load only relevant skills. A user-editable local instruction file is data, not permission to bypass application security/tool policy. Keep prompt versions attached to runs.
- A session execution scope has an explicit owner and shutdown. Fragments collect while started and release view collections when stopped/destroyed; native AppKit fragments are not AndroidX lifecycle-aware by assumption. Do not introduce `GlobalScope`, capture dead Views in long jobs, or promise uninterrupted background execution.
- Persist recoverable checkpoints before acknowledging durable changes. MVP execution may require foreground use; after interruption show interrupted/retry state, not invented completion. Background recovery is a later explicit feature.

## Connectivity and storage: phase 4 onward

BYOK is the independent MVP path. Use a maintained compatible OkHttp version (AppKit currently brings 3.14.9 transitively), validated streaming/cancellation and provider discovery where available. Separate model identity/settings from credentials. Never put keys in resources, prompts, transcript logs, screenshots, backups or exported artifacts. Use Android Keystore-backed AES-GCM to protect locally stored credential ciphertext, with a clear recovery path if a key becomes invalid.

Create SQLite tables/migrations with their features, not now: goals/sessions/messages/plans/questions/answers first; knowledge/evidence, documents/artifacts, jobs/practice later. Files use atomic replacement; use transactions and unique operation IDs for multi-row updates/retries. Large content stays out of Bundles. Sources and model judgments remain distinguishable from derived presentation values.

Optional ChatGPT sign-in requires a separate investigation of **official OpenAI documentation and Android eligibility**. No undocumented ChatGPT endpoints, borrowed session cookies or assumed authorization flow. The app must work with provider API keys regardless of sign-in availability.

## Graph and rich content: phases 3, 6, 8

- `KnowledgeGraphView`: Kotlin Canvas, immutable layout snapshot, separate off-main-thread layout computation, cached drawing objects, pan/pinch around gesture focus, inverse-transform hit testing and fit-to-content. Invalidate only for data/camera/selection changes and finite transitions; no idle physics/redraw loop. Large Canvas datasets require accessible search/detail alternatives and virtual-node accessibility as appropriate.
- Graph appearance: light nodes, fine edges and modest density, quiet labels, stable muted cluster regions. Selection/details must remain touch-readable. Zoom and saved camera are presentation state, not knowledge evidence.
- Formulas/Markdown: evaluate small native span/rendering solutions at the renderer milestone, retaining horizontal overflow for genuinely wide math/tables. Do not choose a whole-screen WebView to avoid implementing native lesson rows.
- Mermaid: a specialized local WebView surface with controlled assets, no credential bridge, bounded content and fullscreen inspection/zoom. It is not the generated-demo sandbox.
- PDF: Storage Access Framework import to app-private storage; evaluate `PdfRenderer` for bounded page bitmaps and PDFBox-Android for searchable text/license/dependency cost. Search/extract selected pages, cite page indices explicitly and cap bitmap/cache memory. Avoid loading an entire large document by default.
- SVG: validate allowed markup and dimensions, render to a bitmap, inspect labels/values/clipping through a vision model, revise within limits, persist source/preview/verification status. Unsafe or unverified output is not silently marked verified.
- Generated HTML/JS: **disabled until isolation is demonstrated**. No privileged `addJavascriptInterface`, credentials, app file access or unrestricted networking. WebView flags alone are not proof of an adequate sandbox; build a threat model and malicious fixtures before enabling execution. Respect the specification's fail-closed requirement.
- Research/PDF/web text is untrusted evidence, never a new instruction authority. Preserve citations and tool permissions independently of retrieved content.

## Validation and independence

Kotlin instrumentation already checks the shell's real navigation, font role binding and restored draft/stack; it also captures app-window images. Manual device captures and documented visual differences are linked from DESIGN_SYSTEM. Foundation tests do not imply an implemented agent, graph, PDF parser or secure demo runtime.

Later milestones add fake-provider/domain tests, streaming-row checks, gesture mathematics, graph integrity, parsing and security fixtures at the point their code exists. Real-model smoke tests remain opt-in and separate. Measure scrolling/layout/rendering before performance abstractions; use Profiler/Perfetto when warranted.

No substantial reference source/prompts/assets were imported. Reference licenses, font notices and the distinction between architectural inspiration and code reuse are recorded in REFERENCES. Choose a first-party distribution license and complete the release notice/security audit before publishing.
