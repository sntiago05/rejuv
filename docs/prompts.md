# Prompt templates

## Claude Project: write a task spec
```
Write the spec for task NNN (<title>) using docs/tasks/000-template.md.
Context: <paste relevant parts of mvp-spec.md / features.md, and the signatures of existing classes it must use>.
Be exact: files, class and method signatures, DTO fields, messages.properties keys, tests, out of scope.
```

## Antigravity / Claude Code: implement
```
Implement docs/tasks/NNN-<name>.md exactly.
Follow AGENTS.md. Only create or modify the files listed in the spec.
If something in the spec is ambiguous or impossible, stop and ask instead of guessing.
When done: run ./mvnw verify, then summarize what you changed and anything I should double-check.
```

## Claude Code for hard tasks (007, 008, 017, 021)
Launch with: `claude --model sonnet --advisor opus` (advisor only for these tasks).

## Claude Project: review a diff
```
Review this diff for task NNN against its spec and AGENTS.md.
Look for: bugs, missing error handling, logic in commands, hardcoded user text, missing tests, out-of-scope changes.
Answer with a short list ordered by severity, then "OK to merge" or "Fix first".
<paste `git diff main...feature/NNN-name` or the relevant files>
```

## End of day: status update
```
Today I merged: <tasks>. Problems: <...>. Update the "Current status" section of PROJECT_CONTEXT.md (max 5 lines).
```
