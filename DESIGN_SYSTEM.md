# Native design reference

Scope: extracted reference rules and the phase-1 Android style specimen, not a complete lesson or graph prototype. Product/appearance comes from LearnHarness; Android engineering comes from AppKit/LiteX. Repository revisions and license boundaries are in [REFERENCES.md](REFERENCES.md).

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
| Home | Graph fills available space; bottom goal input and teach/practice switch; navigation secondary | Phase 1 has only a quiet graph-oriented empty state and style-reference link. Actual graph/goal controls belong to phases 2–3. |
| Navigation | Narrow left session column; serif/prose names, mono filters/search/metadata | AppKit stack and platform Toolbar now; dedicated session/navigation surface later instead of shrinking the desktop sidebar onto phones. |
| Session | Serif title; status/plan actions; document-like prose spine; integrated formulas/diagrams | `LearningSessionFragment` and typed RecyclerView rows in phase 2. No mirrored messenger bubbles. |
| Composer | One rounded bottom shell, growing Source Sans input, trailing send/stop; ancillary controls below | Native multiline EditText, touch-visible submit/stop and IME-aware safe area in phase 2. |
| Question dock | Same shell replaces composer; label → question → options → confidence → “Your thinking” → submit | One persistent native dock; preserve drafts/selection by question identity. Probe opens immediately; teaching checks offer reveal. Collapse is presentation, not reset. |
| Practice | Task-specific exercise/answer/feedback in the persistent interaction area | Reuse the dock/state identity principles in phase 7, not a new modal per exercise. |
| Plan/diagram overlay | Detailed inspection independent of the lesson spine | Phone fullscreen AppKit screen; restore position/draft on return. No tiny desktop popover. |
| Graph | Fine edges, small light nodes, quiet labels; muted cluster regions behind topology | Custom Kotlin Canvas view in phase 3. Separate layout from drawing; zoom gates domain/node labels; touch selection opens readable details. |

Desktop screenshots contain window traffic lights, hover controls and wide columns. These are not Android UI requirements. Preserve hierarchy, content organization and behavior, not desktop window furniture.

## 6. Motion and accessibility

Reference evidence: `globals.css` dock variant fades/rises over **120 ms**; sidebar transitions use **200 ms**; graph hover fade uses **180 ms**. Motion is short and task-driven, not continuous decoration.

Phase 1 uses a **150 ms native transition duration** (`motion_short_ms`), an adaptation inside that observed range rather than a claimed exact source token. Entry is a fade. Exit retains AppKit's positional transition and predictive-back cleanup, shortened to the same duration; gesture progress remains owned by AppKit/the platform. Noninteractive transitions consult `ValueAnimator.areAnimatorsEnabled()` and use zero duration when disabled. No idle animation loop exists.

Use Android focus, touch feedback, TalkBack labels and heading semantics; do **not** copy the web CSS's blanket focus-outline removal. Text sizes are sp, content height is flexible, surfaces scroll, and buttons remain reachable with enlarged type. AppKit's inset-aware root and platform Toolbar keep content clear of system bars; `adjustResize` supports the sample editor/keyboard.

## 7. Style reference and visual verification

Open **Style reference** from Home. It demonstrates three font roles, the complete size ladder, a minimal outlined surface, primary/secondary actions and a multiline restoration draft. It deliberately uses a static ScrollView: building a RecyclerView adapter for a fixed typography specimen would add no value. LiteX/AppKit RecyclerView is installed for phase 2.

Captured Android screens: [home](docs/screenshots/phase1-home.png), [style reference](docs/screenshots/phase1-style-reference.png). They are our own emulator captures, not copied reference assets. Environment and checks are recorded in IMPLEMENTATION_PLAN.

Comparison against the original screenshots:

- Matched: exact base colors; serif title / sans reading / mono chrome separation; thin quiet separator; restrained outlined functional surfaces; no chat bubbles, colorful cards or heavy shadows.
- Corrected during verification: a global theme font was overriding text-appearance fonts, rendering prose and prominent titles in mono. It was replaced with a default TextView appearance; font assertions and screenshots now confirm separate roles.
- Intentional adaptation: 24 dp phone margins, 48 dp controls, platform system bars/back arrow, wrapping text, no desktop sidebar/window controls. The home copy and specimen are original explanatory placeholders, not a reproduction of a full learning session.
- Not yet comparable: real lesson rows, formulas, dock expansion, graph density/clusters and tablet composition. Those screens do not exist in phase 1. No approved Android-sized pixel-diff goldens have been prepared; captures are for manual inspection, not a false claim of screenshot-regression coverage.

Phase 2 must prepare reviewed Android-sized lesson/dock baselines and correct substantial visual mismatches before connecting real models. Pixel equality against a desktop screenshot is not the acceptance criterion.
