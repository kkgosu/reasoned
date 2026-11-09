# Public-release preparation

## PDFBox release shrinker fix (2026-11-09)

- Hosted [PR checks run 42648420967](https://github.com/kkgosu/reasoned/actions/runs/42648420967)
  failed in `:app:minifyReleaseWithR8`: PDFBox references the absent optional JPEG2000 decoder
  `com.gemalto.jp2.JP2Decoder`. Debug assembly, both lint variants and the JVM test step passed;
  the release sample check was skipped after the build failure.
- Added only `-dontwarn com.gemalto.jp2.JP2Decoder` in
  `app/src/release/keepRules/pdfbox.keep`, using AGP 9.3's keep-rules source set. PDFBox checks for
  this optional decoder with `Class.forName` and reports its absence; the app uses PDFBox for text
  extraction and Android `PdfRenderer` for page images. No decoder dependency, broad warning
  suppression or disabled optimization was added.
- After the fix, offline `:app:assembleRelease :app:lintRelease` passed locally, the merged R8
  configuration contains the exact rule, and `scripts/check-release-samples.py` passed with
  debug samples present and removed from release. The updated hosted run is not yet verified.
  The release APK remains unsigned; no signing or publication was performed.

## Attachment/composer review fixes (2026-11-09, not release acceptance)

- Native source-image appends now check the UTF-8 archive budget and retain 1 MiB for a
  response/failure receipt and final usage. An oversized image returns `attachment_limit`
  without dropping existing history. A JVM regression covers non-ASCII retained data, image
  rejection, immutable inputs and subsequent failure/usage persistence.
- New-session Start now consistently uses the validated connection readiness, including attachment
  callbacks and creation cleanup. Loading/hidden screens cannot start a lesson.
- Three attachment checks and the provider-notice/setup journey passed on the disposable offline
  API 36 emulator (**4/4**). The attachment journey now pauses the Activity, delivers the picker
  result before foreground restoration, verifies deferred persistence, resumes, previews/restores
  and removes the PDF without starting learning. The setup journey verifies invalid nonempty
  model settings remain blocked when attachments change and valid settings enable Start.
- Focused attachment, native Responses and compaction JVM checks passed. Both updated APKs were
  produced and installed successfully; the combined assembly/lint command exceeded the 30-second
  shell limit, so current debug lint completion is **not verified**.
- Removed the unused context-ring drawable and accessibility string. No full instrumentation suite,
  real system-picker selection, authenticated provider calls, signing or publication was performed.

## Local composer refresh (2026-11-09, not release acceptance)

- Debug/Android-test assembly and debug lint passed offline. The composer keeps Model/Thinking
  selectors, adds an inline Files/Folder panel, centers icon-only actions and moves context details
  to the lesson toolbar menu. Native picker results wait for the resumed foreground scope.
- Three attachment checks passed on the disposable offline API 36 emulator. The UI journey now
  exercises opening/closing the panel, Back preserving the draft, an intercepted native document
  picker result, PDF preview/restoration and source removal without starting learning.
- Manually inspected the composer/arrow with the keyboard and attachment panel on API 36 using
  authored fixture data. The real system folder picker opened and cancelled without losing the draft.
- Follow-up: `LessonTest#sampleUsesRealChatControlsScrollButtonAndRestoresAnswerDraft` and
  `LessonTest#keyboardKeepsSharedTranscriptVisibleAndSubmitReachable` passed together on API 36
  (**2/2**). The keyboard wait now includes the submit button reaching its usable position above
  the IME: visibility/final insets can arrive before the composer animation finishes. The final
  assertions and five-second timeout remain unchanged. Temporary diagnostic markers were removed.
  The earlier sample-controls timeout did not reproduce; its original cause is unconfirmed.
  The sample menu expectation was updated for the intentionally relocated Context window action.
- No full instrumentation suite, authenticated provider calls, signing or publication was performed.

## 1.2.0 preparation (not published)

- Version name **1.2.0**, version code **3**. Export/import is hidden from release Settings;
  debug builds retain it while the upstream LearnHarness integration is under review.
- Offline debug, Android-test and unsigned release assembly passed. Debug/release lint each report
  **0 errors and 77 warnings**.
- JVM suite: **81 passed, 2 skipped, 0 failures**. The previously reported Windows fixture failure
  did not occur in this run; no assertion was changed for this preparation.
- `scripts/check-release-samples.py` passed: sample lesson, graph and style screens remain in debug
  and are removed from release.
- Nine navigation/restoration, account/model and provider-setup checks passed on the disposable
  offline API 36 emulator after fixing the bottom-sheet lifecycle crash. Fragment cleanup now
  finishes an in-progress dismissal before its window detaches; normal sheet animations remain.
- Two lesson controls/draft/keyboard checks also passed. The previously reported keyboard-test
  timeout did not reproduce in this run; no assertions were relaxed.
- The full instrumentation suite and live-provider acceptance were not run.
- Release output: `app/build/outputs/apk/release/app-release-unsigned.apk`. It is **not
  distributable**
  until signed with the same release key as v1.1 and checked as an update. No tag, upload or GitHub
  release was created by this preparation.

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

### Native touch feedback and component-owned chat gutters

Toolbar follow-up: the owner's captures exposed two gaps in the initial check. The transparent oval
mask stretched with overflow bounds, and the theme still selected legacy `Widget.Toolbar`, whose
navigation background is rectangular. The fix uses fixed 20-dp ripple radii and native
`Widget.Material.Toolbar`, with a separate 48-dp overflow target minimum. The new toolbar test
inspects actual navigation, action and overflow backgrounds in an enlarged toolbar at 100%/200%
fonts and in both layout directions; it fails if either navigation or overflow was not visited.
That test, the component test and the actual AppKit sample-screen check pass on the offline
`emulator-5554`. The sample check also waits for the existing dock bounds animation before asserting
its final icon dimensions; the size assertions remain unchanged. No screenshots or phone changes
were made. This is native geometry/state validation, not a visual-fidelity claim.

Debug/app-test APK assembly passed. Five targeted instrumentation checks passed on the disposable
`emulator-5554` with networking disabled: native icon/compound-button/disclosure geometry; table
wrapping and full-width scrolling; shared sample controls/draft/scroll restoration; authored
Markdown/LaTeX examples; and the layout-gutter check including initially hidden content. The
structural checks include 100%/200% font sizes and RTL; the gutter matrix also covers 320/600-dp
widths. The new component check verifies square circular icon targets, platform fixed-radius
radio/checkbox ripple backgrounds with external spacing, and a full-width vertically centered
collapsed learner-thinking row.

No screenshots, live-provider requests, personal-device installs or data resets were performed.
Sample fixture v6 adds an authored edge-to-edge table example. These targeted results do not replace
a full suite, release/lint run, TalkBack acceptance or visual/motion review. The separately recorded
About alignment failure below was not changed or hidden by these updates.

### Shared sample/runtime chat (targeted checks)

Sample now uses the real runtime fragment, native question contracts and renderer with a local mock
provider and a separate in-memory database. Debug/app-test APK assembly passed. Five focused tests
passed (`LessonTest`: 4/4, plus the Markdown fixture check) on the disposable `emulator-5554`,
with Wi-Fi/mobile data disabled: the full
probe/assessment/plan/teaching path and Stop/restoration, chat controls/draft/scroll-button restoration,
plan navigation, the real IME keeping the editor/submit reachable, and authored Markdown/LaTeX
specimens. No screenshots, credentials or live requests were used. These are not a full-suite,
large-font, TalkBack or minimum-API acceptance result.

The extra `UiPolishTest.screensKeepBoundedRipplesCenteredActionsAndPaddedStartAlignedInputs` check
fails at `fragment_about: Centered label/chat title: -1`. About was not changed in this work; no
baseline reproduction was performed and the assertion remains intact. The diagnostic now names the
layout rather than only the anonymous view ID.

`gw ./gradlew.bat :app:assembleRelease --offline` exceeded the 30-second command limit. Inspection
found fresh R8 mapping/usage outputs but a zero-byte release APK; assembly is **not** recorded as
passed. `python scripts/check-release-samples.py` passed against the debug DEX and those R8 outputs,
confirming fixture removal, not release packaging/signing. Release assembly/lint still need a
separately authorized longer run; the timed-out assembly was not retried.

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
