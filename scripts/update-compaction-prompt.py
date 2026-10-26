#!/usr/bin/env python3
"""Project the pinned LearnHarness compaction prompt; never edit upstream sources."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
source = (root / 'app/src/main/assets/learnharness/src/core/compaction.ts').read_text()
body = source.split('export function buildCompactionPrompt(', 1)[1]
prompt = body.split('return `', 1)[1].split('`;', 1)[0]
prompt = prompt.replace('${planSection}', '{{PLAN}}').replace('${input.spanText}', '{{TRANSCRIPT}}')
assert '${' not in prompt
(root / 'app/src/main/assets/agents/compaction.md').write_text(prompt + '\n')
print('compaction')
