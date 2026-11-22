# Handshake

## Purpose

When a modded client joins a modded server, both sides find out whether the other has CoWorld, agree on settings, and either start syncing or stay vanilla. The handshake happens right after login over the normal encrypted Minecraft connection, using custom packets. It needs no new login, connection, or authentication.

## Steps

1. The client joins normally and sends a **CoWorld Hello** packet containing its mod version, protocol version, and settings: whether it allows offloading, how much disk space it is willing to use, its simulation distance, and (in v2) whether it allows direct P2P.
   The client also reports whether it holds a usable cache from a previous session and, if so, the cache's mod version; if it does, the server proceeds to [rejoin](rejoin.md) instead of the full initial sync.
2. The server sends a **Ping** packet. The client returns it immediately, unchanged. The server does this three times and takes the median round-trip time (RTT).
3. The server decides:
   - **Accept:** sends the protocol version to use, the ownership radius in chunks (the minimum of the client simulation distance and the server's `max_ownership_radius` cap), sync speed limit, and a session ID. The session ID identifies this sub-server session and is needed later for relaying and rejoining.
   - **Decline:** sends a reason, such as ping too high, server full, incompatible versions, or offloading disabled. The client then plays vanilla and nothing else happens.
4. On Accept, the handshake is complete and sync starts (see the sync design).

## Ping checking

- The server's `max_ping` config sets the highest accepted ping. The suggested default is 150 ms. A client over the limit is declined and plays vanilla.
- The three-ping handshake check gives an immediate answer at join time. After that, the server uses Minecraft's built-in per-player ping measurement, the same one shown in the tab list, for ongoing monitoring. If a sub-server owner's ping stays too high mid-session, the server takes its chunks back (see the disconnect design).

## Safety rules

- If either side has no mod, no packet arrives. Vanilla play continues with zero errors.
- If the Hello does not arrive within a few seconds of joining, the server treats the client as vanilla.
- If versions do not match, the server declines and the client plays vanilla. This must never cause a crash or kick.
- If the client crashes or leaves during the handshake, nothing breaks: no chunks have changed owner, and the server was ticking everything already.
- If the client changes its simulation distance mid-session, it sends a small update packet. The server adjusts the ownership radius without requiring a reconnect.
- The handshake runs fresh on every join. No state is stored, so there is nothing to clean up.
