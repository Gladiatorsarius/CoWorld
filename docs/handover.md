# Ownership handover

## What it's for

Moving a chunk's ticking between the real server and a sub-server, in both directions, without a chunk ever being ticked twice or skipped. This isn't just for joining: as the player moves, chunks entering the radius get handed over and chunks leaving it go back, so this runs constantly.

## The core rule

Every chunk has exactly one owner, and only the owner ticks it.

## The handover state

What changes hands is the chunk's complete state at a specific tick number: blocks, block entities, entities, scheduled block and fluid ticks, redstone state, and the random state (world seed, per-chunk random tick state, scheduled tick queue), so random ticks continue exactly as the old owner would have run them.

## How a handover works

1. Handovers only happen at a tick boundary, never mid-tick.
2. The current owner sends a handover offer with the handover state.
3. Until the new owner confirms, the old owner keeps ticking, and the normal delta stream keeps the receiver caught up.
4. The new owner confirms it holds the state at tick N. The old owner stops ticking at the end of tick N, the new owner starts at tick N+1. No gap, no double tick. The state is copied, not re-simulated, so it cannot diverge at the switch.
5. If the confirmation doesn't come in time (lag spike), the handover aborts: the old owner keeps the chunk and tries again later.

## When handovers trigger

- Join: the whole bubble hands over once the sub-server is running and caught up.
- Moving: chunks entering the radius get synced first (movement direction gets priority), and only hand over once caught up. Until then the real server keeps them.
- Leaving the radius, overload, logout: the same protocol back to the real server.

## Optional verification mode

The config `handover_verification_ticks` (default 0, off). When set above 0, both sides tick the chunk in parallel for that many ticks after a handover and compare the core block state (blocks, block entities, scheduled ticks; entity positions are allowed to jitter, since Minecraft ticking is not fully deterministic). On a real mismatch, ownership returns to the old owner. This is a development and debugging tool: it doubles tick work for those chunks while active, so it stays off in normal operation. Server owners who suspect problems can turn it on deliberately.
