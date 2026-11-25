# Handshake

## Purpose

Immediately after normal login, negotiate CoWorld over custom packets on the existing Minecraft connection. This requires no separate account or connection. Declining CoWorld means normal play, not a kick, and does not bypass the server's other mod requirements. V1 ends with Accept or Decline; it does not start synchronization, ticking, or chunk ownership.

## Exchange

The concrete V1 handshake payload IDs are `coworld:hello`, `coworld:ping_probe`,
`coworld:ping_echo`, `coworld:accept`, and `coworld:decline`. Their encodings and
bounds are implemented once in `CoWorldPackets` in Common Mod.

1. The client sends Hello: CoWorld version, protocol version, offloading preference, disk allowance, client simulation distance, and the live snapshot schema identity/version. The shared codec also defines a cache declaration, but the client sends no cache declaration on this branch; `HelloSender.build(settings, cache)` accepts one, yet the join path always uses the overload that passes `null`.
2. The real server sends three ping probes in sequence. The client immediately echoes each unchanged; the server measures each RTT and takes the median. A missing, invalid, changed, or late reply, or a probe deadline expiring, declines offloading with `failed negotiation`. All three replies are required.
3. Accept supplies the agreed protocol, requested ownership radius in chunks, sync speed limit, and a new session ID. The radius is `min(client simulation distance, max_ownership_radius)`. On this branch the client only logs that session ID; Accept does not start sync, rejoin, ticking, or ownership.
4. Decline supplies a reason: offloading disabled, client opted out, capacity reached, ping above the cap, incompatible versions, or failed negotiation. The client logs the reason and continues normal play. No Decline is sent for a missing Hello or a disconnect.
5. No cache metadata is exchanged after Accept, and no rejoin or initial-sync path is selected. No chunks transfer during the handshake, and none are transferred by this branch's handshake follow-up. The declared cache fields and the [rejoin](rejoin.md) / [sync](sync.md) flows remain future work.

## Compatibility and cache validity

The protocol version is a wire-format compatibility identifier, separate from the CoWorld release version. V1 requires matching CoWorld and protocol versions. There is no implicit downgrade or selection based only on the lowest version number. An unsupported live protocol/version declines offloading.

Both endpoints take the live schema identity and version from `ProtocolVersion.V1_SCHEMA_ID` and `ProtocolVersion.V1_SCHEMA_VERSION`; the server entrypoint always configures those constants and requires an exact match before probing, so a Hello with no schema or a mismatch declines with `incompatible versions`. The snapshot adapter schema represented beyond these constants is not defined, and there is no versioned adapter manifest or adapter-readiness gate on this branch.

`CoWorldPackets.CacheDeclaration` defines the server/world UUIDs, cached CoWorld and Minecraft versions, cache-format and hash-format versions, schema identity/version, and a bounded execution manifest with per-file hashes. The client never populates this declaration, the server performs no cache validation after Accept, and no per-chunk hashes are exchanged here. Consequently there is no rejoin-vs-initial-sync routing and no chunk-hash replacement path in this implementation.

## Disk allowance

Hello's disk allowance is a signed 64-bit integer number of bytes (Java `long`), not an unsigned amount: values above `Long.MAX_VALUE` are not representable and decode as negative. The `Hello` constructor rejects negative values, and the server declines offloading for zero or negative allowance. The client turns an invalid or negative configured allowance into zero; zero provides no cache budget and never means unlimited.

The handshake only transmits and checks the allowance. This branch has no cache writes, no reservation of space within the allowance, and no eviction or insufficient-space handling; the existing rules described in [sync](sync.md#eviction-and-insufficient-space) are not implemented here. An allocation/write failure therefore has no handshake-specific recovery path.

## Ping and deadlines

`max_ping` is in milliseconds, default 70. A median equal to the cap passes; above it declines offloading. During an accepted session the server reads `player.connection.latency()` each tick. A value above `max_ping` increments the over-cap counter and a value at or below the cap resets it; reaching `high_ping_ticks` (default 100) clears the local session and its reserved slot and logs the event. No chunk hand-back or ownership transfer is started by that threshold on this branch.

`handshake_hello_timeout_ticks` and `ping_probe_timeout_ticks` both default to 100 real-server ticks, about 5 seconds at 20 TPS. The first starts at login; the second applies separately to each outstanding probe. Missing Hello means normal non-offloading play with no Decline; an expired probe deadline sends `Decline(failed negotiation)`. CoWorld timeout or version failure must not itself crash or kick the player. A connection that ends mid-handshake has changed no chunk ownership.

## Session state

Every join runs a fresh handshake. Pending probes and negotiation state are connection-scoped and discarded on disconnect. The accepted session ID is a random UUID for the live session; it is not a persistent cache key and is not resumed after reconnect. Accept's requested radius is only carried to the client; no candidate area is allocated and no ownership decision is made from it.

Simulation distance is read once when the client joins and sent in Hello. The client does not report later changes, the server does not recompute the requested radius, and no [handover](handover.md) is started for a mid-session change. Future world/mod caches are intended to persist under stable server and world identities as described in [sync](sync.md); this branch does not create or validate them.
