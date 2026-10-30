# Native design reference

Scope: extracted reference rules, the phase-1 style specimen, phase-2 mock classroom, phase-3 Canvas graph and phase-4 native runtime screens. Product/appearance comes from LearnHarness; Android engineering comes from AppKit/LiteX. Repository revisions and license boundaries are in [REFERENCES.md](REFERENCES.md).

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

Resources: `values/dimens.xml`, `drawable/{surface_outline,input_background,button_primary,button_secondary,dialog_background}.xml`.

- Spacing ladder: **4 / 8 / 12 / 16 / 24 / 32 dp**. Reading gutters are 24 dp; neighboring content commonly uses 8, 12, 16 or 24 dp. The chat list itself has no horizontal padding: its components own those gutters.
- Radius ladder: **4 / 8 / 12 / 16 dp**. Foundation buttons use 8; inputs use 16. Native dialogs use the explicitly requested Mastodon treatment: 28 dp corners and a 16 dp window inset, not a new general surface token.
- Separators use 1 dp `border`. Editable fields now have a 1 dp muted-foreground outline, a 2 dp foreground focus outline and a subdued disabled outline; shared input styling includes forms and the lesson composer. Graph search uses a clickable pill on Home and an unboxed editor inside its dedicated toolbar.
- Main reading content remains directly on the page; don't put every paragraph or lesson turn inside a card.
- Functional input/dock: subtle surface fill, thin outline, rounded perimeter. Inline code: quiet
  darker tint and thin outline; tables and Mermaid are content, not floating panels. Diagram
  previews now render inline as native static spans (phase 6); they are not interactive web panels.
- Document-body Markdown tables use native `TableLayout`/`HorizontalScrollView`: columns grow to
  their content up to `table_column_max_width` (192 dp including cell padding), then text wraps.
  Only an overflowing table scrolls sideways; surrounding prose retains the reading-column width.
  Cells retain Markwon/LaTeX, selection, alignment, bold headers and the shared `border` color.
  Compact controls, nested tables and documents exceeding 512 table cells keep Markwon's existing
  wrapped-span fallback to avoid an unbounded native view tree. No HTML/WebView is involved.
- No decorative elevation. Toolbar elevation is zero. Native pressed ripples use the monochrome palette.
- Icon-only controls use 48-dp targets and native fixed-radius circular feedback (`IconButton` /
  `Button.Icon`): a 20-dp ripple radius does not stretch with rectangular or tall bounds. Send/Stop
  keep their square primary circular fill; text buttons retain rounded rectangular fills.
  Toolbar uses `Widget.Material.Toolbar`, not the legacy `Widget.Toolbar` whose navigation
  background is rectangular. Overflow retains a minimum 48-dp target independently of its ripple.
- Radio/checkbox styles inherit the platform Material CompoundButton backgrounds, retaining the
  fixed-radius indicator ripple instead of replacing it with a full-row/card ripple. Spacing is in
  layout margins (including dynamically created options), not extra control padding. Provider rows
  use 24-dp external horizontal gutters; the custom inset indicator drawable is no longer needed.
- `MarkdownContentView` takes reading insets from its TextView. Prose segments retain that padding;
  each table's HorizontalScrollView owns the same end padding with `clipToPadding=false`, so moving
  tables can reach the component edges instead of being clipped by the chat's gutter. Answer cards
  retain their own margins, but their Markdown bodies do not add an outer scrolling constraint.
- Thinking disclosures fill the entire unpadded band/card width. Their labels are vertically centered
  in a minimum 48-dp row, with symmetric vertical padding and no extra band-bottom/font padding.
- **48 dp minimum touch target** is an Android accessibility adaptation, not a LearnHarness desktop token. It takes precedence over the desktop's smaller controls.

Some ladder tokens are intentionally unused in the small specimen; they are the explicitly requested extracted design vocabulary, not speculative widget APIs.

## 5. Component anatomy and behavior

| Component | Reference anatomy | Native adaptation / phase |
|---|---|---|
| Home | Graph fills available space; bottom goal input and teach/practice switch; navigation secondary | Phase 3 displays an explicitly illustrative graph with a full-screen native search entry, an above-node details tooltip and toolbar-overflow zoom/Fit/sample-session actions. No fake custom-goal generation. |
| Navigation | Narrow left session column; serif/prose names, mono filters/search/metadata | Native DrawerLayout wraps the AppKit stack, with recent sessions and pinned Settings. Settings categories combine title/subtitle into one full-width native button. Navigation rows keep full-row ripples and 24-dp text insets. Radio/checkbox controls instead use native indicator ripples and external margins; indicators remain vertically centered. Filled actions and form fields remain inset. Settings has Back to Home; ChatGPT models appear as automatically loaded, account-scoped radio rows. |
| Session | Serif title; status/plan actions; document-like prose spine; integrated formulas/diagrams | Implemented `LearningSessionFragment`: typed native text/formula/answer rows, quiet status and no messenger bubbles. Plan/diagram actions wait for those features. |
| Composer | One rounded bottom shell, growing Source Sans input, trailing send/stop; ancillary controls below | Implemented native 1–4-line EditText, Send/Stop/Resume, visible local-preview notice and AppKit IME-safe area. A stopped stream is resumed before sending the retained note. |
| Question dock | Same shell replaces composer; label → question → options → confidence → “Your thinking” → submit | One persistent native shell. The authored teaching check offers reveal; controls scroll while header/submit remain reachable. Selection, confidence and thinking survive collapse, recreation and Home navigation. Probe/open-response/practice variants wait for their features. |
| Practice | Task-specific exercise/answer/feedback in the persistent interaction area | Reuse the dock/state identity principles in phase 7, not a new modal per exercise. |
| Plan/diagram overlay | Detailed inspection independent of the lesson spine | Phone fullscreen AppKit screen; restore position/draft on return. No tiny desktop popover. |
| Graph | Fine edges, small light nodes, quiet labels; muted cluster regions behind topology | Implemented Kotlin Canvas with off-main layout snapshot, pan/pinch, zoom-gated/collision-suppressed labels and native searchable/readable proposition details. |

Desktop screenshots contain window traffic lights, hover controls and wide columns. These are not Android UI requirements. Preserve hierarchy, content organization and behavior, not desktop window furniture.

## 6. Motion and accessibility

Reference evidence: `globals.css` dock variant fades/rises over **120 ms**; sidebar transitions use **200 ms**; graph hover fade uses **180 ms**. Motion is short and task-driven, not continuous decoration.

Fragment navigation now inherits **AppKit 1.5.3 defaults, exactly as Mastodon**: 300 ms enter with fade and one-fifth-width horizontal movement, 200 ms exit, default cubic Bézier interpolation and library-owned predictive-back cleanup. The custom fade-only/150 ms overrides were deleted. Android's animator-duration setting applies through the native Animator implementation. Dock motion still uses its own short duration; no idle animation loop exists.

Dialogs use a shared native AlertDialog theme rather than custom layouts or a Material Components dependency: raised dark rounded surface, 24 dp preferred content padding, native sans typography, 24 sp titles and mixed-case 14 sp pill-ripple text actions. Buttons retain a 48 dp minimum target and scalable text, including at 200% font scale. Provider account/model pickers and confirmation/welcome dialogs all inherit the same theme; recovery confirmation and secure-window behavior are preserved.

Use Android focus, touch feedback, TalkBack labels and heading semantics; do **not** copy the web CSS's blanket focus-outline removal. Text sizes are sp, content height is flexible, surfaces scroll, and buttons remain reachable with enlarged type. AppKit's inset-aware root and platform Toolbar keep content clear of system bars; `adjustResize` supports the sample editor/keyboard.

## 7. Style reference and visual verification

Screenshot references below are historical verification records. At the owner's request, screenshots are no longer generated; screenshot-only tests and capture helpers have been removed. Current checks use native view/state assertions, not fresh captures.

The debug-only **Sample session** also serves as an offline Markwon/LaTeX playground, using the
real session's `lessonMarkdown` renderer. Authored examples cover emphasis, nested lists, quotes,
links, wrapped/aligned and horizontally scrolling wide tables, code, inline math and display fractions/sums/matrices/aligned equations.
Markdown headings keep a single line break before their body rather than an extra empty line;
ordinary paragraph spacing is unchanged. The sample includes short and wrapping headings.
A quoted `[!IMPORTANT]` marker renders as a bold “Important” label inside the existing native
quote border; body Markdown remains selectable. The sample includes an authored callout specimen.
No credentials or provider requests are needed. Scroll upward from the sample's question to inspect
them; use system font scaling to check larger text. Sample session now opens the same `RuntimeSessionFragment` and layout as a real chat, using the
same `SessionRuntime` with an authored offline provider and a separate in-memory SQLite store.
Its scenario is Probe → three-strand sample assessment → four-step plan → prediction → revealed
teaching check → local notes. The common bottom fade, down-arrow button, send-follow scrolling,
question controls and IME behavior therefore need no duplicate sample implementation.
**Sample session → overflow → Learning plan** opens the real plan screen: steps initially remain
upcoming, then the runtime's native question node IDs advance the positional highlight.
Recreation restores the sample without requesting another response. Model/thinking selectors are
disabled and Context explains the offline fixture; no credentials, billing or graph extraction are
involved. Synthetic plan/history data never enters the real session database or knowledge graph.

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

These captures were manually inspected and are review baselines, **not owner-approved pixel-diff goldens**. No automated image-difference/fidelity score is claimed. The owner subsequently requested proceeding after the graph refinements/selection-blink fix, clearing the gate to phase 4. This does not make the historical captures pixel-diff goldens; the graph still uses independent mock data.

## 9. Phase-3 graph comparison

Native Pixel 7/API-36 review frames: [fit graph](docs/screenshots/phase3-graph.png), [search results/highlight](docs/screenshots/phase3-search.png), [selected proposition](docs/screenshots/phase3-selection.png), [readable detail](docs/screenshots/phase3-proposition.png), [zoom-scaled text](docs/screenshots/phase3-scaled-text.png). These are full-device captures at default type size, not approved image-diff goldens.

Compared visually against the pinned original `knowledge-graph.png` and graph renderer:

| Aspect | Match / deliberate phone adaptation |
|---|---|
| Topology and density | Small light dots, fine 0.65-dp lines, no node cards, sparse overview labels. Thirty authored propositions rather than the reference’s hundreds; no implication that the sample is the learner’s knowledge. |
| Regions | Six subdued semantic tints, using native radial shaders behind topology. Added `graph_region_0…5` tokens have center alpha `0x30` and fade to transparent. These are independently selected approximate tints, not exact upstream hues. Fixed circular regions replace the reference’s organic rasterized neighborhoods; no decorative chrome gradients. |
| Type | Source Sans 3 node labels and mono cluster captions start at 12.5 sp at fit zoom and scale proportionally with graph zoom; controls and the compact tooltip retain native text sizing. Zoom reveals node labels; collision checks prioritize selection and suppress overlaps, including cluster captions in short viewports. Enlarged labels clip naturally at viewport edges. Full statements remain available natively. |
| Camera and touch | Fit, drag, two-finger focus-preserving zoom and double-tap. Fixed-size dots/rings remain visible while world geometry scales. Nearest picking within 24 dp is a phone adaptation, not a promise that overlapping targets are distinguishable; zoom or Browse resolves dense areas. |
| Search and accessibility | Full-screen native case-insensitive word-prefix results, clear-query action, explicit no matches, all nodes for an empty query and a native chevron tooltip opening details. The Home search pill expands/collapses using Mastodon's 700/300 ms motion pattern; API-30+ IME insets animate the results viewport. Search hint/text are vertically centered, and results wrap full propositions. Canvas itself is not an accessibility-focus destination; the full native route is the alternative, pending actual TalkBack verification. |
| Details and provenance | Fullscreen scrollable native statement, reasoning and authored-source disclosure; incoming/outgoing links distinguish relationship direction. Relevant nodes link to the existing sample classroom. No hover-only panel, fake learner confidence, fabricated citations or graph edits. |
| Home chrome | Android toolbar/system bars and 48-dp controls replace desktop traffic lights/hover actions. Bottom button rows are removed. Zoom/Fit, sample lesson and style reference remain available in the toolbar overflow; a compact above-node tooltip follows selection, pans and zooms, with system-animation-aware fades. Selected node rings/labels also fade; no pretend model picker, dictation, goal generation or usage accounting. |

Graph tests pass at normal and 200% text size with animations disabled. Recreated camera/selection and native pan/pinch/search/detail flows are checked; actual TalkBack, large real graphs, tablets/foldables/RTL and formal pixel differences remain unverified.

## 10. Phase-4 runtime adaptation

Phase-5 update (build-only): Home now renders persistent source-backed propositions/argument junctions through the existing Canvas and accessible search/detail screens; an honest empty state replaces invented fixture evidence. The sample graph is a separate overflow destination. Birth-session grouping and denser deterministic placement preserve the monochrome design. Details provide native full-width source actions that open exact saved messages without sending another request.

The session overflow adds Learning plan and Update/Stop knowledge graph. A native dependency-list
screen displays stable steps and prerequisites; current/visited are positional, not
mastery/completion marks. Approve plan confirms directly, without a repeated dialog. While awaiting
approval the existing composer accepts change requests, using Send plan changes when nonblank and
Approve plan when blank; every revised plan still waits for approval. A muted live-region status
reports extraction/auditing/merging independently of the teaching footer. Context details disclose
estimated pending coverage, separate stage usage and additional selected-connection billing. No new
card system, graph editor, Mermaid surface or per-role model settings were introduced.
The Learning plan page omits the introductory scaffolding notice. Its Mermaid preview follows
LearnHarness's status language: visited nodes have light text/outlines, upcoming nodes and arrows
with either endpoint upcoming are dimmed, and the current node has a translucent yellow fill.
This fill approximates, rather than reproduces, upstream's blurred halo; Mermaid layout is retained.
The preview and accessible text list update from the same active-session positions.
Device/large-text acceptance for these additions is not yet claimed.

The real-session renderer reuses the established document rows, font roles and persistent native
dock. It does not introduce chat bubbles, a new component system or web-based document UI. The
hamburger opens a native modal drawer with New session, Knowledge graph, recent saved sessions and a
pinned Settings entry. Settings presents three categories opening focused pages: Providers & models,
Learning preferences, and About & privacy. New session holds the goal, connection summary and a
single Start session action, not account configuration or a second start/preview gate. The
disclosure states that this action sends the goal/preferences using the selected billing. Reopening
a saved session never starts generation. The Providers & models window blocks screenshots/recents
capture and never restores the password field. No key appears in review frames.

Providers & models offers explicit API-key versus ChatGPT-plan radio choices in equal-width, vertically aligned native rows. Forms use 24-dp outer gutters, 16-dp section spacing and 8-dp action gaps. Disabled buttons use 40% alpha; the graph tooltip opts out because its alpha belongs to selection fades. Short connection status replaces introductory walls of text; full retention/billing/privacy guidance remains in About & privacy and the plan acknowledgement. Plan mode hides API-key entry and shows separate account selection, browser sign-in/add/sign-out, refreshed model selection and Manage usage. Native notices distinguish plan usage/possible credits from API billing; a first successful plan connection requires an acknowledgement before model selection or session creation. Saved sessions retain their connection even if setup changes. Plan sessions expose the account/usage link without putting credentials into the transcript. Secure setup screenshot blocking is unchanged; browser authentication is external, never an embedded login.

Start session is one disclosed action: local checkpoint then first request, without a second preview gate. Runtime refinements use a 24-dp rounded composer, unboxed 1–4-line input, Send/Stop icons, session model/thinking selectors and a small context ring. Controls retain 48-dp touch targets; model/thinking controls scroll horizontally instead of clipping at enlarged type. No pretend attachment or voice actions are shown. The ring reports the last request's input share of catalog capacity, or ? when unknown; tapping explains limits and holds account/Manage usage details. The always-visible plan button is removed. A native progress row follows the last message rather than appearing at the top; it stays last as streaming rows arrive. Text composer controls use bounded 8-dp-corner ripples; icon-only controls use square targets and circular feedback.
The runtime transcript fades into the page over its bottom 32 dp, with matching bottom padding so
the final message stays readable. A newly committed learner note or answer smoothly scrolls to the
tail even from history; ordinary updates retain the existing follow-only-when-at-bottom behavior.
A circular down-arrow button overlays the bottom trailing edge of the transcript while newer
content is below the viewport; tapping returns to the tail. It fades in and out over 200 ms,
including when reaching the bottom or expanding the question panel. A reversed transition continues
from the current opacity rather than jumping; hiding disables the touch target immediately and
makes it invisible only after the fade. With system animations disabled, visibility changes are
immediate. The button retains a 48-dp native
ripple touch target and accessible label. The composer has no top margin, joining the bottom fade
without a gap. Disabled system animations use immediate scrolling.
Focused offline sample tests cover the shared controls, draft restoration, scroll button and real
IME at the emulator's current font scale. Explicit tail jumps align the end of tall Markdown rows
and remain at the tail during formula/dock reflow until the learner scrolls away. Large-font, RTL,
TalkBack and visual/motion acceptance for this refinement remain pending. The question panel restores the existing outlined surface. Questions use native Markdown/LaTeX, choices and a six-position 0/20/40/60/80/100 confidence slider. The question panel fades/changes bounds on arrival; answer entry stays in the bottom dock, and the transcript remains visible during probing/IME resize. API-30+ native IME frames animate the inset. These latest changes are build/lint checked only, not claimed verified at 200% text or on-device.

Probe conclusion follows LearnHarness's assessment anatomy: one outlined rounded card, a separate
inset dark rounded block per assessed strand, and the overall summary last inside the outer card.
The number of strand blocks follows the assessment, not a fixed three. Native prose fonts,
selectable Markdown and flexible heights are retained. Device/large-text acceptance is pending.

Answer-card refinement follows the owner's LearnHarness reference: a subtle learner band and thin divider before the next agent turn, not chat bubbles. One answered card holds the Markdown question, outlined choices with selected/correct symbols, and a six-color confidence rail. Correctness is independent of self-reported confidence; missing keys stay ungraded. Written reference answers open without claiming automatic semantic grading. Your thinking, Why and public Agent’s thinking summaries have independent native disclosure controls; encrypted reasoning is never shown. This refinement is build/lint checked only; on-device layout and accessibility review remain pending.

Phase-6 diagram slice (build-only): Mermaid uses the pinned strict/dark offline engine with plain
SVG text labels; authored SVG retains its colors on a white render background. Previews stay in the
prose column and open by tapping the image, without a separate Open diagram label or decorative
card. Inline/fullscreen share the same bounded 2048px bitmap cache instead of rendering twice. Short
fence titles or SVG title/Mermaid accTitle supply accessible image labels. Fullscreen inspection
uses a native canvas, pinch/pan, a Fit menu action and labeled accessibility zoom actions; raw
diagram source is not exposed in the UI. It
shows only Rendering… while loading or an error on failure; the completed image has no
gesture-hint/status banner. Image text is rasterized, so enlarged-text readers can zoom rather than
expecting reflow. Only successful maker jobs receive a FIGURE · MODEL-REVIEWED label; ordinary
fences do not imply review, and rendering never confers a correctness/review badge. No installation,
new captures, device/large-text or live-vision verification was performed; PDF remains next.

Phase-7 slice (build-only): New session uses native Learning/Practice radio rows, optional saved
proposition selection and an explicit web-research checkbox with data/billing disclosure. Practice
reuses the bottom composer and question/document rows, not a second chat UI. Active statements
stay in the transcript; submitted reasoning/hint requests retain learner bands. Committed solutions
appear only at conclusion, labeled as model assessment, not mastery. Practice hides agent-summary
disclosures to avoid solution spoilers. Next problem is a learner action after conclusion;
confirmed local dismissal does not reveal the solution or make a request. Confidence scores are
not added to practice. RESEARCH rows carry a source-derived/not-verified label, with saved read
sources available through the session menu on a native offline selectable-text screen. Session web
permission is visible/controllable in the same overflow; no model output can toggle it. Providers
retains its secure window and adds an independent unsaved Brave password field. Device, enlarged
text, TalkBack, IME and recreation acceptance for these new controls remain pending.

Review captures: [local ready state](docs/screenshots/phase4-ready.png), [seeded free-response dock](docs/screenshots/phase4-question.png), [200% dock](docs/screenshots/phase4-question-large-text.png). These show local test fixtures, **not live model output or assessment quality**. The runtime UI check verifies recreation, secure key entry, a visible editor line and reachable submit above the actual IME at 200% text. These remain manual visual baselines, not approved image-diff goldens.

Navigation review: [drawer](docs/screenshots/navigation-drawer.png), [Settings categories](docs/screenshots/navigation-settings.png), [New session](docs/screenshots/navigation-new-session.png). The reference's `components/sidebar.tsx` and `components/settings/settings-dialog.tsx` were revisited: session history and independent categorized settings are retained, but Android uses a modal phone drawer and full-screen focused pages rather than an inline desktop sidebar and two-column dialog. No unimplemented reference categories were added. The default-text 26-test suite passes; the provider-controls check passes at 200% text, while the longer 200% navigation journey timed out and is not counted as passed.
