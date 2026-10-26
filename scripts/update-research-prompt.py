#!/usr/bin/env python3
"""Project the pinned upstream Researcher prompt without modifying vendored bytes."""
from pathlib import Path
root = Path(__file__).resolve().parents[1]
source = (root / 'app/src/main/assets/learnharness/src/agent/subagents/researcher.ts').read_text()
prompt = source.split('const INSTRUCTIONS = `', 1)[1].split('`;', 1)[0]
(root / 'app/src/main/assets/agents/researcher.md').write_text(prompt + '\n')
print('researcher')
