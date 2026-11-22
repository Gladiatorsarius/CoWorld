# Entity handover

## What it's for

Everything that moves and isn't a player (mobs, dropped items, arrows, minecarts, boats) crossing an ownership border gets handed over with its complete state.

## How it works

1. Trigger: an entity's position crosses into a chunk the other side owns.
2. At the next tick boundary the old owner serializes the entity's complete state (its full NBT: position, rotation, the velocity vector, health, AI state, passengers, the works) and hands it over. The new owner spawns it at tick N+1 and ticks on, the old owner removes it. Same boundary rule as chunks and players.
3. No prediction or shadow ticks needed here. A single entity is a few KB and the swap lands within one tick, so even a watched mob doesn't visibly stutter. Since the motion is included, an arrow keeps flying with the exact same speed and direction on the new side, and a mob keeps its momentum. Nothing restarts from a standstill.
4. Riders transfer as one unit: a player isn't part of this (see the player border handover), but a zombie riding a chicken, a mob stack, a leashed animal, all move together with their full relationships intact.
5. AI that points at blocks stays valid: a villager's workstation, a bee's hive. Those are just block positions, and both sides hold the full world state, so the brain keeps working after the handover. Only the entity moves, never the blocks.

## Edge case

An entity pushed back and forth exactly on the border. Ownership is decided by the chunk its position is in, and the handover is cheap, so bouncing costs a couple of tiny transfers, nothing more.
