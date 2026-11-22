# Player border handover

## What it's for

Moving the ticking of a PLAYER entity between the real server and a sub-server when the player crosses an ownership border. The main case: a player without the mod walks into chunks owned by someone's sub-server. They stay connected to the real server the whole time and never notice, but the authority over their player still changes hands, and it has to be stutter-free even at elytra speed.

## The core rule

The player entity is ticked by whoever owns the chunk they stand in.

## Why chunks aren't enough

A chunk handover can take its time, the old owner keeps ticking until the new one is caught up. A player can't wait: at elytra speed you cross a chunk in under half a second.

## How it works

1. Prediction: the real server tracks every player's speed and direction. When it predicts a border crossing within a short window, it starts the handover. The window is the config `player_handover_prediction_ticks`, default 40 ticks (2 seconds).
2. Shadow ticks: during the window, both sides simulate the player in parallel. The current owner stays authoritative, the other side is a warm standby running the same inputs. World state on both sides is already identical thanks to the delta stream, and the small player state (position, velocity, health, inventory, effects) rides along in the deltas, so the standby stays in lockstep.
3. The swap: at the tick the player crosses, authority flips instantly. The old authority stops at the end of tick N, the new one starts at tick N+1, same boundary rule as the chunk handover. No state transfer is needed at that moment, the standby already has everything. That's the point of the shadow ticks.
4. Wrong prediction: the player veers away, the standby just stands down. Cheap. A teleport instead of walking: the teleport itself is the sync point, authority flips at the next tick boundary.

## What players see

For a player without the mod, completely invisible. Their actions keep routing through the real server, which relays them as inputs to whoever owns their chunk, and results come back as deltas. For a modded player crossing their own bubble edge it's the same mechanism in both directions.
