# Multi-client arbitration

## What it's for

When two or more modded players are close enough that their ownership bubbles overlap or touch. Every chunk still has exactly one owner, so overlaps must be resolved. The real server is the referee: sub-servers never talk to each other directly, everything routes through it, so there is exactly one place where these decisions happen.

## The rule

First come, first served. An existing sub-server keeps its committed chunks, and a newcomer's candidate square is clipped by those claims. A chunk is free for a new sub-server claim only when the real server's committed record assigns it to the real server, no existing sub-server claim remains, and no live attempt reserves it. Free never means ownerless or unticked. Movement or a disconnect request alone does not free a chunk: the old owner retains authority until hand-back commits, or failure recovery invalidates that generation and reclaims it. A clean release makes it claimable after that commit; failure reclaim does so after the real server has installed its recovery state. The nearer eligible player wins a one-time competing claim; proximity does not displace an existing owner.

## Eligible claims

A claimant needs a live accepted offloading session, a running destination sub-server, and a positive effective requested radius after TPS reduction. The chunk must be in its current-dimension candidate square or a required member of a whole redstone set touching that square. The complete transfer unit must satisfy set-size, occupied-chunk and existing-claim rules, and have verified snapshot state plus ordered catch-up through a known boundary. Downloading a chunk for view/background caching alone never establishes a claim. Recheck eligibility before the final stop; loss of eligibility aborts an uncommitted attempt without removing the source's ownership. Handover readiness/final installation and commitment remain required after selection.

## Distance and simultaneous claims

The real server groups eligible claims received during the same real-server tick into one arbitration round. For a free chunk, the earliest round with eligible claimants wins; within that round choose the nearer player. Once a claim is reserved for a handover attempt, later rounds cannot displace it. An aborted attempt immediately releases only its reservation; its committed source owner remains authoritative and resumes if it had stopped. If that source is the real server, the chunk can enter the next arbitration round immediately after the abort is recorded. If it is still a sub-server-owned return transfer, abort does not make the chunk claimable. A late message cannot recreate an aborted reservation. A reservation is not committed ownership or permission to tick.

For chunk `(cx, cz)`, its horizontal block center is `(16*cx + 8, 16*cz + 8)`. Compare `(player_x - center_x)^2 + (player_z - center_z)^2` using authoritative player positions at the end of the arbitration round's server tick, in the chunk's dimension. Ignore Y. This is squared horizontal block distance, not square chunk distance or network latency. Use the real server's one captured position sample per candidate throughout that round, not separate arrival-time samples.

Exact equal distances go to the lowest player UUID interpreted as an unsigned 128-bit integer, not UUID text ordering, session ID, or packet order. For a free indivisible redstone set, arbitrate the whole set once; a candidate's distance is the minimum of its distances to member chunk centers, with the same UUID tie-break. All other set eligibility and sync rules still apply. An existing owner keeps its whole set regardless of a nearer newcomer's distance.

## How it interacts with the rest

Where the clipped bubbles meet, the real server coordinates the border and relays inputs both ways (see delta sync). A redstone set claimed by two bubbles follows the same rule: the first owner keeps the whole set. A player crossing from one bubble into another uses the player border handover, authority moves between two sub-servers instead of sub-server and real server.
