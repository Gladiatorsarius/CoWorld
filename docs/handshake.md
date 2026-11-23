# Handshake

## Purpose

Immediately after normal login, negotiate CoWorld over custom packets on the existing Minecraft connection. This requires no separate account or connection. Declining CoWorld means normal play, not a kick, and does not bypass the server's other mod requirements.

## Exchange

The concrete V1 handshake payload IDs are `coworld:hello`, `coworld:ping_probe`,
`coworld:ping_echo`, `coworld:accept`, and `coworld:decline`. Their encodings and
bounds are implemented once in `CoWorldPackets` in Common Mod.

1. The client sends Hello: CoWorld version, protocol version, offloading preference, disk allowance, client simulation distance, and whether it has a previous cache with its identity/compatibility metadata.
2. The real server sends three ping probes in sequence. The client immediately echoes each unchanged; the server measures each RTT and takes the median. A missing, invalid, or late reply declines offloading. All three replies are required.
3. Accept supplies the agreed protocol, requested ownership radius in chunks, sync speed limit, and a new session ID. The radius is `min(client simulation distance, max_ownership_radius)`. Accept authorizes sync, not ticking or chunk ownership.
4. Decline supplies a reason: offloading disabled, client opted out, capacity reached, ping above the cap, incompatible versions, or failed negotiation. The client continues normal play.
5. After Accept, validate the execution manifest and cache metadata. A usable matching cache takes the [rejoin](rejoin.md) path; otherwise use [initial sync](sync.md). No chunks transfer during the handshake itself.

## Compatibility and cache validity

The protocol version is a wire-format compatibility identifier, separate from the CoWorld release version. V1 requires matching CoWorld and protocol versions. There is no implicit downgrade or selection based only on the lowest version number. An unsupported live protocol/version declines offloading.

Cache reuse additionally requires matching Minecraft version, server execution manifest hashes, and cache-format version. The server/world identity must match, and cached content must pass integrity validation. A cache from an older or otherwise incompatible version goes through full initial sync; it does not make an otherwise compatible live connection fail.

A chunk hash mismatch means that chunk's cached content differs from the server, not that the protocol is incompatible. [Rejoin](rejoin.md) replaces it with the server's chunk. The cache declaration in Hello is a claim to validate, not proof of usability. Declare either no previous cache, or the stable server UUID and world UUID, cached CoWorld and Minecraft versions, cache-format/hash-format versions, the snapshot-adapter/schema identity once specified, and the cached server execution manifest with its file hashes. These identify the compatibility record, not an old ownership grant. Validate them against current server metadata after Accept. Send completed chunk inventory and per-chunk hashes through [rejoin](rejoin.md), not as proof that the whole cache is current in Hello.

## Disk allowance

Hello's disk allowance is a nonnegative integer number of bytes. It caps all CoWorld cache data across server/world directories: mods, config, versions, libraries, player/world state, compatibility/integrity manifests, and temporary or incomplete writes. Account for stored file lengths, not compressed network sizes. Zero provides no cache budget and declines offloading; it never means unlimited. Missing, negative, or noninteger allowance is invalid and declines offloading without kicking the player.

Reserve space within the allowance before writing, including simultaneous old and replacement files during atomic replacement. Use the existing [inactive-cache eviction and insufficient-space rules](sync.md#eviction-and-insufficient-space). The allowance cannot override actual free disk space; an allocation/write failure leaves normal play on the real server and uses the existing sync or recovery path.

## Ping and deadlines

`max_ping` is in milliseconds, default 70. A median equal to the cap passes; above it declines offloading. During an accepted session, built-in player ping above the cap for `high_ping_ticks` (default 100) triggers hand-back. The counter resets at or below the cap.

`handshake_hello_timeout_ticks` and `ping_probe_timeout_ticks` both default to 100 real-server ticks, about 5 seconds at 20 TPS. The first starts at login; the second applies separately to each outstanding probe. Missing Hello means normal non-offloading play. CoWorld timeout or version failure must not itself crash or kick the player. A connection that ends mid-handshake has changed no chunk ownership.

## Session state

Every join runs a fresh handshake. Pending probes and negotiation state are connection-scoped and discarded on disconnect. The accepted session ID identifies the live offloading/relay session; it is not a persistent cache key and is not resumed after reconnect. Accept's requested radius is a candidate-area limit for sync and later offers, not an allocation of chunks. Existing claims, whole redstone sets, occupancy, current TPS reduction, readiness and coordinated commitment decide actual ownership. The real server remains owner of server-owned chunks until a handover commits; other committed owners likewise retain their claims.

World/mod caches persist under stable server and world identities, as described in [sync](sync.md). If client simulation distance changes, the client reports it and the real server recomputes the requested radius, using normal [handover](handover.md) for eligible changes instead of reconnecting.
