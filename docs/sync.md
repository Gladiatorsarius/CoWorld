# Initial sync

## Purpose

After the handshake says Accept, the sub-server needs two things before it can tick anything: the server's mods and the world. While this runs, the player keeps playing completely normally: the real server shows them the world and ticks everything like vanilla.

## Transfer order

1. **Mods manifest:** A list of every mod file on the server, with a hash and size. The client compares it with what it already has cached and tells the server which files it needs. Only missing or changed mods are sent.
2. **Chunks around the player:** The world streams as a spiral starting at the player's position and going out to the ownership radius. These come first because the sub-server needs them to take over.
3. **The rest of the world:** It streams in the background afterwards, throttled by the sync speed limit from the handshake so the game never lags.

## Keeping the world consistent

The real server keeps ticking while the world streams, so a chunk received 30 seconds ago is already outdated. Every chunk is sent with a version number. From the moment a chunk is sent, the server queues every change to it and sends those deltas to the client. The sub-server applies them in order. A chunk is caught up only when its snapshot and all queued deltas have been applied.

Only then can ownership move (see the ownership handover design).

## Storage on the player's PC

The client keeps a per-server cache folder for the world and mods, identified by the server. This makes rejoining fast because the rejoin design hashes against this cache. The disk limit from the handshake caps it; the oldest unused server cache gets cleaned first.

## Failure rules

If sync dies mid-way, nothing breaks. The server owns and ticks everything, and the player keeps playing vanilla. The next join resumes instead of starting over.
