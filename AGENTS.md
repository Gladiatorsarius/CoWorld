# AGENTS.md

Working agreement for AI coding agents in this repository. Read fully before changing anything.

## Project overview
CoWorld ("Client-Offloaded World") is a Fabric mod set for Minecraft 26.3. A server mod and a client mod work together to offload live chunk simulation from the server to players' PCs, so a small server can support many players. The real server keeps the authoritative stored world and coordinates chunk ownership. Players without the client mod play like vanilla.

## Repository layout
- `Client Mod/`, `Server Mod/`, `Common Mod/`: three standalone Fabric mod projects. There is no root Gradle build.
- Common Mod holds the shared language both endpoints must agree on byte for byte: wire protocol, canonical chunk hash, execution manifests, shared types, config defaults. Shared behavior stays in the mod that performs it.
- `docs/`: the system design. Entry point: `docs/coremodel.md`.
- `docs/folder-structure.md`: the full project tree with every planned class and its one-line purpose. Check it before adding files.

## Design docs are the contract
- When asked to implement something, check the `docs/` folder. If it is covered there, follow the rules in the corresponding doc.
- If it is not in `docs/`, ask the developer whether a doc should be written for it.
- If your implementation has to differ from a doc, stop and ask the developer whether that is okay. If approved, update the doc in the same change. Code and docs must never drift apart.

## Build and test
- Java 25. Build a mod with `./gradlew build` inside its own folder.
- Client Mod and Server Mod reference Common Mod through a Gradle composite build and bundle it with jar-in-jar.
- Every feature must be tested before it counts as done.
- Ad-hoc tests you write while developing are deleted again before committing. Do not commit them; they must not pile up.

## Commits, branches, pull requests
- Conventional Commits: `feat:`, `fix:`, `docs:`, `refactor:`, etc.
- Work in a local git clone, not the GitHub web editor.
- Default flow: new branch, push when ready, open a draft PR. The PR text states which manual or in-game checks remain. Never merge without the developer's explicit instruction.
- When babysitting: call watch_pull_request and end your turn. T3 Code wakes you on checks, comments, or conflicts. Do not poll or sleep. Verify each bot finding against the source, fix real ones, dismiss false positives with a written reason. Stay quiet when nothing is new. Stop when the bots are green on the latest commit.

## Review your own work
Before calling a change done:
- Review your own diff critically, then run one review pass with subagents for bugs, performance and simplicity. Fix verified findings.
- Prefer the simplest implementation that works and minimize the lines you write.
- On coding tasks, share your plan and ask questions before writing code.

## Versioning
- Each mod's version lives in its `gradle.properties`. Bump it with every change: feature -> minor, fix -> patch, breaking change -> major.
