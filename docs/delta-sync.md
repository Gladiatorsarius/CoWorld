# Delta sync

## What it's for

After takeover, only changes flow instead of full chunks, in both directions. Your sub-server sends the results of what it simulated, the real server sends everything that reaches into your owned chunks from outside.

## What a delta is

Per chunk, per tick, a list of changes: block changes, block entity updates, entity spawns/moves/removals, scheduled tick changes. Same git-commit model as the initial sync: ordered, each chunk has a version number that increases with every change.

## Sub-server to real server

Every tick, the sub-server packs ALL changes from ALL its owned chunks into one batched, compressed packet. The real server applies them to the authoritative world and broadcasts to everyone else. One packet per tick instead of hundreds of tiny ones.

## Real server to sub-server

Anything from outside that touches your owned chunks, sent immediately: another player's action (relayed per the networking model), block updates crossing the border, a piston pushing in, an explosion reaching in, an entity wandering in. These arrive as INPUTS your sub-server then simulates.

## Chunks you don't own

Two cases. Near chunks you don't own (the border ring outside your bubble and the rest of your view range): the real server ticks those and streams the resulting changes live, the same per-chunk versioned deltas. Your sub-server applies them to keep its cache a true copy and simulates nothing there. So inside your owned chunks it receives inputs to simulate, outside them it receives ready-made results. Far-away cached chunks (the rest of the world from the background sync): no live updates, that would flood the connection on big worlds. They go stale in the cache, and when the player gets close, the client hashes its cached chunks against the server and re-syncs only the ones that changed, the same mechanism as rejoining.

## Why there are no conflicts

Only the owner ever produces block changes in a chunk. The outside world can only send actions/inputs, never state changes. So there's never two versions of a block to merge, only inputs to process.

## Ordering and reliability

Per-chunk version numbers, applied in order. A gap means a lost packet, the receiver asks for a resend. A chunk with no changes sends nothing; the sub-server just sends a tiny heartbeat per tick cycle so the real server knows it's alive and current.
