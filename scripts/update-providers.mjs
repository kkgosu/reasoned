// Usage: node scripts/update-providers.mjs /path/to/installed/@earendil-works/pi-ai
// Reads the installed package only; does not install dependencies or contact a provider.
//
// Emits app/src/main/assets/providers.json: the connectable provider registry plus the
// exact model metadata pi-ai ships for each provider. Wire protocols are the subset the
// Android client can speak natively (see docs/PROVIDERS.md for the mapping rules).
import { readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createHash } from 'node:crypto';

const root = resolve(process.argv[2] ?? 'node_modules/@earendil-works/pi-ai');
const pkg = JSON.parse(await readFile(resolve(root, 'package.json'), 'utf8'));
if (pkg.name !== '@earendil-works/pi-ai') throw new Error('Expected a pi-ai package directory');

const EXTENDED_THINKING_LEVELS = ['off', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max'];

/** pi-ai getSupportedThinkingLevels, flattened to the app's effort vocabulary. */
function efforts(model) {
    if (!model.reasoning) return [];
    return EXTENDED_THINKING_LEVELS
        .filter(level => {
            const mapped = model.thinkingLevelMap?.[level];
            if (mapped === null) return false;
            if (level === 'xhigh' || level === 'max') return mapped !== undefined;
            return true;
        })
        .map(level => level === 'off' ? 'none' : level);
}

/** Port of pi-ai openai-completions detectCompat/getCompat, so the client sends the same params. */
function compat(model) {
    const provider = model.provider;
    const baseUrl = model.baseUrl ?? '';
    const isZai = provider === 'zai' || provider === 'zai-coding-cn' || baseUrl.includes('api.z.ai') || baseUrl.includes('open.bigmodel.cn');
    const isTogether = provider === 'together' || baseUrl.includes('api.together.ai') || baseUrl.includes('api.together.xyz');
    const isMoonshot = provider === 'moonshotai' || provider === 'moonshotai-cn' || baseUrl.includes('api.moonshot.');
    const isOpenRouter = provider === 'openrouter' || baseUrl.includes('openrouter.ai');
    const isNvidia = provider === 'nvidia' || baseUrl.includes('integrate.api.nvidia.com');
    const isAntLing = provider === 'ant-ling' || baseUrl.includes('api.ant-ling.com');
    const isDeepSeek = provider === 'deepseek' || baseUrl.toLowerCase().includes('deepseek.com');
    const isGrok = provider === 'xai' || baseUrl.includes('api.x.ai');
    const detected = {
        supportsDeveloperRole: isOpenRouter
            ? model.id.startsWith('anthropic/') || model.id.startsWith('openai/')
            : !(isNvidia || provider === 'xai' || isTogether || isDeepSeek || isZai || isMoonshot || provider === 'opencode' || isAntLing),
        supportsReasoningEffort: !isGrok && !isZai && !isMoonshot && !isTogether && !isNvidia && !isAntLing,
        maxTokensField: (isDeepSeek || isMoonshot || isTogether || isNvidia || isAntLing || isZai) ? 'max_tokens' : 'max_completion_tokens',
        requiresReasoningContentOnAssistantMessages: isDeepSeek,
        thinkingFormat: isDeepSeek ? 'deepseek'
            : isZai ? 'zai'
                : isTogether ? 'together'
                    : isAntLing ? 'ant-ling'
                        : isOpenRouter ? 'openrouter'
                            : 'openai',
        supportsStrictMode: false
    };
    const explicit = model.compat ?? {};
    return {
        developerRole: explicit.supportsDeveloperRole ?? detected.supportsDeveloperRole,
        reasoningEffort: explicit.supportsReasoningEffort ?? detected.supportsReasoningEffort,
        maxTokensField: explicit.maxTokensField ?? detected.maxTokensField,
        reasoningContent: explicit.requiresReasoningContentOnAssistantMessages ?? detected.requiresReasoningContentOnAssistantMessages,
        thinkingFormat: explicit.thinkingFormat ?? detected.thinkingFormat,
        strict: explicit.supportsStrictMode ?? detected.supportsStrictMode
    };
}

// Headers every kept model declares identically, hoisted to the provider so one request cannot
// omit a header the endpoint needs. The app only carries provider-level headers, so a header that
// is not universal is a hard error instead of a silent per-model drop.
function commonHeaders(models) {
    const headers = {};
    for (const name of new Set(models.flatMap(model => Object.keys(model)))) {
        const value = models[0][name];
        if (typeof value !== 'string' || !models.every(model => model[name] === value)) {
            throw new Error(`Provider header ${name} is not universal across models`);
        }
        headers[name] = value;
    }
    return headers;
}

/** Mirrors the app's provider-header validation so a bad value fails here, not at launch. */
const HEADER_NAME = /^[!#$%&'*+.^_`|~0-9A-Za-z-]{1,64}$/;
const HEADER_VALUE = /^[\x20-\x7E]{1,512}$/;

function validateHeaders(providerId, headers) {
    const entries = Object.entries(headers);
    if (entries.length > 16) throw new Error(`${providerId}: too many provider headers`);
    for (const [name, value] of entries) {
        if (!HEADER_NAME.test(name) || !HEADER_VALUE.test(value)) {
            throw new Error(`${providerId}: invalid provider header ${name}`);
        }
    }
}

// Every connectable provider: which pi-ai model bucket supplies it, which wire protocol the
// app speaks to it, and where the user creates a key. A bucket may differ from the model's
// native api when the provider exposes an OpenAI-compatible projection of the same models.
// `sessionHeader` marks an endpoint that routes a conversation by an explicit header.
// `baseUrl` overrides the model's native base URL when the app's wire needs a different prefix.
const PROVIDERS = [
    { id: 'openai', label: 'OpenAI', wire: 'responses', bucket: 'openai-responses', docs: 'https://platform.openai.com/api-keys' },
    { id: 'xai', label: 'xAI', wire: 'responses', bucket: 'openai-responses', docs: 'https://console.x.ai/' },
    { id: 'meta', label: 'Meta', wire: 'responses', bucket: 'openai-responses', docs: 'https://llama.developer.meta.com/' },
    { id: 'anthropic', label: 'Anthropic', wire: 'anthropic', bucket: 'anthropic-messages', docs: 'https://console.anthropic.com/settings/keys' },
    { id: 'minimax', label: 'MiniMax', wire: 'anthropic', bucket: 'anthropic-messages', docs: 'https://platform.minimax.io/user-center/basic-information/interface-key' },
    { id: 'minimax-cn', label: 'MiniMax CN', wire: 'anthropic', bucket: 'anthropic-messages', docs: 'https://platform.minimaxi.com/user-center/basic-information/interface-key' },
    { id: 'google', label: 'Google', wire: 'gemini', bucket: 'google-generative-ai', docs: 'https://aistudio.google.com/apikey' },
    { id: 'ant-ling', label: 'Ant Ling', wire: 'chat', bucket: 'openai-completions' },
    { id: 'baseten', label: 'Baseten', wire: 'chat', bucket: 'openai-completions', docs: 'https://app.baseten.co/settings/api_keys' },
    { id: 'cerebras', label: 'Cerebras', wire: 'chat', bucket: 'openai-completions', docs: 'https://cloud.cerebras.ai/' },
    { id: 'deepseek', label: 'DeepSeek', wire: 'chat', bucket: 'openai-completions', docs: 'https://platform.deepseek.com/api_keys' },
    { id: 'fireworks', label: 'Fireworks', wire: 'chat', bucket: 'openai-completions', docs: 'https://fireworks.ai/account/api-keys' },
    { id: 'groq', label: 'Groq', wire: 'chat', bucket: 'openai-completions', docs: 'https://console.groq.com/keys' },
    { id: 'huggingface', label: 'Hugging Face', wire: 'chat', bucket: 'openai-completions', docs: 'https://huggingface.co/settings/tokens' },
    { id: 'kimi-coding', label: 'Kimi For Coding', wire: 'chat', bucket: 'openai-completions' },
    { id: 'mistral', label: 'Mistral', wire: 'chat', bucket: 'mistral-conversations', docs: 'https://console.mistral.ai/api-keys', baseUrl: 'https://api.mistral.ai/v1' },
    { id: 'moonshotai', label: 'Moonshot AI', wire: 'chat', bucket: 'openai-completions', docs: 'https://platform.moonshot.ai/console/api-keys' },
    { id: 'moonshotai-cn', label: 'Moonshot AI CN', wire: 'chat', bucket: 'openai-completions', docs: 'https://platform.moonshot.cn/console/api-keys' },
    { id: 'nvidia', label: 'NVIDIA', wire: 'chat', bucket: 'openai-completions', docs: 'https://build.nvidia.com/' },
    { id: 'opencode', label: 'OpenCode Zen', wire: 'chat', bucket: 'openai-completions', docs: 'https://opencode.ai/auth', sessionHeader: 'x-opencode-session' },
    { id: 'opencode-go', label: 'OpenCode Go', wire: 'chat', bucket: 'openai-completions', docs: 'https://opencode.ai/auth', sessionHeader: 'x-opencode-session' },
    { id: 'openrouter', label: 'OpenRouter', wire: 'chat', bucket: 'openai-completions', docs: 'https://openrouter.ai/keys' },
    { id: 'qwen-token-plan', label: 'Qwen Token Plan', wire: 'chat', bucket: 'openai-completions' },
    { id: 'qwen-token-plan-cn', label: 'Qwen Token Plan CN', wire: 'chat', bucket: 'openai-completions' },
    { id: 'qwen-token-plan-individual', label: 'Qwen Token Plan Individual', wire: 'chat', bucket: 'openai-completions' },
    { id: 'together', label: 'Together', wire: 'chat', bucket: 'openai-completions', docs: 'https://api.together.ai/settings/api-keys' },
    { id: 'vercel-ai-gateway', label: 'Vercel AI Gateway', wire: 'chat', bucket: 'anthropic-messages', docs: 'https://vercel.com/ai-gateway', baseUrl: 'https://ai-gateway.vercel.sh/v1' },
    { id: 'xiaomi', label: 'Xiaomi', wire: 'chat', bucket: 'openai-completions' },
    { id: 'xiaomi-token-plan-ams', label: 'Xiaomi Token Plan AMS', wire: 'chat', bucket: 'openai-completions' },
    { id: 'xiaomi-token-plan-cn', label: 'Xiaomi Token Plan CN', wire: 'chat', bucket: 'openai-completions' },
    { id: 'xiaomi-token-plan-sgp', label: 'Xiaomi Token Plan SGP', wire: 'chat', bucket: 'openai-completions' },
    { id: 'zai', label: 'Z.AI', wire: 'chat', bucket: 'openai-completions', docs: 'https://z.ai/manage-apikey/apikey-list' },
    { id: 'zai-coding-cn', label: 'Z.AI Coding CN', wire: 'chat', bucket: 'openai-completions', docs: 'https://open.bigmodel.cn/usercenter/apikeys' }
];

// Providers pi-ai can reach but this client cannot: credential flows (AWS SigV4, GCP ADC,
// GitHub device OAuth, Azure resource URLs) or a proprietary wire (radius, typesafe,
// cloudflare account-scoped templates). Documented in docs/PROVIDERS.md instead of shipped.
const EXCLUDED = ['amazon-bedrock', 'google-vertex', 'azure', 'github-copilot', 'openai-codex', 'radius', 'typesafe', 'cloudflare-ai-gateway', 'cloudflare-workers-ai'];

const source = {};
for (const entry of EXCLUDED) {
    source[entry] = { reason: 'excluded' };
}

const providers = [];
for (const entry of PROVIDERS) {
    const raw = await readFile(resolve(root, `dist/providers/data/${entry.id}.json`), 'utf8');
    const data = JSON.parse(raw);
    const bucket = data[entry.bucket];
    if (!bucket) throw new Error(`${entry.id}: missing bucket ${entry.bucket}`);
    source[entry.id] = { sha256: createHash('sha256').update(raw).digest('hex') };

    const models = [];
    const headerSources = [];
    const seen = new Set();
    let baseUrl = entry.baseUrl ?? null;
    for (const model of Object.values(bucket)) {
        if (model.type !== 'chat' || seen.has(model.id)) continue;
        if (!(model.contextWindow > 0)) throw new Error(`${entry.id}: invalid context window for ${model.id}`);
        const values = efforts(model);
        if (!values.every(value => typeof value === 'string' && value.length > 0)) {
            throw new Error(`${entry.id}: invalid effort mapping for ${model.id}`);
        }
        seen.add(model.id);
        baseUrl ??= model.baseUrl ?? null;
        const item = {
            id: model.id,
            name: model.name,
            window: model.contextWindow,
            maxOutput: model.maxTokens ?? 0,
            efforts: values
        };
        if (entry.wire === 'chat') {
            item.compat = compat(model);
            if (model.thinkingLevelMap) item.compat.thinkingMap = model.thinkingLevelMap;
        } else if (entry.wire === 'anthropic') {
            item.compat = {
                adaptive: model.compat?.forceAdaptiveThinking === true,
                effort: model.compat?.supportsMidConvoEffort === true,
                canDisable: model.thinkingLevelMap?.off !== null
            };
        } else if (entry.wire === 'gemini') {
            item.compat = {
                thinkingParam: model.id.includes('2.5') ? 'budget' : (model.reasoning ? 'level' : null),
                thinkingMap: model.thinkingLevelMap ?? {}
            };
        } else if (entry.wire === 'responses') {
            item.compat = { encryptedReasoning: entry.id === 'openai' };
        }
        models.push(item);
        headerSources.push(model.headers ?? {});
    }
    if (!models.length) throw new Error(`${entry.id}: empty model list`);
    if (!baseUrl) throw new Error(`${entry.id}: no base URL`);
    const provider = {
        id: entry.id,
        label: entry.label,
        wire: entry.wire,
        baseUrl
    };
    const headers = commonHeaders(headerSources);
    validateHeaders(entry.id, headers);
    if (Object.keys(headers).length) provider.headers = headers;
    if (entry.sessionHeader) provider.sessionHeader = entry.sessionHeader;
    provider.models = models.sort((a, b) => a.name.localeCompare(b.name));
    if (entry.docs) provider.docs = entry.docs;
    providers.push(provider);
}

const output = {
    source: { package: pkg.name, version: pkg.version, license: pkg.license, datasets: source },
    providers
};
await writeFile(new URL('../app/src/main/assets/providers.json', import.meta.url), JSON.stringify(output, null, 2) + '\n');
const models = providers.reduce((total, provider) => total + provider.models.length, 0);
console.log(`Generated ${providers.length} providers and ${models} models from ${pkg.name}@${pkg.version}`);
