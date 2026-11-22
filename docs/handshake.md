# Handshake

## Purpose

Immediately after normal login, negotiate CoWorld over custom packets on the existing Minecraft connection. This requires no separate account or connection. Declining CoWorld means normal play, not a kick, and does not bypass the server's other mod requirements.

## Exchange

1. The client sends Hello: CoWorld version, protocol version, offloading preference, disk allowance, client simulation distance, and whether it has a previous cache with its identity/compatibility metadata. Direct P2P preference is for v2, not the v1 relay path.
2. The real server sends three ping probes in sequence. The client immediately echoes each unchanged; the server measures each RTT and takes the median. A missing, invalid, or late reply declines offloading. All three replies are required.
3. Accept supplies the agreed protocol, requested ownership radius in chunks, sync speed limit, and a new session ID. The radius is `min(client simulation distance, max_ownership_radius)`. Accept authorizes sync, not ticking or chunk ownership.
4. Decline supplies a reason: offloading disabled, client opted out, capacity reached, ping above the cap, incompatible versions, or failed negotiation. The client continues normal play.
5. After Accept, validate the mods manifest and cache metadata. A usable matching cache takes the [rejoin](rejoin.md) path; otherwise use [initial sync](sync.md). No chunks transfer during the handshake itself.

## Compatibility and cache validity

V1 requires matching CoWorld and protocol versions. There is no implicit downgrade or selection based only on the lowest version number. An unsupported live protocol/version declines offloading.

Cache reuse additionally requires matching Minecraft version, server mods manifest hashes, and cache-format version. The server/world identity must match, and cached content must pass integrity validation. A cache from an older or otherwise incompatible version goes through full initial sync; it does not make an otherwise compatible live connection fail.

A chunk hash mismatch means that chunk's cached content differs from the server, not that the protocol is incompatible. [Rejoin](rejoin.md) replaces it with the server's chunk. The cache declaration in Hello is a claim to validate, not proof of usability.

## Ping and deadlines

`max_ping` is in milliseconds, suggested default 150. A median equal to the cap passes; above it declines offloading. During an accepted session, built-in player ping above the cap for `high_ping_ticks` (default 100) triggers hand-back. The counter resets at or below the cap.

`handshake_hello_timeout_ticks` and `ping_probe_timeout_ticks` both default to 100 real-server ticks, about 5 seconds at 20 TPS. The first starts at login; the second applies separately to each outstanding probe. Missing Hello means normal non-offloading play. CoWorld timeout or version failure must not itself crash or kick the player. A connection that ends mid-handshake has changed no chunk ownership.

## Session state

Every join runs a fresh handshake. Pending probes and negotiation state are connection-scoped and discarded on disconnect. The accepted session ID identifies the live offloading/relay session; it is not a persistent cache key and is not resumed after reconnect.

World/mod caches persist under stable server and world identities, as described in [sync](sync.md). If client simulation distance changes, the client reports it and the real server recomputes the requested radius, using normal [handover](handover.md) for eligible changes instead of reconnecting.
