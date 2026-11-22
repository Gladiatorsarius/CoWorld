# Ownership handover

## Purpose

Move chunk simulation between the real server and a sub-server, or between two sub-servers through the real server. Each chunk has one authoritative owner. Optional shadow simulation never publishes a second set of results.

The requested region is an inclusive square of chunk coordinates around the player's current chunk. Its radius is `min(client simulation distance, max_ownership_radius)`. Only synced, eligible chunks can move. Existing claims follow [arbitration](multi-client-arbitration.md); connected [redstone sets](redstone.md) move as whole units.

## State and readiness

A snapshot is the complete state after a finished tick N: blocks, block entities, entities and their full transferable state, scheduled block/fluid ticks, redstone state, and random state needed to continue simulation. It carries the chunk identity, version, owner generation, and tick number. A chunk identity includes its dimension and chunk X/Z coordinates.

The source keeps ticking while the destination loads this snapshot and applies subsequent ordered deltas. Ready means the destination has the snapshot and every delta through an explicitly identified completed tick/version, not merely that a download finished or a local queue is empty.

## Transfer

1. The real server coordinates one transfer at a time for the chunk or redstone set. The current owner sends its end-of-tick snapshot and keeps ticking and sending deltas.
2. The destination acknowledges readiness at a specified tick/version. This is preparation, not an ownership change, and does not require the source to stop at a tick that has already passed.
3. At a later completed tick M, the source stops authoritative ticking for the transfer unit. It sends the remaining deltas through M and a final commit marker identifying that exact state.
4. The destination applies everything through M and confirms that final state is installed. Only then does the real server commit the new owner generation and authorize the destination to begin with logical tick M+1. The source cannot resume or publish late results under its old generation after that commit.
5. Preparation does the large state transfer while the source is still running. Only the final catch-up and commit sit on the paused path. Logical ticks are contiguous, but network delay can cause a short wall-clock pause; there is no zero-latency promise.

Before commitment, `handover_offer_timeout_ticks` (default 100 ticks, 5 seconds at 20 TPS) aborts a stalled attempt. The old owner retains ownership and, if already stopped, resumes from its final state. The real server serializes abort versus commit so both cannot succeed. Late messages for an aborted transfer are ignored. A later attempt starts from current state. After commitment, failure follows the new owner's [disconnect/recovery rules](disconnect.md), not an obsolete offer.

## Triggers

- Join: start the sub-server without ownership, then transfer eligible synced chunks.
- Movement: entering chunks sync first, with movement direction prioritized; leaving chunks return through the same protocol.
- Simulation-distance changes: recompute the requested radius and transfer the affected eligible chunks.
- Overload or sustained high ping: reduce offloading using the same protocol.
- Clean logout: wait the disconnect grace period, then keep trying until hand-back is confirmed. Individual preparation attempts may time out; clean shutdown has no overall deadline.

Routine handovers in either direction must not move a chunk containing an unmodded player, moving or stationary. Check the player's occupied chunk, not a protection radius around them. Recheck eligibility before the final stop, not only when an offer starts. One occupied member pins an indivisible redstone set.

The four exceptions are clean owner shutdown, failure recovery, required conflicting-redstone consolidation, and transfer when the old sub-server owner's player has moved more than `vanilla_player_handover_release_distance` chunks away from the occupied chunk. That last transfer can go to the real server or to another eligible sub-server; distance is measured from the old owner's player, never the incoming owner's player. Use `max(abs(dx), abs(dz))` between the old player's chunk and the occupied chunk in the same dimension. The default is 12: exactly 12 stays protected, and 13 or more permits transfer. The real server has no owning player position, so a server-owned chunk cannot qualify through this distance exception alone. For a set, every occupied member must satisfy the distance exception before that exception releases the whole set. Required shutdown/recovery rules are in [disconnect](disconnect.md) and [redstone](redstone.md).

The distance exception releases the old claim; it does not grant the incoming sub-server ownership before sync, readiness, and the coordinated commit. Resolve competing eligible new claimants through [arbitration](multi-client-arbitration.md).

## Optional verification

`handover_verification_ticks` defaults to 0, off. Above 0, the former owner keeps a non-authoritative shadow for that many logical ticks after commitment, using the same inputs. Compare blocks, block entities, and scheduled ticks at matching completed ticks, not network arrival times. Entity-position jitter alone is not a failure.

A genuine mismatch makes the former owner's shadow state win for the compared state and returns authority to it at a coordinated boundary as failure recovery. This can roll back already published results and is a debug-only exception, not normal conflict resolution. Shadow output is never published unless selected during that recovery. The mode doubles simulation work for affected chunks and stays off in normal operation.
