# Initial sync

For the complete startup and continuous-sync sequence, see [syncing order](syncing-order.md).

## Purpose

After Accept, sync the server's mods-folder files and world data into an isolated per-server cache. The player's own client mods are not the sub-server's mod set. The player keeps using the real server while sync runs; no downloaded chunk becomes owned merely because it arrived.

## Order

1. Send a manifest with each server mod file's path, hash, and size. Validate cached files and transfer missing or changed files. The completed manifest identifies the server mod set used for cache compatibility.
2. Stream world chunks in the deterministic order below, expanding through the requested ownership square. Radius is `min(client simulation distance, max_ownership_radius)` before any session-local TPS reduction, measured in chunks. The inclusive square uses chunk-coordinate offsets within that radius on both axes.
3. Continue the remaining world data in the background, subject to the client's disk allowance and combined per-client `sync_speed_limit` in transmitted bytes/second. Starting offloading does not require the entire world to be downloaded.

At radius zero, no ownership is requested. Existing claims can clip the square; connected redstone sets can extend it. See [handover](handover.md) and [redstone](redstone.md).

## Deterministic chunk order

Use one ordering for initial sync, rejoin, and newly needed chunks during movement. Complete the required mods manifest first. Sync required ownership chunks before the remaining view-area chunks, and those before background world chunks; whole-set handover requirements still must be ready before commitment. Ownership candidates use the effective TPS-reduced square plus required whole-set members; existing committed claims still limit which can actually transfer. View eligibility is the inclusive square in the current dimension with radius `min(client view distance, server view-distance cap)`, using the [rejoin active-area boundaries](rejoin.md#active-area-and-hashes). View-only data is cached without local authoritative simulation. Background eligibility is existing stored world chunks outside these higher-priority classes, in this or another dimension; do not generate terrain just to populate the cache. Disk allowance still limits background progress. Within each priority class, sort pending chunks in the current dimension by this ascending tuple:

1. `max(abs(dx), abs(dz))`, the nearest square chunk ring first.
2. `-(dx * vx + dz * vz)`, movement-facing chunks first within that ring.
3. Signed absolute chunk X, then signed absolute chunk Z, both ascending.

Here `(dx, dz)` is the chunk's offset from the player's current chunk. `(vx, vz)` is the horizontal block-position difference between the last two consecutive completed real-server ticks' authoritative player samples in this dimension. Use the same captured samples for an entire queue ordering; no normalization is needed. If either sample is missing, follows a teleport/dimension change, or the player is stationary, use `(0, 0)`. A diagonal uses both components, so ties still fall through to X/Z. This changes transfer priority, not ownership eligibility.

Refresh the pending queue when the sampled player chunk, movement vector, or requested areas change. Do not interrupt or restart a snapshot already being transmitted just to reorder the queue. A chunk needed in multiple classes occurs once in the highest-priority class. Within the background class, finish pending current-dimension chunks first using the ring/direction/X/Z tuple above, then other dimensions in full namespaced identifier order using unsigned lexicographic UTF-8 bytes. Within each other dimension, use signed chunk X then Z because there is no player-relative direction there. Stream existing world data, not newly generated chunks merely to fill an ordering.

## Snapshot and delta ordering

Capture a complete chunk snapshot at the end of an identified tick N, with its chunk version V. Capturing that boundary and starting delta tracking are one consistent operation: every later change is queued, including changes made while the snapshot is still in transit.

Install the snapshot first, then apply deltas after V in version order. Buffer later arrivals until their predecessors exist; do not apply a delta to an older, unrelated snapshot. A missing version invokes [resend or fresh-snapshot recovery](delta-sync.md#ordering-and-recovery).

Caught up means the snapshot and all subsequent deltas through an explicitly identified completed tick/version have been applied. It does not mean the client can tick freely or that the source has stopped changing. [Handover](handover.md) performs a final catch-up at its later stop boundary before changing authority.

## Storage and identity

The real server creates one random UUIDv4 for its server installation and one random UUIDv4 for each world save on first CoWorld initialization. Persist the server ID in installation-level CoWorld metadata and the world ID in that save's CoWorld metadata, separate from session state. Write metadata atomically before advertising either ID or allowing cache sync. A persistence/read failure declines offloading rather than using a temporary identity. Once initialized, missing/corrupt identity metadata requires explicit repair or reidentification, not silently reusing a hostname as identity.

Keep these IDs across normal restart, moving the installation/save, and restoring its backup. Back up both metadata records. A fresh installation gets a new server ID even with an existing world; a fresh world gets a new world ID even on the same server. A restored instance replacing the old one retains identity; an intentionally independent clone must regenerate its server/world identities before accepting CoWorld clients. Exact metadata filenames are implementation details. IDs establish cache identity, not authentication.

Store a separate cache directory under the game directory for each stable server UUID + world UUID. It contains separate mods and world data plus an integrity/compatibility manifest. Dimension and chunk X/Z identify chunks within it. A session ID or a matching hostname alone is not proof that cached world data belongs to this world.

The manifest records CoWorld, Minecraft and cache-format versions, hash format and the [snapshot-adapter/schema identity](rejoin.md#snapshot-adapter-schema-status), the server mods manifest, and completed verified content. Only atomically completed writes with matching hashes are reusable. Interrupted or unverified temporary data is not marked complete. Exact directory spelling is an implementation detail; neither the real server's live disk path nor another server's cache is the sub-server's data directory.

The client's [disk allowance](handshake.md#disk-allowance) caps all cached storage, including temporary writes; it is not only a world-data limit. Evict whole inactive server caches least recently used first; never evict the active connection's cache. If the active working data cannot fit, decline/stop offloading rather than silently deleting live state. A disk allowance does not promise a complete offline world: background transfer may be incomplete.

## Failure and resume

Before ownership transfer, a sync failure leaves ownership unchanged and normal play continues through the real server. A reconnect runs a fresh handshake, revalidates completed cached files/chunks and their compatibility, and resumes from verified content. It never trusts an interrupted write or resumes an old ownership/session grant.

Once chunks have transferred, a later transfer/sync failure does not silently change their owner. Use [handover](handover.md) or [disconnect recovery](disconnect.md). Locally cached world data can be retained for rejoin or singleplayer, but local edits never overwrite the real server during reconciliation.
