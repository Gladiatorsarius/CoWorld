# Initial sync

For the complete startup and continuous-sync sequence, see [syncing order](syncing-order.md).

## Purpose

After Accept, sync the server's complete mods, config, versions and libraries folders and required world data into an isolated per-server cache. The player's own client mods are not the sub-server's mod set. The player keeps using the real server while sync runs; no downloaded chunk becomes owned merely because it arrived.

## Order

Follow [syncing order](syncing-order.md) for the complete phase sequence: validate manifests, finish `versions/` and `libraries/` plus the selected server artifact, finish whole `mods/` and `config/`, then mandatory world metadata/datapacks and all player data. Prepare required chunk snapshots/catch-up and commit handover before ticking. Remaining view and background chunks continue alongside the live change stream. No mandatory bootstrap phase can be deferred to background.

At radius zero, no ownership is requested. Existing claims can clip the square; connected redstone sets can extend it. See [handover](handover.md) and [redstone](redstone.md).

## Deterministic chunk order

Use one ordering for initial sync, rejoin, and newly needed chunks during movement. Complete the execution manifest and all mandatory bootstrap/player data first. Sync required ownership chunks before the remaining view-area chunks, and those before background world chunks; whole-set handover requirements still must be ready before commitment. Ownership candidates use the effective TPS-reduced square plus required whole-set members; existing committed claims still limit which can actually transfer. View eligibility is the inclusive square in the current dimension with radius `min(client view distance, server view-distance cap)`, using the [rejoin active-area boundaries](rejoin.md#active-area-and-hashes). View-only data is cached without local authoritative simulation. Background eligibility is existing stored world chunks outside these higher-priority classes, in this or another dimension; do not generate terrain just to populate the cache. Disk allowance still limits background progress. Within each priority class, sort pending chunks in the current dimension by this ascending tuple:

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

Store a separate cache directory under the game directory for each stable server UUID + world UUID. It contains separate mods, config, versions, libraries and world data plus an integrity/compatibility manifest. Dimension and chunk X/Z identify chunks within it. A session ID or a matching hostname alone is not proof that cached world data belongs to this world.

The manifest records CoWorld, Minecraft and cache-format versions, hash format and the [snapshot-adapter/schema identity](rejoin.md#snapshot-adapter-schema-status), the server execution manifest, and completed verified content. Only atomically completed writes with matching hashes are reusable. Interrupted or unverified temporary data is not marked complete. Exact directory spelling is an implementation detail; neither the real server's live disk path nor another server's cache is the sub-server's data directory.

The client's [disk allowance](handshake.md#disk-allowance) caps all cached storage, including temporary writes; it is not only a world-data limit. Apply the storage policy below across all server/world cache directories.

## Eviction and insufficient space

One eviction candidate is one complete server UUID + world UUID cache directory, including its mods, config, versions, libraries, world files, metadata and temporary files. Never evict individual completed chunks or mod files to make a live cache appear complete. Pin the directory while any connection, sub-server, local singleplayer use, or outstanding writer uses it. A pin lasts through confirmed hand-back and shutdown, not just the disconnect button. Multiple users of the same directory retain separate pins; it is inactive only after the last pin is released.

Maintain last-use metadata on opening, using and releasing a cache. Serialize these events with an increasing persisted use counter; smaller counters mean older use. Choose unpinned directories in oldest-use order, then unsigned server UUID and world UUID as deterministic tie-breaks. A never-used directory sorts before used directories. A metadata failure makes that directory non-evictable until repaired, not implicitly oldest.

Use one serialized allowance ledger shared by all cache writers. Count stored file lengths, including manifests, copies and incomplete writes. Before allocating a write, reserve its full stored length while still counting old files that will coexist with it. Increase the reservation before any growth, and release it only after confirmed deletion or conversion into counted completed bytes. Check actual filesystem available space as well; an allowance is not a disk-space guarantee. Do not count a failed deletion as reclaimed space. Remove abandoned, unverified temporary writes with no active writer first, then evict whole inactive caches until the required reservation fits. Recheck space and handle allocation/write errors even after a successful reservation.

If required working data cannot fit, decline startup. During active ownership, stop new ownership offers and request normal hand-back, keeping live state in memory and retaining the active cache until hand-back confirms. A failed write does not authorize deleting authoritative state, dropping ownership, or reporting a successful hand-back. If the process or connection fails, use [disconnect recovery](disconnect.md); unapplied state can still be lost.

If only optional background data cannot fit, pause background transfer without disabling an otherwise supported active sub-server. Retry when allowance, free space or cache-use state changes, not on a tight failed-write loop. Background transfer may remain incomplete indefinitely. No disk allowance promises a complete offline world.

## Mods, config, runtime and world scope

Sync every regular file recursively under the real server's actual `mods/`, `config/`, `versions/` and `libraries/` roots, including hidden files and files not selected for loading. Do not filter client-only files or arbitrary config entries out of this transfer. The manifest records root kind (`mods`, `config`, `versions` or `libraries`), normalized relative path, stored byte length and SHA-256 content hash. Sort entries by root kind and unsigned UTF-8 path bytes. Nested JARs remain bytes within their containing JAR, not a second independently rewritten file. Runtime dependency discovery does not authorize leaving other folder files out of the copy.

Reject absolute paths, `..` segments, symlinks, special files and paths that escape the selected source root or destination cache root. Reject duplicate normalized paths and platform case/normalization collisions before writing. A required unsafe entry declines offloading rather than following it or silently dropping it. Never merge these files into the player's own mods/config/runtime folders. Exact directory names on the server can be configured, but the selected roots and manifest must be explicit.

The whole-folder policy can send secrets or administrative settings stored inside these selected roots to every accepted offloading client. The server operator must treat these folders as disclosed to those clients and remove or relocate private material before enabling offloading. CoWorld does not claim that copied configuration is sanitized. Files copied for compatibility are not automatically permission to open listeners, contact external services or perform administrative side effects in the sub-server.

Capture a consistent manifest/files revision: freeze the selected bytes during transfer, or restart validation if a file changes. Hash-check before publishing a complete revision atomically. Any mods/config/runtime revision change invalidates the previous execution-environment match; hand back affected ownership before restarting with the changed environment. The runtime selection includes its exact Minecraft/server artifact and loader/library revision, resolved through the version metadata and manifest hashes rather than filenames alone. If its required artifact is stored outside the four selected roots, include that explicitly identified artifact as a manifest entry; do not copy unrelated installation files. All runtime bytes count against disk allowance and sync throttling. Downloading them does not prove that the same-process loader has selected and isolated them. A copied client-only JAR stays in the cache even if the dedicated-server loading rules do not execute it.

Datapacks under the world save belong to world sync, not a separate top-level server datapack directory. Required world datapacks, registries, dimension definitions and simulation settings must be ready before any chunk that depends on them starts ticking. Continue existing stored chunk data in the documented priority order; do not copy live region files concurrently and call them completed-tick snapshots. World metadata that affects simulation comes from the server's consistent authoritative revision; chunk state uses snapshots and deltas. Logs, backups, unselected executables, unrelated installation files and files outside the selected roots are not implicitly included merely because the server uses them. The complete transferable-state boundary is defined in [handover](handover.md#complete-state-scope).

## Mod-state policy

Copying a mod/config/runtime file is not permission to run every service or duplicate every writable database. Before offloading, classify each required mod's behavior and state under a declared compatibility contract:

| State or behavior | Location and sync policy |
| --- | --- |
| Per-chunk/entity simulation and transferable runtime state | Run only under its committed simulator. Include full persistent/transient state in snapshots, deltas, handovers and recovery under the [complete-state contract](handover.md#complete-state-scope). |
| World-global shared state | Keep its authoritative copy on the real server. Supply the supported ordered inputs/results or explicit partition/transfer contract. Do not clone an independently writable global database into every owner. |
| Audit/history databases and administrative logging | Keep the database and writes on the real server. Owners report the relevant event intents with their identified result cycles; publish service effects only after the corresponding state cycle is accepted. Reject superseded generations and deduplicate retransmissions. A snapshot must not replay historical log entries as new events. This does not promise recovery of events never received/applied by the real server. |
| Network services, profiling and host administration | Keep real-server listeners, external-service integrations and server administration on the real server. A copied configuration must not cause a second port bind, duplicate webhook, duplicate admin task or other repeated host side effect. Offloading support requires a declared safe execution mode or adapter for a required mod; copying or omitting its database is not such a mode. |
| Unknown required mod behavior | Keep affected chunks on the real server. If the dependency cannot be localized, decline offloading for the world until supported. Never silently omit required simulation state or guess that saved NBT is complete. |

This classification does not narrow the complete `mods/`, `config/`, `versions/` or `libraries/` folder-copy scope, and does not sanitize their contents. Files in those roots still transfer even if the corresponding behavior remains server-owned. World-level audit/history databases are not required chunk state and do not join the background chunk stream merely because they sit under the world save. A supported local singleplayer copy may lack these real-server services; it is not proof that the whole server installation was reproduced.

Keep global-service ownership separate from chunk authority. If an accepted simulation result needs an administrative side effect, route that effect to the real server's service, not to another independently running service on the client. A mod with no safe distinction between required simulation and forbidden duplicate host behavior is not offload-compatible until that distinction is implemented. Native field mappings and exact mod adapters still require the [versioned adapter manifest](rejoin.md#snapshot-adapter-schema-status).

## Failure and resume

Before ownership transfer, a sync failure leaves ownership unchanged and normal play continues through the real server. A reconnect runs a fresh handshake, revalidates completed cached files/chunks and their compatibility, and resumes from verified content. It never trusts an interrupted write or resumes an old ownership/session grant.

Once chunks have transferred, a later transfer/sync failure does not silently change their owner. Use [handover](handover.md) or [disconnect recovery](disconnect.md). Locally cached world data can be retained for rejoin or singleplayer, but local edits never overwrite the real server during reconciliation.
