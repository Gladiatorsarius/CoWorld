# Syncing order

## Purpose

Use this order for an accepted CoWorld session. A completed file download is never a grant to tick chunks. The real server and existing owners keep their authority until [handover](handover.md) commits each prepared unit. CoWorld uses the server's selected runtime in an isolated same-process environment, not the player's own mod set.

## 1. Manifest and validation

Exchange stable server/world identities, exact Minecraft/loader/runtime versions, and file paths, stored lengths and hashes. Check execution-environment and required-state support before starting simulation. Verify matching cached files and reuse them; do not download unchanged runtime files again merely because the player rejoined. A matching filename or hostname is not proof of compatibility.

## 2. Runtime first

Sync the complete `versions/` and `libraries/` folders and the exact server artifact selected by that runtime. Verify their bytes before use. These folders come before sub-server boot because they supply the intended server runtime and dependencies. Downloading them alone does not select or isolate them inside a running client; [bootstrap](client-server-bootstrap.md) must establish that environment before declaring it supported.

## 3. Server setup

Sync the **whole** `mods/` and **whole** `config/` folders. Do not reduce this to configs for currently installed mods or silently sanitize file contents. Keep them separate from the player's own runtime, mods and config. Datapacks in the world save belong to the next phase, not to a separate top-level datapack copy. All files remain subject to safe-path validation, integrity checks, disk allowance and sync throttling under [initial sync](sync.md).

## 4. World bootstrap and all player data

Sync consistent world metadata/settings, required registries and datapacks, and **all players' data, advancements and statistics** before authoritative ticking. For saves using `world/players/`, include everyone's files, not only the hosting player's. Apply the equivalent scope to other supported save layouts.

These copies do not grant authority to simulate those players. [Player authority](player-border-handover.md) still transfers separately. Shared mod databases and host services remain real-server-owned under the mod-state policy in [initial sync](sync.md); they are not independently writable replicas on every sub-server.

## 5. Required chunk snapshots and catch-up

Sync chunk-level snapshots for eligible requested ownership units, including every member of an indivisible redstone set. Use the nearest-ring/movement-direction ordering in [initial sync](sync.md#deterministic-chunk-order), with existing claims and occupied-chunk restrictions still enforced. Do not send an entire 32-by-32-chunk region merely to transfer one owned chunk.

Capture each snapshot at an identified completed tick/version and track subsequent changes while it travels. Install the snapshot and apply those changes in order. The base cache-coverage square and TPS-reduced ownership request are distinct; downloading a candidate does not mean it can transfer.

## 6. Commit before ticking

Runtime boot and preparation without authority may happen once their prerequisites exist. Authoritative ticking starts only after mandatory setup/world/player data, complete required chunk state, catch-up, readiness, final stop and [handover commitment](handover.md). The source remains owner until commitment. The destination starts from the next logical tick of the installed history, not an independently restarted world.

## 7. Background sync

Continue the remaining view chunks, then existing current-dimension background chunks, then other dimensions in the [deterministic queue order](sync.md#deterministic-chunk-order). Never generate terrain merely to fill the cache. Respect disk and bandwidth limits; if only optional background data cannot fit, pause background sync without deleting live state. Mandatory startup data cannot be deferred to this phase.

## 8. Continuous simulation cycle

This cycle runs alongside background sync, not after the entire world finishes downloading:

1. The real server sequences outside inputs and forwards them to the committed owner.
2. The owner applies ordered inputs and completes its simulation cycle.
3. It sends changed state and a completed-cycle heartbeat, or the heartbeat alone for a no-change cycle.
4. The real server validates and atomically installs the complete cycle, then acknowledges and relays accepted results.
5. Real-server-owned mod services receive their ordered accepted events without duplicated client-side host effects.

[Delta sync](delta-sync.md) owns input deduplication, heartbeat validation, versions, installed-state boundaries and missing-result recovery. Background transfer never authorizes a cached chunk to simulate independently.

## 9. Rejoin, changes and failure

Revalidate execution files, mandatory world/player prerequisites and active cached chunks after a fresh handshake. Keep only verified compatible completed content; replace mismatches with server state. Use a fresh snapshot when required resend history is unavailable. A cache never revives an old session or ownership grant.

Runtime/config/support changes and insufficient working space use the established hand-back or decline paths. A vanished owner follows [disconnect recovery](disconnect.md), which can lose results not yet installed on the real server. [Rejoin](rejoin.md), [initial sync](sync.md) and [bootstrap](client-server-bootstrap.md) define these detailed rules; this document defines their overall order.
