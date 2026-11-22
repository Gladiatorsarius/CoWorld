# CoWorld

CoWorld is a Fabric mod pair (server mod + client mod) for Minecraft 26.3 that lets a small server support many players. Players running the client mod can simulate nearby chunks on their own PCs and stream the results back. The real server keeps the authoritative stored world and coordinates ownership instead of ticking every offloaded chunk itself.

The requested ownership radius is `min(client simulation distance, max_ownership_radius)`, in chunks, forming an inclusive square around the player's current chunk. This is a candidate region, not guaranteed ownership: sync and handover must complete, existing owners keep overlapping claims, and connected redstone sets move only as whole units. See [handover](docs/handover.md), [arbitration](docs/multi-client-arbitration.md), and [redstone](docs/redstone.md).

In v1, all players stay connected to the real server. It relays inputs to an offloaded chunk's owner, stores that owner's resulting changes, and relays results to other players. It does not independently simulate the offloaded chunk.

Visitors do not need the CoWorld client mod. They cannot host a sub-server, but can enter already offloaded chunks through the same relay. CoWorld does not remove other mods' client requirements on a modded server. Routine handovers of an unmodded player's occupied chunk are blocked, moving or stationary, with four exceptions: clean owner shutdown, failure reclaim, required redstone consolidation, and transfer to the real server or another eligible sub-server when the old sub-server owner's player is strictly more than the configured default 12 chunks from that chunk, not when the incoming owner is distant. This is not a protection radius around every unmodded player. Measure this exception in the same dimension as `max(abs(dx), abs(dz))` between the old owner's current player chunk and the occupied chunk, using `floor(x/16)` and `floor(z/16)`, including negative coordinates. At the default threshold, 12 stays protected and 13 qualifies; the real server has no owner-player position for this exception. Keep required transfers' final pause minimal. See [handover](docs/handover.md) and [server configuration](docs/configs.md).

CoWorld is inspired by [MultiPaper](https://github.com/MultiPaper/MultiPaper), which spreads chunk ticking across multiple servers. CoWorld turns the idea around: the extra machines are the players' own PCs.

For the full design, see [docs/coremodel.md](docs/coremodel.md).
