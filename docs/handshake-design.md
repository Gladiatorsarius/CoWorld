# CoWorld Handshake

*   **Author:** gladiatorsarius
*   **Status:** Draft
*   **Date:** 2026-11-22

--------------------------------------------------------------------------------

## Overview

CoWorld needs a connection-scoped negotiation before it can move simulation work
from the real server to a player's computer. The handshake uses custom packets
on the existing Minecraft connection for Hello and the terminal result, and
vanilla keepalive packets for three required RTT probes. It reports client
capabilities and a live schema identity, measures network latency without a
tick wait, and then sends Accept or Decline. On this branch the client sends no
cache declaration and Accept does not start synchronization.

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

* Exchange a versioned Hello, three vanilla keepalive round trips, and one
  terminal result per negotiated connection over the existing Minecraft connection.
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
different packet fields or encodings. The server owns negotiation policy,
timeouts, and all probe timing. The client builds the Hello; vanilla Minecraft
echoes keepalive IDs immediately, so no client-mod probe handler is needed.
Session tracking remains server-side because only the real server can authorize
synchronization.

### Probe transport and thread behavior

The server sends three `ClientboundKeepAlivePacket`s in sequence with IDs whose
sign bit is set. Vanilla uses wall-clock milliseconds for its pending keepalive
challenge, so a negative ID can never match it. The timestamp is taken at
`Connection.doSendPacket` on the Netty event loop, not when the tick thread
queued the packet.

On the server network thread, a mixin at the HEAD of
`ServerCommonPacketListenerImpl.handleKeepAlive` checks the returned ID against
the active probes for that exact listener. A matching ID records
`System.nanoTime()` and cancels the packet so vanilla never sees it. Any other
ID is left untouched for vanilla, which may disconnect an unexpected reply;
this is why an ID must not leak after its probe is discarded.

The client's vanilla `ClientCommonPacketListenerImpl.handleKeepAlive` echoes the
ID immediately through vanilla networking. It has no tick wait and no CoWorld
validation. RTT is therefore independent of server and client TPS. Known edge:
a frozen client window delays the echo and inflates RTT; V1 does not work around
it.

### Packet and component responsibilities

* `Common Mod/src/main/java/gladiatorsarius/coworld_common/protocol/CoWorldPackets.java`
  defines three handshake records—Hello, Accept, and Decline—and their codecs.
  Fabric custom payload IDs are `coworld:hello`, `coworld:accept`, and
  `coworld:decline`. The class also defines field validation boundaries shared
  by both endpoints.
* `Common Mod/src/main/java/gladiatorsarius/coworld_common/protocol/ProtocolVersion.java`
  identifies the exact wire format. A live connection is accepted only when
  the supported CoWorld and protocol versions match the server's policy.
* `Server Mod/src/main/java/gladiatorsarius/coworld_server/handshake/HandshakeHandler.java`
  owns the per-connection state machine, Hello deadline, version and field
  checks, and Accept/Decline construction. It drains probe results on the
  server tick. It does not transition into sync or rejoin.
* `Server Mod/src/main/java/gladiatorsarius/coworld_server/handshake/PingProber.java`
  sends three sequential vanilla keepalive probes, records network-thread echo
  timestamps, matches each reply to its sequence and connection, records RTT
  milliseconds, and calculates the median RTT. `HandshakeHandler` applies the
  per-probe deadline and the inclusive `max_ping` comparison.
* `Server Mod/src/main/java/gladiatorsarius/coworld_server/mixin/`
  contains the network-thread interceptors for serverbound keepalive echoes and
  clientbound keepalive writes. Mismatched replies are never consumed.
* `Server Mod/src/main/java/gladiatorsarius/coworld_server/handshake/SessionManager.java`
  creates fresh session IDs after acceptance, atomically reserves a slot before
  Accept, and releases reserved capacity when a session ends.
* `Client Mod/src/main/java/gladiatorsarius/coworld_client/handshake/HelloSender.java`
  builds Hello from client settings, simulation distance, versions, and the
  live schema constants. Its `build(settings, cache)` overload can carry a
  `CacheDeclaration`, but the join path never supplies one.
* `Client Mod/src/client/java/gladiatorsarius/coworld_client/client/ClientNetworkBridge.java`
  registers the Hello send, result logging, and no probe handler; vanilla
  handles keepalive echoes for both modded and unmodded clients.

### Packet schema

The three handshake payloads use the common Fabric custom-payload codec.
Strings use UTF-8, are limited by encoded byte length rather than character
count, and are decoded strictly. UUIDs use exactly 16 bytes in
most-significant-byte-first order. Integer fields are big-endian Java `int` or
`long` values except the unsigned byte fields called out below; negative values
are rejected where a field is validated. Cache declarations and manifest lists
are bounded before allocation so a malformed packet cannot force unbounded
memory use. The complete Hello payload is at most 64 MiB and the cached
execution-manifest portion at most 32 MiB. Keepalive probes are vanilla
`long` IDs, not CoWorld payloads.

The Hello malformed-input decoder returns an invalid payload after draining the
input; the server declines it. The other decoders reject trailing bytes.
Malformed length prefixes are rejected before allocation.

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
| Keepalive probe | ID | Signed 64-bit `long` with the sign bit set | Unique per connection, never a vanilla millisecond challenge; only an ID owned by this listener is consumed |
| Keepalive probe | sequence | Implicit 1-3 | Follow-up probes are sent only after the previous ID is echoed; a reply for another sequence declines |
| Accept | protocol version | Signed 32-bit `int` | Must equal the agreed V1 protocol |
| Accept | requested ownership radius | Signed 32-bit `int`, chunks | `min(client simulation distance, max_ownership_radius)`, non-negative |
| Accept | sync speed limit | Signed 64-bit `long`, bytes/second | Must be the configured non-negative server value |
| Accept | session ID | 16-byte UUID | Freshly generated for this accepted connection |
| Decline | reason enum | Unsigned 8-bit integer | Exact mapping: `offloading disabled=1`, `client opted out=2`, `capacity reached=3`, `ping above the cap=4`, `incompatible versions=5`, `failed negotiation=6` |

The server consumes a keepalive echo only when its ID belongs to the current
connection's active probe set. Its sequence must match the outstanding probe;
stale, out-of-order, duplicate, or late replies do not create a RTT sample and
decline with `failed negotiation`. An unknown ID is left to vanilla, which may
disconnect the client. All probe IDs leave the active set before Accept, Decline,
or timeout; they move to a retired set so late or duplicate echoes are still
canceled, and both sets are cleared on disconnect. The session ID is
random 16-byte UUID data generated by `SessionManager`; the handshake does not
validate an incoming session ID.

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
| Missing, duplicate, out-of-order, stale, or late keepalive echo from an owned probe | Decline, reason `failed negotiation` | Require all three valid replies; continue normal play |
| Probe deadline expires without a valid reply | Decline, reason `failed negotiation` | Require all three valid replies; continue normal play |
| Median RTT above `max_ping` | Decline, reason `ping above the cap` | Continue normal play |
| Median RTT at or below `max_ping` | Accept | Client logs the result; no sync or rejoin follows on this branch |
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

V1 checks ping only during the three probes. After Accept the handshake does
not monitor connection latency, start hand-back, or change ownership.

The client follows the same connection-local flow from its side:

```text
send Hello -> vanilla echo of keepalive 1 -> vanilla echo of keepalive 2 -> vanilla echo of keepalive 3
                                  |
                         Accept -> log result
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
values only: the client logs the result and no sync, ticking, ownership
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

Ping checks end with Accept: V1 has no post-accept latency counter or
threshold. Disconnect is the only path that releases a reserved slot.

### Logging

`log_level` in `coworld-server.properties` is `normal` or `debug`, default
`normal`. Warnings and errors are always shown. Every CoWorld line starts with a
component tag; handshake lines use `[handshake]`.

At normal, one terminal line is emitted per connection:
`[handshake] accepted player=<name> radius=<chunks>` or
`[handshake] declined player=<name> reason=<reason>`. No session ID or timing is
logged at normal. The client logs `[handshake] accepted` or
`[handshake] declined reason=<reason>`.

At debug, the server logs Hello validation inputs, probe queued/written/echoed
timestamps and IDs, queue-to-write delay, RTT and sequence decisions, rejection
reasons, deadline expiry, final samples/median/cap, and state discard. Late
echoes consumed after discard and unknown IDs forwarded to vanilla are also
logged. No secrets or tokens are
logged at either level; the keepalive ID is a per-connection nonce used to
diagnose a reply leaking to vanilla.

### Data flow

```text
Client HelloSender
        |
        v
Common CoWorldPackets <----> Server HandshakeHandler
                                      |
                                      +--> PingProber <--> vanilla keepalive mixins
                                      |                   (Netty write + echo)
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

* **Compatibility**: The three handshake payload IDs and all field encodings
  must be defined once in Common Mod. Keepalive probes use vanilla packets and
  work with modded and unmodded clients. Protocol version mismatch must decline
  offloading rather than attempt downgrade.
* **Failure isolation**: Invalid packets, timeout, disconnect, and cache
  incompatibility must leave normal Minecraft play and authoritative ownership
  unchanged. Handshake failures must not crash or kick the player.
* **Timing**: Probe deadlines use real-server ticks. RTT is measured in
  milliseconds from the Netty write to the Netty echo, independent of TPS, and
  the median comparison accepts a value equal to `max_ping`. A frozen client
  window inflates RTT; V1 documents this edge without a workaround.
* **State safety**: Session IDs are fresh per accepted connection. Pending
  probes and negotiation state must not survive disconnect or be shared between
  players.
* **Capacity safety**: `SessionManager` atomically increments a numeric
  reserved-slot count before sending Accept and decrements it on disconnect.
  No sub-server is created on this branch and there is no sync/bootstrap
  failure path to release a slot.
* **Storage safety**: The disk allowance is a signed 64-bit byte count; negative
  values are rejected and zero declines. The handshake only transmits and
  checks that value: this branch performs no cache writes, reservation, or
  eviction.

## Risks & Mitigations

* **Malformed or oversized packets**: Bound all strings, lists, integers,
  manifest entries, UUID fields, and cached metadata before allocation. Reject
  malformed packets as `failed negotiation` and send no data derived from the
  invalid declaration.
* **Replay or stale probe packets**: Bind each keepalive ID to its connection
  and sequence. While `PROBING`, `HandshakeHandler` sends
  `Decline(failed negotiation)` for duplicate, stale-sequence, or late echoes
  from one of its IDs. Unknown IDs go to vanilla and may disconnect the client;
  every owned ID leaves the active set before a terminal state and is retired
  until disconnect so late or duplicate echoes cannot reach vanilla. A session
  ID is random per accepted connection and is never
  persisted as a cache key.
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

* **Rationale**: Define the shared wire contract for the three Fabric custom
  handshake payloads: `coworld:hello`, `coworld:accept`, and `coworld:decline`.
  RTT probes are vanilla keepalives and have no CoWorld codec.
* **Implemented content**: Record fields, encode/decode behavior, bounded
  integer and string validation, nullable `schema_id` plus schema version,
  bounded manifest metadata, Decline reasons and names, and a lenient Hello
  decoder that returns an invalid payload. Stale probe rejection is handled by
  `PingProber`, not by this codec.

#### 2. `Common Mod/src/main/java/gladiatorsarius/coworld_common/protocol/ProtocolVersion.java` (Create or modify)

* **Rationale**: Keep the exact wire-format identifier in the shared module.
* **Implemented content**: Exposes the V1 value and exact-match compatibility
  check used by both endpoints, plus the `V1_SCHEMA_ID` / `V1_SCHEMA_VERSION`
  constants sent in Hello. There is no downgrade or lowest-common-version
  selection.

#### 3. `Common Mod/src/main/java/gladiatorsarius/coworld_common/config/CoWorldDefaults.java` (Create or modify)

* **Rationale**: Centralize handshake defaults with the other shared defaults.
* **Planned changes**: Define `log_level = normal`, `max_ping = 70`,
  `handshake_hello_timeout_ticks = 100`, and
  `ping_probe_timeout_ticks = 100`, plus the existing ownership-radius and
  sync-limit defaults consumed by Accept.

#### 4. `Server Mod/src/main/java/gladiatorsarius/coworld_server/config/CoWorldConfig.java` (Create or modify)

* **Rationale**: Load and validate server settings before the handshake uses
  them.
* **Planned changes**: Reject invalid negative or noninteger values and invalid
  `log_level`, expose handshake deadlines and ping cap, and preserve the
  inclusive `max_ping` comparison and nonnegative radius rules. Read
  `offload_enabled` before accepting a Hello and decline new offloading when it
  is false.

#### 4a. `Server Mod/src/main/java/gladiatorsarius/coworld_server/log/CoWorldLog.java` (Create)

* **Rationale**: Gate debug lines by `log_level` and keep component tags in
  every CoWorld line.
* **Implemented content**: Normal terminal handshake results and always-shown
  warnings/errors are emitted directly; debug lines carry `[handshake]` tags and
  include probe IDs, timestamps, decisions, and discard reasons. No session ID
  or other token-like value is logged.

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

* **Rationale**: Isolate the three keepalive probes, write/echo timestamps, echo
  matching, and RTT median calculation. Deadline and ping-cap policy stay in
  `HandshakeHandler`.
* **Implemented content**: Sends `ClientboundKeepAlivePacket`s sequentially with
  negative random IDs, records the write timestamp on the Netty thread, records
  echoes from the server network thread, requires three samples, and calculates
  the median. `HandshakeHandler` rejects stale, duplicate, and late echoes,
  applies the per-probe deadline, and compares the median with `max_ping`.

#### 6a. `Server Mod/src/main/java/gladiatorsarius/coworld_server/mixin/` (Create)

* **Rationale**: Intercept vanilla keepalive traffic without changing the client.
* **Implemented content**: `ServerKeepAliveMixin` cancels owned echoes before
  vanilla can disconnect the player; `ConnectionSendMixin` records the send
  timestamp at the Netty write. Unknown IDs are never consumed.

#### 7. `Server Mod/src/main/java/gladiatorsarius/coworld_server/handshake/SessionManager.java` (Create)

* **Rationale**: Own accepted session identity and reserved capacity.
* **Implemented content**: Generates a fresh session ID per accepted connection
  and atomically reserves a numeric slot before Accept. Reserved capacity is
  released on disconnect. There is no sub-server, bootstrap failure path,
  post-accept ping monitoring, or hand-back behavior.

#### 8. `Server Mod/src/main/java/gladiatorsarius/coworld_server/CoWorldServer.java` (Modify)

* **Rationale**: Register the server payload handlers and connect handshake
  lifecycle hooks to login, tick, and disconnect. No sync or rejoin service is
  connected on this branch.
* **Planned changes**: Wire HandshakeHandler, PingProber, SessionManager,
  validated configuration, tagged logging, and the two keepalive mixins without
  placing packet definitions in the server module.

#### 9. `Client Mod/src/main/java/gladiatorsarius/coworld_client/handshake/HelloSender.java` (Create)

* **Rationale**: Build the client declaration from settings and current client
  state.
* **Implemented content**: Sends versions, offloading preference, a signed
  64-bit disk allowance, simulation distance, and the live schema ID/version in
  Hello. The overload that accepts a `CacheDeclaration` is unused by the join
  path, so no stable identities, manifest hashes, or format versions are sent.

#### 10. Probe echo in `ClientNetworkBridge.java`

* **Rationale**: No client probe handler is needed; vanilla echoes keepalives.
* **Implemented content**: The bridge no longer registers a custom probe
  receiver. Modded and vanilla clients both use vanilla keepalive handling.

#### 11. `Client Mod/src/client/java/gladiatorsarius/coworld_client/client/ClientNetworkBridge.java` (Create)

* **Rationale**: Keep Fabric client-only networking calls out of common client
  code and expose the shared payload registration to the client entrypoint.
* **Implemented content**: Registers Accept and Decline receivers, sends Hello,
  and logs the terminal result without the session UUID. `CoWorldClient`
  registers the three payload codecs; no common client handshake state or
  sync/rejoin orchestrator is driven by those results.

#### 12. `Client Mod/src/main/java/gladiatorsarius/coworld_client/CoWorldClient.java` (Modify)

* **Rationale**: Register the shared payload types on the client. The Hello
  flow and result logging live in `ClientNetworkBridge`.
* **Implemented content**: Registers the three payload codecs only. There are no
  cache metadata providers, no accepted-metadata handoff, and no sync or rejoin
  orchestrator on this branch.

#### 13. `docs/handshake.md` (Modify)

* **Rationale**: Keep the design contract synchronized with the concrete wire
  identifiers and schema decision.
* **Planned changes**: Document the three handshake payload IDs, vanilla
  keepalive probes, exact packet contents, nullable `schema_id` and integer
  version matching, client-only result logging, and the state and timeout rules.

#### 14. `docs/folder-structure.md` (Modify)

* **Rationale**: Record the files and responsibilities added by this design.
* **Planned changes**: Replace planned placeholders with the finalized
  handshake file list and note the client-only network bridge boundary.

#### 15. `Common Mod/src/test/java/gladiatorsarius/coworld_common/protocol/CoWorldPacketsTest.java` (Not present)

* **Status**: Removed with the rest of the JUnit test code. Wire validation is
  checked by manual or in-game verification only.

#### 16. `Server Mod/src/test/java/gladiatorsarius/coworld_server/handshake/HandshakeHandlerTest.java` (Not present)

* **Status**: Removed with the rest of the JUnit test code. Handshake state
  transitions are checked by manual or in-game verification only.

#### 17. `Server Mod/src/test/java/gladiatorsarius/coworld_server/handshake/PingProberTest.java` (Not present)

* **Status**: No dedicated prober test. The three sequential probes, unchanged
  echoes, median equality/above-cap behavior, and deadline expiry have no
  automated test.

#### 18. `Server Mod/src/test/java/gladiatorsarius/coworld_server/handshake/SessionManagerTest.java` (Not present)

* **Status**: No dedicated session test. Session ID generation, slot
  reservation, and slot release have no automated test.

#### 19. `Client Mod/src/test/java/gladiatorsarius/coworld_client/handshake/HelloSenderTest.java` (Not present)

* **Status**: No HelloSender or client-settings test. Client behavior is
  checked by manual or in-game verification only.

#### 20. Probe echo coverage

* **Rationale**: Confirm vanilla echoes every keepalive ID unchanged.
* **Implemented content**: Neither endpoint has client probe code; the vanilla
  client echoes any keepalive ID immediately. Cancel-before-vanilla and
  unknown-ID forwarding need a manual or in-game check.

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

* **Test policy**: No JUnit test code is retained in any mod. Verification is
  manual or in-game only; no source-compiled harness replaces it.

* **Common build**: Run `./gradlew build` inside `Common Mod/`.
* **Server build**: Run `./gradlew build` inside `Server Mod/`.
* **Client build**: Run `./gradlew build` inside `Client Mod/`.
* **Protocol checks**: The three payload IDs and their codecs are registered by
  both entrypoints; exact V1 matching needs a manual or in-game check. There is
  no round-trip encoding suite.
* **Behavior checks**: Manually check an unexpected keepalive before Hello,
  packets after a terminal state, a null schema on both sides, and three
  vanilla echoes reaching Accept. Other failure paths need manual or in-game
  checks; there is no cache mismatch path on this branch.
* **Timing checks**: The handler requires three probes and the prober computes
  the median from network-thread timestamps. Median equality/above-cap
  behavior, TPS independence, both deadlines, and the frozen-window edge have
  no automated test.
* **Integration check**: Verify that the terminal result is logged at normal,
  that debug logs show queued/written/echoed timings with tagged lines, and
  that an incompatible or absent client continues normal play. There is no sync
  or rejoin follow-up and no chunk transfer to verify on this branch.

--------------------------------------------------------------------------------

*Drafted with EGM*
