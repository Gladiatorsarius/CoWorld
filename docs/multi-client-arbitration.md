# Multi-client arbitration

## What it's for

When two or more modded players are close enough that their ownership bubbles overlap or touch. Every chunk still has exactly one owner, so overlaps must be resolved. The real server is the referee: sub-servers never talk to each other directly, everything routes through it, so there is exactly one place where these decisions happen.

## The rule

First come, first served. Whoever owns a chunk first keeps it, and a newcomer's bubble is clipped where it overlaps an existing one. Ownership only changes when a chunk frees up: the owner's bubble moves away or they disconnect, the chunk returns to the real server, and any bubble covering it can claim it. If two bubbles cover a freed chunk, the nearer player wins that one-time claim. There is no flipping risk, since claims only happen on free-up.

## How it interacts with the rest

Where the clipped bubbles meet, the real server coordinates the border and relays inputs both ways (see delta sync). A redstone set claimed by two bubbles follows the same rule: the first owner keeps the whole set. A player crossing from one bubble into another uses the player border handover, authority moves between two sub-servers instead of sub-server and real server.
