# Multi-client arbitration

## What it's for

When two or more modded players are close enough that their ownership bubbles overlap or touch. Every chunk still has exactly one owner, so overlaps must be resolved. The real server is the referee: sub-servers never talk to each other directly, everything routes through it, so there is exactly one place where these decisions happen.

## The rule

First come, first served. Whoever owns a chunk first keeps it, and a newcomer's bubble is clipped where it overlaps an existing one. Ownership only changes when a chunk frees up: the owner's bubble moves away or they disconnect, the chunk returns to the real server, and any bubble covering it can claim it. If two bubbles cover a freed chunk, the nearer player wins that one-time claim. There is no flipping risk, since claims only happen on free-up.

## Distance and simultaneous claims

The real server groups eligible claims received during the same real-server tick into one arbitration round. For a free chunk, the earliest round with eligible claimants wins; within that round choose the nearer player. Once a claim is reserved for a handover attempt, later rounds cannot displace it. An aborted attempt releases that reservation for a later round. A reservation is not committed ownership or permission to tick.

For chunk `(cx, cz)`, its horizontal block center is `(16*cx + 8, 16*cz + 8)`. Compare `(player_x - center_x)^2 + (player_z - center_z)^2` using authoritative player positions at the end of the arbitration round's server tick, in the chunk's dimension. Ignore Y. This is squared horizontal block distance, not square chunk distance or network latency. Use the real server's one captured position sample per candidate throughout that round, not separate arrival-time samples.

Exact equal distances go to the lowest player UUID interpreted as an unsigned 128-bit integer, not UUID text ordering, session ID, or packet order. For a free indivisible redstone set, arbitrate the whole set once; a candidate's distance is the minimum of its distances to member chunk centers, with the same UUID tie-break. All other set eligibility and sync rules still apply. An existing owner keeps its whole set regardless of a nearer newcomer's distance.

## How it interacts with the rest

Where the clipped bubbles meet, the real server coordinates the border and relays inputs both ways (see delta sync). A redstone set claimed by two bubbles follows the same rule: the first owner keeps the whole set. A player crossing from one bubble into another uses the player border handover, authority moves between two sub-servers instead of sub-server and real server.
