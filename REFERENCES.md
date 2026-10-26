# Reference and licensing ledger

Research baseline: 2026-10-25. Scope: phases 0–6 (PDF pending). **Reasoned** is an independent
prototype name, not an official LearnHarness client. Reference checkouts were inspected outside this
repository. Selected LearnHarness sources are now vendored, and the search transition adapts
Mastodon's native pattern; see the reuse notices below.

## Pinned repositories

Links below use immutable revisions. Source descriptions apply to these revisions, not necessarily their current default branches.

| Reference | Revision | Role / license |
|---|---|---|
| [LearnHarness](https://github.com/amosblomqvist/learnharness/tree/0795ef197cca04f6a8e7d50d10ed4dc71bd1a0cc) | `0795ef197cca04f6a8e7d50d10ed4dc71bd1a0cc` | Product, teaching, appearance; AGPL-3.0 license text in `LICENSE` |
| [LiteX](https://github.com/grishka/LiteX/tree/1cc74ad2b2c30e2cce6441212b5ed373c7ecb8ab) | `1cc74ad2b2c30e2cce6441212b5ed373c7ecb8ab` | Lightweight Android libraries; Apache-2.0, `LICENSE.txt` |
| [AppKit](https://github.com/grishka/appkit/tree/2f69cf481eaf398327e0c3e5dbbffd74314b0f63) | `2f69cf481eaf398327e0c3e5dbbffd74314b0f63` | Native fragment stack and widgets; published 1.5.3 POM declares The Unlicense |
| [Mastodon Android](https://github.com/mastodon/mastodon-android/tree/24e8cdbc1f2bc9a4164d911f2a20894f39e6268b) | `24e8cdbc1f2bc9a4164d911f2a20894f39e6268b` | Engineering patterns/search-transition adaptation; GPL-3.0 license text in `LICENSE` |

## Phase-5 reference checks

At the same pinned revision, inspected `src/agent/tools/plan.ts`, `src/core/plan-tree.ts`, and `src/core/contracts/plan.ts` for stable session-only dependency DAGs, whole-plan rewrites, learner approval and call-derived positional status. Android uses structured JSON/native dependency lists rather than porting Mermaid parsing.

Inspected `src/agent/subagents/extraction.ts`, `src/core/contracts/graph.ts`, `src/core/coverage.ts`, `src/server/turn-service.ts`, `src/server/chat-handler.ts`, and `components/extraction-coverage.tsx` for teacher-prose-only coverage, exact message anchors, same-source digest continuation, Extractor/Auditor separation, identity-only verdicts, joint-premise chains, atomic coverage and hybrid dispatch. The code's factory threshold is 4096 estimated tokens; stale comments mentioning 8192 were not treated as authoritative. Android uses the existing SQLite owner and one foreground job, not the desktop/server process pool, JSONL stores or separate model-role settings. The original extraction source remains byte-preserved; a local generator projects its active prompt constants into native assets, with an independent compatibility appendix.

## Phase-6 diagram reference checks

Inspected pinned `components/mermaid-render.ts`, `components/artifacts/visual-card.tsx`,
`components/mermaid-error.tsx`, `src/core/session-markdown.ts` and
`src/agent/subagents/visual-maker.ts`. Retained strict/dark Mermaid at 16px, inline
figures/fullscreen inspection and write/edit/render/actual-image vision revision. Android uses
native image spans/canvas and a restricted offline rasterizer, not React/Electron, background
workers, file artifacts, browser SVG injection or inversion/download UI. The model-review badge
records a completed stage that received the current PNG, not independent correctness.

Mermaid 11.17.0 is a bundled unmodified MIT browser asset; source hash is in
`assets/diagrams/NOTICE.md`, full license and inclusive dependency notices are under
`assets/licenses/mermaid*.txt`. Its version matches the reference lockfile. The Visual Maker prompt
is projected verbatim from preserved upstream bytes, with a separate Android capability note and
AGPL provenance. Device/live acceptance and PDF remain pending.

## LearnHarness inspection map

All paths in this table are relative to the pinned LearnHarness repository above.

| Files inspected | Findings carried into the Android design |
|---|---|
| `docs/architecture.md` | Separate presentation, classroom/session execution, jobs, knowledge extraction and storage ownership. Preserve responsibilities, not Next.js routes, Node filesystem stores or Pi integration. |
| `docs/teaching-methodology.md` | Establish current and goal understanding; find the minimal dependency route; teach one reasoning step and check it before proceeding; repair before building on weak understanding. |
| `docs/vision.md` | Graph as home; propositions and provenance; session plan is not the persistent graph; teacher writes a document, learner contributes reasoning rather than messenger bubbles. |
| `app/globals.css`, `app/layout.tsx` | Authoritative palette, font roles, modular type/spacing/radius ladders, prose/input leading, subdued content chrome and motion. |
| `components/sidebar.tsx`, `components/home.tsx` | Secondary navigation and session history; graph-dominant home with a bottom learning-goal composer. |
| `components/composer.tsx`, `components/question-dock.tsx` | One persistent shell changes task; retain question selection and reasoning through collapse; explicit submit, accessible stop. |
| `components/ai-elements/message.tsx`, `components/md-blocks.tsx` | Integrated teaching prose, Markdown, math, code, tables and diagrams; no repeated decorative message cards. |
| `components/session-overlay.tsx`, `components/practice-dock.tsx` | Plan/diagram inspection and task-specific practice interactions. Phone overlays must become usable fullscreen surfaces. |
| `components/graph-view.tsx`, `components/graph-canvas.ts`, `components/cluster-geometry.ts` | Canvas rendering, camera transforms, picking, theme-resolved colors, zoom-gated labels, stable cluster hues/soft regions and selected-node details. The Android phase-3 fixture uses its own deterministic layout, native radial regions and independently authored propositions, not a TypeScript port. |
| `docs/assets/teaching-session.png`, `docs/assets/knowledge-graph.png` | Visually inspected: serif session title, restrained mono chrome, broad prose column, bottom question/reasoning dock; fine graph connections and muted cluster regions. Linked below, not copied into the APK or this repository. |

Original screenshots: [teaching session](https://github.com/amosblomqvist/learnharness/blob/0795ef197cca04f6a8e7d50d10ed4dc71bd1a0cc/docs/assets/teaching-session.png), [knowledge graph](https://github.com/amosblomqvist/learnharness/blob/0795ef197cca04f6a8e7d50d10ed4dc71bd1a0cc/docs/assets/knowledge-graph.png).

**Source-versus-document drift:** older reference documentation discusses Sigma.js and `follows`/`supports` edges; the pinned graph source uses its own Canvas renderer and reasoning-chain/implication structures. Treat the source as authoritative for current appearance. Do not transplant either evolving graph schema blindly: implement the Android specification's proposition/reasoning/evidence model explicitly in phase 5. Planning in the reference can be a moment within a session rather than an exposed permanent phase; Probe → Plan → Teach → Practice remains this project's educational sequence.

## Android engineering inspection map

- LiteX `README.md`, `recyclerview/build.gradle`, `LICENSE.txt`: forked RecyclerView without the AppCompat dependency; packages retain `androidx.recyclerview` names, Maven coordinates are `me.grishka.litex`. Do not add the standard AndroidX RecyclerView alongside it.
- AppKit `README.md`, `appkit/build.gradle`, and `appkit/src/main/java/me/grishka/appkit/{FragmentStackActivity,Nav}.java`: activity-owned stack, `Nav.go` / `Nav.finish`, bundle/container restoration, predictive back callbacks and transitions. Use the actual library, not a Kotlin rewrite of its internals.
- AppKit `fragments/{AppKitFragment,ToolbarFragment}.java`, `views/{UsableRecyclerView,FragmentRootLinearLayout}.java`, `res/values/styles.xml`: platform Toolbar, expected `toolbar` / `appkit_content` IDs, inset-aware fragment root and clickable holders. `Theme.AppKit` is based on the platform theme, not the Material Components library.
- Mastodon `mastodon/src/main/java/org/joinmastodon/android/MainActivity.java`, `fragments/HomeFragment.java`, `ui/displayitems/{StatusDisplayItem,TextStatusDisplayItem}.java`: split rich content into typed rows, choose specialized holders, bind existing views, keep item identity and callbacks separate from the runtime. No social-feed visuals or Java source were imported.

## Phase-3 platform implementation

No additional dependency or upstream graph source/assets were imported. Drawing uses Android Canvas/Paint/RadialGradient; interaction uses GestureDetector/ScaleGestureDetector. Native AutoCompleteTextView/ArrayAdapter/ListPopupWindow provide the searchable all-node alternative. Android API-36 SDK sources for autocomplete/dropdown behavior were inspected: empty-query filtering can dismiss a popup, and DropDownListView forces a root TextView to scroll horizontally. The implementation uses native filter completion and a container-based XML result row rather than replacing the platform filter/list.

The original graph screenshot and pinned Canvas/cluster sources guided the visual comparison recorded in DESIGN_SYSTEM. Circular radial regions, fixed fixture geometry and smaller phone data density are explicit differences; no exact upstream-force-layout or pixel-fidelity claim is made.

## Phase-4 API and platform sources

Official OpenAI documentation was fetched before implementation: [Responses streaming](https://developers.openai.com/api/docs/guides/streaming-responses), [function calling](https://developers.openai.com/api/docs/guides/function-calling), and [Create a response](https://developers.openai.com/api/reference/resources/responses/methods/create). These establish typed SSE terminal events, strict function schemas, call/result IDs, `store=false` and stateless reasoning-item continuation. The adapter implements only text and the app's allowlisted local functions; no hosted search/code tools or ChatGPT authentication. Model access is account-specific: the app requires a manually entered model ID rather than bundling a default or claiming availability.

Android's [Keystore guidance](https://developer.android.com/privacy-and-security/keystore) guided non-exportable AES keys and off-main cryptography. Credential ciphertext uses AtomicFile in noBackupFilesDir; Android SQLite/transactions and native view/window APIs provide persistence, secure key entry and the existing IME-aware dock. No credential/encryption/ORM/agent SDK was added.

OkHttp's published metadata was inspected for compatibility. 5.5.1 requires compile SDK 37; [5.4.0](https://repo.maven.apache.org/maven2/com/squareup/okhttp3/okhttp-android/5.4.0/) permits compile SDK 36, preserving the verified Android baseline. Direct HTTP/SSE has deterministic intercepted-transport tests, including blocked-body cancellation. No real API key or paid OpenAI request was used; API behavior/model quality against a live account remain unverified.

## Official ChatGPT plan connection (optional OSS distribution)

The owner selected a public open-source app. The implementation follows OpenAI's [OSS overview](https://developers.openai.com/siwc/token-sharing-open-source), [browser sign-in](https://developers.openai.com/siwc/token-sharing-open-source/sign-in), [accounts/refresh/storage](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions), [models/inference](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference), [preview limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations), [errors/recovery](https://developers.openai.com/siwc/token-sharing-open-source/errors-and-recovery) and [usage UI guidelines](https://developers.openai.com/siwc/ui-ux-guidelines) (consulted 2026-10-26). This is documented public OAuth, not cookies, Codex credential reuse or a private ChatGPT endpoint. Eligibility and plan limits remain account-specific; no live account was authorized during implementation.

Nimbus JOSE+JWT **10.10** adds vetted RS256/JWKS verification rather than custom cryptography. Its published jar shades Gson **2.14.0** and JCIP annotations **1.0-1**; their POMs declare Apache-2.0. Attribution is in `assets/licenses/runtime_dependencies.txt`, with full license text in `okhttp.txt`. This is not a comprehensive dependency/vulnerability audit. First-party licensing and public release remain owner decisions; selecting OSS distribution is not itself a license grant.

## Navigation/settings refinement

Revisited the pinned `components/sidebar.tsx` and `components/settings/settings-dialog.tsx`: session history belongs in navigation, while provider configuration is separate from session creation and settings use focused categories. The desktop implementation uses an inline sidebar and two-column modal settings; the owner selected a native Android modal drawer and category list opening full-screen pages. No source was ported and no unsupported desktop categories were scaffolded.

AndroidX DrawerLayout **1.2.0** supplies drawer motion, scrim, focus/accessibility and back handling, including its API-33+ overlay-priority back callback; AppKit keeps the actual fragment stack and inset ownership. The published DrawerLayout source was inspected instead of adding custom gesture/back machinery. Its `androidx.collection` dependency is excluded because LiteX already provides those classes; the initial duplicate-class build failure was resolved without replacing LiteX. Added Apache-2.0 runtime notices are bundled with the existing inventory.

## Shipped libraries

Declared runtime dependencies: `me.grishka.appkit:appkit:1.5.3`, `me.grishka.litex:recyclerview:1.2.1.1`, `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2` (introduced for the phase-2 mock stream), `com.squareup.okhttp3:okhttp:5.4.0` (phase 4), `com.nimbusds:nimbus-jose-jwt:10.10` (optional ChatGPT identity verification), and `androidx.drawerlayout:drawerlayout:1.2.0` (navigation refinement). AGP adds `androidx.databinding:viewbinding:9.3.3` for native generated view bindings; this does not introduce AppCompat/Material/Compose.

The [published AppKit POM](https://repo.maven.apache.org/maven2/me/grishka/appkit/appkit/1.5.3/appkit-1.5.3.pom) explicitly identifies **The Unlicense**. The repository README does not substitute for those published redistribution terms. Its transitive runtime includes LiteX collection 1.1.0, SwipeRefreshLayout 1.1.0.1 and ViewPager 1.0.0. Phase 4's direct dependency upgrades the resolved HTTP stack from OkHttp 3.14.9/Okio 1.17.2 to OkHttp/okhttp-android 5.4.0 and Okio/okio-jvm 3.17.0. Resolved Kotlin stdlib is now 2.2.21, AndroidX annotation/annotation-jvm 1.10.0, startup-runtime 1.2.0 and tracing 1.0.0. The OkHttp platform initializer does not replace app architecture or add AppCompat/Material. Apache-2.0 applies to these transitive libraries. Coroutines adds its core/JVM 1.10.2 artifacts and BOM; JetBrains annotations now resolves to 23.0.0. DrawerLayout additionally resolves AndroidX core 1.3.0, customview 1.1.0, lifecycle runtime/common 2.0.0, arch.core common 2.0.0 and versionedparcelable 1.1.0, under Apache-2.0. AndroidX test dependencies are test-only, not an application framework.

Reproduce the resolved inventory with:

```sh
./gradlew :app:dependencies --configuration debugRuntimeClasspath
```

Coroutines uses Apache-2.0; its pinned [upstream license](https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/LICENSE.txt) attribution is retained in `assets/licenses/kotlinx_coroutines.txt`, with the full Apache license alongside it in `litex.txt`. ViewBinding has been removed throughout the app, including its Gradle setting; view references use native `findViewById`.

The direct phase-4 dependency overrides AppKit's old HTTP version; native foundation/classroom/graph regressions validate its use with AppKit. Additional runtime notices are bundled in `assets/licenses/runtime_dependencies.txt`, with the [OkHttp 5.4.0](https://github.com/square/okhttp/blob/parent-5.4.0/LICENSE.txt) and [Okio 3.17.0](https://github.com/square/okio/blob/parent-3.17.0/LICENSE.txt) Apache terms already included in `okhttp.txt`. A dependency version update and functional tests are not a comprehensive release vulnerability/license audit.

## Redistributed fonts

Fonts are unmodified upstream binaries, obtained separately from LearnHarness. All three families permit bundling under **SIL OFL 1.1**, with their copyright/license notices retained under `app/src/main/assets/licenses/`.

| Family / bundled cuts | Upstream download source | Notice |
|---|---|---|
| JetBrains Mono Regular 400, Medium 500 | [JetBrainsMono `fonts/ttf/`](https://github.com/JetBrains/JetBrainsMono/tree/master/fonts/ttf) | `jetbrains_mono.txt` from upstream `OFL.txt` |
| Source Sans 3 Regular 400, Bold 700, Black 900 | [Adobe Source Sans release `TTF/`](https://github.com/adobe-fonts/source-sans/tree/release/TTF) | `source_sans_3.txt` from upstream `LICENSE.md` |
| CMU Serif Bold 700 (`cmunbx.otf`) | [CTAN cm-unicode archive](https://mirrors.ctan.org/fonts/cm-unicode.zip), `fonts/otf/cmunbx.otf` | `cmu_serif.txt` from archive `doc/OFL.txt` |

Font download branches/mirrors can move; the exact checked-in binaries are the reproducible assets. CMU's reserved font family name is “Computer Modern Unicode fonts”; retain the license and do not rename a modified family in violation of OFL. Android resource filenames are not modifications to font internals. No runtime font downloads are needed.

AppKit, LiteX and OkHttp license texts and the coroutines notice are also bundled in `assets/licenses/`. Preserve applicable dependency notices when packaging a release and audit the final artifact's transitive notices. The project has not selected a license for its own first-party source; that is an owner decision before public distribution. This ledger is not legal advice.

## Source reuse and licensing boundaries

- At the owner's request, `assets/learnharness/` now contains unmodified instruction/skill sources from the pinned LearnHarness revision, including root development guidance, all teaching/practice fragments, original readers/references and specialist/compaction source files. `SOURCE.sha256` pins all 36 files; `NOTICE.md` distinguishes active instructions from archived source.
- The Prober/Teacher use original prompt composition plus a separate Android capability appendix. This is not a claim that missing upstream tools, practice, Python readers or background agents execute on Android.
- These imports retain the upstream AGPL license. Preserve notices and meet applicable corresponding-source obligations; choose a compatible first-party distribution license before publishing. The earlier reference-only independence statement no longer applies to these files.
- Android layouts and Kotlin implementation remain native. Dialog resources follow Mastodon's rounded native Material treatment without importing its custom builder. Navigation inherits the same AppKit 1.5.3 transition implementation as Mastodon: 300 ms enter, 200 ms exit, cubic Bézier and predictive-back cleanup.
- `GraphSearchFragment.kt` adapts the rounded search-bar expansion/collapse and 700/300 ms timing from Mastodon's [SearchQueryFragment.java](https://github.com/mastodon/mastodon-android/blob/24e8cdbc1f2bc9a4164d911f2a20894f39e6268b/mastodon/src/main/java/org/joinmastodon/android/fragments/discover/SearchQueryFragment.java), using a smaller Kotlin/native implementation and separate IME handling. Mastodon's GPL-3.0 text is bundled as `assets/licenses/mastodon.txt`; retain attribution and applicable corresponding-source obligations before distribution.
- No reference screenshots, logos or wordmarks are bundled. Automated screenshot generation has been removed at the owner's request.
- Dependency use and separately licensed fonts retain their applicable licenses. This ledger does not replace a release license audit.
