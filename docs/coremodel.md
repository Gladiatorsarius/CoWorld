# Core model: live chunk simulation offloading

A Fabric mod pair (server mod + client mod) for Minecraft 26.3. When a player with the client mod joins a server running the server mod, the server hands the live simulation of the chunks around that player to the player's own computer. The player's PC ticks its surroundings, and the server keeps the authoritative world. Players without the mod connect normally and the server ticks everything for them, exactly like vanilla.

## Joining

1. [Handshake](handshake.md): the client announces it has the mod, the server answers.
2. [Initial sync](sync.md): the server sends its mods folder, then streams its world folder to the client, starting with the chunks around the player and expanding in the background. While this initial sync runs, the server keeps ticking everything.
3. The client [starts a local server inside the Minecraft process](client-server-bootstrap.md) (like singleplayer's integrated server, but running only the mods the server sent), and loads the downloaded world there. It is created on join and closes with the game. This local server is what simulates the player's surroundings.
4. Chunk by chunk, as the sync completes, [ownership moves from the real server to the local server](handover.md). From then on, [only changes flow in both directions](delta-sync.md) instead of full chunks.
5. On later joins, the client [rejoins](rejoin.md) and sends hashes for its cached chunks. The server only re-sends what changed.

Because each connection starts its own local server inside the game with that server's mods, a player can use this on several different servers.

## Ownership and ticking

Every chunk has exactly one owner, and only the owner ticks it. Everything not owned by a player's local server stays with the real server. The local server continuously sends its simulation results back, and the real server applies them to the authoritative world, so all other players see the player's actions live.

## Borders

The player entity is ticked by whoever owns the chunk they stand in. The real server tracks the player's speed and direction, and when it predicts a border crossing within a short configurable window, both sides simulate the player in parallel (one authoritative, one warm standby), so the crossing is an instant swap with no stutter, even at elytra speed. Other entities crossing a border are handed over with their complete state. A redstone contraption spanning a border is always ticked entirely by one side: whichever side first needs to tick into foreign territory takes ownership of the contraption's whole connected set of chunks.

## Multiple modded players

Each modded client owns the chunks around itself. Where two ownership areas meet, the real server coordinates the border.

## Disconnecting

When the player logs off or the game closes, the local server closes with it and ownership moves back to the real server, which resumes ticking from the latest state it has. The downloaded world stays on the player's PC as a full, playable copy that can also be opened in singleplayer.
