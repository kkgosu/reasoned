# Security

## Reporting

Do not put credentials, private chats, personal app databases or unredacted device logs in public
issues. Use the repository host's private vulnerability reporting when the owner has enabled it. A
private reporting channel must be configured before public release; no security contact or response
SLA is advertised yet.

This is pre-release software. There is no supported public release line or independent
security-audit certification yet.

## Boundaries implemented

- API/search/OAuth credentials use atomic AES-GCM ciphertext files and Android Keystore keys; backup
  and cleartext traffic are disabled.
- OAuth uses an external browser, PKCE, state/nonce checks, verified JWT identity and a bounded
  loopback callback. No embedded browser login or shared account cookies.
- Provider tools have explicit role/permission, schema, replay, argument and step-budget validation.
  Display repair cannot rewrite saved answer commitments or grading.
- Research is a separate bounded context. Web text is untrusted data, not authority to grant tools.
  Public HTTPS, port, redirect and DNS-result checks exclude private/special-use addresses; Brave
  credentials are confined to its fixed endpoint. No reader proxy or PDF fetch fallback.
- Diagrams use bundled offline Mermaid and a restricted local renderer, with no model-authored
  JavaScript, Java bridge or external resources. Active SVG is rejected, not executed.
- Experimental LAN lesson exchange uses a separate certificate-pinned HTTPS client, private IPv4
  endpoints and explicit single-use QR pairing. Its trust never applies to provider/public-web calls.
  Tokens stay Keystore-backed on Android; source-free ZIP validation, writer gates, explicit conflicts
  and transactional/journaled receipts also apply to network transfers. No generic application API,
  provider credentials or deletion command is exposed through this transport. Physical LAN/firewall,
  camera and browser/Electron UI acceptance remain open; see [scope and checks](docs/LEARNHARNESS_SYNC.md).
- Generation and specialists are foreground/cancellable operations. Billing connections do not
  silently fall back.

## Limits

Learning data is app-private but not encrypted at rest as a database. Retained graph knowledge
survives chat deletion by design. Model/prompt-injection mistakes remain possible; a model output,
citation or audited extraction is not a security or correctness guarantee. Provider retention,
network availability and account policy are outside the app's control.

The release-preparation source review was performed by the implementing agent, **not an independent
auditor**. Offline tests cover OAuth validation, credential ciphertext, transport redaction, unsafe
web/SVG input rejection, persistence and learning flows; they do not establish resistance to every
attack or verify live provider behavior. See [release gates](docs/RELEASE_CHECKLIST.md)
and [privacy details](PRIVACY.md).
