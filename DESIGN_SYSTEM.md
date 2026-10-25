# Native design reference

Scope: extracted reference rules, the phase-1 style specimen, phase-2 mock classroom and phase-3 Canvas graph prototype. Product/appearance comes from LearnHarness; Android engineering comes from AppKit/LiteX. Repository revisions and license boundaries are in [REFERENCES.md](REFERENCES.md).

## 1. Authoritative sources

LearnHarness revision `0795ef197cca04f6a8e7d50d10ed4dc71bd1a0cc`:

- [`app/globals.css`](https://github.com/amosblomqvist/learnharness/blob/0795ef197cca04f6a8e7d50d10ed4dc71bd1a0cc/app/globals.css): `:root`, `.dark`, `@font-face`, `@theme inline`, `.lh-prose`, `.lh-input`, `dock-variant-in`, “Chrome melt” and reduced-motion rules.
- [`app/layout.tsx`](https://github.com/amosblomqvist/learnharness/blob/0795ef197cca04f6a8e7d50d10ed4dc71bd1a0cc/app/layout.tsx): JetBrains Mono / Source Sans 3 font setup and always-dark shell.
- `components/sidebar.tsx`: compact mono navigation, prose session names, muted metadata, active accent surface and quiet right separator.
- `components/home.tsx`: graph as the main surface; learning-goal composer and secondary mode/provider controls at the bottom.
- `components/composer.tsx`, `components/question-dock.tsx`, `components/practice-dock.tsx`: persistent bottom task surface and composer/question/practice variants.
- `components/ai-elements/message.tsx`, `components/md-blocks.tsx`: prose, math and integrated Markdown blocks.
- `components/session-overlay.tsx`: plan/diagram inspection. `components/graph-view.tsx`, `components/graph-canvas.ts`: graph appearance, camera, labels and picking.
- Visual comparison: `docs/assets/teaching-session.png`, `docs/assets/knowledge-graph.png`, linked in REFERENCES. These were visually inspected, not merely inferred from CSS.

The global scales are authoritative but individual source components contain local exceptions (for example the composer's 6 px inset and calculated 10 px button radius). Do not silently promote every exception into a new global Android token. The native foundation uses the requested 4-unit ladder.

## 2. Palette

Exact source hexes from `globals.css`; centralized in `app/src/main/res/values/colors.xml`.

| Role | Android resource | Value |
|---|---|---|
| Page / canvas | `background` | `#141414` |
| Raised functional surface | `surface` | `#1C1C1C` |
| Main ink | `foreground` | `#E6E6E6` |
| Secondary ink | `muted_foreground` | `#8D8D8D` |
| Separator / input outline | `border` | `#434343` |
| Selected / pressed surface | `accent_surface` | `#2A2A2A` |
| Primary action fill | `primary` | `#E6E6E6` |
| Primary action ink | `primary_foreground` | `#141414` |

Always dark; no separate light palette, colorful branding, gradient cards or Material dynamic colors. Emphasis is bold/italic foreground ink, not arbitrary colored text. Semantic error, confidence, grading and graph-region hues exist in the reference but are feature-specific: extract and add them only with those components. The graph's subtle colored regions communicate grouping; they are not permission to recolor app chrome.

## 3. Typography

All families are bundled and usable offline through platform font resources (minimum API **26**, chosen explicitly for native XML font support). License/source details are in REFERENCES and packaged notices.

| Role | Family / weight | Android family |
|---|---|---|
| Navigation, controls, labels, metadata, code | JetBrains Mono 400; 500 when emphasized | `@font/ui` |
| Reading, explanations, question text, inputs | Source Sans 3 400; strong 700; headings 900 | `@font/prose` |
| Prominent / screen titles | CMU Serif Bold 700 | `@font/title` |

`TextAppearance.Harness` is mono by default; `.Label`, `.Meta`, `.Prose`, `.Title` and `.Toolbar` express roles. Default TextViews receive a text appearance, **not a theme-level `android:fontFamily`**: the latter overrides the font in explicit text appearances on platform TextViews. Instrumentation checks both prose and chrome font selection to prevent that regression.

| CSS reference size | Android dimension | Source ladder leading | Intended role |
|---|---|---|---|
| 11 px | `text_11` = 11 sp | 1.4 | Quiet tracked labels |
| 12.5 px | `text_12_5` = 12.5 sp | 1.4 | Metadata / compact actions |
| 14 px | `text_14` = 14 sp | 1.45 | UI text |
| 16 px | `text_16` = 16 sp | 1.45 | Base large text; prose overrides to 1.5 |
| 20 px | `text_20` = 20 sp | 1.4 | Subheadings |
| 25 px | `text_25` = 25 sp | 1.3 | Screen titles |
| 32 px | `text_32` = 32 sp | 1.25 | Prominent titles |

This is a role-preserving sp mapping, not a physical-pixel conversion. Android density and the user's font scale determine rendered pixels. Reading prose uses `lineSpacingMultiplier=1.5`; editable prose uses 1.25, matching `.lh-input`. Other phase-1 samples use native font metrics; exact CSS line boxes are not yet replicated. Do not force fixed-height text containers to imitate desktop screenshots. Labels use 0.08 em tracking. Preserve glyph fallback for unsupported scripts and symbols rather than substituting Roboto for every family.

## 4. Geometry and surfaces

Resources: `values/dimens.xml`, `drawable/{surface_outline,button_primary,button_secondary}.xml`.

- Spacing ladder: **4 / 8 / 12 / 16 / 24 / 32 dp**. Current page padding 24 dp; neighboring content commonly uses 8, 12, 16 or 24 dp.
- Radius ladder: **4 / 8 / 12 / 16 dp**. Foundation buttons use 8; the surface/input specimen uses 16.
- Borders/separators: 1 dp in `border`. This maps the source's thin CSS borders to density-independent Android geometry.
- Main reading content remains directly on the page; don't put every paragraph or lesson turn inside a card.
- Functional input/dock: subtle surface fill, thin outline, rounded perimeter. Inline code: quiet darker tint and thin outline; tables and Mermaid are content, not floating panels. These content renderers are phase 2/6, not implemented here.
- No decorative elevation. Toolbar elevation is zero. Native pressed ripples use the monochrome palette.
- **48 dp minimum touch target** is an Android accessibility adaptation, not a LearnHarness desktop token. It takes precedence over the desktop's smaller controls.

Some ladder tokens are intentionally unused in the small specimen; they are the explicitly requested extracted design vocabulary, not speculative widget APIs.

## 5. Component anatomy and behavior

| Component | Reference anatomy | Native adaptation / phase |
|---|---|---|
| Home | Graph fills available space; bottom goal input and teach/practice switch; navigation secondary | Phase 3 displays an explicitly illustrative graph with native search/Browse, an above-node details tooltip and toolbar-overflow zoom/Fit/sample-session actions. No fake custom-goal generation. |
| Navigation | Narrow left session column; serif/prose names, mono filters/search/metadata | AppKit stack and platform Toolbar now; dedicated session/navigation surface later instead of shrinking the desktop sidebar onto phones. |
| Session | Serif title; status/plan actions; document-like prose spine; integrated formulas/diagrams | Implemented `LearningSessionFragment`: typed native text/formula/answer rows, quiet status and no messenger bubbles. Plan/diagram actions wait for those features. |
| Composer | One rounded bottom shell, growing Source Sans input, trailing send/stop; ancillary controls below | Implemented native 1–4-line EditText, Send/Stop/Resume, visible local-preview notice and AppKit IME-safe area. A stopped stream is resumed before sending the retained note. |
| Question dock | Same shell replaces composer; label → question → options → confidence → “Your thinking” → submit | One persistent native shell. The authored teaching check offers reveal; controls scroll while header/submit remain reachable. Selection, confidence and thinking survive collapse, recreation and Home navigation. Probe/open-response/practice variants wait for their features. |
| Practice | Task-specific exercise/answer/feedback in the persistent interaction area | Reuse the dock/state identity principles in phase 7, not a new modal per exercise. |
| Plan/diagram overlay | Detailed inspection independent of the lesson spine | Phone fullscreen AppKit screen; restore position/draft on return. No tiny desktop popover. |
| Graph | Fine edges, small light nodes, quiet labels; muted cluster regions behind topology | Implemented Kotlin Canvas with off-main layout snapshot, pan/pinch, zoom-gated/collision-suppressed labels and native searchable/readable proposition details. |

Desktop screenshots contain window traffic lights, hover controls and wide columns. These are not Android UI requirements. Preserve hierarchy, content organization and behavior, not desktop window furniture.

## 6. Motion and accessibility

Reference evidence: `globals.css` dock variant fades/rises over **120 ms**; sidebar transitions use **200 ms**; graph hover fade uses **180 ms**. Motion is short and task-driven, not continuous decoration.

Phase 1 uses a **150 ms native transition duration** (`motion_short_ms`), an adaptation inside that observed range rather than a claimed exact source token. Entry is a fade. Exit retains AppKit's positional transition and predictive-back cleanup, shortened to the same duration; gesture progress remains owned by AppKit/the platform. Noninteractive transitions consult `ValueAnimator.areAnimatorsEnabled()` and use zero duration when disabled. No idle animation loop exists.

Use Android focus, touch feedback, TalkBack labels and heading semantics; do **not** copy the web CSS's blanket focus-outline removal. Text sizes are sp, content height is flexible, surfaces scroll, and buttons remain reachable with enlarged type. AppKit's inset-aware root and platform Toolbar keep content clear of system bars; `adjustResize` supports the sample editor/keyboard.

## 7. Style reference and visual verification

Open **Style reference** from Home. It demonstrates three font roles, the complete size ladder, a minimal outlined surface, primary/secondary actions and a multiline restoration draft. It deliberately uses a static ScrollView: building a RecyclerView adapter for a fixed typography specimen would add no value. LiteX/AppKit RecyclerView now renders the phase-2 lesson.

Phase-2 Android captures (historical home): [home](docs/screenshots/phase2-home.png), [reading](docs/screenshots/phase2-reading.png), [question](docs/screenshots/phase2-question.png), [thinking](docs/screenshots/phase2-thinking.png), [feedback](docs/screenshots/phase2-feedback.png). They are our own full-device captures, including system bars, not copied reference assets; keyboard-free review states are selected. Environment and checks are recorded in IMPLEMENTATION_PLAN.

Comparison against the original screenshots:

- Matched: exact base colors; serif title / sans reading / mono chrome separation; thin quiet separator; restrained outlined functional surfaces; no chat bubbles, colorful cards or heavy shadows.
- Corrected during verification: a global theme font was overriding text-appearance fonts, rendering prose and prominent titles in mono. It was replaced with a default TextView appearance; font assertions and screenshots now confirm separate roles.
- Intentional adaptation: 24 dp phone margins, 48 dp controls, platform system bars/back arrow, wrapping text, no desktop sidebar/window controls. The home copy and specimen are original explanatory placeholders, not a reproduction of a full learning session.
- Phase-1 limitations were lesson rows, formulas, dock expansion, graph density and tablet composition. Phase 2 covers the first three; phase 3 adds the graph comparison below. Tablet composition remains unverified.

## 8. Phase-2 phone adaptation and comparison

[Manually laid-out phone reference](docs/design/phase2-phone-reference.svg), prepared before implementing the session, adapts the original teaching-session screenshot to a 412 × 915 logical phone frame. It is an independent design target, not an app-generated golden; controls/system bars are schematic and font display depends on installed families. Actual captures above use Pixel 7, 1080 × 2400, Android 16/API 36, default font scale 1.0.

Manual comparison of the source screenshot, phone target and native captures:

| Aspect | Result / deliberate adaptation |
|---|---|
| Colors and surfaces | Same exact page/ink/surface/border tokens; only the functional dock/controls are outlined. No card per teacher turn, shadows, gradients or new branding. |
| Type hierarchy | CMU screen/content titles, Source Sans reading/question/thinking, JetBrains Mono status/actions. Native text scales in sp; no fixed-height prose. |
| Reading layout | 24 dp margins; unboxed explanations and integrated equations. The sample is independently authored, simpler one-variable calculus rather than copied stochastic-gradient teaching text. |
| Math | Native serif Unicode equations, horizontal overflow and spoken descriptions. No claim of KaTeX-quality arbitrary LaTeX; missing rich math/Markdown remains a later renderer task. |
| Question anatomy | Prediction → question → 48 dp choices → confidence → optional thinking → submit. Native monochrome slider/radio indicators replace desktop controls. “I don’t know” disables/omits confidence without deleting the retained slider value. |
| Phone height | Desktop can show the entire dock; phone uses a bounded scrollable form with pinned header/submit. 60% dock / 40% reading split when expanded, minimal bar when collapsed. The design SVG is schematic; its all-at-once overview is not a promise that every control fits without scrolling. |
| Keyboard / large type | Testing exposed a nearly zero-height editor at 200% type plus the IME. The dock now temporarily takes the reading pane’s height while entering an answer, then restores reading when the keyboard closes. Real-IME instrumentation asserts a visible text line and submit above the keyboard at both scales. |
| Interaction/motion | Explicit teaching-check reveal, non-destructive collapse, 150 ms native bounds transitions targeted only at the dock (zero with disabled animators), Stop/Resume and honest authored-note replies. Busy mock streams do not implement real-model steering. |
| Navigation | Platform back/stack replaces the desktop sidebar; no fabricated plan, model selector, microphone, usage accounting or background-agent controls. |

These captures were manually inspected and are review baselines, **not owner-approved pixel-diff goldens**. No automated image-difference/fidelity score is claimed. User visual review is still the gate before real AI; phase 3 uses independent mock graph data.

## 9. Phase-3 graph comparison

Native Pixel 7/API-36 review frames: [fit graph](docs/screenshots/phase3-graph.png), [search results/highlight](docs/screenshots/phase3-search.png), [selected proposition](docs/screenshots/phase3-selection.png), [readable detail](docs/screenshots/phase3-proposition.png), [zoom-scaled text](docs/screenshots/phase3-scaled-text.png). These are full-device captures at default type size, not approved image-diff goldens.

Compared visually against the pinned original `knowledge-graph.png` and graph renderer:

| Aspect | Match / deliberate phone adaptation |
|---|---|
| Topology and density | Small light dots, fine 0.65-dp lines, no node cards, sparse overview labels. Thirty authored propositions rather than the reference’s hundreds; no implication that the sample is the learner’s knowledge. |
| Regions | Six subdued semantic tints, using native radial shaders behind topology. Added `graph_region_0…5` tokens have center alpha `0x30` and fade to transparent. These are independently selected approximate tints, not exact upstream hues. Fixed circular regions replace the reference’s organic rasterized neighborhoods; no decorative chrome gradients. |
| Type | Source Sans 3 node labels and mono cluster captions start at 12.5 sp at fit zoom and scale proportionally with graph zoom; controls and the compact tooltip retain native text sizing. Zoom reveals node labels; collision checks prioritize selection and suppress overlaps, including cluster captions in short viewports. Enlarged labels clip naturally at viewport edges. Full statements remain available natively. |
| Camera and touch | Fit, drag, two-finger focus-preserving zoom and double-tap. Fixed-size dots/rings remain visible while world geometry scales. Nearest picking within 24 dp is a phone adaptation, not a promise that overlapping targets are distinguishable; zoom or Browse resolves dense areas. |
| Search and accessibility | Native case-insensitive word-prefix results, bright match rings/dimmed other dots, explicit no matches, Browse-all and a native chevron tooltip opening details. Search hint/text are vertically centered. Results wrap full propositions rather than forcing horizontal dropdown text. Canvas itself is not an accessibility-focus destination; the full native route is the alternative, pending actual TalkBack verification. |
| Details and provenance | Fullscreen scrollable native statement, reasoning and authored-source disclosure; incoming/outgoing links distinguish relationship direction. Relevant nodes link to the existing sample classroom. No hover-only panel, fake learner confidence, fabricated citations or graph edits. |
| Home chrome | Android toolbar/system bars and 48-dp controls replace desktop traffic lights/hover actions. Bottom button rows are removed. Zoom/Fit, sample lesson and style reference remain available in the toolbar overflow; a compact above-node tooltip follows selection, pans and zooms, with system-animation-aware fades. Selected node rings/labels also fade; no pretend model picker, dictation, goal generation or usage accounting. |

Graph tests pass at normal and 200% text size with animations disabled. Recreated camera/selection and native pan/pinch/search/detail flows are checked; actual TalkBack, large real graphs, tablets/foldables/RTL and formal pixel differences remain unverified.
