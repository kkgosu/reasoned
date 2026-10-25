# Reference and licensing ledger

Research baseline: 2026-10-25. Scope: phases 0–1 only. **Reasoned** is an independent prototype name, not an official LearnHarness client. References were inspected outside this repository; their source trees are not vendored or built into the app.

## Pinned repositories

Links below use immutable revisions. Source descriptions apply to these revisions, not necessarily their current default branches.

| Reference | Revision | Role / license |
|---|---|---|
| [LearnHarness](https://github.com/amosblomqvist/learnharness/tree/0795ef197cca04f6a8e7d50d10ed4dc71bd1a0cc) | `0795ef197cca04f6a8e7d50d10ed4dc71bd1a0cc` | Product, teaching, appearance; AGPL-3.0 license text in `LICENSE` |
| [LiteX](https://github.com/grishka/LiteX/tree/1cc74ad2b2c30e2cce6441212b5ed373c7ecb8ab) | `1cc74ad2b2c30e2cce6441212b5ed373c7ecb8ab` | Lightweight Android libraries; Apache-2.0, `LICENSE.txt` |
| [AppKit](https://github.com/grishka/appkit/tree/2f69cf481eaf398327e0c3e5dbbffd74314b0f63) | `2f69cf481eaf398327e0c3e5dbbffd74314b0f63` | Native fragment stack and widgets; published 1.5.3 POM declares The Unlicense |
| [Mastodon Android](https://github.com/mastodon/mastodon-android/tree/24e8cdbc1f2bc9a4164d911f2a20894f39e6268b) | `24e8cdbc1f2bc9a4164d911f2a20894f39e6268b` | Engineering patterns only; GPL-3.0 license text in `LICENSE` |

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
| `components/graph-view.tsx`, `components/graph-canvas.ts` | Canvas rendering, camera transforms, picking, theme-resolved colors, zoom-gated labels, cluster regions and selected-node details. |
| `docs/assets/teaching-session.png`, `docs/assets/knowledge-graph.png` | Visually inspected: serif session title, restrained mono chrome, broad prose column, bottom question/reasoning dock; fine graph connections and muted cluster regions. Linked below, not copied into the APK or this repository. |

Original screenshots: [teaching session](https://github.com/amosblomqvist/learnharness/blob/0795ef197cca04f6a8e7d50d10ed4dc71bd1a0cc/docs/assets/teaching-session.png), [knowledge graph](https://github.com/amosblomqvist/learnharness/blob/0795ef197cca04f6a8e7d50d10ed4dc71bd1a0cc/docs/assets/knowledge-graph.png).

**Source-versus-document drift:** older reference documentation discusses Sigma.js and `follows`/`supports` edges; the pinned graph source uses its own Canvas renderer and reasoning-chain/implication structures. Treat the source as authoritative for current appearance. Do not transplant either evolving graph schema blindly: implement the Android specification's proposition/reasoning/evidence model explicitly in phase 5. Planning in the reference can be a moment within a session rather than an exposed permanent phase; Probe → Plan → Teach → Practice remains this project's educational sequence.

## Android engineering inspection map

- LiteX `README.md`, `recyclerview/build.gradle`, `LICENSE.txt`: forked RecyclerView without the AppCompat dependency; packages retain `androidx.recyclerview` names, Maven coordinates are `me.grishka.litex`. Do not add the standard AndroidX RecyclerView alongside it.
- AppKit `README.md`, `appkit/build.gradle`, and `appkit/src/main/java/me/grishka/appkit/{FragmentStackActivity,Nav}.java`: activity-owned stack, `Nav.go` / `Nav.finish`, bundle/container restoration, predictive back callbacks and transitions. Use the actual library, not a Kotlin rewrite of its internals.
- AppKit `fragments/{AppKitFragment,ToolbarFragment}.java`, `views/{UsableRecyclerView,FragmentRootLinearLayout}.java`, `res/values/styles.xml`: platform Toolbar, expected `toolbar` / `appkit_content` IDs, inset-aware fragment root and clickable holders. `Theme.AppKit` is based on the platform theme, not the Material Components library.
- Mastodon `mastodon/src/main/java/org/joinmastodon/android/MainActivity.java`, `fragments/HomeFragment.java`, `ui/displayitems/{StatusDisplayItem,TextStatusDisplayItem}.java`: split rich content into typed rows, choose specialized holders, bind existing views, keep item identity and callbacks separate from the runtime. No social-feed visuals or Java source were imported.

## Shipped libraries

Direct runtime dependencies: `me.grishka.appkit:appkit:1.5.3` and `me.grishka.litex:recyclerview:1.2.1.1`.

The [published AppKit POM](https://repo.maven.apache.org/maven2/me/grishka/appkit/appkit/1.5.3/appkit-1.5.3.pom) explicitly identifies **The Unlicense**. The repository README does not substitute for those published redistribution terms. Its transitive runtime includes LiteX collection 1.1.0, SwipeRefreshLayout 1.1.0.1, ViewPager 1.0.0, AndroidX annotations 1.3.0, OkHttp 3.14.9 and Okio 1.17.2. Kotlin stdlib 2.2.10 is supplied by AGP's built-in Kotlin support. Apache-2.0 applies to these transitive libraries; JetBrains annotations 13.0 is also present. AndroidX test dependencies are test-only, not an application framework.

Reproduce the resolved inventory with:

```sh
./gradlew :app:dependencies --configuration debugRuntimeClasspath
```

AppKit's old transitive HTTP client is unused by this offline foundation. Before model connectivity, select a maintained compatible OkHttp version, validate AppKit compatibility, and review dependencies for vulnerabilities. Do not mistake a transitive library's presence for implemented model communication.

## Redistributed fonts

Fonts are unmodified upstream binaries, obtained separately from LearnHarness. All three families permit bundling under **SIL OFL 1.1**, with their copyright/license notices retained under `app/src/main/assets/licenses/`.

| Family / bundled cuts | Upstream download source | Notice |
|---|---|---|
| JetBrains Mono Regular 400, Medium 500 | [JetBrainsMono `fonts/ttf/`](https://github.com/JetBrains/JetBrainsMono/tree/master/fonts/ttf) | `jetbrains_mono.txt` from upstream `OFL.txt` |
| Source Sans 3 Regular 400, Bold 700, Black 900 | [Adobe Source Sans release `TTF/`](https://github.com/adobe-fonts/source-sans/tree/release/TTF) | `source_sans_3.txt` from upstream `LICENSE.md` |
| CMU Serif Bold 700 (`cmunbx.otf`) | [CTAN cm-unicode archive](https://mirrors.ctan.org/fonts/cm-unicode.zip), `fonts/otf/cmunbx.otf` | `cmu_serif.txt` from archive `doc/OFL.txt` |

Font download branches/mirrors can move; the exact checked-in binaries are the reproducible assets. CMU's reserved font family name is “Computer Modern Unicode fonts”; retain the license and do not rename a modified family in violation of OFL. Android resource filenames are not modifications to font internals. No runtime font downloads are needed.

AppKit, LiteX and OkHttp license texts are also bundled in `assets/licenses/`. Preserve applicable dependency notices when packaging a release and audit the final artifact's transitive notices. The project has not selected a license for its own first-party source; that is an owner decision before public distribution. This ledger is not legal advice.

## Independence boundaries

- No LearnHarness/Mastodon source files, prompts, stylesheets, screenshots, logos or wordmarks are included in the application.
- Android layouts, resources, copy and Kotlin source are independently authored. Palette values and font roles implement the requested reference direction.
- Dependency use and separately licensed fonts are intentional redistribution, with their applicable licenses; “independent” is not a claim that dependencies are proprietary.
- Any future code or asset copying from the copyleft references requires a separate license-compliance decision. Do not assume a source translation avoids copyright obligations.
