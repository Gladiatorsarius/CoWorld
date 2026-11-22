# Rejoin

## Purpose

Reuse a verified compatible cache after a fresh [handshake](handshake.md), without downloading unchanged chunks again. Cache reuse does not restore the last session's ownership.

## Compatibility

Hello reports the previous cache and its metadata. Reuse requires the same server UUID and world UUID, matching CoWorld and Minecraft versions, matching server execution manifest hashes, matching cache-format version, and intact cached data. Validate every mods/config/runtime file through its execution-manifest hash. Revalidate all player files and mandatory bootstrap data before ticking; a matching cached region does not waive those prerequisites. Missing, corrupt, older-version, or otherwise incompatible caches take [initial sync](sync.md).

Live CoWorld/protocol incompatibility declines offloading; incompatible cached data only changes the sync path. A cached world played in singleplayer is not authoritative and must pass the same comparison.

## Active area and hashes

The active area is the union of the requested ownership square and the player's current view area in the current dimension, not the sum of the two radii. Both squares are centered on `floor(player_x / 16), floor(player_z / 16)`, including negative coordinates. The ownership radius is `min(client simulation distance, max_ownership_radius)`, before any [session-local TPS reduction](configs.md#performance-and-verification). The view radius is `min(client view distance, server view-distance cap)`, a nonnegative integer number of chunks. A chunk belongs to either square when `abs(dx) <= its radius` and `abs(dz) <= its radius`; include edges and corners. Radius 0 includes the center chunk for view/cache coverage, but still requests no offloading when the ownership radius is 0. These are cache-coverage squares, not a change to vanilla rendering or ownership rules. Recompute them after chunk movement, dimension changes, or either distance changing. Redstone chunks needed for a whole-set handover must also be synced before that transfer.

The active area's ownership-square radius uses the base requested radius for cache coverage, even when TPS reduction makes the effective ownership request smaller. This keeps coverage distinct from current ticking eligibility. Candidate transfers still use the reduced radius and all whole-set/claim restrictions.

1. The client sends one hash for each completed cached chunk in this area, keyed by dimension and chunk X/Z. Use the canonical state encoding below on both sides.
2. The real server compares against a consistent completed-tick snapshot of its authoritative stored chunk state. A matching hash confirms that snapshot/version without resending its payload. A differing hash makes the server send the full chunk; missing chunks use the same initial-sync snapshot path. Cached state is never uploaded to replace the server's version.
3. For both matches and replacements, track changes from the comparison/snapshot boundary and apply them in order. A hash match alone is not proof of remaining current while the world ticks. Use the snapshot/delta and caught-up rules in [sync](sync.md#snapshot-and-delta-ordering).
4. Use the exact [sync queue order](sync.md#deterministic-chunk-order): requested ownership chunks, then the remaining view area, with nearest rings and movement direction ordered deterministically. Once the sub-server is ready, eligible caught-up chunks use [ownership handover](handover.md). Other visible chunks remain cached without becoming owned.

Cached chunks outside the active area stay lazy and can be stale. Hash-check them before they enter active use as the player approaches. A changed chunk needs a fresh snapshot, not an assumption that the server retained all deltas since the last session.

## Canonical chunk hash, format 1

Compute SHA-256 over the uncompressed bytes of one canonical compound value defined below. Send the 32 digest bytes; a text diagnostic uses exactly 64 lowercase hexadecimal characters. This is a content hash, not a hash of region-file bytes, compression output, runtime object addresses, or a transport packet.

The root has these fields, including empty collections:

- `hash_format`: Int value 1.
- `dimension`: String containing the full namespaced dimension identifier.
- `chunk_x`, `chunk_z`: Int chunk coordinates.
- `blocks`: List of compounds `{x: Int, y: Int, z: Int, state: String, properties: Compound}` for every block position within the dimension's build height. X/Z are local 0 through 15; Y is absolute. Include air, use full namespaced block identifiers, and represent property values as strings. Sort by `(y, z, x)`. Do not hash palette indexes or runtime registry numbers.
- `block_entities`: List of compounds `{x: Int, y: Int, z: Int, data: Compound}` with absolute coordinates and complete saved/transferable data, sorted by `(y, z, x)`.
- `entities`: List of compounds `{uuid: ByteArray, data: Compound}`. UUID is exactly 16 bytes, most-significant byte first; data is the complete transferable entity state, including type, position, rotation, velocity, health, inventory, effects, AI state, and rider/passenger/leash references. Sort top-level entities by unsigned 128-bit UUID; encode relationship references by UUID and retain meaningful passenger order. Include each non-player entity once: a singleton in its position chunk, or all members of a riding/leash group in its lead's position chunk under [entity handover](entity-handover.md#related-entities-and-block-references). A trailing member's physical chunk must not duplicate it. Include player state once in the player's position chunk under its separate player authority, never as a duplicated non-player group member.
- `block_ticks`, `fluid_ticks`: Lists of compounds `{target: String, x: Int, y: Int, z: Int, due_tick: Long, priority: Int, order: Long}`. Target is the full namespaced block/fluid identifier, position is absolute, and order is the simulation's execution-order key. Preserve execution order, including equal-time ordering; do not replace absolute due ticks with time-to-arrival or discard ordering keys.
- `redstone`, `random`: Compounds holding the complete additional transferable redstone and random-generator continuation state. Blocks/block entities remain present even when they also carry redstone data.
- `mod_data`: Compound keyed by full namespaced mod-data identifier, containing all mod-provided state needed to restore the chunk and its simulation.
- `extra`: Compound containing all other transferable chunk snapshot fields, including biomes, lighting, heightmaps, chunk status, and persistent chunk metadata where present. Use namespaced registry identifiers instead of local numeric registry IDs. Do not omit a field merely because it is absent from the list above.

Use the same complete-state snapshot adapters on the client and server. Missing required state, an unsupported mod-state type, or an unknown hash format makes the cache entry unusable; do not silently hash a partial chunk. Changing this schema or an adapter's state representation requires a new hash/cache-format version. A matching execution manifest is still required. Fields in `data`, `mod_data`, and `extra` retain their schema-defined types and list order.

Canonical values use these type tags: Byte=1, Short=2, Int=3, Long=4, Float=5, Double=6, ByteArray=7, String=8, List=9, Compound=10, IntArray=11, LongArray=12. Each value starts with its one-byte tag, followed by:

- Byte/Short/Int/Long: respectively 1/2/4/8 bytes, signed two's-complement, big-endian.
- Float/Double: respectively 4/8 bytes, big-endian IEEE-754 bits as stored. Preserve signed zero and NaN payload bits; do not stringify or round numbers.
- String: unsigned 32-bit big-endian UTF-8 byte length, then those bytes, with no terminator or Unicode normalization. Reject invalid Unicode rather than replacing characters.
- ByteArray: unsigned 32-bit big-endian element count, then raw bytes. IntArray/LongArray: the same count prefix, then 4/8-byte signed big-endian elements, with no per-element tag.
- List: unsigned 32-bit big-endian element count, then each complete tagged value in order. Preserve order except for the explicitly sorted root lists above. An empty list has count 0 and no element-type byte.
- Compound: unsigned 32-bit big-endian entry count, then entries sorted by unsigned lexicographic UTF-8 key bytes. Each entry is its key encoded as a String payload without a tag, then its complete tagged value. Reject duplicate keys. An empty compound has count 0; no end tag or root name is written.

A value/count that cannot be represented by these rules is not hashable in format 1. There is no whitespace, padding, compression header, or other framing in the hash input. Exclude server/session IDs, transport headers, packet sequence numbers, chunk versions, owner generations, and snapshot-boundary tick envelopes. Include tick values that are part of simulation state, such as scheduled due ticks. Hash the same completed-state boundary on both sides; retain its tick/version alongside the digest for catch-up, not inside the content envelope.

## Snapshot adapter schema status

Format 1 above is the normative canonical byte encoding and root-field contract, not a complete Minecraft/mod snapshot adapter specification. The normative adapter/schema version for mapping native saved and runtime state into these fields is **not yet specified**. In particular, complete `data`, `redstone`, `random`, `mod_data` and `extra` representations cannot be inferred from the generic Compound type or matching mod filenames.

Before implementation, publish a versioned snapshot-adapter/schema manifest defining each native/mod field, type, list order, registry mapping, default/absent-field handling, and continuation-state capture. Both endpoints must use the same manifest and adapter versions, alongside hash/cache-format versions and the matching mod manifest. Carry that identity in cache compatibility metadata. Unsupported or mismatched adapters make the affected state ineligible for hashing, cache reuse and offloading; full sync cannot fix an unsupported state mapping. A representation change requires a new adapter/schema identity and hash/cache-format version. No implementation-ready adapter version is implied by the label format 1.
