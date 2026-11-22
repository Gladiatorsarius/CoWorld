# Entity handover

## Ownership and trigger

Non-player entities include mobs, items, projectiles, boats, and minecarts. The authoritative owner is the owner of the chunk containing the entity's position, not every chunk touched by its bounding box.

Use `floor(x / 16)` and `floor(z / 16)`, including negative coordinates. Each chunk includes its lower coordinate boundary and excludes its upper one. A position exactly at a shared edge belongs to the chunk beginning there. Moving between chunks with the same owner needs no handover; moving to a different owner does. Transfers between sub-servers route through the real server in v1.

## Tick ordering and state

The source finishes tick N, including movement into the destination chunk. At that boundary it stops ticking the transferred entity and sends its complete transferable state: full NBT plus position, rotation, velocity, health, AI state, and rider/passenger/leash relationships needed to continue it. Complete state means restoring the same entity, not spawning a fresh default mob with only its location copied.

The destination installs that final state before starting logical tick N+1. The old owner removes its authoritative entity instance and cannot publish further results for it. There must be no duplicate authoritative tick and no skipped logical tick. State may take wall-clock time to arrive; the transfer is small but a one-tick wall-clock latency bound is not guaranteed. Chunk handovers and generation validation follow [ownership handover](handover.md).

No entity prediction or shadow simulation is required. Velocity and rotation continue from the source instead of restarting movement from a standstill.

## Related entities and block references

Non-player riders/passengers and their relationships transfer as one unit rather than splitting a riding stack between owners. Preserve leash references as part of the complete relationship state. Player authority, including a player involved in a crossing, follows [player border handover](player-border-handover.md); it is not duplicated as a non-player passenger.

AI references to blocks, such as a workstation or hive, retain their dimension and block positions. Those blocks do not move with the entity. This does not imply every cache has a current full world: nearby unowned state and outside inputs follow [delta sync](delta-sync.md); distant cached chunks must be checked before active reuse.

Repeated crossings can cause repeated small transfers. Exact-edge ownership still follows the same coordinate rule, not an arbitrary owner choice or a new distance margin.
