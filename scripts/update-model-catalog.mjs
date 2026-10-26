// Usage: node scripts/update-model-catalog.mjs /path/to/installed/@earendil-works/pi-ai
// Reads the installed package only; does not install dependencies or contact a provider.
import { readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { createHash } from 'node:crypto';

const root = resolve(process.argv[2] ?? 'node_modules/@earendil-works/pi-ai');
const { name, version } = JSON.parse(await readFile(resolve(root, 'package.json'), 'utf8'));
if (name !== '@earendil-works/pi-ai') throw new Error('Expected a pi-ai package directory');
const raw = await readFile(resolve(root, 'dist/providers/data/openai.json'), 'utf8');
const { getSupportedThinkingLevels } = await import(pathToFileURL(resolve(root, 'dist/models.js')));
const { OPENAI_MODELS } = await import(pathToFileURL(resolve(root, 'dist/providers/openai.models.js')));
const models = Object.fromEntries(Object.values(OPENAI_MODELS).map(model => {
    const id = model.id;
    if (!id || id.startsWith('chat:')) throw new Error(`Expected wire model ID, got ${id}`);
    const efforts = model.reasoning ? [...new Set(getSupportedThinkingLevels(model).map(level =>
        model.thinkingLevelMap?.[level] ?? (level === 'off' ? 'none' : level)))] : [];
    if (!(model.contextWindow > 0) || !efforts.every(value => typeof value === 'string')) throw new Error(`Invalid metadata: ${id}`);
    return [id, { name: model.name, window: model.contextWindow, efforts }];
}));
if (!Object.keys(models).length) throw new Error('Empty catalog');
// Regression: pi's typed catalog keys (chat:...) are not model IDs accepted by the API.
if (Object.values(OPENAI_MODELS).some(model => !models[model.id])) throw new Error('Missing wire model ID');
const output = { source: { package: name, version, license: 'MIT', sha256: createHash('sha256').update(raw).digest('hex') }, models };
await writeFile(new URL('../app/src/main/assets/openai-models.json', import.meta.url), JSON.stringify(output, null, 2) + '\n');
console.log(`Generated ${Object.keys(models).length} models from ${name}@${version}`);
