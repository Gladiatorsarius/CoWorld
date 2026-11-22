# Disconnect

## What it's for

What happens when a modded player leaves, cleanly or not.

## Clean disconnect (log off, close the game)

1. The player's inputs cut immediately and they see themselves leave right away.
2. Behind that, the sub-server waits a short grace window (the config `disconnect_grace_ticks`, default 20 ticks = 1 second), then starts the hand-back with no player attached.
3. The hand-back is cheap, because the real server has been applying the delta stream all along and is already current. The sub-server sends the final deltas and completes the normal handover protocol back to the real server, and it takes as long as it takes, even on a slow connection. The mod holds the game open until the real server confirms the handover is complete. Only then does the game actually close. A clean disconnect is always clean.
4. The local server closes. The cached world stays on disk for the next rejoin and also works as a playable singleplayer copy.
5. The one exception: if the real server itself dies mid-hand-back, the connection is gone anyway, so the client gives up and closes. The cache stays on disk and the next rejoin sorts it out.

## Ungraceful disconnect (crash, killed game, network drop)

1. The heartbeat from the delta stream stops. The config `disconnect_timeout_ticks` (default 600 = 30 seconds) decides how long the real server waits. Until the timeout, those chunks just freeze; lag spikes don't kill anyone's session.
2. At the timeout the real server reclaims ownership instantly and resumes ticking from the latest state it has. No waiting on the client.
3. Deltas that were in flight and never arrived are lost: at most a second of simulation in that bubble, the same kind of small rollback a vanilla server crash gives you. There's no divergence, because the real server simply never applied them.
4. When the player rejoins, the rejoin hash check handles it naturally: their cache is a few ticks ahead of the server in their old bubble, the hashes differ, the server re-sends its version of those chunks.

## Mid-handover

At any disconnect, the handover abort rule covers it, the old owner keeps the chunk. Other players nearby notice nothing except chunks returning to the real server.
