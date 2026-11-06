# Providers

Reasoned sends model requests straight from your device. There is no relay server. The connectable providers are fixed in [`app/src/main/assets/providers.json`](../app/src/main/assets/providers.json), and each one maps to one of four wire formats the app speaks natively.

**Status.** Request translation, stream parsing and endpoint headers for every wire are covered by passing JVM tests. The app's exact request shape has been probed against each endpoint with a fake key. No authenticated model call has been made, so treat this page as what the app sends, not as a promise that a given account can use it. Read [Verification status](#verification-status) for the limits.

## What you can connect

Open **Providers & models** and tap the **Provider** button. The picker offers three kinds of entry.

- 33 API-key providers. Each entry labels the key field with its own name and stores its own key.
- **Custom endpoint**. An OpenAI-compatible server you configure with a full base URL and its own key setting. It is a local setting, not part of the bundled registry. Each distinct URL is a separate endpoint: changing the URL creates a new connection with its own key, and sessions created against the old URL keep using it.
- The ChatGPT plan connection. It signs in through the browser and keeps its own tokens.

The custom endpoint accepts any `https://` URL. Plain `http://` works only for `localhost`, `127.0.0.1` and `10.0.2.2`, so you can reach an OpenAI-compatible server running on the device itself or on the machine that runs the emulator. [`app/src/main/res/xml/network_security_config.xml`](../app/src/main/res/xml/network_security_config.xml) permits cleartext for exactly those three hosts and denies it in the base config. Every other host must use `https://`, including a LAN address such as `http://192.168.1.5:11434`. The manifest keeps `usesCleartextTraffic="false"`, which applies only below API 24, and the app's minimum is 26. Tick **This server needs no API key** when the server does not check credentials. A keyless endpoint sends no auth header, even when a key is stored. With the box cleared, the app sends the saved key, and a request with none fails with `key_missing` before it leaves the device.

The plan connection predates this work, and [ARCHITECTURE.md](../ARCHITECTURE.md) covers it. This page covers the API-key providers and the custom endpoint.

## Wires

A wire is the request and stream format a provider speaks. The app implements four, and every registry provider maps to exactly one.

| Wire | Format | Request URL |
| --- | --- | --- |
| `RESPONSES` | OpenAI Responses | `<base>/responses` |
| `CHAT` | OpenAI Chat Completions | `<base>/chat/completions` |
| `ANTHROPIC` | Anthropic Messages | `<base>/v1/messages` |
| `GEMINI` | Google Generative Language | `<base>/models/<model>:streamGenerateContent?alt=sse` |

The provider base URL is the exact prefix the app uses. A provider whose API lives under `/v1` carries that segment in its base URL. DeepSeek uses `https://api.deepseek.com/chat/completions`, Mistral uses `https://api.mistral.ai/v1/chat/completions`, and Vercel AI Gateway uses `https://ai-gateway.vercel.sh/v1/chat/completions`.

| Wire | Auth header | Extra header |
| --- | --- | --- |
| `RESPONSES` | `Authorization: Bearer <key>` | none |
| `CHAT` | `Authorization: Bearer <key>` | none |
| `ANTHROPIC` | `x-api-key: <key>` | `anthropic-version: 2023-06-01` |
| `GEMINI` | `x-goog-api-key: <key>` | none |

Every request also sends `User-Agent: Reasoned/<version> (Android)`. OpenCode Zen and OpenCode Go send `x-opencode-client: Reasoned` and the stable conversation id in `x-opencode-session`. NVIDIA sends `NVCF-POLL-SECONDS: 3600` and `X-BILLING-INVOKE-ORIGIN: Reasoned`.

The Gemini key goes in a header, not in the query string. Every request asks for an SSE stream.

## Providers

The table lists all 33 entries, grouped by wire. The model count is the number of curated models the asset carries for that provider.

| Provider | ID | Wire | Models |
| --- | --- | --- | --- |
| OpenAI | `openai` | `RESPONSES` | 42 |
| xAI | `xai` | `RESPONSES` | 4 |
| Meta | `meta` | `RESPONSES` | 6 |
| Anthropic | `anthropic` | `ANTHROPIC` | 17 |
| MiniMax | `minimax` | `ANTHROPIC` | 4 |
| MiniMax CN | `minimax-cn` | `ANTHROPIC` | 4 |
| Google | `google` | `GEMINI` | 24 |
| Ant Ling | `ant-ling` | `CHAT` | 3 |
| Baseten | `baseten` | `CHAT` | 14 |
| Cerebras | `cerebras` | `CHAT` | 2 |
| DeepSeek | `deepseek` | `CHAT` | 2 |
| Fireworks | `fireworks` | `CHAT` | 8 |
| Groq | `groq` | `CHAT` | 7 |
| Hugging Face | `huggingface` | `CHAT` | 80 |
| Kimi For Coding | `kimi-coding` | `CHAT` | 4 |
| Mistral | `mistral` | `CHAT` | 37 |
| Moonshot AI | `moonshotai` | `CHAT` | 4 |
| Moonshot AI CN | `moonshotai-cn` | `CHAT` | 4 |
| NVIDIA | `nvidia` | `CHAT` | 21 |
| OpenCode Zen | `opencode` | `CHAT` | 28 |
| OpenCode Go | `opencode-go` | `CHAT` | 18 |
| OpenRouter | `openrouter` | `CHAT` | 386 |
| Qwen Token Plan | `qwen-token-plan` | `CHAT` | 21 |
| Qwen Token Plan CN | `qwen-token-plan-cn` | `CHAT` | 21 |
| Qwen Token Plan Individual | `qwen-token-plan-individual` | `CHAT` | 9 |
| Together | `together` | `CHAT` | 15 |
| Vercel AI Gateway | `vercel-ai-gateway` | `CHAT` | 305 |
| Xiaomi | `xiaomi` | `CHAT` | 3 |
| Xiaomi Token Plan AMS | `xiaomi-token-plan-ams` | `CHAT` | 2 |
| Xiaomi Token Plan CN | `xiaomi-token-plan-cn` | `CHAT` | 2 |
| Xiaomi Token Plan SGP | `xiaomi-token-plan-sgp` | `CHAT` | 2 |
| Z.AI | `zai` | `CHAT` | 7 |
| Z.AI Coding CN | `zai-coding-cn` | `CHAT` | 4 |

Vercel AI Gateway runs on the Chat wire at `https://ai-gateway.vercel.sh/v1` even though the generator copies its models from the Anthropic Messages bucket. The bucket picks which model records to copy, and the wire decides what the client sends. The live endpoint audit confirmed that this base reaches the gateway auth gate.

OpenCode Go requires `x-opencode-session` on every generate request. The app sends the session's stable internal id, so a conversation keeps the same routing identity across teaching, compaction, extraction, research and figure review. OpenCode Zen sends the same header for parity even though it did not reject a request without it.

## Not included

Nine pi-ai providers are left out. Each one needs a credential flow, a URL shape or a wire this client does not implement. OpenAI Codex is left out because the ChatGPT plan connection already covers it.

| Provider | ID | Reason |
| --- | --- | --- |
| Amazon Bedrock | `amazon-bedrock` | AWS credential chain and SigV4 signing. |
| Google Vertex | `google-vertex` | Google Cloud application default credentials. |
| Azure | `azure` | The endpoint is built per resource, so there is no shared base URL. |
| GitHub Copilot | `github-copilot` | Device OAuth flow. |
| OpenAI Codex | `openai-codex` | The ChatGPT plan connection already covers this backend. |
| Radius | `radius` | Proprietary wire. |
| TypeSafe | `typesafe` | Proprietary wire. |
| Cloudflare AI Gateway | `cloudflare-ai-gateway` | Account-scoped URL template. |
| Cloudflare Workers AI | `cloudflare-workers-ai` | Account-scoped URL template. |

## Connection identity

A session records its connection as a string, and the string decides the provider, the billing source and the auth path.

| Form | Meaning |
| --- | --- |
| `key:<provider>` | An API-key connection, for example `key:deepseek`. |
| `key:custom-<endpoint>` | One custom endpoint, pinned by a short hash of its normalized URL. |
| `key:custom` | A session saved by the first custom-endpoint build. It keeps the endpoint that was current when that build ran. |
| `oaiapp_<account>` | One ChatGPT plan account. |
| `api` | A session saved before the provider picker existed. It reads as OpenAI and stays readable. |
| `imported` | A lesson bound to the local settings after import. |

Switching the picker never changes an existing session's connection. Changing the custom URL also never changes an existing session; the old endpoint mapping stays in local preferences so that session keeps reaching its original server. An archived assistant message records its provider id and its wire id, so the message keeps its origin after you move to another provider.

## Credentials

Each provider has its own encrypted key file. The details:

- The file is `provider-<id>.credential` in `noBackupFilesDir`. OpenAI keeps the older name `openai.credential` so existing installs keep working. A custom endpoint uses `provider-custom-<endpoint>.credential`; the first custom-endpoint build's `provider-custom.credential` stays attached only to the endpoint it was pinned to.
- The ciphertext is AES-256-GCM with a fresh IV per save and atomic replacement. The AES key lives in Android Keystore under the alias `reasoned.<name>.v1`.
- The transport reads only the store that belongs to the selected provider. A key is never sent to a different provider.
- The key field accepts 16 to 512 printable ASCII characters. Leaving the field empty is rejected on every connection except a custom endpoint marked keyless.
- Brave Search uses a separate store named `brave`, so research credentials never mix with model credentials.

## Models and metadata

The asset carries 1,110 chat models, each with a display name, a context window, a maximum output token count and the thinking levels it accepts. 918 of them list at least one thinking level.

The model picker shows the curated list for the selected provider. **Enter model ID** accepts an ID by hand when the model you want is missing, using the same validator as the transport (`@` and `~` are allowed).

**Refresh models** calls the provider's model-list endpoint for the current wire and merges the result with the curated list. Discovery is an explicit action and is never required.

A model ID must match `[A-Za-z0-9][A-Za-z0-9._:/@~-]{0,99}`. IDs that start with `sk-` are rejected. A rejected ID fails locally with `model_id_invalid`, before any request.

The bundled metadata is a description of what each provider publishes. It is not a promise that your account can use a given model.

## Thinking levels

Effort uses seven app-level levels: `none`, `minimal`, `low`, `medium`, `high`, `xhigh` and `max`. A model advertises the subset it accepts, and a request with an unsupported effort fails with `reasoning_not_supported` before anything is sent.

Each wire maps those levels to its own shape, and the compatibility flags in the asset choose the shape per model.

| Wire | What the request carries |
| --- | --- |
| `RESPONSES` | The canonical `reasoning` object with `effort`. Encrypted-reasoning fields are removed on providers that do not support them. |
| `CHAT` | A per-model `thinkingFormat`. `openai` sends `reasoning_effort` when a level is chosen. `deepseek` sends `thinking` and `reasoning_effort`. `zai` sends a `thinking` object. `openrouter` sends nested `reasoning.effort`. `together` sends `reasoning.enabled`. `ant-ling` sends `reasoning.effort`. |
| `ANTHROPIC` | `thinking` with `adaptive` when the model supports it, otherwise `budget_tokens`. `output_config.effort` is added when the model supports mid-conversation effort. Budgets are 1024 for minimal, 2048 for low, 4096 for medium, 8192 for high, 12288 for xhigh and 16384 for max. |
| `GEMINI` | `thinkingConfig` with `includeThoughts`. Models with `2.5` in the id get `thinkingBudget`. Other reasoning models get `thinkingLevel`. |

`none` sends no thinking block where the model allows it. A thinking turn also gets more output room where the wire needs it, since reasoning tokens share the token budget.

## Errors

Only a machine code reaches the UI, never provider prose or a key. The common codes are `unauthorized`, `rate_limit`, `context_limit`, `timeout`, `provider_unavailable` and `invalid_request`. A provider that answers with an error object contributes its own code when the app recognizes it.

An HTTP 400, 404, 405 or 422 becomes `invalid_request`, and when the provider's top-level `error` object names the field it rejected, the reason carries that one name as `invalid_request:<field>`, for example `invalid_request:max_tokens`. Only `param`, `code`, `type` and `status` are read, and only when the value is a short string or number of letters, digits, `_`, `.` and `-`. The message text, headers and the rest of the body never reach the app.

## Regenerating the registry

```sh
node scripts/update-providers.mjs /path/to/installed/@earendil-works/pi-ai
```

The script reads the installed package directory you pass, writes `app/src/main/assets/providers.json` and prints the provider and model counts. It contacts no provider and installs nothing.

The bundled asset came from `@earendil-works/pi-ai` 1.4.0, which is MIT licensed. The asset records the package name, version and license it was generated from.

The provider list and the exclusion list are arrays in [`scripts/update-providers.mjs`](../scripts/update-providers.mjs). Adding a provider is one entry plus a wire choice. The script stops on a missing model bucket, an empty model list, a model without a context window or a bad effort mapping, so a broken provider halts the run.

## Verification status

[`ProviderWireTest.kt`](../app/src/test/java/com/kkgosu/reasoned/ProviderWireTest.kt) exercises request translation and stream parsing with canned provider streams. It has 24 tests, and all 24 pass. They cover the Chat, Anthropic and Gemini request translators and their stream parsers, inline image translation, Gemini function-call thought signatures through the native archive, the provider registry, the connection ids and the custom endpoint URL rule.

[`ProviderEndpointTest.kt`](../app/src/test/java/com/kkgosu/reasoned/ProviderEndpointTest.kt) drives the real `WireProvider` with a mock HTTP client for every one of the 33 providers. It pins each full request URL literally and asserts the credential header, `User-Agent`, OpenCode session and client headers, NVIDIA headers, model field and model-list behavior. It makes no network call.

The full JVM suite is 87 tests with one pre-existing Windows-only CRLF failure in `NativeTransferPackageTest`, reproduced on the base commit; CI runs on Ubuntu. The provider tests do not depend on it. `ProviderKeylessTest` adds five cases for the keyless transport rules: no auth header on the model list or on respond even with a saved key, a non-keyless custom endpoint that fails before either call without a key, and a registry provider that is never keyless. To run the provider suites alone, use `./gradlew :app:testDebugUnitTest --tests "com.kkgosu.reasoned.ProviderWireTest" --tests "com.kkgosu.reasoned.ProviderEndpointTest"`.

On 2026-11-06 the app's exact request shape was sent once to each of the 33 registered endpoints with a fake key. Thirty-two reached an auth or model gate. `opencode-go` returned HTTP 400 `MissingSessionID`; after the session header fix it returned HTTP 401. Mistral and Vercel AI Gateway returned HTTP 404 at their old app URLs; after the `/v1` base fixes they returned HTTP 401. No successful model request was made.

That is endpoint and translation verification only. No authenticated API-key call has been made to any of the 33 providers. That leaves these unverified for each provider:

- account eligibility and sign-up requirements
- which models your account can use
- tool-calling quality
- live image support. Figures already send a locally rendered PNG through the Visual Maker loop; the translation is covered for every wire, but no vendor was called live.

Live API-key use overall remains untested, including OpenAI. The ChatGPT plan path was exercised on the owner's device, as recorded in [ARCHITECTURE.md](../ARCHITECTURE.md).

The provider picker, the custom endpoint entry and the dynamic notices are recorded from the debug
build in `docs/screenshots/providers/`: `provider-picker.png`, `provider-picker-custom.png`,
`new-session-deepseek.png`, `privacy-provider.png` and `privacy-retention.png`. The key screen sets
`FLAG_SECURE`, so it is deliberately absent from captures; `ProviderSetupTest` verifies the key
field clears on a provider switch and that two custom endpoints have separate encrypted stores.

The custom endpoint is the one path that has run end to end. On 2026-11-01, a debug build on a disposable API 36 emulator, configured through **Providers & models**, ran one teaching session against [`scripts/local-provider-stub.mjs`](../scripts/local-provider-stub.mjs) at `http://10.0.2.2:8765/v1`. The session reached `finish_lesson` in six model calls: `probe`, `finish_probe`, `plan_init`, a predict quiz, a check quiz, then `finish_lesson`. The app showed "Session ended. Not a mastery certification." No API key was used, because the endpoint was marked keyless. That run verifies the custom endpoint path against a stub. Every live vendor provider remains unverified.

To repeat the run, start the stub on the machine that runs the emulator:

```sh
node scripts/local-provider-stub.mjs 8765
```

On the emulator, set the base URL to `http://10.0.2.2:8765/v1`, tick **This server needs no API key**, and select the model id `stub-teach`, which the stub reports at `/v1/models`. The stub has no dependencies, binds to `127.0.0.1`, and answers from a fixed script, so it exercises request and stream handling rather than model quality.
