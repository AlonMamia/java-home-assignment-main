# CLAUDE.md

Instructions for Claude Code when working in this repository.

## Update DECISIONS.md when behavior or design changes on request

Whenever the user asks to change the behavior or design of a feature that was previously built or modified in this repo, update `DECISIONS.md` as part of the same task (not as a separate follow-up).

For each such change, the `DECISIONS.md` entry must:
- **State explicitly that the change was requested by the user** — do not attribute a decision to the user unless they actually requested it. Independent (unrequested) engineering choices should be described as such, not as user-requested.
- **Describe what changed and why**, at the same level of detail as the rest of the document (concrete before/after, file/method names, endpoints affected).
- **Note any important tradeoff** introduced by the change, or explicitly say there is none if it's a pure equivalent refactor.

Do not add a `DECISIONS.md` entry for trivial formatting changes or renames that carry no behavioral or architectural meaning.

Preserve the file's existing content, structure, and language/format (the document is written in Hebrew, RTL, with numbered sections and sub-sections) — append new sections/sub-sections rather than rewriting prior ones. Where a new change supersedes something stated earlier in the document, add a short forward-pointing note at the original location (matching the pattern already used in the document, e.g. "עדכון (סעיף X.Y): ...") rather than deleting or rewriting the historical record.
