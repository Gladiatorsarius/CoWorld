# Client server bootstrap

## What it's for

Once the mods and the surrounding chunks are synced, the client starts the sub-server inside the running Minecraft process. This is the same mechanism singleplayer already uses: opening a singleplayer world starts an integrated server inside the game process and the client connects to it internally. The sub-server reuses that, nothing new is invented.

## When it starts

It starts as soon as (a) the server's mods are synced and (b) every chunk inside the ownership radius is synced and caught up. Caught up means the chunk snapshot plus every change the real server made since, applied in order. The background stream of the rest of the world keeps running after.

## Why not wait for the whole world

Waiting for a full world copy would delay offloading by minutes to hours on large worlds and waste disk, with no correctness gain. The sub-server only ever ticks inside the fully synced ownership bubble, and everything past the bubble edge is handled by the border design.

## What it runs

Only the mods the server sent, loaded from that server's cache folder. The player's own client mods keep running as usual and never mix with them.

## What it loads

The cached world copy. At the start it ticks nothing. Ownership moves chunk by chunk (see the ownership handover design), and it only ever ticks chunks it owns.

## How players reach it

Players inside its owned chunks talk to it through the real server.

## Lifecycle

Created on join, closes when the player logs off or closes the game. Nothing keeps running in the background. Each server connection starts its own sub-server, and they never share mods or worlds.

## Performance guard

The sub-server shares the game process, so its tick health is watched. If it cannot hold the configured minimum TPS, the real server shrinks the ownership radius or takes chunks back instead of letting the game stutter.

## Crash rule

If the sub-server crashes, ownership snaps back to the real server and the player keeps playing. Worst case is a short hiccup, never a lost world or a kicked player.
