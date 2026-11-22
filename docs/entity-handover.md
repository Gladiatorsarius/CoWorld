# Entity handover

## Ownership and trigger

Non-player entities include mobs, items, projectiles, boats, and minecarts. A singleton's authoritative owner is the owner of the chunk containing its position, not every chunk touched by its bounding box. A connected non-player riding/leash group instead follows its lead's position as defined below; a trailing member can lie in another owner's chunk without changing the group's entity authority or that chunk's block ownership.

Use `floor(x / 16)` and `floor(z / 16)`, including negative coordinates. Each chunk includes its lower coordinate boundary and excludes its upper one. A position exactly at a shared edge belongs to the chunk beginning there. Moving between chunks with the same owner needs no handover; moving to a different owner does. Transfers between sub-servers route through the real server in v1.

## Tick ordering and state

The source finishes tick N, including movement into the destination chunk. At that boundary it stops ticking the transferred entity and sends its complete transferable state: full NBT plus position, rotation, velocity, health, AI state, and rider/passenger/leash relationships needed to continue it. Complete state means restoring the same entity, not spawning a fresh default mob with only its location copied.

The destination installs that final state before starting logical tick N+1. The old owner removes its authoritative entity instance and cannot publish further results for it. There must be no duplicate authoritative tick and no skipped logical tick. State may take wall-clock time to arrive; the transfer is small but a one-tick wall-clock latency bound is not guaranteed. Chunk handovers and generation validation follow [ownership handover](handover.md).

No entity prediction or shadow simulation is required. Velocity and rotation continue from the source instead of restarting movement from a standstill.

## Related entities and block references

Treat riding/passenger and leash links between non-player entities as undirected edges for group membership. Their transitive connected component is one transfer unit, even when members span differently owned chunks. Candidate leads are vehicles carrying non-player riders/passengers with no non-player vehicle parent, and non-player leash holders. Choose the candidate with the lowest UUID interpreted as an unsigned 128-bit integer. If a cyclic group has no candidate, use its lowest member UUID. A singleton leads itself. Preserve the directed relationships and meaningful passenger order; the undirected graph is only for membership.

The group belongs to the owner of the lead's position chunk. A non-lead crossing alone does not transfer it. When the lead crosses to a different owner, stop every group member after the source's final tick N, send each member once with all links, and install the whole group before any member ticks at N+1. Do not duplicate a trailing member in the simulator of its physical chunk. Relationship creation/removal recomputes membership and lead at the completed-tick boundary; reconcile affected entities to the newly selected group owner through the same complete-state transfer before they resume. In v1, the real server coordinates these transfers and rejects superseded entity authority.

Players are not members, candidate leads, or graph bridges for this rule. Preserve references to player riders/leash holders as external references, but their authority follows [player border handover](player-border-handover.md). A player riding a horse is not silently transferred as a non-player passenger. Player actions and effects involving a separately owned group route through the real server as outside inputs.

AI references to blocks, such as a workstation or hive, retain their dimension and block positions. Those blocks do not move with the entity. This does not imply every cache has a current full world: nearby unowned state and outside inputs follow [delta sync](delta-sync.md); distant cached chunks must be checked before active reuse.

Repeated lead crossings can cause repeated small transfers. Exact-edge membership uses the same coordinate rule for the lead (or singleton), not an arbitrary owner choice.
