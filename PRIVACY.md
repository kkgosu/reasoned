# Privacy and local data

Reasoned has no app-operated backend or configured analytics/crash-upload service. Network features
contact the selected third-party services directly. Their policies and retention rules still apply.

## Stored on this device

- Chats, drafts, submitted answers, confidence reports, learning plans, graph propositions/chains
  and available source-message links.
- Practice problems, hidden model-committed solutions, attempts and model verdicts.
- Research reports, read URLs, retrieval metadata and bounded page excerpts; diagram source and
  learning preferences.
- Provider/account settings and credentials; optional paired-computer identity and pending transfer metadata.

Sessions and learning data use app-private SQLite/files, **not database encryption**. API keys,
Brave keys, OAuth credentials and LAN pairing tokens are AES-GCM encrypted with Android Keystore-backed keys in
no-backup storage. Android backup and device transfer are disabled for app data. This does not
protect data on an unlocked, compromised or rooted device.

Long-pressing a drawer chat allows renaming or deletion. Deletion removes its chat/history and
original source links while intentionally retaining extracted graph knowledge and its grouping
title; propositions can still contain information derived from that chat. This is not secure erasure
of filesystem remnants. Uninstalling or clearing app storage removes local app data. Experimental
file Import/Export and paired-LAN exchange can create copies outside this app; deleting here does not
delete those copies. Native ZIP/JSON includes private history and hidden model commitments, not a
sanitized transcript. Pending lesson ZIPs remain app-private but are not encrypted lesson storage.

## Data sent when using network features

- **OpenAI / ChatGPT:** goals, preferences, answers and relevant session context for
  teaching/practice; bounded teacher passages and graph context for extraction/auditing; earlier
  context for compaction. Figure creation sends a brief and locally rendered PNGs for model
  inspection. Research sends its self-contained question and retrieved excerpts to the session's
  model. These features can incur extra requests on the selected billing connection.
- **Paired LearnHarness computer (experimental):** explicit send/receive exchanges selected native
  lessons and their graph over certificate-pinned HTTPS. Pairing shares a device label and creates a
  local access token, not a provider account. The computer stores token hashes and committed transfer
  receipts; the phone stores encrypted connection/operation credentials and private pending archives
  for retry. QR/connection strings contain a short-lived pairing secret: do not share them. Neither
  pairing nor receiving/opening starts model requests. File export remains independent, including
  manual storage through a chosen document provider such as Google Drive; its policies apply.
- **GitHub update checks:** pressing **Check for updates** in About requests this repository’s
  latest stable release from the public GitHub API. GitHub receives your network address and
  ordinary request metadata, not app credentials or learning data. There are no background checks;
  opening the releases link uses your browser.
- **Authentication:** browser-based OpenAI authorization, OAuth registration/discovery, token
  exchange/refresh and account-scoped model discovery. Signing out clears local tokens; a failed
  remote revocation can leave a grant that must be disconnected at the provider.
- **Brave Search:** queries sent with the separately configured search key. Search has separate
  billing and requires session permission to be used by the agent.
- **Visited public HTTPS sites:** the requested URL, device network address and ordinary request
  metadata. Page fetches do not carry app-account cookies or provider keys. External links opened in
  your browser follow the browser's own policies.

Model requests set `store: false`; that is **not a guarantee of zero provider retention**. Do not
submit information you cannot share with the selected provider. The app does not silently switch
billing connections.

Saved lesson/diagram viewing is local, Mermaid is bundled rather than fetched from a CDN, and
SVG/Markdown do not load remote lesson images. Research is opt-in and restricted to public HTTPS
text pages. None of these controls makes generated teaching, figures or web sources independently
trustworthy.

## Diagnostics

Current Android diagnostic logs contain bounded protocol/status metadata and error codes, not
intentional transcript or credential dumps. System logs and bug reports can still contain
identifying device information. Review/redact logs before sharing; never attach credentials or a
personal app database to a public issue.
