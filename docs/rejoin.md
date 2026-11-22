# Rejoin

## Purpose

Reuse a verified compatible cache after a fresh [handshake](handshake.md), without downloading unchanged chunks again. Cache reuse does not restore the last session's ownership.

## Compatibility

Hello reports the previous cache and its metadata. Reuse requires the same server UUID and world UUID, matching CoWorld and Minecraft versions, matching server mods manifest hashes, matching cache-format version, and intact cached data. Validate mod files through their manifest hashes. Missing, corrupt, older-version, or otherwise incompatible caches take [initial sync](sync.md).

Live CoWorld/protocol incompatibility declines offloading; incompatible cached data only changes the sync path. A cached world played in singleplayer is not authoritative and must pass the same comparison.

## Active area and hashes

The active area is the union of the requested ownership square and the player's current view area in the current dimension, not the sum of the two radii. The square is centered on the player's chunk with inclusive chunk-coordinate radius `min(client simulation distance, max_ownership_radius)`. Redstone chunks needed for a whole-set handover must also be synced before that transfer.

1. The client sends one hash for each completed cached chunk in this area, keyed by dimension and chunk X/Z. Hash the chunk's complete transferable state with the same canonical representation used by the server, excluding transport/session metadata.
2. The real server compares against a consistent completed-tick snapshot of its authoritative stored chunk state. A matching hash confirms that snapshot/version without resending its payload. A differing hash makes the server send the full chunk; missing chunks use the same initial-sync snapshot path. Cached state is never uploaded to replace the server's version.
3. For both matches and replacements, track changes from the comparison/snapshot boundary and apply them in order. A hash match alone is not proof of remaining current while the world ticks. Use the snapshot/delta and caught-up rules in [sync](sync.md#snapshot-and-delta-ordering).
4. Prioritize the player's surroundings, then the remaining view area, with movement direction first. Once the sub-server is ready, eligible caught-up chunks use [ownership handover](handover.md). Other visible chunks remain cached without becoming owned.

Cached chunks outside the active area stay lazy and can be stale. Hash-check them before they enter active use as the player approaches. A changed chunk needs a fresh snapshot, not an assumption that the server retained all deltas since the last session.
