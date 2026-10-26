"""Project pinned LearnHarness TS prompt constants into Android assets; no source edits/network."""
from pathlib import Path
import json
import re

root = Path(__file__).resolve().parents[1]
source = (root / 'app/src/main/assets/learnharness/src/agent/subagents/extraction.ts').read_text()
preamble = re.search(r'export const SESSION_PREAMBLE =(.+?);', source, re.S).group(1)
preamble = ''.join(json.loads(s) for s in re.findall(r'"(?:[^"\\]|\\.)*"', preamble))
anchor = json.loads(re.search(r'export const SESSION_MESSAGE_ID_RULE\s*=\s*("(?:[^"\\]|\\.)*")', source).group(1))
core = re.search(r'export const EXTRACTION_CORE = `(.*?)`;', source, re.S).group(1)
auditor = re.search(r'const AUDITOR_INSTRUCTIONS = `(.*?)`;', source, re.S).group(1)
for name, prompt in [('extractor', f'{preamble}\n\n{core}\n\n{anchor}'), ('auditor', auditor)]:
    (root / f'app/src/main/assets/agents/{name}.md').write_text(prompt + '\n')
    print(name)
