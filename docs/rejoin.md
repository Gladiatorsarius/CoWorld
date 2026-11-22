# Rejoin

## What it's for

When a player comes back after logging out, their client still has the whole cached world from last time. Instead of re-downloading everything, we find what changed while they were gone and sync only that.

## Where it fits

Rejoin runs right after the handshake. The handshake gets one addition: the client says "I have a cache from a previous session" and which mod version that cache is from. If it does, the server skips the full initial sync and goes straight to the hash exchange below. If it doesn't (first join, wiped cache, old mod version), the handshake is exactly as before and the full initial sync runs. Nothing else in the handshake changes.

## How it works

1. On rejoin, the client sends the server one hash per cached chunk for the active area (the ownership bubble plus view range). The rest of the cached world stays lazy: it gets hash-checked later as the player approaches, exactly like the far-chunk rule in delta sync.
2. The server compares each hash against its current state. Unchanged chunk: confirmed from cache, zero data sent. Changed chunk: the server re-sends that full chunk once, then the normal delta stream takes over again. (Deltas-since-your-version would need the server to store its whole change history, not worth it. A changed chunk is a few KB.)
3. Order: chunks around the player first, then the rest of the view range, movement direction gets priority, same as the initial sync.
4. Once the active area is caught up, the ownership bubble hands over again, exactly per the handover design.
5. Chunks never seen before just take the normal initial-sync path.

## Fallback

If the cache is corrupted or from an older mod version, the client drops it and does a full initial sync instead.
