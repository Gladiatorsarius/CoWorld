# Disconnect

## Clean shutdown

1. Cut the departing player's inputs immediately and show the disconnect locally. The sub-server remains connected without that player attached.
2. Continue simulation for `disconnect_grace_ticks` (default 20 ticks, about 1 second at 20 TPS), then begin returning every chunk still owned by that sub-server.
3. Prepare the real server with the existing delta stream, then use [handover](handover.md): readiness first, final stop at a later tick boundary, remaining deltas and commit, then server simulation from the next logical tick. Keep the final paused interval as short as possible.
4. An individual transfer attempt can time out and retry, but clean shutdown has no overall deadline. A slow connection is not a reason to discard unconfirmed state or silently fall back to crash recovery. The mod delays actually closing the game until the real server confirms all hand-backs are committed.
5. Close the sub-server. Retain the verified cache for rejoin or singleplayer use. A cache may still lack background chunks; keeping it does not prove the entire world was downloaded.

If the connection to the real server is lost, including server failure, clean confirmation is impossible. Close locally, retain the cache, and let the real server apply the ungraceful path if it is still running. Rejoin reconciles against whichever state the real server retained.

## Ungraceful loss

A killed process, whole-game crash, or vanished connection stops the owner's per-cycle heartbeat. `disconnect_timeout_ticks` (default 600 real-server ticks, about 30 seconds at 20 TPS) is measured from the last valid heartbeat. Only a valid advancing completed-cycle heartbeat resets the counter; see [heartbeat validation](delta-sync.md#heartbeats-and-completed-cycles). Duplicate packets and network keepalives do not.

The affected chunks are exactly the chunks still assigned to that sub-server, not its requested radius or every nearby player's chunks. Until timeout, the real server does not simulate them. Without incoming results their visible simulation freezes; lag alone does not instantly revoke ownership.

At timeout, the real server invalidates the missing owner's generation, reclaims its chunks, and resumes from its [latest fully installed state/frontier](delta-sync.md#latest-applied-state) at a tick boundary. It does not wait for the missing owner. Any result never applied to the real server can be lost. There is no fixed one-second rollback bound: per-tick batching does not bound network backlog.

A sub-server-only failure that can still be reported through the live client connection permits immediate reclaim rather than waiting for a heartbeat timeout. This cannot guarantee that the player keeps playing after a crash of the entire Minecraft process.

## Cache reconciliation

Cached state can be ahead, behind, locally edited, or otherwise different. On [rejoin](rejoin.md), the server compares hashes and replaces differing cached chunks with its authoritative state. Cached results never overwrite the server merely because they are newer locally. Incompatible or corrupt caches take full initial sync.

## Mid-transfer ownership

The real server's committed ownership record decides which side owns the chunk. Before commitment, abort leaves it with the old owner; if that owner disappeared, use its failure/reclaim path. After commitment, the new owner is responsible, and its failure uses the same recovery rules. Late messages from a superseded session/generation cannot resurrect ownership.

Clean shutdown and failure reclaim cannot depend on an unmodded player eventually leaving a chunk. These required transfers are exceptions to the routine unmodded-player occupied-chunk restriction; prepare first and keep the final stop minimal. They can still cause a short pause or failure rollback, so normal-play invisibility is not a guarantee against crashes.
