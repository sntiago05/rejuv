---
name: new-discord-command
description: Create or modify a Discord slash command in the Rejuv bot following the project architecture (Java 21, Spring Boot 4.1, JDA 5). Use this skill whenever a task adds, changes or fixes a bot command, even if the word skill is not mentioned.
---

# Create a Discord slash command

## Architecture rules
- The command class goes in `commands/`, implements `SlashCommand`, and is a Spring `@Component` (auto-registered by `CommandRouter`).
- It only validates input, calls a service and builds the reply. All logic lives in a service in `services/`.
- Services never receive or return JDA types; map to/from records in the command.
- External data comes only through `clients/` (cached).
- All user-facing text comes from `messages.properties` via `Messages`.
- Call `deferReply()` if the work can exceed 3 seconds.
- Use ephemeral replies for errors and private info; public replies only where the spec says so.

## Steps
1. Read the task spec and `../../../AGENTS.md`.
2. Add message keys to `messages.properties` (prefix with the command name, e.g. `profile.not_linked`).
3. Create or extend the service; write its unit tests first.
4. Create the command class: `name()`, `data()` (options with descriptions), `handle()`.
5. Handle errors: not linked, no data, upstream busy (503), unexpected (log with error id).
6. Run `./mvnw verify`.

## Checklist
- [ ] No business logic in the command
- [ ] No hardcoded user text
- [ ] Error cases from the spec handled with friendly messages
- [ ] Service unit tests pass
