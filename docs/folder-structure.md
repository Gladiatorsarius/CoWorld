# Folder structure

The repository contains one Fabric mod project, `CoWorld/`, next to the design docs. It is a complete, independently buildable Fabric mod for Minecraft 26.3, generated with the [Fabric template generator](https://fabricmc.net/develop/template/). Build it with its own wrapper: `./gradlew build` inside `CoWorld/`. One jar ships all three roles; Loom's `splitEnvironmentSourceSets()` separates `src/main` (no Minecraft client classes) from `src/client` (client-only classes).

The `common` package holds the shared language both endpoints must agree on byte for byte: the wire protocol, the canonical chunk hash, the execution-manifest format, shared identity/version types, and the configuration defaults. Shared behavior stays in the package that performs it. `server` holds real-server logic; `client` holds player-side logic, and only genuinely client-only classes go into `src/client`.

## Common package

`CoWorld/src/main/java/gladiatorsarius/coworld/common/`, loaded on both endpoints. `CoWorldCommon` is the single `main` entrypoint and registers the three handshake payload codecs once for both sides.

```
common/
├── CoWorldCommon.java                  # mod entrypoint, shared payload registration
├── protocol/                           # wire format, one definition for both sides
│   ├── CoWorldPackets.java             # every packet: handshake, sync, deltas, handover, claims
│   └── ProtocolVersion.java            # wire-format version, must match (handshake.md)
├── hash/                               # rejoin.md format 1
│   ├── CanonicalChunkHasher.java       # format-1 canonical encoding + SHA-256
│   └── SnapshotAdapter.java            # stub only; adapter schema intentionally unspecified
├── manifest/                           # sync.md execution manifests
│   ├── ExecutionManifest.java          # root kind, path, byte length, SHA-256, canonical sort
│   ├── ManifestCodec.java              # serialize/parse manifests
│   └── SafePathValidator.java          # rejects absolute paths, "..", symlinks, escapes
├── types/                              # shared identities and version records
│   ├── ChunkId.java                    # dimension + chunk x/z, floor(x/16) math
│   ├── ChunkVersion.java               # chunk version per completed tick's change set
│   ├── OwnerGeneration.java            # owner generation, changes on handover
│   ├── TransferUnit.java               # chunk or whole redstone set identity
│   ├── SessionId.java                  # accepted offloading session identity
│   └── CycleFrontier.java              # per-unit completed-tick/version progress
├── config/
│   └── CoWorldDefaults.java            # every default from configs.md in one place
├── entity/
│   └── EntityGroupMath.java            # group membership, lead = lowest unsigned UUID
└── redstone/
    └── BandSummary.java                # two-column border-band summary record + codec
```

## Server package

`CoWorld/src/main/java/gladiatorsarius/coworld/server/`. `CoWorldServer` is the `server` entrypoint, so it runs only on a dedicated server.

```
server/
├── CoWorldServer.java                  # server entrypoint, wires everything
├── config/                             # configs.md
│   ├── CoWorldConfig.java              # loads and validates all server settings
│   └── SyncTokenBucket.java            # per-client sync_speed_limit token bucket
├── handshake/                          # handshake.md
│   ├── HandshakeHandler.java           # Hello/Accept/Decline exchange, timeouts, probe drain
│   ├── PingProber.java                 # three vanilla keepalive probes, network-thread RTT, median
│   └── SessionManager.java             # accepted session IDs, planned high-ping tracking
├── log/                                # configs.md log_level
│   └── CoWorldLog.java                 # tagged normal/debug logging gate
├── mixin/                              # network-thread keepalive interception
│   ├── ConnectionSendMixin.java        # records send time at the Netty write
│   └── ServerKeepAliveMixin.java       # consumes owned echoes, forwards other IDs to vanilla
├── sync/                               # sync.md
│   ├── ServerWorldIdentity.java        # creates and persists server/world UUIDs
│   ├── ExecutionManifestBuilder.java   # hashed manifests of versions/libraries/mods/config
│   ├── FileSyncSender.java             # streams folder files, safe paths, throttled
│   ├── WorldDataSender.java            # world metadata, datapacks, all player data
│   ├── ChunkSnapshotter.java           # end-of-tick snapshots, starts delta tracking
│   └── ModStateClassifier.java         # mod-state policy per required mod
├── syncorder/                          # syncing-order.md
│   ├── SessionSyncCoordinator.java     # drives phases 1-9 per accepted session
│   └── BackgroundSyncQueue.java        # deterministic ring/movement chunk order
├── ownership/                          # coremodel.md
│   ├── OwnershipLedger.java            # committed owner + generation per chunk
│   └── OwnershipSquare.java            # radius math, TPS reduction, claim clipping
├── bootstrap/                          # client-server-bootstrap.md, server side
│   ├── SubServerRegistry.java          # live sub-servers, max_sub_servers cap
│   └── TpsGuard.java                   # windowed TPS check, radius reduction
├── deltasync/                          # delta-sync.md
│   ├── InputSequencer.java             # per-chunk input sequences, action IDs, dedup
│   ├── CycleInstaller.java             # validates and atomically installs one cycle
│   ├── HeartbeatValidator.java         # only advancing per-cycle heartbeats count
│   └── ResultBroadcaster.java          # relays owner results to other players
├── handover/                           # handover.md
│   ├── HandoverCoordinator.java        # one-at-a-time transfer, commit/abort
│   ├── TransferReservation.java        # reserves a unit during an attempt
│   └── VerificationShadowManager.java  # optional post-commit shadow comparison
├── entity/                             # entity-handover.md
│   ├── EntityOwnershipTracker.java     # entity/group to chunk-owner mapping
│   ├── EntityGroupGraph.java           # riding/leash components (common math)
│   └── EntityTransferCoordinator.java  # final-tick group transfers via real server
├── playerborder/                       # player-border-handover.md
│   ├── PlayerAuthorityTracker.java     # which simulator owns each player
│   ├── CrossingPredictor.java          # speed/direction prediction, warm standby trigger
│   └── TeleportTransferHandler.java    # teleport boundary transfer
├── redstone/                           # redstone.md
│   ├── BorderBandIndex.java            # versioned band summaries per boundary
│   ├── DependencyRuleRegistry.java     # versioned rule families per MC/mod set
│   ├── RedstoneSetGraph.java           # connected components, set membership, size cap
│   └── ConsolidationCoordinator.java   # merge/split/oversize consolidation onto server
├── rejoin/                             # rejoin.md
│   ├── CacheCompatibilityValidator.java # cache metadata vs versions/manifest hashes
│   └── ChunkHashComparer.java          # client hashes vs authoritative state
├── disconnect/                         # disconnect.md
│   ├── HeartbeatWatchdog.java          # disconnect_timeout_ticks from last heartbeat
│   ├── OwnershipReclaimer.java         # reclaims a vanished owner's chunks
│   └── HandBackTracker.java            # confirms clean hand-back after grace ticks
└── arbitration/                        # multi-client-arbitration.md
    └── ClaimArbiter.java               # first-come-first-served, nearer-player tiebreak
```

`CoWorld/src/main/resources/coworld.mixins.json` registers the two server keepalive mixins. The mod's one `fabric.mod.json` lists it with `"environment": "server"`.

## Client package

`CoWorld/src/main/java/gladiatorsarius/coworld/client/` (src/main sources, no client-only classes) and `CoWorld/src/client/java/gladiatorsarius/coworld/client/` (client-only). The `client` entrypoint runs only on the client.

```
client/                                 # src/main
├── config/
│   └── ClientSettings.java             # loads disk allowance and offloading preference
├── handshake/                          # handshake.md
│   └── HelloSender.java                # builds Hello with versions, allowance, cache metadata
├── cache/                              # sync.md storage/identity, rejoin.md
│   ├── CacheDirectoryManager.java      # per server/world UUID cache dirs, pins
│   ├── ManifestValidator.java          # revalidates cached files against manifest hashes
│   ├── FileSyncReceiver.java           # safe-path atomic writes into the cache
│   └── CacheEvictor.java               # LRU eviction, use counters, disk allowance
├── sync/                               # sync.md, syncing-order.md client side
│   ├── SyncOrchestrator.java           # client side of phases 1-9
│   ├── ChunkInstallPipeline.java       # installs snapshot, then ordered deltas
│   └── BackgroundSyncManager.java      # view/background chunks, pauses on disk pressure
├── subserver/                          # client-server-bootstrap.md
│   ├── SubServerBootstrap.java         # starts the in-process sub-server without ownership
│   ├── IsolatedRuntimeLoader.java      # isolated server runtime/mod loading, mechanism open
│   ├── SubServerLifecycle.java         # startup, active, hand-back, shutdown stages
│   └── SubServerTpsMeter.java          # own completed ticks per elapsed second
├── simulation/                         # coremodel.md
│   └── OwnedChunkTicker.java           # ticks only committed chunks
├── deltasync/                          # delta-sync.md
│   ├── DeltaProducer.java              # per-tick change records with versions/generation
│   ├── HeartbeatEmitter.java           # exactly one heartbeat per completed cycle
│   ├── InputApplier.java               # applies sequenced outside inputs at tick boundary
│   ├── RemoteChunkApplier.java         # applies other owners' results to cached chunks
│   └── ResendHistory.java              # bounded resend history (ticks + bytes)
├── handover/                           # handover.md
│   ├── HandoverParticipant.java        # source/destination roles, readiness, final stop
│   └── ShadowVerifier.java             # optional non-authoritative shadow after commit
├── entity/                             # entity-handover.md
│   ├── EntityGroupTracker.java         # local membership/lead recompute (common math)
│   └── EntityTransferHandler.java      # captures/installs complete entity state
├── playerborder/                       # player-border-handover.md
│   ├── PlayerStandbySimulator.java     # warm standby for predicted crossings
│   └── PlayerAuthorityHandler.java     # sole-authority ticking, final-state handoff
└── redstone/                           # redstone.md
    └── BandSummaryProducer.java        # produces band summaries from owned chunks

client/                                 # src/client
├── CoWorldClientClient.java            # client entrypoint
├── ClientNetworkBridge.java            # client networking to the shared protocol
└── ShutdownProgressOverlay.java        # keeps the game open until hand-back confirms
```

## Notes

- `protocol/` and `hash/` exist only in the common package. The server and client packages import them, so packet formats and the canonical chunk hash cannot drift apart.
- The sub-server lives in the client package's `src/main`: it runs a dedicated-server environment inside the client process and must not touch client-only classes. Only the thin `ClientNetworkBridge` sits in `src/client`, because Fabric's client networking API is client-only.
- One `fabric.mod.json` with environment `*` declares three entrypoints: `main` (`CoWorldCommon`), `server` (`CoWorldServer`) and `client` (`CoWorldClientClient`). One mixin config holds the server keepalive mixins.
- `CoWorld/` carries the template's own `README.md`, `LICENSE` (CC0), `.gitignore`, and `.gitattributes`, local to the project.
- The build needs JDK 25 (`./gradlew build` in `CoWorld/`). Template placeholders (description, authors, contact links) are unchanged from the generator.
