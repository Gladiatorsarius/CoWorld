# CoWorld Handshake

*   **Author:** gladiatorsarius
*   **Status:** Draft
*   **Date:** 2026-11-22

--------------------------------------------------------------------------------

## Overview

CoWorld needs a connection-scoped negotiation before it can move simulation work
from the real server to a player's computer. The handshake uses custom packets
on the existing Minecraft connection. It reports client capabilities and cache
metadata, measures network latency with three required echo probes, and then
either authorizes synchronization or declines offloading.

The handshake must not become a new login requirement. A client that does not
have CoWorld, opts out, fails validation, or misses a deadline continues normal
play on the real server. An accepted handshake creates a fresh live session and
authorizes synchronization; it does not grant chunk ownership or start ticking
chunks.

## The Problem

The server cannot safely send a client world state or assign simulation work
without first answering several questions:

* Which CoWorld and wire-protocol versions can both sides use?
* Does the client want offloading and have enough disk allowance for the cache?
* Is the connection responsive enough for interactive simulation?
* Can an existing cache be considered compatible with this server and world?
* Can the server decline without kicking a player or changing chunk ownership?

A failed or incomplete negotiation must leave the authoritative server world
unchanged. A successful negotiation must provide enough information for the
later sync or rejoin flow to choose the right path, while keeping cache claims
and ownership decisions separate from the handshake.

## Goals

* Exchange a versioned Hello, three unchanged ping echoes, and one terminal
  result per negotiated connection over the existing Minecraft connection.
  A normal timeout or disconnect sends no Decline; terminal outcomes are not
  duplicated.
* Reject unsupported versions, invalid fields, zero or invalid disk allowance,
  missed probes, late probes, and median RTT above `max_ping` without kicking
  the player.
* Compute the accepted candidate radius as the smaller of the client's
  simulation distance and the configured maximum ownership radius.
* Create a fresh connection-scoped session ID only after successful
  negotiation; never treat it as a persistent cache key or a resumed session.
* Preserve normal real-server play when the client has no CoWorld support,
  declines offloading, disconnects, or times out.
* Route compatible cache metadata to the rejoin flow and incompatible or
  unusable cache metadata to initial sync, without transferring chunks during
  the handshake.
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

* The snapshot adapter still needs to define the schema represented by
  `schema_id` and its integer version. Until then, a null value or exact-match
  failure selects initial sync.
* V1 live offloading is disabled when no versioned snapshot-adapter manifest
  and concrete live schema IDs exist. Full sync cannot enable unsupported
  adapter state.

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
  checks, Accept/Decline construction, and transition into sync or rejoin.
* `Server Mod/src/main/java/gladiatorsarius/coworld_server/handshake/PingProber.java`
  sends three sequential probes, validates unchanged echoes, applies the
  per-probe deadline, calculates the median RTT, and compares it inclusively
  with `max_ping`.
* `Server Mod/src/main/java/gladiatorsarius/coworld_server/handshake/SessionManager.java`
  creates fresh session IDs after acceptance and tracks ongoing high-ping ticks.
  Disconnect cleanup removes all connection-scoped state.
* `Client Mod/src/main/java/gladiatorsarius/coworld_client/handshake/HelloSender.java`
  builds Hello from client settings, simulation distance, versions, and the
  cache compatibility declaration.
* `Client Mod/src/main/java/gladiatorsarius/coworld_client/handshake/PingEchoResponder.java`
  immediately echoes each valid probe payload unchanged and does not fabricate
  a reply after a deadline.

### Packet schema

The five handshake packets use the common Fabric custom-payload codec.
Strings use
UTF-8 and are limited by encoded byte length, never character count. UUIDs use exactly 16 bytes in most-significant-byte-first order.
Unsigned values cannot encode negatives. Cache declarations and manifest lists
are bounded before allocation so a malformed packet cannot force unbounded
memory use. The complete Hello payload is at most 64 MiB. Individual string
fields are UTF-8, length-bounded, and counted by encoded byte length. The
cached execution-manifest portion is at most 32 MiB; all manifest framing,
cache UUIDs, versions, schema fields, and Hello framing remain inside the
separate 64 MiB complete-payload limit.

Every decoder must consume exactly the declared fields and reject trailing or
padding bytes. It must reject malformed length prefixes before allocating.

| Record | Field | Type and bounds | Required validation |
|---|---|---|---|
| Hello | release version | UTF-8 string, 1-32 encoded bytes | Exact CoWorld release match for V1 |
| Hello | protocol version | Unsigned 32-bit integer | Exact V1 value; no downgrade |
| Hello | offloading preference | Boolean | Required |
| Hello | disk allowance | Unsigned 64-bit integer, bytes | Reject missing, negative, or noninteger input; zero means Decline |
| Hello | client simulation distance | Unsigned 32-bit integer, chunks | Reject values outside the signed 32-bit server model used by Minecraft; zero remains valid |
| Hello | live snapshot schema ID | Nullable UTF-8 string, 1-128 encoded bytes | Presence bit distinguishes absent from present; live server and client values must match for offloading |
| Hello | live snapshot schema version | Unsigned 32-bit integer | If live schema ID is absent, encode 0; otherwise exact server/client match is required |
| Hello | cache declaration | Presence byte plus optional declaration | `0` means no cache; `1` means one complete declaration; all other values are malformed |
| Cache declaration | stable server UUID | Exactly 16 bytes | Required when a cache is declared |
| Cache declaration | stable world UUID | Exactly 16 bytes | Required when a cache is declared |
| Cache declaration | cached CoWorld version | UTF-8 string, 1-32 encoded bytes | Compared after Accept |
| Cache declaration | cached Minecraft version | UTF-8 string, 1-32 encoded bytes | Compared after Accept |
| Cache declaration | cache format version | Unsigned 32-bit integer | Compared after Accept |
| Cache declaration | hash format version | Unsigned 32-bit integer | Compared after Accept |
| Cache declaration | snapshot `schema_id` | Nullable UTF-8 string, 1-128 encoded bytes | An explicit presence bit distinguishes null from an empty or invalid string |
| Cache declaration | snapshot schema version | Unsigned 32-bit integer | If `schema_id` is absent, encode 0 and reject any nonzero value; if present, exact match is required |
| Cache declaration | cached execution manifest | Bounded manifest entry list, at most 100,000 entries and 32 MiB of encoded manifest bytes | `u32` entry count; each entry uses `u8` root kind, `u32` UTF-8 path-byte length, path bytes, `u64` stored length, and exactly 32 hash bytes; reject malformed lists |
| Manifest entry | root kind | Unsigned 8-bit enum: `mods=1`, `config=2`, `versions=3`, `libraries=4` | Required; the server compares against the canonical execution-manifest format |
| Manifest entry | relative path | UTF-8 bytes, at most 4,096 bytes | No absolute path, `..`, symlinks, platform normalization collisions, or duplicate normalized paths |
| Manifest entry | stored byte length | Unsigned 64-bit integer | Must agree with the cached file's verified or declared length under the server-side manifest rules |
| Manifest entry | SHA-256 content hash | Exactly 32 bytes | Must match canonical digest bytes |

Manifest entries use unsigned UTF-8 lexicographic key order by root kind first
and normalized relative-path bytes second. Normalize separators to `/`, reject
absolute paths and `..` segments, and do not apply Unicode normalization.
Reject duplicate normalized paths, platform case/normalization collisions, and
entries outside one of the four root kinds. Encode strings and paths as
unsigned byte-length prefixes followed by raw bytes, with no terminator or end
tag. Integers are signed/unsigned big-endian according to their table type.

| Record | Field | Type and bounds | Required validation |
|---|---|---|---|
| PingProbe | probe nonce | Unsigned 64-bit integer | Unique for the outstanding probe and not reused within the connection |
| PingProbe | probe sequence | Unsigned 8-bit integer, 1-3 | Must match one of the three sequential probe positions |
| PingEcho | echoed nonce | Unsigned 64-bit integer | Must exactly equal the current PingProbe nonce |
| PingEcho | echoed sequence | Unsigned 8-bit integer, 1-3 | Must exactly equal the current PingProbe sequence |
| Accept | protocol version | Unsigned 32-bit integer | Must equal the agreed protocol |
| Accept | requested ownership radius | Unsigned 32-bit integer, chunks | `min(client simulation distance, max_ownership_radius)` |
| Accept | sync speed limit | Unsigned 64-bit integer, bytes/second | Must be the configured server value |
| Accept | session ID | 16-byte UUID | Freshly generated for this accepted connection |
| Decline | reason enum | Unsigned 8-bit integer | Exact mapping: `offloading disabled=1`, `client opted out=2`, `capacity reached=3`, `ping above the cap=4`, `incompatible versions=5`, `failed negotiation=6` |

The server accepts a PingEcho only when it belongs to the current
connection, sequence, and nonce. Stale, out-of-order, duplicate, unknown,
cross-connection, changed, invalid, or late replies do not create a RTT sample.
The session ID is random 16-byte UUID data; it is validated only by the live
server session record and is never derived from persistent cache identity.

### Rejection routing

| Input or event | Result sent to client | Exact route |
|---|---|---|
| No Hello received by the Hello deadline | None | Continue normal play |
| Malformed Hello or invalid integer/string field | Decline, reason `failed negotiation` | Continue normal play |
| CoWorld or protocol version mismatch | Decline, reason `incompatible versions` | Continue normal play |
| Server and client do not support the same live snapshot adapter/schema | Decline, reason `incompatible versions` | Continue normal play; full sync cannot repair unsupported mapping |
| Server `offload_enabled=false` | Decline, reason `offloading disabled` | Continue normal play; existing owners are requested to hand back |
| Client preference is opt-out | Decline, reason `client opted out` | Continue normal play |
| Zero disk allowance | Decline, reason `offloading disabled` | Continue normal play |
| Requested ownership radius is zero | Decline, reason `offloading disabled` | Continue normal play; do not reserve sub-server capacity or Accept |
| Server offloading capacity unavailable | Decline, reason `capacity reached` | Continue normal play |
| Missing, invalid, duplicate, out-of-order, changed, or late ping reply | Decline, reason `failed negotiation` | Require all three valid replies; continue normal play |
| Median RTT above `max_ping` | Decline, reason `ping above the cap` | Continue normal play |
| Median RTT at or below `max_ping` | Accept | Begin cache validation and sync/rejoin routing |
| Connection ends during Hello or probing | None | Discard state; no ownership or chunk change |
| Malformed cache declaration | Decline, reason `failed negotiation` | Cache claims are declared by Hello and are not proof of compatibility |

Missing, negative, or noninteger disk allowance follows the malformed-Hello
branch and sends `failed negotiation`. The server never sends Decline after a
disconnect or missing-Hello timeout.

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
                                      --> ACCEPT + SessionId --> SYNC_OR_REJOIN
```

After acceptance, the live session has one additional state transition:

The client follows the same connection-local flow from its side:

```text
send Hello -> echo probe 1 -> echo probe 2 -> echo probe 3
                                  |
                         Accept -> wait for sync/rejoin
                         Decline/timeout -> remain in normal play
```

### Hello validation and cache handoff

Hello carries the CoWorld release version, exact protocol version, offloading
preference, nonnegative integer disk allowance, client simulation distance, and
live snapshot-adapter identity/version plus one cache declaration. The live
adapter fields use the same nullable-ID/presence rule as cache metadata. The
server and client live adapter IDs and versions must match exactly before
Accept. The declaration is either no previous cache or a record
containing the stable server and world UUIDs, cached CoWorld and Minecraft
versions, cache-format and hash-format versions, a nullable `schema_id` string
and integer schema version, and the cached execution manifest with file hashes.
When `schema_id` is absent, the field's presence bit is false and the schema
version is zero; a present ID is a 1-128-encoded-byte UTF-8 string and uses the
exact schema version declared with it.

The server rejects malformed values and a zero allowance for offloading, but it
does not reject an otherwise compatible connection merely because the cache is
old or unusable. After Accept, cache compatibility and integrity validation
select rejoin or initial sync. `schema_id` and its version must match exactly; null or mismatch selects initial
sync. Completed chunk inventory and per-chunk hashes are exchanged through
rejoin, not used as proof during Hello.

In V1, a null or mismatched snapshot `schema_id` is a cache-routing decision,
not a live CoWorld/protocol incompatibility. It keeps the handshake compatible
and routes validation toward initial sync. The snapshot-adapter contract is
separate from this routing: if the server and client do not support the same
adapter/schema mapping, affected state is ineligible for canonical hashing,
cache reuse, and offloading. Full initial sync cannot repair that unsupported
mapping, and the implementation must not claim offloading readiness for that
state. A null schema on the initial V1 implementation always means initial
sync rather than rejoin.

Before probing completes, the server checks whether the live server and client
adapter capabilities match. If they do not, it declines with
`incompatible versions` instead of reserving capacity or sending Accept.
Initial sync remains available only for a compatible live adapter whose cached
copy is missing, stale, corrupt, or otherwise unusable.

For V1, a versioned live adapter manifest and concrete live adapter IDs are
required before offloading. If either endpoint has no implementation-ready
adapter, offloading is ineligible and the server continues normal play rather
than treating an initial sync as proof of adapter support.

### Accept and Decline behavior

Accept contains the agreed protocol, the candidate radius
`min(client simulation distance, max_ownership_radius)`, the configured sync
speed limit, and a newly generated session ID. It authorizes synchronization
only. Ownership claims, redstone-set rules, occupancy checks, TPS reductions,
readiness, and handover commitment remain in their existing components.

If the computed candidate radius is zero, the server sends
`offloading disabled` instead of Accept. It does not reserve sub-server
capacity and does not start sync or rejoin for that result.

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

After acceptance, `SessionManager` counts consecutive real-server ticks whose
built-in player ping exceeds `max_ping`. Reaching `high_ping_ticks` begins the
existing hand-back flow, and a measurement at or below the cap resets the
counter.

### Data flow

```text
Client HelloSender
        |
        v
Common CoWorldPackets <----> Server HandshakeHandler
        ^                             |
        |                             +--> PingProber
PingEchoResponder                     |
                                      +--> SessionManager
                                      |
                         Accept ---> Sync coordinator
                         cache ----> Rejoin validator / Initial sync
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
* **Capacity safety**: Capacity acceptance atomically reserves a sub-server slot
  for the live session before sending Accept. The reservation is released if
  sync or bootstrap fails and follows startup, active, hand-back, and disconnect
  states. No separate handshake may consume the same slot without the atomic
  reservation succeeding.
* **Storage safety**: The disk allowance is a nonnegative byte budget for all
  cache data, including temporary and replacement files. The handshake only
  declares the budget; sync remains responsible for reservation and writes.

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
  on timeout or disconnect. Do not create ownership or cache work before
  Accept. Do not reserve sub-server capacity during Hello or ping probing;
  reserve it atomically only after all probes pass and immediately before
  sending Accept.
* **Untrusted cache declarations**: Treat declarations as claims only. Compare
  server/world UUIDs, versions, manifest hashes, and schema identities with
  server-owned authority after Accept. Do not trust a Hello claim to prove
  integrity or security.
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

* Define the snapshot-adapter schema represented by `schema_id` and its integer
  version once the adapter is designed.
* Add an explicit protocol capability negotiation only if a future wire format
  requires compatible feature subsets; V1 deliberately requires exact matching.
* Mid-session simulation-distance changes are specified in `docs/coremodel.md`.

## Detailed Implementation Plan

### Single implementation list

#### 1. `Common Mod/src/main/java/gladiatorsarius/coworld_common/protocol/CoWorldPackets.java` (Create)

* **Rationale**: Define the shared wire contract for the five Fabric custom
  handshake payloads: `coworld:hello`, `coworld:ping_probe`,
  `coworld:ping_echo`, `coworld:accept`, and `coworld:decline`.
* **Planned changes**: Specify record fields, encode/decode behavior, bounded
  integer and string validation, nullable `schema_id` plus schema version,
  bounded manifest metadata, nonce and sequence fields, Decline reasons, stale
  probe rejection, and the rule that PingEcho preserves the probe identity
  unchanged.

#### 2. `Common Mod/src/main/java/gladiatorsarius/coworld_common/protocol/ProtocolVersion.java` (Create or modify)

* **Rationale**: Keep the exact wire-format identifier in the shared module.
* **Planned changes**: Expose the V1 value and exact-match compatibility check
  used by both endpoints; do not implement downgrade or lowest-common-version
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
* **Planned changes**: Start the Hello deadline at login, accept one valid
  Hello, invoke PingProber, send exactly one Accept or Decline, route accepted
  cache metadata to rejoin or initial sync after Accept, and discard state on
  disconnect. Check the server-wide offloading switch and candidate radius
  and live adapter capability before Ping or capacity reservation. Reserve
  capacity only after probes pass and immediately before Accept. Missing Hello
  becomes normal play without a Decline.

#### 6. `Server Mod/src/main/java/gladiatorsarius/coworld_server/handshake/PingProber.java` (Create)

* **Rationale**: Isolate the three-probe measurement and deadline logic.
* **Planned changes**: Send probes sequentially, bind each reply to its
  outstanding probe, reject changed, duplicate, invalid, or late replies,
  require all three samples, calculate the median RTT, and compare it to
  `max_ping`.

#### 7. `Server Mod/src/main/java/gladiatorsarius/coworld_server/handshake/SessionManager.java` (Create)

* **Rationale**: Own accepted session identity and post-accept ping monitoring.
* **Planned changes**: Generate a fresh session ID per accepted connection,
  track consecutive over-cap ping ticks, reset at or below the cap, trigger
  existing hand-back behavior at `high_ping_ticks`, atomically reserve
  sub-server capacity before Accept and release it on failure or disconnect,
  clean up on disconnect.

#### 8. `Server Mod/src/main/java/gladiatorsarius/coworld_server/CoWorldServer.java` (Modify)

* **Rationale**: Register the server payload handlers and connect handshake
  lifecycle hooks to login, tick, disconnect, sync, and rejoin services.
* **Planned changes**: Wire HandshakeHandler, PingProber, SessionManager, and
  validated configuration without placing packet definitions in the server
  module.

#### 9. `Client Mod/src/main/java/gladiatorsarius/coworld_client/handshake/HelloSender.java` (Create)

* **Rationale**: Build the client declaration from settings and current client
  state.
* **Planned changes**: Send versions, offloading preference, nonnegative disk
  allowance, simulation distance, and the cache declaration with stable
  identities, manifest hashes, format versions, nullable `schema_id`, and
  schema version. Send the live snapshot-adapter ID and version in Hello;
  leave cache-adapter identity separate from live-adapter compatibility.

#### 10. `Client Mod/src/main/java/gladiatorsarius/coworld_client/handshake/PingEchoResponder.java` (Create)

* **Rationale**: Provide the client half of the required probe exchange.
* **Planned changes**: Register the ping-probe receiver and immediately return
  an unchanged PingEcho for each valid outstanding probe. Never invent a reply
  for an expired or unknown probe.

#### 11. `Client Mod/src/client/java/gladiatorsarius/coworld_client/client/ClientNetworkBridge.java` (Create)

* **Rationale**: Keep Fabric client-only networking calls out of common client
  code and expose the shared payload registration to the client entrypoint.
* **Planned changes**: Register the five client payload handlers, send Hello and
  echoes through the existing Minecraft connection, deliver Accept or Decline
  to the common client handshake state.

#### 12. `Client Mod/src/main/java/gladiatorsarius/coworld_client/CoWorldClient.java` (Modify)

* **Rationale**: Start the Hello flow after login and route the result into sync
  or normal-play behavior.
* **Planned changes**: Wire HelloSender and cache metadata providers, accept the
  server's decision, log Decline reasons without an in-game message, and pass
  accepted metadata to the sync or rejoin orchestrator.

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
* **Planned changes**: Test round-trip encoding for all five handshake records,
  each payload ID, malformed and out-of-range fields, nullable schema values,
  Decline reason values, nonce/sequence binding, and unchanged probe/echo
  payload identity.
  Include total Hello size limits, manifest entry and path limits, canonical
  root-kind/path ordering and oversized declaration rejection.

#### 16. `Server Mod/src/test/java/gladiatorsarius/coworld_server/handshake/HandshakeHandlerTest.java` (Create)

* **Rationale**: Verify server state transitions and fallback behavior.
* **Planned changes**: Cover valid acceptance, missing Hello, invalid fields,
  version mismatch, opt-out, zero allowance, duplicate terminal decisions,
  server-wide offloading disable, radius zero, disconnect cleanup, and routing
  to sync versus rejoin after Accept. Cover live adapter mismatch and
  post-probe capacity reservation.

#### 17. `Server Mod/src/test/java/gladiatorsarius/coworld_server/handshake/PingProberTest.java` (Create)

* **Rationale**: Verify the measurable latency policy.
* **Planned changes**: Cover three sequential probes, unchanged echoes,
  invalid/late/missing replies, median calculation, equality at `max_ping`,
  rejection above the cap, and the requirement that fewer than three samples
  cannot pass.

#### 18. `Server Mod/src/test/java/gladiatorsarius/coworld_server/handshake/SessionManagerTest.java` (Create)

* **Rationale**: Verify connection-scoped session identity and ongoing ping
  handling.
* **Planned changes**: Cover fresh IDs on reconnect, consecutive over-cap tick
  counting, reset at or below the cap, hand-back trigger, atomic capacity
  reservation/release, and disconnect cleanup.

#### 19. `Client Mod/src/test/java/gladiatorsarius/coworld_client/handshake/HelloSenderTest.java` (Create)

* **Rationale**: Verify that the client declares the exact settings and cache
  metadata required by the server.
* **Planned changes**: Cover opt-in and opt-out, disk allowance boundaries,
  simulation distance, no-cache declaration, complete cache declaration,
  nullable schema ID, and schema version.

#### 20. `Client Mod/src/test/java/gladiatorsarius/coworld_client/handshake/PingEchoResponderTest.java` (Create)

* **Rationale**: Verify the client never alters or fabricates probe replies.
* **Planned changes**: Cover immediate unchanged echoes and ignored unknown,
  duplicate, malformed, or expired probes.

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

* **Common build**: Run `./gradlew build` inside `Common Mod/`.
* **Server build and tests**: Run `./gradlew build` inside `Server Mod/`.
* **Client build and tests**: Run `./gradlew build` inside `Client Mod/`.
* **Protocol checks**: Confirm all five handshake IDs, field encodings, exact
  protocol matching, and round-trip tests pass.
* **Behavior checks**: Confirm normal play survives no Hello, invalid Hello,
  failed probes, version mismatch, opt-out, disconnect, and cache mismatch.
* **Timing checks**: Confirm three probes are mandatory, median equality passes,
  above-cap median declines, and each timeout uses real-server ticks.
* **Integration check**: With a compatible client, verify Accept leads to sync
  or rejoin without transferring chunks during the handshake. With an
  incompatible or absent client, verify normal play continues.

--------------------------------------------------------------------------------

*Drafted with EGM*
