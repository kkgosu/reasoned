# LearnHarness instruction sources

Source: https://github.com/amosblomqvist/learnharness
Revision: `0795ef197cca04f6a8e7d50d10ed4dc71bd1a0cc`
License: upstream GNU Affero General Public License v3.0; full text in `LICENSE`.
The learning-data skill declares `AGPL-3.0-only` and retains its own license.

The files listed in `SOURCE.sha256` are unmodified upstream bytes, with their original repository-relative paths. They include the shared base, every Prober/Teacher/Practice fragment, the learning-data skill and its readers/reference, root development guidance, prompt assembly sources, specialist instructions embedded in TypeScript, and compaction instruction sources. TypeScript and Python sources are preserved as source/reference, not executed by Android.

`AgentInstructions.kt` mirrors upstream `prompt-segments.ts` / `composeInstructions`: shared base,
sorted numeric-prefix role fragments, then sorted shared fragments, trimmed and separated by
`\n\n---\n\n`. Practice omits the teaching base and uses upstream's four-file shared allowlist.
Prober and Teacher are the active teaching roles; the separate practice mode now activates the
pinned Practice fragments. The separate foreground extraction channel also activates the pinned
Extractor/Auditor instructions.

The app appends a separately authored `agents/android-compatibility.md`; it never rewrites upstream fragments or adds the old independently written reasoning-check skill. The appendix maps the available Android tools and explicitly states missing capabilities and rendering differences. This is instruction-source parity plus a compatibility layer, not full runtime feature parity.

The upstream root `AGENTS.md` is development guidance, not a teaching system prompt. Neither it nor the learning-data skill or specialist sources are appended to teaching prompts. The local learning-preferences `AGENTS.md` remains learner data, not this upstream development file.

`ModelProvider.kt` also ports the ChatGPT payload allowlist and terminal/error semantics from `src/agent/chatgpt-stream.ts` at the same revision. `RuntimeTools.kt` admits the upstream `learnharness` namespace only for plan sessions. Android retains OkHttp, bounded parsing, Keystore storage and foreground lifecycle handling; Electron IPC and Pi's JavaScript runtime are not bundled. These Kotlin adaptations are separate from the unmodified asset inventory.

Phase 5 projects `EXTRACTOR_INSTRUCTIONS` and `AUDITOR_INSTRUCTIONS` from the already-vendored `src/agent/subagents/extraction.ts` into `assets/agents/extractor.md` and `auditor.md`, using `scripts/update-extraction-prompts.py`. The original constants' wording is retained, with a separate runtime Android capability note. The Kotlin pipeline follows upstream teacher-prose coverage, message anchors, Extractor → identity Auditor → merger separation, joint-premise arguments and the hybrid automatic/manual trigger. Android uses one foreground writer, SQLite transactions and structured JSON plan inputs instead of Electron/server workers, JSONL ledgers or Mermaid parsing. Chains additionally require exact message anchors. The 36-file pinned inventory remains untouched.

Phase 6 projects `INSTRUCTIONS` verbatim from the already-vendored
`src/agent/subagents/visual-maker.ts` into `assets/agents/visual-maker.md` using
`scripts/update-visual-prompt.py`. `VisualMaker.kt` adapts its bounded write/edit/render → actual
PNG input → vision/revision loop to foreground Kotlin and the saved session connection.
`DiagramRenderer.kt` / `renderer.html` use an independently authored restricted offline rasterizer;
Mermaid settings and native inspection follow the product intent in `components/mermaid-render.ts`
and `components/artifacts/visual-card.tsx`, not their React implementation. Android denies active
SVG rather than silently sanitizing it, does not run background jobs or reuse the desktop
filesystem, and never marks merely rendered output as verified. The 36-file pinned source inventory
remains unchanged.

Phase 7 projects `INSTRUCTIONS` verbatim from preserved `src/agent/subagents/researcher.ts`
into `assets/agents/researcher.md` with `scripts/update-research-prompt.py`. `Researcher.kt`
retains its isolated question → search/read → sourced brief contract with a separate native
capability/security note. `ResearchWeb.kt` adapts Brave search and public HTTPS text fetching,
not upstream filesystem/PDF readers or reader proxies. Practice uses the unchanged practice
fragments; native `problem`/`conclude`, local dismissal, explicit next-problem pacing and saved
attempts adapt its doctrine without adding mastery scores. The original 36 source hashes are
unchanged. Additional native limits and per-session web permission remain Android-specific.

This vendored content is copyleft material, unlike the earlier reference-only use. Preserve attribution, license and applicable corresponding-source obligations when distributing. The application's first-party licensing and compatibility must be resolved before public binary release; this notice does not choose a license for unrelated code.
