# Server configuration

This list grows as more parts of the design are locked in.

- `max_ping`: Highest ping, in milliseconds, a sub-server owner may have. Checked during the handshake using the median of three pings, then monitored during the session. **Default:** 150 ms (suggested).
- `max_ownership_radius`: Server-side cap on how big any ownership area may be. The actual radius per player is the minimum of their simulation distance and this cap. **Default:** not set yet.
- `sync_speed_limit`: Maximum bandwidth for the initial world and mods sync per client. Sent in the handshake Accept. **Default:** not set yet.
- `offload_enabled`: Master switch for offloading on this server. When off, every client plays vanilla. **Default:** not set yet.
- `max_sub_servers`: Maximum number of sub-servers the real server supports at once. New owners beyond the cap are declined and play vanilla. **Default:** not set yet.
- `redstone_takeover_max_chunks`: The largest redstone contraption set (in chunks) that can move to a sub-server; bigger sets stay entirely with the real server. **Default:** 64.
- `min_subserver_tps`: TPS a sub-server must hold. If it falls below this value, the real server shrinks its ownership radius or takes chunks back. **Default:** 20.
- `player_handover_prediction_ticks`: How many ticks ahead a predicted player border crossing starts the parallel simulation. **Default:** 40 (2 seconds).
- `handover_verification_ticks`: After a handover, both sides tick the chunk in parallel for this many ticks and compare core block state; a real mismatch hands the chunk back. Debugging tool; doubles tick work while active. **Default:** 0 (off).
