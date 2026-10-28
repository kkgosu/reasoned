# Public-release preparation

## Verified locally

- [x] Application license selected by the owner: **AGPL-3.0-only**; root `LICENSE`, README and
  in-app About updated. Third-party licenses remain separate.
- [x] Debug app, Android-test APK and unsigned release assemble using cached dependencies with
  `--offline` on JDK 21.
- [x] Debug/release lint: **0 errors**, with 55 debug and 53 release warnings still present.
  Warnings are not a claim of full accessibility/device acceptance.
- [ ] Current complete instrumentation suite: **74/77 pass** on a disposable API 36 arm64 emulator
  with Wi-Fi/mobile data disabled. Three failures reproduce on unmodified `eee613a`; see below.
  The historical release-preparation run passed 76/76. No live model or Brave requests; no
  screenshots.
- [x] Non-destructive install-over-existing and launcher smoke check on the owner's Pixel 8 Pro, API
  37: installation succeeded and activity launch returned `Status: ok`. No fixture suite, app-data
  clearing or live request was performed on the phone. This is not a complete phone acceptance
  journey.
- [x] All 36 vendored source hashes match.
- [ ] Restore exact visual-maker/compaction prompt projections: their byte-comparison tests
  currently
  fail on both the package refactor and unmodified baseline; neither asset was changed by this
  refactor.
- [x] Targeted tracked-file scan found no API-key-shaped or private-key candidates. This is not a
  comprehensive secret scan, Git-history audit or remote-host scan.
- [x] Implementer source review of credentials/OAuth, research URL/DNS restrictions, tool
  boundaries, renderer isolation, manifest/backup settings and diagnostic logging. **Not an
  independent audit.**
- [x] README, privacy/security notes and explicit release limitations added.

Local ignored evidence: `build/release-check/build-packages.txt` (latest successful build/lint),
`build/release-check/instrumentation-packages.txt` (latest full 77-test run),
`build/release-check/instrumentation-packages-baseline.txt` (same three failures on unmodified
HEAD),
`build/release-check/instrumentation-graph-summary-full.txt` (earlier full run),
`build/release-check/instrumentation-ui-audit-full.txt` (previous full-app UI run),
`build/release-check/instrumentation-ui-audit.txt` (targeted UI checks),
`build/release-check/instrumentation-ui-polish.txt` (previous run),
`app/build/reports/lint-results-{debug,release}.{html,sarif}`. Do not commit personal device logs,
databases or credentials.

### Package/responsibility refactor

The root now contains only `MainActivity` and `ReasonedApplication`. Packages follow the reference's
`fragments`, `model`, `api`, `controllers` and `ui/*` grouping, with concrete `storage` and shared
JSON
accessors in `utils`. Session/question models were separated from SQLite, graph values/validation
from
knowledge SQL, OAuth lifecycle from loopback/JWT verification, HTTPS from Responses SSE parsing, and
tool schemas from completed-output validation. Request/state construction was extracted from the
runtime without changing coroutine, cancellation or transaction ordering. XML inflation references
and existing instrumentation imports were updated; the manifest, application ID, database version,
checkpoint keys, credential aliases and instruction assets were not changed.

Offline debug/test/release assembly and debug/release lint pass. The full suite has **74 passes and
three failures**: `AgentInstructionsTest.compactionPromptIsProjectedExactlyFromPinnedSource`,
`DiagramTest.vendoredEngineAndVisualPromptMatchPinnedSources`, and
`DiagramTest.inlineAndBlockCodeHaveEqualVisibleTopAndBottomInsets` (bottom inset 105 for the same
wrapped inline-code fixture). All three reproduce in a separate build of unmodified `eee613a`, so
this is not a clean test-suite pass and the failures remain release gates. No assertions were
relaxed or tests skipped. After the baseline check, the refactored APK/test APK were reinstalled on
the emulator and the launcher activity was confirmed resumed. A baseline-to-refactor emulator
install-over-existing/launcher smoke check passed; it does not certify every saved back stack.
The phone was not modified during this refactor. No screenshots or live requests were made.

### Regressions found and repaired during the first executable full-suite run

- Android ICU rejected an unescaped closing brace in the formula environment regex, crashing
  authored content rendering; the brace is now escaped.
- Home's first graph load fitted over a restored camera; only an already-empty graph now requests
  the extra fit.
- Framework text restoration could save an empty selection before dynamic question options existed;
  draft writes now wait for the matching question view.
- Ambiguous numeric web hosts could enter platform name resolution; malformed numeric IPv4 forms are
  rejected before resolution.
- Gradle daemon criteria forced an unavailable JDK 25 download; the pin was removed and JDK 21
  builds verified.
- Fixed lint indentation, exact prompt projections, JUnit `Unit` return signatures and stale fixture
  assumptions (stable row IDs, teacher-only extraction, Markdown code padding, JSON image URL
  encoding, proposition IDs and asynchronous drawer loading).

The initial run failed; a subsequent complete run passed 72 tests. After UI polish, the rebuilt
APK/test APK pass all 73 tests, including a new structural layout check across 25
screen/row/dialog/toolbar layouts at 320/600 dp, 100%/200% type and RTL. It checks bounded
button/icon/list ripples, centered action-button text, start-aligned navigation/disclosure rows,
chat titles and labels, padded start-aligned inputs (multiline at the top, single-line vertically
centered), and button text height. Short text and empty hints in learning-preference and goal fields
are checked at the first top-aligned baseline. The subsequent full-app source/layout audit passed
all 74 tests after adding a second pass that exposes initially hidden text, supplies wrapping
status/body content, and checks effective screen gutters and text height. The test preserves the
real lesson list's horizontal padding and vertical scrolling, and checks the viewport rather than
the offscreen contents of horizontal scrollers. Removed the search-empty alignment exception:
empty-state text also remains start-aligned. Account names and graph-node navigation labels are
start-aligned; the preferences field no longer masks its hint with a content description. Native
phone visual/motion acceptance remains open; no screenshots were taken.

### Graph summary refinement

The subsequent full suite passes **76/76** tests. Per owner preference the selected-node label still
opens a separate native screen, but its content follows LearnHarness's compact
name/statement/Sessions card. Named session links replace message-excerpt buttons and deduplicate
repeated evidence from the same chat. Source rows are edge-to-edge with bounded ripple feedback and
24-dp text gutters; extraction/authored-example disclaimer text was removed from the summary at the
owner's request. Practice and relevant sample-lesson actions remain in the toolbar menu.
Single-premise chains become direct edges; multi-premise junctions are invisible, excluded from
picking/search and cannot reopen as knowledge cards, including after restored selection. New
fixtures cover junction semantics and real source-chat navigation. No screenshots or live requests
were used.

### Full-app UI audit coverage

All 25 layout resources and their fragment/adapter producers were reviewed, rather than treating
shared style coverage as a complete screen audit. The distinction below matters: a measured fixture
or source review is not manual visual acceptance of a populated screen.

| Surface                    | States/content inspected in source and layouts                                                                                                            | Executed offline coverage                                                                                                                        |
|----------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------|
| Graph home                 | Loading, empty/error/extraction text, search entry, selected-node label                                                                                   | Layout gutters/alignment; graph selection, tooltip, camera and recreation fixtures                                                               |
| Graph search               | Empty results, result rows, input, clear action, toolbar                                                                                                  | Layout/ripple checks; native search/navigation/recreation fixtures                                                                               |
| Graph node                 | Missing node, concise name/statement, full-width distinct named source-chat links or no-source state, toolbar lesson/practice action                      | Layout checks; native summary/source-chat navigation and duplicate-evidence fixture; source styling/alignment and sample-action fixture          |
| Drawer and chat management | Loading/empty list, long chat names, active row, native context menu, rename/delete dialogs                                                               | Row/dialog layouts; native drawer/context-menu and persistence fixtures                                                                          |
| New session                | Learning/practice modes, target selection, consent, goal input, connection/error text                                                                     | Both visible and initially hidden layouts; practice/runtime behavior fixtures; target-picker presentation source-reviewed                        |
| Classroom and Practice     | Status/error labels, expanded/collapsed question, choices/multiselect, don't-know, confidence, draft/read-thought fields, composer/model/context controls | Layout checks; native question restoration, panel measurement/IME-callback, runtime and practice fixtures                                        |
| Transcript                 | Teacher/learner prose, disclosures, answer cards, confidence, Markdown/math/diagram blocks                                                                | Row layouts and wrapping text; rendering/answer-card fixtures; dynamically added answer rows source-reviewed                                     |
| Learning plan              | Missing/error text, saved diagram, node title/status/summary/dependencies                                                                                 | Container/layout checks and plan/runtime fixtures; populated plan paragraph layout source-reviewed                                               |
| Research sources           | Empty/error, report headings, excerpts, URL/date/hash metadata, limitations                                                                               | Container/layout checks and research/persistence fixtures; populated report text layout source-reviewed                                          |
| Settings                   | Navigation, multiline preferences, API/ChatGPT sections, account name, model rows, keys, loading/error/status text and native pickers                     | All sections exposed in layout checks; credential/catalog fixtures; account/model picker presentation source-reviewed without live authorization |
| About/style reference      | Titles, privacy/provenance prose, reference link, type samples and draft field                                                                            | Measured layouts, typography, navigation and restoration fixtures                                                                                |
| Diagram inspector          | Loading/error, image viewport, source dialog, fit/zoom actions                                                                                            | Status/layout checks and offline diagram rendering fixtures; source-dialog padding source-reviewed                                               |

Common checks: 320/600 dp, normal/200% font scale, RTL, bounded button/icon/list ripple backgrounds,
action-vs-navigation alignment, multiline top/single-line vertical input alignment, text height and
effective horizontal gutters. This does **not** certify all combinations of real generated content,
TalkBack traversal, native dialogs at every scale, landscape, motion or phone visual appearance;
those remain acceptance gaps below.

## Before publishing an alpha

- [ ] Choose a public repository/source URL and verify the complete publishable Git tree and history
  contain no secrets/private artifacts. Preserve the pre-existing staged changes deliberately;
  release preparation did not commit or reset them.
- [ ] Publish the exact matching source, notices, build instructions and applicable
  corresponding-source material with any binary release. Confirm distribution-license obligations,
  including AGPL/GPL-derived components; license selection alone is not legal review.
- [ ] Configure a private vulnerability-reporting channel on the chosen host.
- [ ] Choose release version/tag and generate/store a release signing key outside the repository.
  `assembleRelease` currently produces an **unsigned** APK; debug signing is not release signing.
- [ ] Record signed-APK checksum and signing-certificate fingerprint, verify installation/update,
  and attach the matching source/tag. No release has been signed, uploaded or published by this
  preparation.
- [ ] Make remaining acceptance gaps visible in the release notes rather than advertising production
  completeness.

## Remaining acceptance gaps

- [ ] Minimum API 26 and representative API 27–35 devices/emulators. The locally installed emulator
  images start at API 33; no API 26 image was downloaded during this work.
- [ ] End-to-end phone journeys: real lesson/probe/first-plan approval/check/Practice, chat
  rename/delete with retained graph, background/interruption and restoration. Offline fixtures cover
  their underlying paths, but are not a substitute for manual acceptance.
- [ ] Separately authorized live checks of OAuth/model catalogs/Responses SSE, account/billing
  scopes, extraction, visual review and Brave/public-page research. Previous owner observations are
  not a current release gate pass.
- [ ] TalkBack, enlarged text, RTL, landscape, tablets/foldables and motion/IME acceptance. Resolve
  relevant lint warnings based on actual semantics rather than suppressing them wholesale.
- [ ] An independent security review if release assurance requires one; current review is by the
  implementer.

## Deliberately outside this release scope

PDF import/render/search, arbitrary generated HTML/JavaScript demos, background agents, sync and
data export/import. Local backup/transfer remains disabled; explain uninstall/clear-storage data
loss to users.
