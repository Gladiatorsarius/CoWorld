# Redstone

## Purpose

A redstone contraption can depend on update order, quasi-connectivity, and piston behavior. Represent connected contraptions as sets of chunks and keep each set under one owner. The real server coordinates these sets as ownership metadata; this is not a second simulation of offloaded redstone.

## Connection detection

On chunk load and redstone component placement/breaking, check both outgoing and incoming power/update connections: what a component can affect and what can affect it. Look two blocks along the applicable connection paths, including effects through one solid block and piston quasi-connectivity. Distance counts successive adjacent block-to-block steps on an actual directed power/update path, up to two steps per detected connection, not Euclidean or square distance to every nearby component. Test incoming paths as well as outgoing ones. Follow the detected connections transitively to build the complete set; the two-step check does not limit a whole contraption to two blocks. Do not check only currently powered dust; an unpowered connection can become active later.

Map connected components to their containing chunks. A cross-chunk connection unifies the whole chunk sets; multiple components in one chunk cannot give that chunk multiple owners. Breaking a connection splits a set only if no other connection still joins the resulting pieces.

The real server receives component/topology changes from the current simulator, so owned chunks do not need an independent authoritative server tick for detection.

## Border band and piston moves

The two-block border band is the two block columns immediately inside each horizontal chunk edge. In local block X/Z coordinates 0 through 15, a block is in the band when either coordinate is 0, 1, 14, or 15. Corners belong to both adjacent bands.

Recheck connectivity when any moved block's source or destination touches that band, including a move crossing the chunk boundary while remaining in the band. Do not limit the trigger to entering/leaving the band. Interior piston work that touches neither source nor destination band needs no cross-chunk recheck. Block placement/breaking still uses the normal checks above.

## Whole-set ownership

A sub-server owns all chunks of a set or none. Sets may extend beyond its requested ownership radius; the radius is not permission to cut a contraption in half. A set with at most `redstone_takeover_max_chunks` distinct chunks is eligible (default 64); a larger set stays entirely on the real server. Equality passes the cap.

Overlapping player bubbles do not displace an existing set owner. Use [multi-client arbitration](multi-client-arbitration.md) for an existing whole set: first come, first served. Ordinary claims still require synced state and [handover](handover.md). One unmodded-player-occupied member pins the whole set for routine handover, moving or stationary. The distance-release exception compares the old owner's player chunk with every occupied member; only when all are strictly more than the configured default 12 chunks away can that exception transfer the whole set to the real server or another eligible sub-server. The incoming owner's distance does not release the old claim. Nearby empty chunks are not pinned merely by proximity. See [handover](handover.md#triggers) for all four exceptions and the square-distance rule.

## Topology changes and moving machines

A placement, break, or border-band piston move can merge sets, split them, or carry a flying machine into new chunks. The real server updates the set's chunk membership from the reported topology at the completed-tick boundary, before the affected operation resumes. Recompute connected components for removals as well as additions; retain every chunk still connected by another path. Flag the resulting complete sets, add newly connected chunks, and remove obsolete membership only after checking remaining connections. Membership metadata is not itself an ownership grant. Do not leave a newly connected chunk with another owner while the operation proceeds.

If a pending operation would join differently owned sets, pause that affected operation, consolidate the complete resulting set onto the real server at a coordinated boundary, then resume the operation under one owner. Do the same when a set becomes too large for sub-server ownership. The real server is the coordinator and destination for required consolidation. Reuse [ownership handover](handover.md#transfer) for every source-owned part, with one reservation for the complete resulting set. Pause the triggering operation, prepare destination state while sources keep their existing simulation, collect readiness, then request each source's final stop at its next completed tick M. Different source histories retain their own identified tick/version boundaries; do not infer a common M from arrival time. Install every final state and queued outside input locally before committing all affected owner generations to the real server as one set. No part resumes the triggering operation early. After the whole-set commit, the real server continues each installed history at its next logical tick and resumes the operation under one owner. Chunks already server-owned use local handover roles, not self-addressed packets.

If preparation aborts, release the reservation, retain the prior committed owners, and resume any stopped source from its final state; keep the cross-owner triggering operation deferred until consolidation succeeds. A source failure uses [disconnect recovery](disconnect.md) before consolidation continues, rather than inventing missing state. Prepare snapshots/deltas before the final stop so the paused interval is minimal. These required consistency transfers are exceptions to routine unmodded-player occupied-chunk rule; ordinary overlap alone is not.

For growth that stays with one eligible owner, include every newly connected chunk before resuming the affected operation. A split changes membership but is not itself permission for another player to steal still-owned chunks. Claims and later releases continue through arbitration.
