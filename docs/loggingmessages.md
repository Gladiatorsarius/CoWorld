# Logging messages

Every log message that exists, and what it is for.

## Normal

| Tag | Message | Purpose |
|---|---|---|
| `[common]` | `CoWorld common protocol loaded` | Confirm the shared protocol initialized. |
| `[handshake]` | `accepted player={} radius={}` | Server terminal result for an accepted handshake. |
| `[handshake]` | `declined player={} reason={}` | Server terminal result for a declined handshake. |
| `[handshake]` | `accepted` | Client confirmation of an Accept payload. |
| `[handshake]` | `declined reason={}` | Client confirmation of a Decline payload. |

## Debug

| Tag | Message | Purpose |
|---|---|---|
| `[handshake]` | `hello accepted player={} radius={} schema={}:{} protocol={}` | Hello validation inputs and requested radius. |
| `[handshake]` | `probe queued player={} sequence={} id={} queued_ns={}` | Keepalive probe queued for sending. |
| `[handshake]` | `probe written player={} sequence={} id={} delay_ms={}` | Network-thread write and queue-to-write delay. |
| `[handshake]` | `probe echoed player={} sequence={} id={} echo_ns={}` | Network-thread echo timestamp. |
| `[handshake]` | `probe rejected player={} sequence={} reason=unexpected sequence` | Echo sequence mismatch. |
| `[handshake]` | `probe echo without write player={} sequence={} id={}` | Echo observed before its write timestamp. |
| `[handshake]` | `probe accepted player={} sequence={} id={} rtt_ms={} samples={}/3` | Accepted RTT sample and progress. |
| `[handshake]` | `decision player={} samples_ms={} median_ms={} max_ping_ms={} result={}` | Final samples, median, cap, and result. |
| `[handshake]` | `probe deadline expired player={} sequence={} tick={}` | Per-probe timeout. |
| `[handshake]` | `decline player={} reason={}` | Decline reason on a terminal path. |
| `[handshake]` | `discarded player={} reason=disconnect` | Connection closed; active and retired probe IDs are cleared. |
| `[handshake]` | `echo consumed after discard player={} id={}` | Late or duplicate echo canceled instead of reaching vanilla. |
| `[handshake]` | `echo forwarded to vanilla player={} id={}` | Unknown keepalive ID left to vanilla handling. |

## Warnings and errors (always shown)

| Tag | Message | Purpose |
|---|---|---|
| `[config]` | `invalid CoWorld server config {}: {}` | Server config could not be read or validated. |
| `[config]` | `could not write CoWorld server config {}` | Server default config could not be written. |
| `[config]` | `invalid CoWorld client config {}; offloading disabled` | Client config failed closed. |
| `[config]` | `could not write CoWorld client config {}` | Client default config could not be written. |
