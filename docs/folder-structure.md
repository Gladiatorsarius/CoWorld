# Folder structure

The repository contains three standalone Fabric mod projects side by side, next to the design docs: `Client Mod/`, `Server Mod/`, and `Common Mod/`. Each is a complete, independently buildable Fabric mod for Minecraft 26.3, generated with the [Fabric template generator](https://fabricmc.net/develop/template/). There is no root Gradle build. Build any project with its own wrapper: `./gradlew build` inside its folder. Client Mod and Server Mod reference Common Mod through a Gradle composite build (`includeBuild('../Common Mod')` in `settings.gradle`) and bundle its jar with Loom jar-in-jar, so the common code always matches the mod that ships it.

Common Mod holds the shared language the two endpoints must agree on byte for byte: the wire protocol, the canonical chunk hash, the execution-manifest format, shared identity/version types, and the configuration defaults. Shared behavior stays in the mod that performs it. Client Mod and Server Mod both split sources with `src/main` (no Minecraft client classes) and `src/client` (client-only classes); Common Mod and Server Mod use only `src/main`.

## Common Mod

`Common Mod/src/main/java/gladiatorsarius/coworld_common/`, environment `*`, loaded on both endpoints.

```
coworld_common/
├── CoWorldCommon.java                  # mod entrypoint
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

## Server Mod

`Server Mod/src/main/java/gladiatorsarius/coworld_server/`, environment `server`. Its `src/client` sourceset stays empty.

```
coworld_server/
├── CoWorldServer.java                  # mod entrypoint, wires everything
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

`Server Mod/src/main/resources/coworld_server.mixins.json` registers the two
server mixins, and `fabric.mod.json` lists that config.

## Client Mod

`Client Mod/src/main/java/gladiatorsarius/coworld_client/` (common sources, no client-only classes) and `Client Mod/src/client/java/gladiatorsarius/coworld_client/client/` (client-only). Environment `client`.

```
coworld_client/                         # src/main
├── CoWorldClient.java                  # common entrypoint
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

coworld_client/client/                  # src/client
├── CoWorldClientClient.java            # client entrypoint
├── ClientNetworkBridge.java            # client networking to the shared protocol
└── ShutdownProgressOverlay.java        # keeps the game open until hand-back confirms
```

## Notes

- `protocol/` and `hash/` exist only in Common Mod. Client Mod and Server Mod import them, so packet formats and the canonical chunk hash cannot drift apart.
- The sub-server lives in Client Mod's `src/main`: it runs a dedicated-server environment inside the client process and must not touch client-only classes. Only the thin `ClientNetworkBridge` sits in `src/client`, because Fabric's client networking API is client-only.
- Each mod folder carries the template's own `README.md`, `LICENSE` (CC0), `.gitignore`, and `.gitattributes`, local to that project.
- The builds need JDK 25 (`./gradlew build` in any of the three folders). Template placeholders (description, authors, contact links) are unchanged from the generator.
