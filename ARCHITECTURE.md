# Reasoned — Android architecture

## Scope and current implementation

This document separates **implemented phases 0–4** from **future feature design**. The mock
classroom and Canvas graph retain independently authored illustrative fixtures. Phase 4 adds an
independent, opt-in OpenAI runtime and durable local sessions; it does not turn the sample graph
into learner evidence. Phase 5 implements dependency plans and persistent knowledge
extraction/storage. Its offline emulator regression fixtures now execute successfully; live-model
and broader device acceptance remain pending (see `docs/RELEASE_CHECKLIST.md`). The owner's go-ahead
after graph refinements clears the prior visual-review gate for this implementation; live-model
smoke testing remains opt-in and was not performed.

Phase 7 implements knowledge-aware Practice and an isolated foreground Researcher with opt-in
Brave/public-page tools; phase-6 PDF remains deferred. Release preparation executes the complete
76-test offline suite on API 36, including native rendering and persistence. Shared native styles
center only ordinary action-button text; navigation/disclosure rows, chat titles, labels, prose and
input remain start-aligned. Multiline fields use top alignment; single-line fields use vertical
centering. Styles also
provide padded, bounded ripple hit areas, including toolbar icons and list rows. Layout fixtures
exercise all 25 screen/row/dialog/toolbar layouts at 320/600 dp, up to 200% font scale and RTL.
A second pass exposes initially hidden content and checks screen gutters and text height, accounting
for lesson-list padding and horizontally scrolling formula/control viewports. These are structural
checks, not screenshot-based visual acceptance; dynamic screen/source review coverage is recorded
in `docs/RELEASE_CHECKLIST.md`. Live provider/web, minimum-API and full device acceptance remain
separate gates.

The app is standalone, Kotlin-first, traditional Views. It is not a web wrapper, LearnHarness client, Mastodon skin, or Pi host. It executes its own Kotlin learning runtime locally and can call OpenAI's Responses API directly with the learner's API key or an explicitly authorized ChatGPT plan connection. No custom backend, Node.js or remote agent runtime is part of the design.

Current single Gradle application module:

```text
app/src/main/java/com/kkgosu/learnharness/
  MainActivity.kt             AppKit FragmentStackActivity, root and transitions
  HomeFragment.kt             Sample graph, search entry and lesson entry
  GraphSearchFragment.kt      Full-screen native search, search-bar morph and IME animation
  KnowledgeGraphView.kt       Canvas drawing, gestures, picking and saved camera
  GraphLayout.kt              Immutable geometry and camera mathematics
  SampleGraph.kt              Authored propositions and illustrative relationships
  GraphNodeFragment.kt        Concise proposition summary and named source-chat links
  LearningSessionFragment.kt  Native transcript/dock, visible-only mock collection
  LessonDisplayItem.kt        Typed text/formula/answered-question blocks
  LessonAdapter.kt            Stable-ID holders and targeted append/text updates
  MockLesson.kt               Bounded authored fixture, StateFlow and saved state
  ModelProvider.kt            Real/fake boundary, bounded Responses SSE and HTTP cancellation
  CredentialStore.kt          Keystore AES-GCM, atomic no-backup credential ciphertext
  ChatGptAuth.kt              Browser OAuth/PKCE, verified identity, encrypted registrations/refresh
  SessionRuntime.kt           Application owner, foreground Prober/Teacher/Practice tool loop
  Practice.kt                 Hidden solution commitments and saved learner submissions/verdicts
  Researcher.kt               Isolated bounded question/search/read/brief model context
  ResearchWeb.kt              Brave search and validated public HTTPS text reader
  ResearchSourcesFragment.kt  Offline saved reports, retrieval metadata and plain-text excerpts
  RuntimeTools.kt             Role-specific strict schemas and independent native validation
  SessionStore.kt             SQLite sessions/messages/questions/answers/runs and checkpoints
  ProviderSettingsFragment.kt Secure credentials and account-scoped model choices
  NewSessionFragment.kt       Goal and one-tap session start, separate from connection settings
  SettingsFragment.kt         Settings categories, learning preferences and privacy
  RuntimeSessionFragment.kt   Real runtime projection into the existing lesson rows/dock
  StyleReferenceFragment.kt   Static native type/surface/input specimen
app/src/main/res/
  values/                     Dark palette, dimensions, text/widget styles, strings
  font/                       Bundled licensed fonts and platform family resources
  layout/                     Shared AppKit toolbar shell, home, style specimen
  drawable/                   Minimal shapes, ripples and independent launcher mark
app/src/main/assets/licenses/  Dependency/font notices
app/src/androidTest/           Kotlin navigation/restoration/font checks; no screenshots
```

- Minimum API **26**, selected for native XML font-family resources; compile/target API **36**, available locally and validated on Android 16.
- Java 17 compile compatibility; **all first-party application and test source is Kotlin**. AGP 9.3.3 supplies built-in Kotlin support; no redundant Kotlin Android plugin is added.
- AppKit **1.5.3**, LiteX RecyclerView **1.2.1.1**, platform widgets. No Compose, AppCompat, AndroidX Navigation, Hilt or Material Components. `Theme.AppKit` inherits Android's platform Material-era widget theme; that is not the Material Components dependency or a Material 3 visual design.
- Coroutines **1.10.2**, native `findViewById` (ViewBinding disabled) and maintained **OkHttp 5.4.0** (overriding AppKit's old transitive 3.14.9). The newer 5.5.1 AAR requires compile API 37, so 5.4.0 retains the verified API-36 baseline. SQLite and versioned LearnHarness instruction sources now support the real runtime; no generated-code renderer is enabled.
- INTERNET permission enables explicit OpenAI and learner-permitted web research. No backend,
  session-cookie authentication or bundled credentials. Official ChatGPT browser OAuth is optional
  alongside BYOK. Cleartext and automatic HTTP retries are disabled. OpenAI redirects are disabled;
  the separate public web reader follows bounded HTTPS redirects after validating every destination.
  Backup/device-transfer exclusions cover sessions/settings as well as the no-backup credential
  file.

## Practice and research: phase-7 implementation, build-only

Practice reuses the same runtime/transcript/composer/checkpoint and unchanged upstream practice
fragments; it does not fork a second session engine. The selected graph snapshot and attempts
are learner material/evidence, never a mastery score. Strict phase schemas enforce one active
`problem`, immutable hidden solution, model-judged `conclude`, and explicit native next-problem
permission. Public reasoning summaries are suppressed in practice to avoid solution spoilers.
Model prose adherence (hint quality, legitimate conclusion and fresh-surface transfer) is not a
native correctness oracle and needs live evaluation. Local dismissal needs no inference and keeps
unsent work. Additive checkpoint keys preserve existing sessions without another database migration.

Research has an independent context and only `web_search`/`web_fetch`; parent history is not copied.
Session-level permission is controlled only by native UI. The Brave key uses a distinct
`CredentialStore` alias/no-backup ciphertext, never the session's provider credentials. Brave
headers
are restricted to its fixed search endpoint, with redirects denied for authenticated search.
Page fetching has no cookies, proxy, scripts, file access or PDF fallback. URL checks include IP
literals (OkHttp can bypass Dns for these); the socket Dns hook rejects any private/special-use
answer, preventing a preflight check/second-resolution gap. Every redirect re-enters that policy.
Decompressed bodies are capped at 1 MiB and inert jsoup text excerpts at 8000 characters.

Research limits: one per action, four saved reports per session, eight model calls / three
searches /
three reads within the parent's foreground timeout. Completed request usage persists stage-by-stage;
Brave charges are not included in model token totals. An interrupted result is saved before
starting,
so restoration cannot silently replay a half-finished research job. Final source URLs must have been
read; source title/URL/retrieval time/excerpt/hash persist alongside the brief. A native offline
source
screen displays excerpts as selectable plain text. Web data never grants tools, account changes,
permissions or billing changes. These are native boundaries, not a guarantee against all model
misinterpretation of untrusted prose. Reports and practice rows are not graph-extraction passages.

## Navigation and lifecycle: implemented

`MainActivity` extends the actual AppKit `FragmentStackActivity`. It installs `HomeFragment` only when `savedInstanceState == null`; otherwise AppKit/platform fragment restoration owns the stack. Fragments have public no-argument construction. `Nav.go` opens the style reference, sample session or proposition details; toolbar/system back and `Nav.finish` return.

All screens use `ToolbarFragment` with a small shared XML shell that supplies AppKit's required `toolbar` and `appkit_content` IDs. The root is `FragmentRootLinearLayout`, leaving inset handling with the library instead of duplicating system-bar-height calculations. No custom back dispatcher or parallel navigation state exists. The manifest opts into modern back callbacks; AppKit handles API-specific predictive back. Ordinary navigation uses AppKit's default transitions. Drawer destinations retain Home underneath; `Nav.go` also supplies AppKit's toolbar-back argument. Graph search alone uses the custom-transition hook for a Mastodon-style search-bar expansion/collapse. On API 30+, a native WindowInsetsAnimation callback follows IME bottom insets; API 26–29 retain native adjustResize behavior.

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
- **Session execution:** the phase-2 fixture already publishes `StateFlow` snapshots, without providers/tools. The phase-4 application-owned runtime coordinates turns, cancellation, pending questions, usage, tool results and persistence.
- **Providers/tools:** narrow boundaries where substitution is real (fake provider versus network provider). Prefer a concrete store/controller and constructor parameters; no generic repository, plugin registry or DI container for one implementation.
- **Persistence:** SQLite transactions now couple session checkpoints, message identities, questions and accepted answers; app-private files will hold later documents/artifacts. Each entity has one owning writer; derivation never silently overwrites evidence.

Do not reproduce the reference's HTTP routes, six-system framework, barrels or synchronous Node filesystem stores. Preserve their useful separation of rendering, execution and evidence ownership.

## Lesson rendering: phase 2 implemented

Mastodon's typed `StatusDisplayItem`/holder pattern is the engineering reference, not code to translate. `LessonDisplayItem` has concrete text, formula and answered-question types; `LessonAdapter` binds specialized holders using AppKit `UsableRecyclerView.Adapter` over LiteX RecyclerView. More row types arrive with actual renderers, not empty scaffolding.

`MockLesson` owns one authored gradient-descent checkpoint. It never interprets arbitrary text as a model request or claims to assess reasoning. Question selection, optional reasoning, confidence, composer draft, transcript IDs and exact partial-response text live in a single activity-owned snapshot, saved before AppKit restores fragments. The fixture has a stable revision/question identity, at most three checkpoint attempts and eight 2,000-character notes, keeping saved state bounded; real transcripts must move to SQLite. Returning Home and reopening the sample retains its state within the task. No force-stop/fresh-task durability is promised.

The fragment uses AppKit `onShown`/`onHidden` to collect/render and run a cancellable coroutine. Hiding/backgrounding cancels view work; an active mock continues from its existing offset on return. Explicit Stop remains paused through recreation. Twelve-character chunks arrive at most once per 60 ms; existing block instances/IDs are reused, only the final text block is replaced, and only that holder receives a payload update. Append-only fixture updates deliberately do not need a generic diff engine. The reader is followed only at the tail; recreation restores RecyclerView state before subsequent updates.

The dock is a transcript sibling. Teaching checks are explicitly revealed; collapse preserves the mounted controls and model state. A native scroll form occupies 60% of available content height when expanded, with header/submit outside the scroll region. While editing an answer with the IME, the reading pane/status are temporarily hidden to leave a usable editor even at 200% type; dismissing the keyboard restores them. This uses AppKit-delivered native IME insets, without applying padding a second time. Controls use stable resource IDs, including `labelFor` labels: anonymous labels generate small IDs that can collide with AppKit 1.5.3's fragment containers.

Basic authored sample equations use native Unicode math with spoken descriptions and horizontal overflow. Real sessions now use Markwon's native Markdown/LaTeX spans; specialized rich-content renderers remain deferred. Visual targets, comparisons and captures are in DESIGN_SYSTEM.

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

## Plans and persistent knowledge: phase 5, build-only acceptance pending

`StudyPlan` stores stable lowercase node IDs, titles/summaries, prerequisite edges and a monotonic
version in the session checkpoint; old plan tool arguments remain in protocol history. `plan_init`
uses structured JSON and validates duplicates/endpoints/self-loops/DAG cycles before committing.
The first plan pauses for explicit approval; persisted `planApproved` lets mid-lesson rewrites
continue teaching without another barrier. Rewrites replace structure, not a completion ledger.
Current/visited positions derive only from tagged question calls and are never mastery assessments.
Each accepted `plan_init` also appends a persisted PLAN row with a Mermaid diagram generated from
its validated nodes/edges, not from optional model prose. Labels are escaped and node IDs are
app-generated; PLAN rows are not teaching evidence. The native Learning plan page renders the same
structure (including older saved plans) and keeps the textual dependency list as an accessible
fallback. Approve plan confirms directly without a second dialog. While awaiting approval, the
existing composer accepts revision requests and switches its
action from approval to Send plan changes. Before the first approval, persisted `planPending`
survives generation/reopening/interruption: revised plans and plain clarification replies still
require approval, unsent edits cannot be approved, and question/figure/finish tools are denied.
Older checkpoints infer prior approval from an existing plan without pending approval. Retry after
an interrupted revision restores plan review without replaying generation.
Plan/revision prose is excluded from canonical teaching evidence; there is no automatic
plan→knowledge conversion.

`KnowledgeExtraction` owns one app-wide foreground job, independently of the teaching command. The owner chose LearnHarness's hybrid dispatch: after a completed teaching turn, 4096 estimated uncovered tokens trigger extraction; the session menu can request it earlier. Estimates are ceil(chars/4) over the exact teacher-prose view, not provider context usage. Each run snapshots its initial passages, greedily batches without splitting messages and stops on an unprocessed-batch failure; earlier committed batches remain covered. Opening/restoring sessions never dispatches. MainActivity foreground changes and credential removal cancel stage requests; cancellation/failure cannot checkpoint a partial batch. No server/service/background-resume promise, parallel writer pool or additional provider/model defaults were added.

Only completed canonical assistant text is eligible. New checkpoints explicitly mark validated rendered response-row IDs, including responses assembled from several final SSE message items. Legacy checkpoints recover only exact canonical text matches against protocol history; ambiguous older combined rows are not claimed as covered. Learner answers/confidence, question/tool records, interrupted deltas and plan JSON are excluded. SHA-256 passage coverage includes source message identity and exact text. The Extractor sees an uncovered batch plus previously extracted claims/arguments from that source. The Auditor sees findings and paginated graph-read tools, never the raw transcript; links must target existing nodes it actually read. Both stages use the source session's captured model/effort/connection. Per-stage completed-response usage is persisted separately and disclosed alongside teaching usage; interrupted usage remains potentially unreported. Stage errors never change the teaching session into an extraction error.

The deterministic merger resolves only explicit audited identity links or exact statement equality,
never fuzzy name similarity. First-created labels/statements remain unchanged; subsequent phrasings
join aliases and provenance. Assertions retain exact `(session,message)` sources with SQLite foreign
keys. Reasoning chains are the stored structure. A single-premise chain projects to a direct
premise→conclusion edge; multi-premise chains retain invisible, unpickable implication junctions.
Junctions are excluded from search and summary navigation. Endpoints and post-identity DAGs are
validated independently of model output. A single SQLite transaction writes nodes, aliases, chains,
source anchors and all batch coverage; duplicate coverage is a no-op. Bounds are 32 new
propositions/48 chains per batch, 12 premises per chain, 2000 stored propositions/4000 arguments, 3
extractor or 16 auditor steps and 180 seconds per batch, with inherited transport/context limits.
Oversize/invalid input fails visibly, not by dropping evidence.

Home/search/details now read the persistent graph, with an honest empty state. The authored graph
remains a separate menu destination. Existing Canvas/GraphLayout code projects bounded birth-session
communities; denser clusters use deterministic sunflower placement off-main. Selecting a proposition
and tapping its anchored label opens a native summary screen modeled on LearnHarness: name,
statement and distinct source-chat titles. Source links open the first recorded evidence message for
that chat without starting another request; excerpts and mechanical reasoning/related-node sections
are not shown. No extraction disclaimers are displayed on this screen. Source-chat rows span the
full viewport with bounded ripple feedback and 24-dp text gutters. Practice/sample-lesson actions
remain in the summary toolbar menu. `learning_context` supplies up to 24 goal-ranked propositions
and bounded source IDs for later sessions, explicitly without an inference of learner understanding.
Manual correction/deletion UI, full-history management, specialized rich diagrams and separate
model-role settings are not part of this phase.

Validation: APK/test-APK compilation and lint, pinned-source hashes and an offline smoke check of the actual SQLite DDL pass. Instrumentation fixtures cover plan failures/position, additive migration, atomic/replay-safe merging, identity-collapse rollback, immutable evidence, hybrid dispatch, source privacy/billing, cancellation and coverage; these are compiled only, not device-executed. Live extraction accuracy, source-link/plan UI journeys and enlarged-text behavior remain unverified.

## Agent runtime and prompts: phase 4 implemented

`ReasonedApplication` lazily owns one `SessionRuntime`, a concrete SQLite store and a Keystore
credential store. The graph/mock screens do not initialize the runtime or send requests. A native
DrawerLayout wraps AppKit's existing stack/inset owner, with New session, Knowledge graph, recent
sessions and a pinned Settings entry; the library handles drawer scrim, gestures and back. Settings
opens focused Providers & models, Learning preferences and About & privacy pages. New session
collects the goal; the explicitly disclosed Start session action creates its local checkpoint and
requests the first teaching step in one tap. An argument consumed before loading triggers that first
request only after the checkpoint opens successfully; saved-session navigation and activity
recreation never replay it. The underlying runtime create operation remains local for callers that
only need a checkpoint. Only Start session, Start on a previously idle session, Send, explicit
Retry, first-plan approval or Next problem initiates learning generation. Within that foreground
action, automatic Probe→Teacher handoff, bounded local tool correction and context summarization can
make additional same-connection requests. Account/model discovery can make separate authenticated
requests. API model IDs are user-entered; ChatGPT connections automatically load the active
account's model catalog on the Providers screen and show full-width radio rows with display names,
saving selected slugs per connection. Refresh is explicit; account changes cancel the previous load
and discard its rows. No catalog request is required to create a local session, and there is no
assumed default or unverified account-access claim. The newest 30 saved sessions are browsable; full
history management is later work.

`RuntimeSessionFragment` uses its own native composer layout with the shared `LessonAdapter`, not
`MockLesson` or a parallel renderer. Native Markwon spans render standard Markdown and
inline/display LaTeX in transcript, questions and choices, without web-based document UI or a remote
image loader; only fenced diagrams use the isolated offline rasterizer described below. Typed prose
blocks have monotonically allocated IDs; streaming replaces only the current block, at most once per
100 ms. Accepted questions and answers both enter history; older missing questions are reconstructed
from SQLite evidence without rewriting protocol context. Written answers are displayed once, not
duplicated as answer plus reasoning. One pending question holds free-response or 2–32 choices,
question reasoning, optional reading thoughts, explicit `dontKnow`, applicable confidence in
20-percent steps and collapse state. Prober questions open automatically. Teacher `type: predict`
opens before teaching; `type: check` arrives collapsed after teaching. A check's separate
`thinkingWhileReading` field remains available while its question is collapsed; question-answer
reasoning appears on reveal. Ungraded `type: ask` opens immediately with no plan tag or key.
Legacy saved questions without a type preserve their previous arrival/collapse state. The transcript
remains visible when editing; a separate scrollable
question panel leaves a persistent answer/Send dock above the IME. An ephemeral native progress row
after the last transcript message shows generation, including while waiting for first text; it is
not stored as lesson content. The question panel keeps the existing rounded outline, and question
arrival uses bounds/fade motion. On API 30+, final AppKit bottom padding is deferred while an IME
animation is active; native animation-frame insets resize the viewport without an initial jump,
and AppKit resumes ownership at animation end. No separately timed keyboard animator is used.
`RuntimeSessionLayout` adds 50 dp to the expanded question panel by redistributing native weighted
transcript/panel heights, capped by the available transcript space; total height and composer space
remain unchanged. Collapsed layout is unchanged. Layout and synthetic IME show/hide regression
fixtures are compiled; APK/test-APK compilation and lint pass, with device motion still unverified.

Answered questions render as one typed native card reconstructed from the existing question/answer
evidence, including legacy sessions: question, outlined options, selected/correct marks, a six-color
confidence rail, learner thinking and Why disclosures. The learner selects multiple choice (`probe`/
`quiz`) or free recall (`recall`/`explain`), at creation or from Question format for future
questions. Pending questions retain their original mechanic/commitment. Graded tools commit the
explanation/reference and exact answer key before submission; neither is rendered in the pending
dock. `ask_user_question` is separate and ungraded, with a written Other response allowed; graded
choices require selection or explicit don’t-know. `QuestionContract` separates option labels from
stable values, supports single/multiple selection, additional details and clarification
descriptions. The dock creates native radios or checkboxes for every option, not four fixed views.
Selected values survive drafts/reopening; returned selections carry original value, label and
one-based index. Grading uses exact set equality with committed values, independent of labels/order;
saved label-only questions retain their original identities. Free responses reveal the reference but
receive no automatic semantic verdict. Legacy/explicitly ungraded questions have no correctness
badge. Learner bands and thin separators distinguish authorship without chat bubbles.
Reasoning-capable models request `reasoning.summary=auto` unless effort is off/none. Only completed
provider `summary_text` enters collapsible THINKING rows; encrypted content remains opaque. THINKING
rows never become graph evidence. AppKit toolbar state is applied in onCreateOptionsMenu and
refreshed via the fragment's invalidateOptionsMenu, not the unused platform preparation hook; graph
Stop is visible only while extraction actually runs. Manual extraction with no eligible passages
reports why instead of implying a new graph was created. Build-only verification; UI/live acceptance
remains pending.

`ToolContentPresentation` repairs a fresh native CommonMark/LaTeX presentation tree for
tool-authored questions/options/references and newly revealed practice solutions only. It uses the
installed renderer's command vocabulary for recognized swallowed JSON prefixes/extra escaping, and
conservatively recovers document newlines with corroborating evidence. Native code boundaries (
including nested fenced/indented code), link destinations, TeX comments, native environment/substack
rows and physical display newlines remain literal. Ordinary assistant prose and learner responses
are not passed through this recovery. This is bounded best-effort recovery, not a full upstream
content-reader port; unsupported/ambiguous formulas may remain source. Saved tool arguments, option
values, answer keys, learner facts and grading are never rewritten. Historical answer cards use
their saved question contract, not a join by rendered labels. Build/lint and fixture compilation
only; no device/live acceptance has been run for this refinement.

The rounded runtime composer exposes Send/Stop, session model, thinking level and context snapshot.
Model discovery stays on the session's saved connection; selection never generates or changes
billing. The API picker intersects the fetched IDs with known Responses capabilities; an
existing/manual model remains usable. ChatGPT uses its account-scoped model names/slugs. Capability
metadata (42 exact IDs, context windows and supported wire reasoning efforts) is generated from
pi-ai 1.4.0 with `scripts/update-model-catalog.mjs`, not guessed from model-name prefixes. The asset
records its source version and SHA-256. The generator reads flattened model.id values, not pi-ai's
internal chat:-prefixed catalog keys; exact API IDs therefore resolve their levels and capacity,
including gpt-6.1-sol. Unknown models offer only Auto and unknown capacity. Successful model lists
are cached in local preferences by connection and shared between session/Providers pickers; opening
a picker reuses the cache, while Refresh models explicitly fetches/replaces it. Failed refresh
preserves the prior cache; replacing/forgetting an API key or signing out/resetting plan connections
invalidates the associated UI cache. It contains names/IDs only, never credentials. Auto omits
reasoning.effort; an explicit supported level is checkpointed and sent on the next request. The
context ring uses last completed request input usage divided by catalog capacity, not accumulated
billing tokens or an invented live tokenizer; its dialog also shows saved input/output token totals
and explains the snapshot, unknowns, automatic summary costs/date and independent archive safety
bounds. Composer ripples use a bounded rounded-button mask with a transparent resting background, so
the overlaid context button does not hide the ring. Account details/Manage usage moved into this
dialog instead of an always-visible plan button.

The Prober diagnoses before `finish_probe` commits the pinned structured `assessment`: one
`{strand, assessment}` per dependency strand plus an optional overall summary. Assessments record
reliable floor, ceiling, misconceptions and what teaching should build from, not a syllabus.
They persist as ASSESSMENT rows/checkpoint data, never teaching evidence. The Teacher receives
them in protocol history and `learning_context`. A successful `finish_probe` acknowledges the call,
resets phase-local answer/context counters and immediately continues with Teacher instructions/tools
within the same foreground action. There is no intermediate route approval. Legacy route checkpoints
migrate locally on open without making a request; an interrupted handoff stays Teacher on retry.
The assessment is not a dependency-validated plan or extracted knowledge. The Teacher writes a
validated
StudyPlan, pauses for explicit approval and tags later questions with its stable node IDs. The
Teacher checks one reasoning step at a time, can repair via another question, and ends with a
summary, never a mastery certification. `dontKnow` answers contain neither confidence nor an
incorrect verdict; free recall and ungraded answers also omit confidence. Answer cards retain
question-thinking and reading-thinking as separate disclosures. Prediction references stay tucked
behind Why after submission. `learning_context` reads current-session state and explicitly selected
practice material, not an automatic preview of prior proposition statements. Probe/Practice can read
saved graph organization through `list_sessions` (birth-session metadata/counts) and
`list_propositions` (names born there only). No old transcripts, proposition statements or
sample-graph contents are returned. Both are read-only, parameterized SQLite queries; prior coverage
is not learner mastery. Teaching has no direct graph mutation tools; no filesystem, shell,
arbitrary network, code-execution, graph-write or remote built-in tools are available.
`AgentInstructions.kt` loads unmodified LearnHarness Prober/Teacher sources from
`assets/learnharness/`, using upstream's base → sorted role fragments → sorted shared appendix
order. A separate `agents/android-compatibility.md` maps current tool names and states unavailable
tools/rendering; it does not rewrite source fragments. The independently written v1
prompts/reasoning-check skill were removed. Practice uses its original role fragments; the
learning-data skill remains inactive. Extractor/Auditor constants are projected from the preserved
specialist source into
separate active stage assets; TypeScript itself is never executed by Android. Runs record
`learnharness-0795ef197cca-v9` adds stable question identities/multiple selection and read-only
graph organization to the v8 continuity rules; legacy `1` and v1–v8 sessions adopt it on the next
generation without dropping transcript or commitments.
Local AGENTS.md preferences are editable in Settings → Learning preferences and snapshotted as a *
*user** message at session creation, not elevated instructions or permissions.

The Responses adapter uses the fixed HTTPS OpenAI endpoint, `store=false`, stateless input history and opaque completed reasoning items (`reasoning.encrypted_content`). Only a validated `response.completed` can commit model context/tool effects. The fixed-endpoint transport validates SSE framing rather than rejecting a valid stream because of its Content-Type label: only data, comments and standard event/id/retry fields are accepted; ordinary JSON/HTML responses are not a fallback. Partial arguments never execute. Native checks independently enforce tool/role allowlists, exact argument keys/types, choice/route bounds, one call per response and replay IDs; unknown output types, refusals, incomplete/EOF/malformed streams and over-budget responses stop visibly. A mutex serializes commands and checkpoints; the deterministic fake provider uses this same runner.

Bounds: eight main-provider steps and 180 seconds per learner action, including handoff and at most
two correction attempts for invalid local question/plan calls. These return fixed `ok:false` errors
and valid node IDs without applying the call or retaining its provisional displayed prose.
Permissions,
replays, malformed provider envelopes and transport failures remain fatal, not automatically
retried.
One foreground figure and one research job per action retain their own bounded calls. HTTP limits
remain 120-second call / 45-second read / 15-second connect; API requests cap output at 4,096 tokens
(ChatGPT omits this unsupported parameter), SSE at 2 MiB, line/event/completed output at 256 KiB,
and visible text at 24,000 characters per response.

Long-session context uses the pinned upstream compaction prompt projected by
`scripts/update-compaction-prompt.py`, preserving exact current-plan structure. Before a turn, an
estimate based on reported usage plus new content (or characters/4 without usage) triggers at the
smaller of 150,000 tokens and catalog window minus 16,384 reserve. Unknown windows use 150,000.
One additional tool-free request uses the same model/effort/connection and records usage. A summary
commits atomically only after a valid nonempty completed response. Failure/cancellation preserves
the
previous summary/cut; reopening never resumes it. Full protocol/transcript, source anchors,
commitments
and graph evidence are untouched. Model input alone becomes fallible historical summary + exact
native
state/learner preferences + recent complete exchanges (up to a 20,000-token target, reduced for
small
windows). Cuts never orphan tool results or separate reasoning from its call. Encrypted/public model
reasoning is not summarized as learner evidence. Oversized uncuttable exchanges stop visibly rather
than dropping pending work. Archive safety bounds are separate: 10,000 rendered blocks, 16 million
text characters and 16 MB UTF-8 protocol. Compaction never creates teaching or graph evidence.

Hiding the runtime screen or stopping the Activity cancels the foreground job; there is no service/background-execution promise. Cancellation closes the HTTP call/body, including blocked reads. Finally checkpoints run non-cancellably on IO; streamed partial text is marked interrupted and excluded from model context. Startup marks unfinished runs/sessions interrupted; Retry starts a new charged request rather than claiming token-level resume. Reported usage accumulates completed responses/runs; interrupted or rejected responses may have billed usage the client cannot report. Draft writes debounce 200 ms and flush on actions/hiding. Native saved state carries no real transcript or credentials.

SQLite v3 preserves sessions, messages, questions, answers and runs, with additive migrations
from v1/v2 for knowledge tables and separate display-title/deletion metadata. Drawer long-press
opens a native context menu; rename input and delete confirmation use native dialogs. Renaming
changes presentation in the drawer, graph groups,
source links and graph metadata reads, never the original learning goal or wire transcript.
Confirmed deletion stops that chat's foreground work and transactionally clears its checkpoint,
messages, questions, answers, runs and graph source/coverage/run records. Propositions and reasoning
chains remain; a non-openable origin row retains only its ID, group title, timestamp and deletion
marker. Deleted chats are excluded from navigation/recovery, and stale saves cannot resurrect them.
The graph keeps named groups without inventing source links; details disclose unavailable sources.
Build/lint and test-APK compilation plus host SQLite DDL/migration/deletion checks pass; native
management/cancellation/migration fixtures are compiled, not device-executed.
Checkpoints preserve model/prompt version, phase, wire history, pending tool, provisional route and
input state. Accepted answers and their source message IDs commit transactionally with the
checkpoint; `(session, call_id)` prevents duplicate answers. Replacing session or message rows is
deliberately avoided so foreign-key cascades cannot erase evidence; checkpoint message writes
UPDATE/INSERT only changed rows, retaining knowledge source anchors. Only a retracted provisional
tail beyond the authoritative last row is removed. Large checkpoints are read through bounded SQLite
substr slices so multi-megabyte Unicode history does not exceed Android CursorWindow row limits.
IO/storage failures are surfaced; no destructive migration or fabricated recovery is used.

Use coroutines, structured concurrency and Flow. A bounded multi-step loop yields model deltas, validated tool calls, results, usage and completion/errors. Provider and local tool outputs are untrusted inputs: enforce schemas, byte/token limits, allowed tools, timeouts and step budgets. Cancellation closes streaming responses and cancels child work; never convert cancellation into a successful answer.

- Prober/Teacher own teaching; Extractor/Auditor now run in the separate foreground knowledge channel. Practice, Researcher, PDF Reader, Visual Maker and Demo Builder remain later scoped roles, not an always-running multi-agent swarm.
- Context selection is deterministic and budgeted: relevant session evidence, selected graph facts and role-allowed instructions/tools. Context management must retain references to source records.
- Preserve original instruction/skill sources under `assets/learnharness/`, with source revision, AGPL license and SHA-256 checks; keep Android compatibility text separate and load only active role instructions. A user-editable local instruction file is data, not permission to bypass application security/tool policy. Keep prompt versions attached to runs.
- A session execution scope has an explicit owner and shutdown. Fragments collect while started and release view collections when stopped/destroyed; native AppKit fragments are not AndroidX lifecycle-aware by assumption. Do not introduce `GlobalScope`, capture dead Views in long jobs, or promise uninterrupted background execution.
- Persist recoverable checkpoints before acknowledging durable changes. MVP execution may require foreground use; after interruption show interrupted/retry state, not invented completion. Background recovery is a later explicit feature.

## Connectivity and storage: phase 4 implemented, extensions deferred

BYOK uses Android Keystore AES-256-GCM with a fresh provider-generated IV and atomic ciphertext replacement in `noBackupFilesDir`. Key entry disables view-state/autofill saving and the entire Providers & models window is FLAG_SECURE; leaving clears the field. Keys are read only for the Authorization header, never inserted into model input, database checkpoints, logs or exports. Forget key removes both ciphertext and alias; missing/invalid Keystore keys require explicit reset/re-entry, never plaintext fallback. Model ID/settings are separate. Session content is private SQLite, not encrypted-at-rest beyond Android's storage protections. `store=false` is requested, not represented as a provider zero-retention guarantee. API billing and data transfer are disclosed before generating.

Knowledge/evidence and dependency plans are implemented in phase 5. Documents/artifacts and
jobs/practice tables remain later features. Context compaction is implemented as described above.
Additional providers, remaining specialized rich content, and background recovery remain deferred.
Full release dependency/security review, API-26/device diversity and a real-key smoke test are not
claimed by the deterministic emulator checks.

### Optional ChatGPT plan connection

The owner selected a public open-source distribution. `ChatGptAuth` follows the documented OSS public-client flow: stable per-install UUID host ID, dynamic first registration, retained per-registration client ID/verified subject, browser authorization, fresh state/nonce/S256 PKCE and an ephemeral 127.0.0.1 `/auth/callback` listener. Callback parsing is bounded and checks state, duplicate parameters, host and returning client ID. Nimbus JOSE+JWT 10.10 verifies RS256 signatures using the pinned issuer's JWKS, issuer/audience/expiry/nonce/authorized party and optional access-token hash; no hand-written signature verification. Identity-only grants stay signed in but cannot generate through the plan.

Accounts, access/refresh/ID tokens, scopes, expiry and host ID share an atomically encrypted no-backup record separate from BYOK (maximum ten registrations/256 KiB). Refresh and account writes serialize through one mutex; rotating credentials persist before inference. Terminal refresh errors clear tokens but retain registration/identity for reauthorization; temporary errors do not erase credentials. Sign-out stops generation, attempts remote refresh-token revocation and clears local tokens, reporting unconfirmed revocation with a ChatGPT settings link. Explicit reset recovers unreadable credentials but discards registrations/host and does not revoke remote grants; an inline summary explains the recovery purpose and the retained lessons/remote access before its existing confirmation dialog. Visible account labels use verified email without opaque registration suffixes; internal connection IDs and billing isolation are unchanged. OAuth/discovery JSON stays bounded at 256 KiB; only the model catalog gets a 2 MiB allowance, checked before reading the complete body. Oversized responses have a local safety-limit explanation, not a plan-quota message.

Each saved session fixes its connection to `api` or one issued registration; old checkpoints default to `api`. Switching the provider settings picker never changes a session's billing, and plan failures never fall back to an API key. Requests use the same public Responses HTTPS/SSE adapter, `store=false`, `stream=true` and complete input history. Plan requests use a Kotlin port of upstream `chatGPTPayload`: selected model, `store=false`, `stream=true`, its optional-field allowlist, system→developer history conversion, and top-level `tools` under the `learnharness` namespace. The old `additional_tools` input substitution is removed. Unsupported parameters (including `max_output_tokens`) are stripped. Native validation accepts that namespace only for plan sessions, rejects foreign namespaces, and retains legacy unnamespaced calls. Original call IDs/item IDs, namespaces and opaque reasoning persist with their tool results. Stream errors honor top-level and nested machine codes; any response error, premature `[DONE]`, EOF or missing completed terminal fails without committing tools. API-key payloads remain unchanged. No hosted tools, cookies, ChatGPT conversation access, client secret or undocumented inference endpoint is involved. Provider settings and plan sessions expose Manage usage and explicit plan/credit disclosures.

Sign-in is app-owned so it can continue while the system browser covers the activity; no foreground service is added. Cancellation/timeout closes the loopback socket. If Android kills the process during authorization, pending PKCE/state are intentionally lost and the user starts a fresh attempt; an already-issued registration is retained once received. Live catalog loading and explicit refresh were verified on the owner's already-authorized Pixel 8 Pro, with seven model choices. One explicitly authorized Pixel 8 Pro diagnostic later received HTTP 200 with 76 valid JSON SSE frames under an unexpected Content-Type label; the old header gate stopped it before parsing. That gate is replaced by strict framing/completion checks, verified with fixtures; no second live request was sent, and end-to-end lesson completion is still unverified. Fresh Android browser authorization and production refresh-token rotation remain unverified; deterministic local fixtures are not an eligibility guarantee. API-key use stays independent.

## Graph: phase 3 implemented

`SampleGraph` supplies 30 stable-ID propositions, 32 illustrative directed relationships and six
named clusters. Text lives in resources. These are not extracted facts, assessed mastery or a
session plan. Summary screens show the name and brief statement with no invented source-chat links.
Relevant sample-lesson navigation remains in the toolbar menu. No source citation, confidence or
learning history is fabricated.

`HomeFragment` computes a deterministic cluster layout on `Dispatchers.Default` while shown, cancels view work when hidden/destroyed, and binds a separate read-only `GraphSnapshot` on the main thread. Layout is not simulated inside drawing. `KnowledgeGraphView` caches paints, radial region shaders, shortened labels and reusable collision rectangles. It draws light dots/fine lines/soft semantic regions, hides overlapping labels, and invalidates only on layout/data, camera, selection or search changes and finite native selection fades. Node and cluster text scale proportionally with relative fit zoom. Selection fades respect disabled system animations and finish/cancel when hidden/detached; there is no physics timer, animation coroutine or idle redraw loop.

A single `GraphCamera` handles fit, pan, inverse picking and clamped focus-preserving zoom. Platform `GestureDetector`/`ScaleGestureDetector` handle taps, dragging, double-tap and two-pointer scaling; moving the pinch centroid pans the camera. Picking uses the nearest node within a 24-dp screen radius; dense areas remain ambiguous until zoomed or browsed. Native toolbar-overflow zoom/Fit actions provide non-pinch controls; sample lesson and style-reference navigation also live there instead of bottom button rows. The view parcels only camera center, relative fit zoom and selected ID. Resizing preserves relative zoom/world center; the fixture is regenerated, not serialized into saved state.

A Home search button opens `GraphSearchFragment`: native `EditText`, `ListView` and `ArrayAdapter` provide case-insensitive word-prefix search over labels/statements, explicit no-match feedback and all 30 nodes when the query is empty. The search bar expands into the screen (700 ms) and collapses on return (300 ms), with a search/back icon crossfade; system animation settings apply. Query/selection survive recreation, and result focusing waits until a restored hidden graph has laid out. Choosing a result focuses/selects the node; a small native tooltip above it, with a chevron and a 48-dp touch target, opens its readable ScrollView details. The tooltip tracks camera changes, stays within the viewport, hides for offscreen nodes and fades between selections. Search rows wrap full propositions. The visual Canvas is omitted from accessibility focus in favor of this complete native search/browse/detail route; virtual Canvas nodes and a full TalkBack journey remain future validation/work, not claimed capabilities.

The fixture deliberately uses a fixed six-cluster arrangement, radial tints, linear picking and O(n²) label decluttering. Large real graphs need bounded incremental layout, spatial indexing and an appropriate accessible representation. Saved camera/selection are presentation state, never knowledge evidence. See DESIGN_SYSTEM for the visual comparison and IMPLEMENTATION_PLAN for checks/limitations.

## Rich content: phases 6, 8

Phase 6 Mermaid/SVG slice is implemented, build-only acceptance pending; PDF remains unimplemented.
Native Markwon document spans now recognize `mermaid` and `svg` fences. `DiagramPlugin` displays
tappable static images with descriptive labels, a shared bounded 24 MiB bitmap/source-key cache and
attachment-aware cancellation. Inline and fullscreen views use the same 2048px-long-edge bitmap;
tapping retains the visible bitmap even if it was evicted, so opening a ready preview does not
repeat rendering. Cache misses after restoration still rerender locally. Image-only touch handling
bypasses TextView selection/focus-induced scroll, while ordinary prose selection and
keyboard/TalkBack spans remain native. Equal transcript rows no longer report a spurious adapter
change/re-follow. Ordinary code blocks and LaTeX retain their native rendering. `DiagramFragment` is
a native fullscreen bitmap canvas with pinch/pan, double-tap/toolbar fit, labeled accessibility zoom
actions and selectable source; reopening does not call a model.

`DiagramRenderer` serializes all local jobs through one short-lived WebView at a time. The
unmodified Mermaid 11.17.0 browser bundle is pinned/hash-recorded locally, using strict security,
dark theme, 16px sans-serif and plain SVG labels. Source is at most 24,000 characters/64 KiB;
configuration directives/frontmatter are denied, graph edges capped at 200. The app-owned runner
rasterizes within 15 seconds: UI previews request a 2048px long edge (at most 2048×2048), including
upsampling small vector sources before rasterization. Visual Maker keeps the original
natural-size/1024px cap and 512 KiB PNG transmission bound, so UI sharpness does not increase model
image size. Native PNG result limits remain bounded by the selected profile. Authored SVG is
XML-validated (namespace, allowed elements, finite bounded viewBox, depth/count, no active
tags/events/references/external styles), then decoded as an inert SVG image, never inserted into the
live document. CSP, request interception and WebView flags independently deny network, file/content
URLs, frames, workers, storage and privileged bridges. Only the bundled script and app-owned runner
execute. Malformed/unsupported output keeps source available and fails visibly in the inspector;
this is not an HTML/JS demo sandbox. Owner-requested `ReasonedDiagrams` diagnostics report ephemeral
render IDs, elapsed time, source character count, WebView version, fixed stage/error categories,
resource status, console severity/line and PNG dimensions/size. They never log source, lesson text,
URLs, console/error messages or stacks. Owner-device logs identified the Mermaid bootstrap failure:
the deny-all interceptor returned HTTP 403 for the renderer's main frame, leaving both engine and
runner absent until timeout. The renderer now loads an exact intercepted local HTML route plus the
pinned script route; only the runner's inert SVG data-image decode may use browser handling. All
other requests remain blocked and network/file/content denial stays enabled. Main-page/engine
loading errors fail immediately. This correction is build-checked, not yet device-render verified.

The teacher's `visual` tool is available only after an approved plan. `VisualMaker` projects the
pinned upstream INSTRUCTIONS verbatim, adds an independent Android capability note, and ports
write_svg/edit_svg/render_svg → actual PNG → selected-model inspection/revision → final-notes
semantics. At most eight stage calls, 18,000 SVG characters, 512 KiB PNG and 2 MiB stage history; no
final delivery unless the current source's actual image reached a completed model request. The
source plus final notes persist as a typed FIGURE transcript row: MODEL-REVIEWED, not independently
verified. Plain inline SVG/Mermaid has no review badge. Stage images/protocol are temporary;
previews rerender offline from saved source. Figure rows are not canonical graph evidence. A durable
interrupted tool result precedes work, completed stage usage is checkpointed, and
cancellation/restoration never resumes generation silently. Same session model, effort, connection
and billing, with no automatic vision-model fallback. Figures send only their self-contained brief
and local renders, not the full lesson. Usage totals include completed figure calls; the context
ring remains the last teaching-request snapshot. Device rendering, gesture/accessibility and live
vision acceptance remain unverified.

- Formulas/Markdown: native Markwon/math spans, not a whole-screen WebView; genuinely wide math
  retains horizontal overflow.
- Mermaid: offline specialized rasterizer only; no interactive bindings or generated-JS execution.
- PDF: Storage Access Framework import to app-private storage; evaluate `PdfRenderer` for bounded page bitmaps and PDFBox-Android for searchable text/license/dependency cost. Search/extract selected pages, cite page indices explicitly and cap bitmap/cache memory. Avoid loading an entire large document by default.
- SVG: source/notes/model-review status are persisted; bitmap previews are bounded ephemeral caches.
  Model review is never presented as an objective correctness guarantee.
- Generated HTML/JS: **disabled until isolation is demonstrated**. No privileged `addJavascriptInterface`, credentials, app file access or unrestricted networking. WebView flags alone are not proof of an adequate sandbox; build a threat model and malicious fixtures before enabling execution. Respect the specification's fail-closed requirement.
- Research/PDF/web text is untrusted evidence, never a new instruction authority. Preserve citations and tool permissions independently of retrieved content.

## Validation and independence

Kotlin instrumentation checks the shell, mock classroom and graph: navigation, font roles, drafts/choices/confidence, parcelled partial-stream recovery, stable IDs/payloads, retry limits, reading position, real keyboard geometry, deterministic graph layout, camera/inverse-picking math, actual native pan/pinch events, parcelled camera/selection, search/browse and detail navigation. Screenshot generation was removed at the owner's request; older capture links in DESIGN_SYSTEM are historical. These tests do not imply an implemented agent, persistent knowledge store, PDF parser or secure demo runtime.

Later milestones add fake-provider/domain tests, persistent graph integrity, parsing and security fixtures at the point their code exists. Real-model smoke tests remain opt-in and separate. Measure scrolling/layout/rendering before performance abstractions; use Profiler/Perfetto when warranted.

LearnHarness instruction/skill sources are now imported verbatim under `assets/learnharness/` at the owner's request, including inactive specialist/compaction source references. Their AGPL terms and corresponding-source obligations apply; this is no longer reference-only use. Reference licenses, font notices and the distinction between architectural inspiration and code reuse are recorded in REFERENCES. Choose a first-party distribution license and complete the release notice/security audit before publishing.
