# Player border handover

## Ownership borders

A player's authoritative simulator is the owner of the chunk containing their position. Use `chunk_x = floor(x / 16)` and `chunk_z = floor(z / 16)`, including negative coordinates. A chunk covers `[16*x, 16*(x+1))` on each horizontal axis: a position exactly at a boundary belongs to the chunk beginning there, not the one ending there. Position, not bounding-box overlap or camera position, breaks ties.

An ownership border separates chunks with different authoritative owners, including two sub-servers. A bubble edge means the boundary of actual assigned chunks, not simply a radius: overlapping claims clip the shape and whole redstone sets can extend it.

An unmodded player remains connected to the real server and can enter an already offloaded chunk. This transfers their player authority, not ownership of the chunk beneath them. Their occupied chunk blocks routine chunk handovers in either direction, moving or stationary. This does not protect a surrounding radius of empty chunks. The [four handover exceptions](handover.md#triggers) include clean shutdown, failure recovery, required redstone consolidation, and transfer to the real server or another eligible sub-server after the old owner's player moves strictly more than the configured default 12 chunks away. The incoming owner's distance is not the release condition.

## Preparation

The real server tracks player speed and direction. A predicted crossing at or within `player_handover_prediction_ticks` (default 40 simulation ticks, about 2 seconds at 20 TPS) starts a warm standby on the destination owner.

Both sides receive the same player inputs and current player state, including position, rotation, velocity, health, inventory, and effects. The standby also receives nearby world results through the delta stream. Only the current owner publishes authoritative results. Standby simulation is a specific player-transfer exception, not general client prediction or a second authoritative simulation of the destination chunk.

If the player turns away, cancel the unused standby without changing authority.

## Crossing tick

The current owner finishes tick N, including the movement that places the player in the destination chunk. It remains the sole player authority for that complete tick. The destination begins with N+1 after applying the final authoritative player state through N. Do not tick the player again merely because its position changed chunks partway through tick N.

A prepared standby keeps the final transfer small. It is not permission to publish speculative divergent state. If it is late or not prepared, wait for final state before starting N+1 rather than double-ticking or inventing missing state; a short wall-clock pause remains possible.

## Teleport

A teleport is a synchronization point, not a predictable continuous crossing. Send the destination owner the complete final player state at the teleport boundary, including the destination position and relevant dimension. Authority changes at the next tick boundary only after that state is installed. No warmup distance is required, but destination readiness still is. If ownership is unchanged, no simulator transfer is needed.

## What players see

Inputs continue through the real server to the current owner, and results return through the relay. The ordinary prepared crossing aims to avoid visible interruption, including for elytra flight, but cannot promise zero network delay. Required owner shutdown/failure recovery follows [disconnect](disconnect.md), independently of routine occupancy protection.
