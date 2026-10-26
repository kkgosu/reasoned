// Usage: npm install --prefix /tmp/mermaid --ignore-scripts --omit=dev mermaid@11.17.0
//        node scripts/vendor-mermaid.mjs /tmp/mermaid/node_modules
import { readFileSync, readdirSync, mkdirSync, writeFileSync, copyFileSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { createHash } from 'node:crypto';

const modules = resolve(process.argv[2]);
const root = resolve(import.meta.dirname, '..');
const target = join(root, 'app/src/main/assets/diagrams');
const licenses = join(root, 'app/src/main/assets/licenses');
const pkg = JSON.parse(readFileSync(join(modules, 'mermaid/package.json')));
if (pkg.version !== '11.17.0') throw Error('Expected mermaid 11.17.0');
mkdirSync(target, { recursive: true });
copyFileSync(join(modules, 'mermaid/dist/mermaid.min.js'), join(target, 'mermaid.min.js'));
copyFileSync(join(modules, 'mermaid/LICENSE'), join(licenses, 'mermaid.txt'));
const notices = ['Mermaid 11.17.0 and its npm runtime dependency notices.\nThe prebuilt browser bundle retains its own bundled-license comments.\nThis inventory is deliberately inclusive; not all installed packages enter the browser bundle.'];
function scan(directory) {
  for (const entry of readdirSync(directory, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
    if (!entry.isDirectory() || entry.name.startsWith('.')) continue;
    const path = join(directory, entry.name);
    if (entry.name.startsWith('@')) { scan(path); continue; }
    const info = JSON.parse(readFileSync(join(path, 'package.json')));
    notices.push(`\n\n===== ${info.name} ${info.version} (${JSON.stringify(info.license)}) =====`);
    const files = readdirSync(path).filter(name => /^(license|copying|notice)(\.|$)/i.test(name));
    if (!files.length) {
      const readme = readdirSync(path).find(name => /^readme/i.test(name));
      if (!readme || !readFileSync(join(path, readme), 'utf8').includes('Permission is hereby granted'))
        throw Error(`Missing license file: ${info.name}`);
      files.push(readme); // fastdom and strictdom carry their MIT text in README.
    }
    for (const file of files) notices.push(`\n--- ${file} ---\n${readFileSync(join(path, file), 'utf8')}`);
    if (readdirSync(path).includes('node_modules')) scan(join(path, 'node_modules'));
  }
}
scan(modules);
writeFileSync(join(licenses, 'mermaid-dependencies.txt'), notices.join('\n'));
const sha = createHash('sha256').update(readFileSync(join(target, 'mermaid.min.js'))).digest('hex');
writeFileSync(join(target, 'NOTICE.md'), `# Offline Mermaid renderer\n\nMermaid ${pkg.version}, https://github.com/mermaid-js/mermaid (MIT).\nUnmodified npm dist/mermaid.min.js, SHA-256: ${sha}.\nLicense and inclusive npm dependency notices: assets/licenses/mermaid*.txt.\nRegenerate with scripts/vendor-mermaid.mjs and the pinned npm version.\nNo CDN, network diagrams, native JavaScript bridge or user-authored JavaScript.\n`);
console.log(`Mermaid ${pkg.version}: ${sha}`);
