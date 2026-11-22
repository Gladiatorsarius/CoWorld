# Initial sync

## Purpose

After Accept, sync the server's mods-folder files and world data into an isolated per-server cache. The player's own client mods are not the sub-server's mod set. The player keeps using the real server while sync runs; no downloaded chunk becomes owned merely because it arrived.

## Order

1. Send a manifest with each server mod file's path, hash, and size. Validate cached files and transfer missing or changed files. The completed manifest identifies the server mod set used for cache compatibility.
2. Stream world chunks nearest the player's current chunk first, expanding through the requested ownership square. Radius is `min(client simulation distance, max_ownership_radius)`, measured in chunks. The inclusive square uses chunk-coordinate offsets within that radius on both axes. Movement direction gets priority.
3. Continue the remaining world data in the background, subject to the client's disk allowance and combined per-client `sync_speed_limit` in transmitted bytes/second. Starting offloading does not require the entire world to be downloaded.

At radius zero, no ownership is requested. Existing claims can clip the square; connected redstone sets can extend it. See [handover](handover.md) and [redstone](redstone.md).

## Snapshot and delta ordering

Capture a complete chunk snapshot at the end of an identified tick N, with its chunk version V. Capturing that boundary and starting delta tracking are one consistent operation: every later change is queued, including changes made while the snapshot is still in transit.

Install the snapshot first, then apply deltas after V in version order. Buffer later arrivals until their predecessors exist; do not apply a delta to an older, unrelated snapshot. A missing version invokes [resend or fresh-snapshot recovery](delta-sync.md#ordering-and-recovery).

Caught up means the snapshot and all subsequent deltas through an explicitly identified completed tick/version have been applied. It does not mean the client can tick freely or that the source has stopped changing. [Handover](handover.md) performs a final catch-up at its later stop boundary before changing authority.

## Storage and identity

Store a separate cache directory under the game directory for each stable server UUID + world UUID. It contains separate mods and world data plus an integrity/compatibility manifest. Dimension and chunk X/Z identify chunks within it. A session ID or a matching hostname alone is not proof that cached world data belongs to this world.

The manifest records CoWorld, Minecraft and cache-format versions, the server mods manifest, and completed verified content. Only atomically completed writes with matching hashes are reusable. Interrupted or unverified temporary data is not marked complete. Exact directory spelling is an implementation detail; neither the real server's live disk path nor another server's cache is the sub-server's data directory.

The client's disk allowance caps cached storage. Evict whole inactive server caches least recently used first; never evict the active connection's cache. If the active working data cannot fit, decline/stop offloading rather than silently deleting live state. A disk allowance does not promise a complete offline world: background transfer may be incomplete.

## Failure and resume

Before ownership transfer, a sync failure leaves ownership unchanged and normal play continues through the real server. A reconnect runs a fresh handshake, revalidates completed cached files/chunks and their compatibility, and resumes from verified content. It never trusts an interrupted write or resumes an old ownership/session grant.

Once chunks have transferred, a later transfer/sync failure does not silently change their owner. Use [handover](handover.md) or [disconnect recovery](disconnect.md). Locally cached world data can be retained for rejoin or singleplayer, but local edits never overwrite the real server during reconciliation.
