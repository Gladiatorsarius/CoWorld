# Core model: live chunk simulation offloading

For the complete startup and continuous-sync sequence, see [syncing order](syncing-order.md).

CoWorld is a Fabric server/client mod pair for Minecraft 26.3. A player running the client mod can host a sub-server inside their Minecraft process to simulate nearby chunks. The real server keeps the authoritative stored world and coordinates ownership.

Players without CoWorld connect normally and cannot host a sub-server. They stay connected to the real server even inside another player's offloaded chunks. CoWorld is optional for visitors, but a server's other mods can impose their own client requirements.

## Joining

1. [Handshake](handshake.md) checks versions, offloading preferences, capacity, and ping. Accept starts sync, not ownership.
2. [Initial sync](sync.md) sends hashed manifests and the complete server versions/libraries folders followed by the complete mods/config folders, then required world metadata/datapacks, all player data and chunk snapshots nearest the player first, with the rest in the background. These are the sub-server's mods, not the player's own client-mod set.
3. [Bootstrap](client-server-bootstrap.md) starts the sub-server in the same Minecraft process using its isolated per-server/world cache. Synced data is loaded without authoritative ticking.
4. [Handover](handover.md) transfers eligible caught-up chunks or whole redstone sets. Readiness is acknowledged while the old owner keeps ticking; only final catch-up and commit require a brief stop at a later tick boundary.
5. After takeover, [delta sync](delta-sync.md) carries ordered per-tick results and outside inputs. [Rejoin](rejoin.md) revalidates compatible cached content after every fresh handshake, without restoring old ownership grants.

## Ownership

The requested radius is `min(client simulation distance, max_ownership_radius)`, in chunks. It defines an inclusive square centered on the player's current chunk, not automatic ownership of every loaded chunk. Radius zero means no offloading.

The hosting player's client simulation-distance setting, reported in Hello and on changes, supplies `client simulation distance`; it is not the view distance or another visiting player's setting. The real server recomputes the candidate square after that player changes chunk or dimension, changes simulation distance, the server cap changes, or the TPS guard changes its session-local reduction. Apply `max(0, min(client simulation distance, max_ownership_radius) - reduction)` after the [TPS guard](configs.md#performance-and-verification); a successful window does not reset reduction. Recalculation prepares normal transfers, never silently changes committed ownership.

Every chunk has exactly one authoritative owner. Only that owner's results modify the authoritative world. Existing claims remain first come, first served under [multi-client arbitration](multi-client-arbitration.md). Connected redstone chunks stay whole and may extend beyond the requested square within the configured set-size cap. Chunks that are not ready, eligible, or committed stay with their current owner.

### Simulation distance update

Fabric custom payload ID: `coworld:simulation_distance_update`. It is a post-Accept payload, separate from the three handshake payloads (Hello, Accept, Decline) and the vanilla keepalive RTT probes. It is a bounded, session-bound follow-up, not an ownership-grant packet.

Fields:

- Session ID: 16-byte UUID. It must match the active accepted session.
- Simulation distance: u32, in chunks. Reject values outside the signed 32-bit server model. Zero is valid.
- Update sequence: u64, monotonic per live session. Stale or out-of-order updates are ignored.

Behavior: if the client simulation distance changes after Accept, HelloSender sends the update with the active session ID, the bounded distance and a monotonic sequence. An accepted update recomputes the requested ownership radius as `min(client simulation distance, max_ownership_radius)`, applies the session-local TPS reduction, and uses the normal [handover](handover.md) and cache-coverage ([rejoin](rejoin.md)) rules. It never reallocates or revokes committed ownership directly.

Routing: a valid update on the accepted session has no terminal result. The server recomputes the candidate radius and follows the normal handover rules. A stale, out-of-order, malformed or wrong-session update gets no result: the server ignores it and keeps the active session and the last accepted distance.

Server validation: reject stale or out-of-order sequences, and ignore other or missing session IDs. A wrong-session, malformed, replayed or invalid update never alters the active session or committed ownership.

Risks and mitigations: bind every update to the active session ID, enforce the monotonic sequence, and ignore bad updates without changing ownership.

Implementation: `SessionManager` validates each update against the active session and the monotonic sequence, then recomputes the radius through the normal handover rules. `HelloSender` adds a post-Accept sender. This behavior needs manual or in-game verification; no automated test code is retained.

Use `floor(x/16)` and `floor(z/16)` for position-to-chunk membership, including negative coordinates. A chunk includes its lower boundary and excludes its upper one. Entity/player position, not bounding-box overlap, determines membership. Non-player riding/leash groups are the explicit entity-authority exception: all members follow their deterministic lead's position chunk, even when trailing members occupy other chunks. This does not move those chunks' block ownership. Players keep their own position-based authority. See [entity groups](entity-handover.md#related-entities-and-block-references).

Routine chunk handovers in either direction are blocked for the chunk an unmodded player occupies, moving or stationary, not a radius around that player. One occupied member pins a whole redstone set. Exceptions are clean owner shutdown, failure recovery, required conflicting/oversized redstone consolidation, and transfer to the real server or another eligible sub-server when the old sub-server owner's player is more than `vanilla_player_handover_release_distance` chunks from the occupied chunk (default 12, strict `>`, not measured from the incoming owner). [Handover](handover.md#triggers) defines the square chunk-distance and set rules. An unmodded player can still walk into an already offloaded chunk without moving the chunk's ownership.

## Networking and conflicts

In v1, the real server relays inputs to the current owner, stores the resulting changes, and relays results to other players. It does not simulate offloaded chunks or merge independently produced block states. Outside effects enter the owner's simulation as inputs, not competing block writes. Nearby unowned caches receive their actual owner's results; distant caches are checked before reuse.

General client prediction is not part of this model.

## Borders and explicit shadow exceptions

[Player border handover](player-border-handover.md) predicts crossings and prepares a non-authoritative player warm standby; only one side publishes player results. [Other entities](entity-handover.md) transfer complete state after the source's final tick, before the destination's next logical tick. [Redstone](redstone.md) uses connected ownership sets so a contraption never intentionally ticks under two owners.

Optional [handover verification](handover.md#optional-verification) shadows chunks for debugging. A genuine mismatch selects the former owner's complete transfer-unit shadow snapshot at the compared tick, including entities, scheduled ticks, redstone/random state and pending-input bookkeeping, not only the compared block fields. [Coordinated recovery](handover.md#optional-verification) revokes the divergent generation, installs the snapshot, assigns fresh non-reused versions/generation, and returns authority before resuming at the next logical tick. It can roll back published results inside the unit; effects outside it are not globally undone. This and player warm standby are explicit non-authoritative simulation exceptions, not shared ownership or normal duplicate server simulation.

## Disconnect and cache

[Disconnect](disconnect.md) cuts player inputs, waits the configured grace period, and holds clean shutdown until hand-back is confirmed. A vanished owner is reclaimed after the heartbeat timeout from the server's latest applied state. Unapplied changes can be lost without a fixed one-second bound.

The cache stays on disk for rejoin and can be used as a local singleplayer copy, but background sync may still be incomplete. Local edits or ahead-of-server state never overwrite the real server during rejoin. Stable server/world identity, matching versions/mod hashes, and verified content determine reuse.
