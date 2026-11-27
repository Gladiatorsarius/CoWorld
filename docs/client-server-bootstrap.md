# Client server bootstrap

For the complete startup and continuous-sync sequence, see [syncing order](syncing-order.md).

## Start conditions

After an accepted [handshake](handshake.md), sync the complete server mods/config/versions/libraries folders, all player files and required world metadata/chunks through [initial sync](sync.md) or [rejoin](rejoin.md). Start a sub-server inside the running Minecraft process, like an integrated singleplayer server, without giving it chunk ownership yet.

Required chunks must have a verified snapshot plus ordered changes through an identified completed tick/version. A completed download or hash match alone is not enough. Existing owners keep ticking until [handover](handover.md) commits each eligible chunk or whole redstone set.

## Region and data

The requested region is an inclusive square around the player's current chunk, with radius `min(client simulation distance, max_ownership_radius)` in chunks, less the [session-local TPS reduction](configs.md#performance-and-verification), clamped at zero. Evaluate a connected redstone set as one unit: touching the square can make the whole set a candidate, including members outside the square, but does not override an existing owner's claim. If a member is already committed to another sub-server, do not take an unclaimed fragment of that set. Keep the existing whole-set owner; a topology change joining different owners follows required [redstone consolidation](redstone.md#topology-changes-and-moving-machines). Apply the set-size cap and occupied-chunk restrictions before offering a transfer. An unmodded player occupying a chunk blocks routine transfer of that chunk, whether moving or stationary, subject to the four [handover exceptions](handover.md#triggers). The sub-server ticks only its actual committed ownership, not the whole square or every chunk loaded in its cache.

Load world data and the server-sent mods from this connection's isolated server/world cache directory under the game directory. The cache has separate mods/config/runtime/world data and integrity metadata; [sync](sync.md#storage-and-identity) defines its identity and reuse rules. It is not the live real-server path. The player's own client mods remain separate from the sub-server mod set.

The rest of the world can keep streaming in the background. Requiring every distant chunk before startup would delay offloading without making the owned region safer.

## Same-process mod environment

CoWorld is one mod with server and client sides. Together they are responsible for creating the sub-server's isolated dedicated-server-compatible environment inside the existing Minecraft process. Merely copying JARs or starting a normal integrated server is not that mechanism: Fabric's physical server-only environment excludes integrated servers, and normal Loader resolution permits one version per mod ID.

Preserve the same-process requirement. Before declaring a mod set supported, CoWorld must provide and validate its loading/isolation mechanism for that exact set, including server entrypoints, mixins, dependencies, registries, exact selected server runtime/libraries and separate configuration paths, without mutating the running player's own client environment. This document assigns that requirement to CoWorld's combined sides; it does not specify an already working alternate loader. Incompatible or unsupported sets decline offloading and keep normal play on the real server. A complete folder download is not a compatibility proof. See [Fabric metadata](https://docs.fabricmc.net/develop/loader/fabric-mod-json) and [Loader constraints](https://docs.fabricmc.net/develop/loader/).

## Lifecycle and networking

The stages are negotiation, sync/revalidation, startup without ownership, handover, active simulation, then hand-back and shutdown. Accepted sessions do not tick until handover completes. In v1, player inputs and simulation results route through the real server, including other players visiting the owned chunks; it stores and relays results without ticking those chunks itself.

On clean disconnect, stop the player's inputs, wait `disconnect_grace_ticks` (20), then hand back ownership. The mod keeps the game open until the server confirms completion, even on a slow connection. See [disconnect](disconnect.md). Each connection uses separate cached server/world data; no sub-server runs after its Minecraft process exits.

## TPS guard

`min_subserver_tps` defaults to 20. Measure completed sub-server ticks per actual elapsed second over each `subserver_tps_window_ticks` window (default 100 real-server ticks). Equality passes; a lower rate fails. Each failed window reduces the effective requested radius by one chunk. At zero, return remaining ownership, using the transfer and occupied-chunk rules rather than silently dropping state. After every reduction, recompute candidates using the same claim clipping, whole-set expansion, size cap, and occupied-chunk checks. Never slice a redstone set at the smaller square boundary: a qualifying set may still extend outside it, while a set no longer requested returns whole when eligible. Until that hand-back commits, its existing owner keeps authority. At zero no set remains requested for offloading.

## Failure

If a sub-server-only crash can be reported while the client connection survives, the real server invalidates its ownership and immediately reclaims from the latest applied state. If the entire game/connection vanishes, use the 600-tick heartbeat timeout. In-flight unapplied changes can be lost; neither a crash-free client nor uninterrupted play after a whole-process crash is guaranteed. Cache reconciliation always uses the real server's state.
