# CoWorld Handshake

*   **Author:** gladiatorsarius
*   **Status:** Draft
*   **Date:** 2026-11-22

--------------------------------------------------------------------------------

## Overview

CoWorld needs a connection-scoped negotiation before it can move simulation work
from the real server to a player's computer. The handshake uses custom packets
on the existing Minecraft connection. It reports client capabilities and a live
schema identity, measures network latency with three required echo probes, and
then sends Accept or Decline. On this branch the client sends no cache
declaration and Accept does not start synchronization.

The handshake must not become a new login requirement. A client that does not
have CoWorld, opts out, fails validation, or misses a deadline continues normal
play on the real server. An accepted handshake creates a fresh session record
and sends Accept; it does not grant chunk ownership, start ticking chunks, or
start sync or rejoin.

## The Problem

The server cannot safely send a client world state or assign simulation work
without first answering several questions:

* Which CoWorld and wire-protocol versions can both sides use?
* Does the client want offloading and have enough disk allowance for the cache?
* Is the connection responsive enough for interactive simulation?
* Could a future cache declaration be compatible with this server and world?
* Can the server decline without kicking a player or changing chunk ownership?

A failed or incomplete negotiation must leave the authoritative server world
unchanged. V1 carries no cache claims and selects no sync/rejoin path; a future
flow must keep cache claims and ownership decisions separate from the handshake.

## Goals

* Exchange a versioned Hello, three unchanged ping echoes, and one terminal
  result per negotiated connection over the existing Minecraft connection.
  A missing Hello or disconnect sends no Decline; a probe deadline expiring
  sends `Decline(failed negotiation)`. Terminal outcomes are not duplicated.
* Reject unsupported versions, invalid fields, zero or negative disk allowance,
  missed or late probes, and median RTT above `max_ping` without kicking the
  player.
* Compute the accepted candidate radius as the smaller of the client's
  simulation distance and the configured maximum ownership radius.
* Create a fresh connection-scoped session ID only after successful
  negotiation; never treat it as a persistent cache key or a resumed session.
* Preserve normal real-server play when the client has no CoWorld support,
  declines offloading, disconnects, or times out.
* Send no cache declaration from the V1 client and perform no cache validation
  or rejoin/initial-sync routing after Accept; no chunks are transferred.
* Keep pending probes, negotiation state, and any provisional session state
  isolated to the connection and discard them on disconnect.

## Non-Goals

* Assigning, transferring, or committing chunk ownership.
* Starting client ticking, creating a sub-server, or authorizing simulation.
* Transferring chunks, files, world data, execution manifests, or inventories.
* Treating a cache declaration as proof that cached content is usable.
* Supporting implicit protocol downgrade, lowest-common-version selection, or
  live negotiation across unsupported version pairs.
* Reusing an accepted session ID after reconnecting.
* Changing the server's other mod requirements or login policy.
* Replacing the existing sync, rejoin, handover, disconnect, or cache-eviction
  rules.

## Open Questions

* V1 declares the schema represented by `schema_id` and its integer version as
  `ProtocolVersion.V1_SCHEMA_ID` / `ProtocolVersion.V1_SCHEMA_VERSION`. Both
  endpoints send those constants; an exact-match failure declines with
  `incompatible versions` before probing. There is no rejoin selection from a
  null or mismatched schema on this branch.
* There is no versioned snapshot-adapter manifest or adapter-readiness gate.
  The constants are the only current schema identity, and no code checks an
  adapter implementation before Accept.

## Technical Plan (Architecture)

The wire format belongs in Common Mod so the client and server cannot define
different packet fields or encodings. The server owns negotiation policy and
timeouts. The client builds the Hello and echoes probes without changing their
contents. Session tracking remains server-side because only the real server can
authorize synchronization and observe ongoing player ping.

### Packet and component responsibilities

* `Common Mod/src/main/java/gladiatorsarius/coworld_common/protocol/CoWorldPackets.java`
  defines five handshake records—Hello, PingProbe, PingEcho, Accept, and
  Decline—and their codecs. Fabric custom payload IDs are
  `coworld:hello`, `coworld:ping_probe`, `coworld:ping_echo`, `coworld:accept`,
  and `coworld:decline`. The class also defines field validation boundaries
  shared by both endpoints.
* `Common Mod/src/main/java/gladiatorsarius/coworld_common/protocol/ProtocolVersion.java`
  identifies the exact wire format. A live connection is accepted only when
  the supported CoWorld and protocol versions match the server's policy.
* `Server Mod/src/main/java/gladiatorsarius/coworld_server/handshake/HandshakeHandler.java`
  owns the per-connection state machine, Hello deadline, version and field
  checks, and Accept/Decline construction. It does not transition into sync or
  rejoin.
* `Server Mod/src/main/java/gladiatorsarius/coworld_server/handshake/PingProber.java`
  sends three sequential probes, matches each echo to the outstanding nonce and
  sequence, records RTT milliseconds, and calculates the median RTT.
  `HandshakeHandler` applies the per-probe deadline and the inclusive
  `max_ping` comparison.
* `Server Mod/src/main/java/gladiatorsarius/coworld_server/handshake/SessionManager.java`
  creates fresh session IDs after acceptance, atomically reserves a slot before
  Accept, and releases reserved capacity when a session ends. `HandshakeHandler`
  tracks high-ping ticks.
* `Client Mod/src/main/java/gladiatorsarius/coworld_client/handshake/HelloSender.java`
  builds Hello from client settings, simulation distance, versions, and the
  live schema constants. Its `build(settings, cache)` overload can carry a
  `CacheDeclaration`, but the join path never supplies one.
* `Client Mod/src/client/java/gladiatorsarius/coworld_client/client/ClientNetworkBridge.java`
  immediately echoes each valid probe payload unchanged. The server rejects
  expired or unknown probes.

### Packet schema

The five handshake packets use the common Fabric custom-payload codec.
Strings use UTF-8, are limited by encoded byte length rather than character
count, and are decoded strictly. UUIDs use exactly 16 bytes in
most-significant-byte-first order. Integer fields are big-endian Java `int` or
`long` values except the unsigned byte fields called out below; negative values
are rejected where a field is validated. Cache declarations and manifest lists
are bounded before allocation so a malformed packet cannot force unbounded
memory use. The complete Hello payload is at most 64 MiB and the cached
execution-manifest portion at most 32 MiB.

Hello and PingEcho malformed-input decoders return an invalid payload after
draining the input; the server declines those. The other decoders reject
trailing bytes. Malformed length prefixes are rejected before allocation.

| Record | Field | Type and bounds | Required validation |
|---|---|---|---|
| Hello | release version | UTF-8 string, 1-32 encoded bytes | Exact CoWorld release match for V1 |
| Hello | protocol version | Signed 32-bit `int` | Exact V1 value; no downgrade |
| Hello | offloading preference | Boolean | Required |
| Hello | disk allowance | Signed 64-bit `long`, bytes | Negative values make Hello invalid; zero or negative declines; values above `Long.MAX_VALUE` are not representable |
| Hello | client simulation distance | Signed 32-bit `int`, chunks | Negative values make Hello invalid; zero remains valid but yields zero radius |
| Hello | live snapshot schema ID | Nullable UTF-8 string, 1-128 encoded bytes | Presence bit distinguishes absent from present; both endpoints send `ProtocolVersion.V1_SCHEMA_ID` and the server requires an exact match |
| Hello | live snapshot schema version | Signed 32-bit `int` | If live schema ID is absent, encode 0; otherwise exact server/client match is required |
| Hello | cache declaration | Presence byte plus optional declaration | `0` means no cache; `1` means one complete declaration; all other values are malformed. The V1 client always sends `0` |
| Cache declaration | stable server UUID | Exactly 16 bytes | Required when a cache is declared; not compared by V1 endpoints |
| Cache declaration | stable world UUID | Exactly 16 bytes | Required when a cache is declared; not compared by V1 endpoints |
| Cache declaration | cached CoWorld version | UTF-8 string, 1-32 encoded bytes | Defined in the codec; not compared by V1 endpoints |
| Cache declaration | cached Minecraft version | UTF-8 string, 1-32 encoded bytes | Defined in the codec; not compared by V1 endpoints |
| Cache declaration | cache format version | Signed 32-bit `int` | Negative values rejected; not compared by V1 endpoints |
| Cache declaration | hash format version | Signed 32-bit `int` | Negative values rejected; not compared by V1 endpoints |
| Cache declaration | snapshot `schema_id` | Nullable UTF-8 string, 1-128 encoded bytes | Presence bit distinguishes null from an empty or invalid string; not compared by V1 endpoints |
| Cache declaration | snapshot schema version | Signed 32-bit `int` | Negative values rejected; must be 0 when `schema_id` is absent; not compared by V1 endpoints |
| Cache declaration | cached execution manifest | Bounded manifest entry list, at most 100,000 entries and 32 MiB of encoded manifest bytes | Signed 32-bit non-negative count; each entry uses `u8` root kind, signed 32-bit UTF-8 path-byte length, path bytes, signed 64-bit non-negative stored length, and exactly 32 hash bytes; reject malformed lists |
| Manifest entry | root kind | Unsigned 8-bit enum: `mods=1`, `config=2`, `versions=3`, `libraries=4` | Required; no server-side manifest comparison is performed by V1 |
| Manifest entry | relative path | UTF-8 bytes, 1-4,096 bytes | Reject backslashes, absolute/tilde paths, NUL, empty or `.`/`..` segments, illegal characters, reserved Windows names, and duplicate or platform-colliding paths |
| Manifest entry | stored byte length | Signed 64-bit `long` | Negative values rejected; no cached file length is checked by V1 |
| Manifest entry | SHA-256 content hash | Exactly 32 bytes | Length checked; no digest is verified by V1 |

Manifest entries use unsigned UTF-8 lexicographic key order by root kind first
and path bytes second. Path validation rejects backslashes, absolute or tilde
paths, empty/`.`/`..` segments, `:` and Windows-illegal characters, and
reserved Windows names. Duplicate detection NFKC-normalizes and lowercases each
path and strips trailing dots/spaces (`platformAlias`) to catch platform
collisions. Strings and paths use byte-length prefixes followed by raw bytes,
with no terminator or end tag.

| Record | Field | Type and bounds | Required validation |
|---|---|---|---|
| PingProbe | probe nonce | Signed 64-bit `long` | Drawn randomly per probe; only the outstanding nonce is accepted and there is no per-connection reuse tracking |
| PingProbe | probe sequence | Unsigned 8-bit integer, 1-3 | Must match one of the three sequential probe positions |
| PingEcho | echoed nonce | Signed 64-bit `long` | Must exactly equal the current PingProbe nonce |
| PingEcho | echoed sequence | Unsigned 8-bit integer, 1-3 | Must exactly equal the current PingProbe sequence |
| Accept | protocol version | Signed 32-bit `int` | Must equal the agreed V1 protocol |
| Accept | requested ownership radius | Signed 32-bit `int`, chunks | `min(client simulation distance, max_ownership_radius)`, non-negative |
| Accept | sync speed limit | Signed 64-bit `long`, bytes/second | Must be the configured non-negative server value |
| Accept | session ID | 16-byte UUID | Freshly generated for this accepted connection |
| Decline | reason enum | Unsigned 8-bit integer | Exact mapping: `offloading disabled=1`, `client opted out=2`, `capacity reached=3`, `ping above the cap=4`, `incompatible versions=5`, `failed negotiation=6` |

The server accepts a PingEcho only when it belongs to the current connection,
sequence, and nonce. Stale, out-of-order, duplicate, unknown, cross-connection,
changed, invalid, or late replies do not create a RTT sample; an invalid reply
declines with `failed negotiation`. The session ID is random 16-byte UUID data
generated by `SessionManager`; the handshake does not validate an incoming
session ID.

### Rejection routing

| Input or event | Result sent to client | Exact route |
|---|---|---|
| No Hello received by the Hello deadline | None | Continue normal play |
| Malformed Hello or invalid integer/string field | Decline, reason `failed negotiation` | Continue normal play |
| CoWorld or protocol version mismatch | Decline, reason `incompatible versions` | Continue normal play |
| Live schema ID/version mismatch against the V1 constants | Decline, reason `incompatible versions` | Continue normal play; no adapter manifest or sync fallback exists |
| Server `offload_enabled=false` | Decline, reason `offloading disabled` | Continue normal play; no hand-back request is sent on this branch |
| Client preference is opt-out | Decline, reason `client opted out` | Continue normal play |
| Zero disk allowance | Decline, reason `offloading disabled` | Continue normal play |
| Requested ownership radius is zero | Decline, reason `offloading disabled` | Continue normal play; do not reserve a slot or send Accept |
| Server offloading capacity unavailable | Decline, reason `capacity reached` | Continue normal play |
| Missing, invalid, duplicate, out-of-order, changed, or late ping reply | Decline, reason `failed negotiation` | Require all three valid replies; continue normal play |
| Probe deadline expires without a valid reply | Decline, reason `failed negotiation` | Require all three valid replies; continue normal play |
| Median RTT above `max_ping` | Decline, reason `ping above the cap` | Continue normal play |
| Median RTT at or below `max_ping` | Accept | Client logs the session ID; no sync or rejoin follows on this branch |
| Connection ends during Hello or probing | None | Discard state; no ownership or chunk change |
| Malformed cache declaration | Decline, reason `failed negotiation` | V1 client sends none; no cache validation is performed |

A negative disk allowance makes Hello invalid and follows the malformed-Hello
branch; the wire field is an integer, so a noninteger allowance cannot be
encoded. The server never sends Decline after a disconnect or missing-Hello
timeout, but a probe deadline expiry does send `Decline(failed negotiation)`.

### State flow

```text
login
  |
  | start hello deadline
  v
WAITING_FOR_HELLO
  |--missing Hello deadline/disconnect
  |                              --> NORMAL_PLAY
  |--malformed/invalid Hello
  |                              --> DECLINE(failed negotiation) --> NORMAL_PLAY
  |--version mismatch           --> DECLINE(incompatible) --> NORMAL_PLAY
  |--server offloading disabled --> DECLINE(offloading disabled) --> NORMAL_PLAY
  |--offloading opt-out         --> DECLINE(client opted out) --> NORMAL_PLAY
  |--zero allowance             --> DECLINE(offloading disabled) --> NORMAL_PLAY
  |--requested radius is zero   --> DECLINE(offloading disabled) --> NORMAL_PLAY
  |--capacity unavailable       --> DECLINE(capacity reached) --> NORMAL_PLAY
  |--live adapter mismatch      --> DECLINE(incompatible) --> NORMAL_PLAY
  |
  | valid Hello
  v
PROBING
  |--probe reply invalid/late/missing --> DECLINE(failed negotiation) --> NORMAL_PLAY
  |--three valid replies, median > cap --> DECLINE(ping above the cap) --> NORMAL_PLAY
  |--three valid replies, median <= cap
                                      --> ACCEPT + SessionId --> ACCEPTED
```

After acceptance the server observes `player.connection.latency()` each tick.
Reaching `high_ping_ticks` above `max_ping` clears the local session and its
reserved slot and logs the event; no chunk hand-back or ownership change is
started by that threshold on this branch.

The client follows the same connection-local flow from its side:

```text
send Hello -> echo probe 1 -> echo probe 2 -> echo probe 3
                                  |
                         Accept -> log session ID
                         Decline/timeout -> remain in normal play
```

### Hello validation and cache handoff

Hello carries the CoWorld release version, exact protocol version, offloading
preference, signed 64-bit disk allowance, client simulation distance, and the
live schema identity/version. Both endpoints take that identity from
`ProtocolVersion.V1_SCHEMA_ID` and `ProtocolVersion.V1_SCHEMA_VERSION`; the
server requires an exact match before probing. The shared codec also defines a
nullable `schema_id` plus version for cache metadata and the optional cache
declaration with stable server/world UUIDs, versions, format versions, and a
bounded execution manifest with hashes. `HelloSender` has an overload that
accepts a `CacheDeclaration`, but the join path always passes `null`.

The server rejects malformed values, unsupported versions, a schema mismatch,
a zero or negative allowance, and a zero radius. It does not validate a cache
claim after Accept because no cache claim is sent on this branch; there is no
cache compatibility or integrity check, no rejoin/initial-sync selection, and
no chunk inventory or hash exchange. The server entrypoint configures the V1
constants, so a Hello with no schema or a mismatch declines with
`incompatible versions` rather than routing to initial sync.

Before probing completes, the server compares the live schema constants it was
constructed with against the values in Hello. If they do not match, it declines
with `incompatible versions` before reserving a slot or sending Accept. There
is no versioned adapter manifest, no adapter-readiness gate, and no sync
fallback: V1 negotiation alone does not claim offloading readiness.

### Accept and Decline behavior

Accept contains the agreed protocol, the candidate radius
`min(client simulation distance, max_ownership_radius)`, the configured sync
speed limit, and a newly generated session ID. On this branch it carries those
values only: the client logs the session ID and no sync, ticking, ownership
claim, redstone-set rule, occupancy check, TPS reduction, readiness check, or
handover commitment follows.

If the computed candidate radius is zero, the server sends
`offloading disabled` instead of Accept. It does not reserve a slot and does
not start sync or rejoin for that result.

Decline contains one reason from the documented set: offloading disabled,
client opted out, capacity reached, ping above the cap, incompatible versions,
or failed negotiation. The client logs the reason but shows no in-game message.
The server sends no ownership change with Decline. Missing Hello is treated as
normal play without requiring a Decline packet.

### Timeout and disconnect rules

The Hello timer starts at login and uses
`handshake_hello_timeout_ticks` (default 100 real-server ticks). Each probe has
its own `ping_probe_timeout_ticks` deadline (default 100 ticks). The server
requires all three replies; it never calculates a median from fewer samples.
Disconnecting at any state discards probes, negotiation state, and any
uncommitted session data. No handshake failure can crash or kick the player.

After acceptance, `HandshakeHandler` counts consecutive real-server ticks
whose built-in player ping (`player.connection.latency()`) exceeds `max_ping`.
Reaching `high_ping_ticks` clears the local session, releases its reserved
slot, and logs the event; no hand-back or ownership flow is started. A
measurement at or below the cap resets the counter.

### Data flow

```text
Client HelloSender
        |
        v
Common CoWorldPackets <----> Server HandshakeHandler
        ^                             |
        |                             +--> PingProber
ClientNetworkBridge (PingEcho)        |
                                      +--> SessionManager (session ID + reserved slot count)
                                      |
                         Accept/Decline ---> client logs the result
```

## Alternatives Considered

### Alternative A: Use one ping sample

*   **Pros**: Fewer packets and a shorter negotiation.
*   **Cons**: A single sample is too sensitive to transient scheduling,
    congestion, or packet delay.
*   **Reason for Rejection**: Rejected in the design: one measurement is too
    noisy for deciding whether the connection can support offloading. Three
    required probes provide a small sample while preserving a bounded handshake.

### Alternative B: Reject when any probe has the maximum RTT

*   **Pros**: Conservative behavior and direct detection of the worst observed
    delay.
*   **Cons**: One temporary spike would block an otherwise usable player.
*   **Reason for Rejection**: Rejected in the design: the median of three
    probes limits the effect of one transient spike while still rejecting
    consistently slow connections.

### Alternative C: Make the handshake mandatory

*   **Pros**: The server could require every client to identify CoWorld before
    login completes.
*   **Cons**: Players without the client mod, players who opt out, and clients
    with failed negotiation would be kicked or denied normal play.
*   **Reason for Rejection**: Rejected in the design: CoWorld is an optional
    offloading path and must not change the server's other mod requirements or
    prevent vanilla-compatible play.

### Alternative D: Use a separate bootstrap connection

*   **Pros**: A separate connection could isolate handshake state and large
    payloads from normal Minecraft traffic.
*   **Cons**: It adds authentication, lifetime, NAT/firewall, and
    account-identity work while duplicating the existing Minecraft connection.
*   **Reason for Rejection**: Rejected in the design: the handshake must use
    the existing connection and must not require a second account or transport.

### Alternative E: Add a persistent session during login

*   **Pros**: A persistent session could let a reconnect resume negotiation or
    cache state.
*   **Cons**: It would add stale-session ownership risks and require persistent
    account-bound state that the handshake contract explicitly rejects.
*   **Reason for Rejection**: Rejected in the design: every join requires a
    fresh handshake and the accepted session ID is not a reconnect key.

## Non-Functional Requirements

* **Compatibility**: The five handshake payload IDs and all field encodings must
  be defined once in Common Mod. Protocol version mismatch must decline
  offloading rather than attempt downgrade.
* **Failure isolation**: Invalid packets, timeout, disconnect, and cache
  incompatibility must leave normal Minecraft play and authoritative ownership
  unchanged. Handshake failures must not crash or kick the player.
* **Timing**: Probe deadlines use real-server ticks. RTT is measured in
  milliseconds, and the median comparison accepts a value equal to `max_ping`.
* **State safety**: Session IDs are fresh per accepted connection. Pending
  probes and negotiation state must not survive disconnect or be shared between
  players.
* **Capacity safety**: `SessionManager` atomically increments a numeric
  reserved-slot count before sending Accept and decrements it on disconnect or
  the over-cap-ping session reset. No sub-server is created on this branch and
  there is no sync/bootstrap failure path to release a slot.
* **Storage safety**: The disk allowance is a signed 64-bit byte count; negative
  values are rejected and zero declines. The handshake only transmits and
  checks that value: this branch performs no cache writes, reservation, or
  eviction.

## Risks & Mitigations

* **Malformed or oversized packets**: Bound all strings, lists, integers,
  manifest entries, UUID fields, and cached metadata before allocation. Reject
  malformed packets as `failed negotiation` and send no data derived from the
  invalid declaration.
* **Replay or stale probe packets**: Bind PingEcho to the current connection,
  sequence, and random nonce. Ignore duplicates, stale sequences, and unknown
  probes. A session ID is random per accepted connection and is never persisted
  as a cache key.
* **Handshake resource exhaustion**: Keep one bounded negotiation state machine
  per connection, start deadlines at login/probe receipt, and discard all state
  on timeout or disconnect. No ownership or cache work is created. No slot is
  reserved during Hello or ping probing; the count is reserved atomically only
  after all probes pass and immediately before sending Accept.
* **Untrusted cache declarations**: V1 sends no cache declaration, so no Hello
  cache claim is consumed on this branch. The codec can decode a declaration,
  but neither endpoint compares UUIDs, versions, manifest hashes, or schema
  identities after Accept, and no integrity check exists.
* **Sensitive cache metadata**: Do not include file contents or secrets in the
  handshake. Log only bounded, non-sensitive rejection reasons and keep detailed
  compatibility diagnostics server-side or in normal server logs.
* **Timeout or disconnect races**: Terminal packet generation must be
  connection-scoped and once-only. Disconnect cleanup prevents a late Accept,
  Decline, or probe from changing state or ownership.

## Additional Resources

* `docs/handshake.md`
* `docs/folder-structure.md`
* `docs/configs.md`
* `docs/sync.md`
* `docs/rejoin.md`
* `docs/coremodel.md`

## Future Improvements

* Define the snapshot-adapter schema beyond the V1 identity constants, and add a
  versioned adapter manifest if adapter readiness must be checked.
* Add an explicit protocol capability negotiation only if a future wire format
  requires compatible feature subsets; V1 deliberately requires exact matching.
* Mid-session simulation-distance changes are not implemented on this branch;
  `docs/coremodel.md` describes future behavior.

## Detailed Implementation Plan

### Single implementation list

#### 1. `Common Mod/src/main/java/gladiatorsarius/coworld_common/protocol/CoWorldPackets.java` (Create)

* **Rationale**: Define the shared wire contract for the five Fabric custom
  handshake payloads: `coworld:hello`, `coworld:ping_probe`,
  `coworld:ping_echo`, `coworld:accept`, and `coworld:decline`.
* **Implemented content**: Record fields, encode/decode behavior, bounded
  integer and string validation, nullable `schema_id` plus schema version,
  bounded manifest metadata, nonce and sequence fields, Decline reasons, and
  lenient Hello/PingEcho decoders that return an invalid payload. Stale probe
  rejection is handled by `PingProber`, not by this codec; PingEcho preserves
  the probe identity unchanged.

#### 2. `Common Mod/src/main/java/gladiatorsarius/coworld_common/protocol/ProtocolVersion.java` (Create or modify)

* **Rationale**: Keep the exact wire-format identifier in the shared module.
* **Implemented content**: Exposes the V1 value and exact-match compatibility
  check used by both endpoints, plus the `V1_SCHEMA_ID` / `V1_SCHEMA_VERSION`
  constants sent in Hello. There is no downgrade or lowest-common-version
  selection.

#### 3. `Common Mod/src/main/java/gladiatorsarius/coworld_common/config/CoWorldDefaults.java` (Create or modify)

* **Rationale**: Centralize handshake defaults with the other shared defaults.
* **Planned changes**: Define `max_ping = 70`, `high_ping_ticks = 100`,
  `handshake_hello_timeout_ticks = 100`, and
  `ping_probe_timeout_ticks = 100`, plus the existing ownership-radius and
  sync-limit defaults consumed by Accept.

#### 4. `Server Mod/src/main/java/gladiatorsarius/coworld_server/config/CoWorldConfig.java` (Create or modify)

* **Rationale**: Load and validate server settings before the handshake uses
  them.
* **Planned changes**: Reject invalid negative or noninteger values, expose
  handshake deadlines and ping cap, and preserve the inclusive `max_ping`
  comparison and nonnegative radius rules. Read `offload_enabled` before
  accepting a Hello and decline new offloading when it is false.

#### 5. `Server Mod/src/main/java/gladiatorsarius/coworld_server/handshake/HandshakeHandler.java` (Create)

* **Rationale**: Own the server-side connection state machine and decision
  boundary.
* **Implemented content**: Starts the Hello deadline at login, accepts one valid
  Hello, invokes PingProber, sends at most one Accept or Decline, and discards
  state on disconnect. It checks the server-wide offloading switch, candidate
  radius, and live schema constants before probing, and reserves a slot only
  after probes pass and immediately before Accept. There is no cache-metadata
  routing after Accept. Missing Hello becomes normal play without a Decline; an
  expired probe deadline sends `Decline(failed negotiation)`.

#### 6. `Server Mod/src/main/java/gladiatorsarius/coworld_server/handshake/PingProber.java` (Create)

* **Rationale**: Isolate the three-probe generation, echo matching, and RTT
  median calculation. Deadline and ping-cap policy stay in `HandshakeHandler`.
* **Implemented content**: Sends probes sequentially with random nonces and
  positions 1-3, matches each accepted echo to the outstanding nonce and
  sequence, records RTT milliseconds, requires three samples, and calculates
  the median. `HandshakeHandler` rejects invalid, changed, duplicate, and late
  echoes, applies the per-probe deadline, and compares the median with
  `max_ping`.

#### 7. `Server Mod/src/main/java/gladiatorsarius/coworld_server/handshake/SessionManager.java` (Create)

* **Rationale**: Own accepted session identity and post-accept ping monitoring.
* **Implemented content**: Generates a fresh session ID per accepted connection
  and atomically reserves a numeric slot before Accept. The over-cap ping tick
  counting lives in `HandshakeHandler` and clears the session/slot at
  `high_ping_ticks`; `SessionManager` only releases the slot when called. There
  is no sub-server, bootstrap failure path, or hand-back behavior.

#### 8. `Server Mod/src/main/java/gladiatorsarius/coworld_server/CoWorldServer.java` (Modify)

* **Rationale**: Register the server payload handlers and connect handshake
  lifecycle hooks to login, tick, and disconnect. No sync or rejoin service is
  connected on this branch.
* **Planned changes**: Wire HandshakeHandler, PingProber, SessionManager, and
  validated configuration without placing packet definitions in the server
  module.

#### 9. `Client Mod/src/main/java/gladiatorsarius/coworld_client/handshake/HelloSender.java` (Create)

* **Rationale**: Build the client declaration from settings and current client
  state.
* **Implemented content**: Sends versions, offloading preference, a signed
  64-bit disk allowance, simulation distance, and the live schema ID/version in
  Hello. The overload that accepts a `CacheDeclaration` is unused by the join
  path, so no stable identities, manifest hashes, or format versions are sent.

#### 10. Ping echo in `ClientNetworkBridge.java`

* **Rationale**: Provide the client half of the required probe exchange.
* **Planned changes**: Register the ping-probe receiver in the bridge and return
  an unchanged PingEcho for each probe. The server rejects expired or unknown
  probes.

#### 11. `Client Mod/src/client/java/gladiatorsarius/coworld_client/client/ClientNetworkBridge.java` (Create)

* **Rationale**: Keep Fabric client-only networking calls out of common client
  code and expose the shared payload registration to the client entrypoint.
* **Implemented content**: Registers PingProbe, Accept, and Decline receivers,
  sends Hello and echoes through the existing Minecraft connection, and logs
  Accept or Decline. `CoWorldClient` registers the five payload codecs; no
  common client handshake state or sync/rejoin orchestrator is driven by those
  results.

#### 12. `Client Mod/src/main/java/gladiatorsarius/coworld_client/CoWorldClient.java` (Modify)

* **Rationale**: Register the shared payload types on the client. The Hello
  flow and result logging live in `ClientNetworkBridge`.
* **Implemented content**: Registers the five payload codecs only. There are no
  cache metadata providers, no accepted-metadata handoff, and no sync or rejoin
  orchestrator on this branch.

#### 13. `docs/handshake.md` (Modify)

* **Rationale**: Keep the design contract synchronized with the concrete wire
  identifiers and schema decision.
* **Planned changes**: Document the five handshake payload IDs, exact packet
  contents, nullable `schema_id` and integer version matching, client-only
  Decline logging, and the state and timeout rules.

#### 14. `docs/folder-structure.md` (Modify)

* **Rationale**: Record the files and responsibilities added by this design.
* **Planned changes**: Replace planned placeholders with the finalized
  handshake file list and note the client-only network bridge boundary.

#### 15. `Common Mod/src/test/java/gladiatorsarius/coworld_common/protocol/CoWorldPacketsTest.java` (Create)

* **Rationale**: Verify the shared wire contract independently of either
  endpoint.
* **Retained tests**: Three tests cover rejection of negative schema versions,
  several unsafe manifest paths, and exact V1 protocol matching. Round-trip
  encoding, payload IDs, Decline values, size limits, canonical ordering, and
  oversized declarations are not covered by a retained test on this branch.

#### 16. `Server Mod/src/test/java/gladiatorsarius/coworld_server/handshake/HandshakeHandlerTest.java` (Create)

* **Rationale**: Verify server state transitions and fallback behavior.
* **Retained tests**: Four tests cover ignoring an echo before Hello, ignoring
  packets after a terminal state, accepting a null schema when neither side
  configures one, and reaching Accept after three echoes with the concrete V1
  schema. Missing Hello, invalid fields, version mismatch, opt-out, allowance
  and radius rejection, capacity, disconnect cleanup, and sync/rejoin routing
  have no retained test on this branch.

#### 17. `Server Mod/src/test/java/gladiatorsarius/coworld_server/handshake/PingProberTest.java` (Not present)

* **Status**: No dedicated prober test is retained on this branch. The three
  sequential probes and unchanged echoes are exercised indirectly by
  `HandshakeHandlerTest`; median equality/above-cap behavior and deadline
  expiry have no direct test.

#### 18. `Server Mod/src/test/java/gladiatorsarius/coworld_server/handshake/SessionManagerTest.java` (Not present)

* **Status**: No dedicated session test is retained on this branch. Session ID
  generation and slot reservation are exercised indirectly through
  `HandshakeHandlerTest`; over-cap tick counting and slot release are not.

#### 19. `Client Mod/src/test/java/gladiatorsarius/coworld_client/handshake/HelloSenderTest.java` (Not present)

* **Status**: No HelloSender test is retained on this branch. The only client
  test is `ClientSettingsTest`, which checks rejection of a negative disk
  allowance.

#### 20. Probe echo coverage

* **Rationale**: Confirm the client never alters or fabricates probe replies.
* **Planned changes**: The inlined bridge echo copies nonce and sequence unchanged;
  unchanged echoes are exercised by `HandshakeHandlerTest`. No separate client
  responder class or test remains.

#### 21. `Common Mod/gradle.properties` (Modify)

* **Rationale**: Keep the common wire contract release version aligned with the
  feature.
* **Planned changes**: Bump the Common Mod version according to the repository's
  feature-versioning rule.

#### 22. `Server Mod/gradle.properties` (Modify)

* **Rationale**: Publish the server handshake implementation with a matching
  feature version.
* **Planned changes**: Bump the Server Mod version according to the repository's
  feature-versioning rule.

#### 23. `Client Mod/gradle.properties` (Modify)

* **Rationale**: Publish the client handshake implementation with a matching
  feature version.
* **Planned changes**: Bump the Client Mod version according to the repository's
  feature-versioning rule.

### Verification Plan

* **Test policy**: The retained JUnit classes on this branch are
  `CoWorldPacketsTest` (three tests), `HandshakeHandlerTest` (four), and
  `ClientSettingsTest` (one). `PingProberTest`, `SessionManagerTest`, and
  `HelloSenderTest` are not present. No source-compiled harness replaces these
  retained tests or connected-client and in-game verification.

* **Common build**: Run `./gradlew build` inside `Common Mod/`.
* **Server build and tests**: Run `./gradlew build` inside `Server Mod/`.
* **Client build and tests**: Run `./gradlew build` inside `Client Mod/`.
* **Protocol checks**: The five payload IDs and their codecs are registered by
  both entrypoints; the retained common test checks exact V1 matching. No
  round-trip encoding suite is retained.
* **Behavior checks**: Retained tests cover an echo before Hello, packets after
  a terminal state, a null schema on both sides, and three echoes reaching
  Accept. Other failure paths need manual or in-game checks; there is no cache
  mismatch path on this branch.
* **Timing checks**: The handler requires three probes and the prober computes
  the median, but neither median equality/above-cap behavior nor either
  deadline has a retained test.
* **Integration check**: Verify that Accept is logged by the client and that an
  incompatible or absent client continues normal play. There is no sync or
  rejoin follow-up and no chunk transfer to verify on this branch.

--------------------------------------------------------------------------------

*Drafted with EGM*
