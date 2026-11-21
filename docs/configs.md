# Server configuration

This list grows as more parts of the design are locked in.

- `max_ping`: Highest ping, in milliseconds, a sub-server owner may have. Checked during the handshake using the median of three pings, then monitored during the session. **Default:** 150 ms (suggested).
- `max_ownership_radius`: Server-side cap on how big any ownership area may be. The actual radius per player is the minimum of their simulation distance and this cap. **Default:** not set yet.
- `sync_speed_limit`: Maximum bandwidth for the initial world and mods sync per client. Sent in the handshake Accept. **Default:** not set yet.
- `offload_enabled`: Master switch for offloading on this server. When off, every client plays vanilla. **Default:** not set yet.
- `max_sub_servers`: Maximum number of sub-servers the real server supports at once. New owners beyond the cap are declined and play vanilla. **Default:** not set yet.
