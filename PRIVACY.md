# Privacy and local data

Reasoned has no app-operated backend or configured analytics/crash-upload service. Network features
contact the selected third-party services directly. Their policies and retention rules still apply.

## Stored on this device

- Chats, drafts, submitted answers, confidence reports, learning plans, graph propositions/chains
  and available source-message links.
- Practice problems, hidden model-committed solutions, attempts and model verdicts.
- Research reports, read URLs, retrieval metadata and bounded page excerpts; diagram source and
  learning preferences.
- Provider/account settings and credentials.

Sessions and learning data use app-private SQLite/files, **not database encryption**. API keys,
Brave keys and OAuth credentials are AES-GCM encrypted with Android Keystore-backed keys in
no-backup storage. Android backup and device transfer are disabled for app data. This does not
protect data on an unlocked, compromised or rooted device.

Long-pressing a drawer chat allows renaming or deletion. Deletion removes its chat/history and
original source links while intentionally retaining extracted graph knowledge and its grouping
title; propositions can still contain information derived from that chat. This is not secure erasure
of filesystem remnants. Uninstalling or clearing app storage removes local app data; export/import
is not available.

## Data sent when using network features

- **OpenAI / ChatGPT:** goals, preferences, answers and relevant session context for
  teaching/practice; bounded teacher passages and graph context for extraction/auditing; earlier
  context for compaction. Figure creation sends a brief and locally rendered PNGs for model
  inspection. Research sends its self-contained question and retrieved excerpts to the session's
  model. These features can incur extra requests on the selected billing connection.
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
