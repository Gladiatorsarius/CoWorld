# Logging

CoWorld has two log levels, set by `log_level` in the config.

## Levels

- `normal` (default): only the most important events. It stays quiet so server owners can read it at a glance.
- `debug`: as much as possible. Meant for finding problems and for development.

Warnings and errors are always shown.

## What goes at normal

Only events a server owner needs to know about, such as a handshake result or a new server being set up. Events that happen often, like handovers, are debug only. When unsure, ask the user.

## What goes at debug

Everything useful for understanding what happened and why: timings, state changes, decisions with their inputs.

## Rules

- Every line starts with a tag for its component, so logs can be filtered.
- Never log secrets or tokens at any level.
- Every log message is listed in loggingmessages.md.
- Add a new message to loggingmessages.md in the same change that adds it.
