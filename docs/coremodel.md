# Core model: live chunk simulation offloading

CoWorld is a Fabric server/client mod pair for Minecraft 26.3. A player running the client mod can host a sub-server inside their Minecraft process to simulate nearby chunks. The real server keeps the authoritative stored world and coordinates ownership.

Players without CoWorld connect normally and cannot host a sub-server. They stay connected to the real server even inside another player's offloaded chunks. CoWorld is optional for visitors, but a server's other mods can impose their own client requirements.

## Joining

1. [Handshake](handshake.md) checks versions, offloading preferences, capacity, and ping. Accept starts sync, not ownership.
2. [Initial sync](sync.md) sends a hashed manifest and files from the server's mods folder, then world data nearest the player first, with the rest in the background. These are the sub-server's mods, not the player's own client-mod set.
3. [Bootstrap](client-server-bootstrap.md) starts the sub-server in the same Minecraft process using its isolated per-server/world cache. Synced data is loaded without authoritative ticking.
4. [Handover](handover.md) transfers eligible caught-up chunks or whole redstone sets. Readiness is acknowledged while the old owner keeps ticking; only final catch-up and commit require a brief stop at a later tick boundary.
5. After takeover, [delta sync](delta-sync.md) carries ordered per-tick results and outside inputs. [Rejoin](rejoin.md) revalidates compatible cached content after every fresh handshake, without restoring old ownership grants.

## Ownership

The requested radius is `min(client simulation distance, max_ownership_radius)`, in chunks. It defines an inclusive square centered on the player's current chunk, not automatic ownership of every loaded chunk. Radius zero means no offloading.

Every chunk has exactly one authoritative owner. Only that owner's results modify the authoritative world. Existing claims remain first come, first served under [multi-client arbitration](multi-client-arbitration.md). Connected redstone chunks stay whole and may extend beyond the requested square within the configured set-size cap. Chunks that are not ready, eligible, or committed stay with their current owner.

Use `floor(x/16)` and `floor(z/16)` for position-to-chunk membership, including negative coordinates. A chunk includes its lower boundary and excludes its upper one. Entity/player position, not bounding-box overlap, determines membership.

Routine chunk handovers in either direction are blocked for the chunk an unmodded player occupies, moving or stationary, not a radius around that player. One occupied member pins a whole redstone set. Exceptions are clean owner shutdown, failure recovery, required conflicting/oversized redstone consolidation, and transfer to the real server or another eligible sub-server when the old sub-server owner's player is more than `vanilla_player_handover_release_distance` chunks from the occupied chunk (default 12, strict `>`, not measured from the incoming owner). [Handover](handover.md#triggers) defines the square chunk-distance and set rules. An unmodded player can still walk into an already offloaded chunk without moving the chunk's ownership.

## Networking and conflicts

In v1, the real server relays inputs to the current owner, stores the resulting changes, and relays results to other players. It does not simulate offloaded chunks or merge independently produced block states. Outside effects enter the owner's simulation as inputs, not competing block writes. Nearby unowned caches receive their actual owner's results; distant caches are checked before reuse.

The planned v2 adds opt-in direct P2P per owner with relay fallback. It is not required for v1. General client prediction is not part of this model.

## Borders and explicit shadow exceptions

[Player border handover](player-border-handover.md) predicts crossings and prepares a non-authoritative player warm standby; only one side publishes player results. [Other entities](entity-handover.md) transfer complete state after the source's final tick, before the destination's next logical tick. [Redstone](redstone.md) uses connected ownership sets so a contraption never intentionally ticks under two owners.

Optional [handover verification](handover.md#optional-verification) shadows chunks for debugging. A genuine mismatch selects the old shadow state, which can roll back results. This and player warm standby are explicit non-authoritative simulation exceptions, not shared ownership or normal duplicate server simulation.

## Disconnect and cache

[Disconnect](disconnect.md) cuts player inputs, waits the configured grace period, and holds clean shutdown until hand-back is confirmed. A vanished owner is reclaimed after the heartbeat timeout from the server's latest applied state. Unapplied changes can be lost without a fixed one-second bound.

The cache stays on disk for rejoin and can be used as a local singleplayer copy, but background sync may still be incomplete. Local edits or ahead-of-server state never overwrite the real server during rejoin. Stable server/world identity, matching versions/mod hashes, and verified content determine reuse.
