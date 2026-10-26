"""Project the pinned Visual Maker prompt verbatim; no source edits or network."""
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
source = (root / 'app/src/main/assets/learnharness/src/agent/subagents/visual-maker.ts').read_text()
prompt = re.search(r'const INSTRUCTIONS = `(.*?)`;', source, re.S).group(1)
assert '${' not in prompt and '\\' not in prompt  # No TS template expansion/unescaping is necessary.
(root / 'app/src/main/assets/agents/visual-maker.md').write_text(prompt + '\n')
print('visual-maker')
