# Delta sync

## Purpose

After takeover, owners send simulation results and receive outside inputs. In v1, the real server relays both directions, stores accepted results in its authoritative world data, and broadcasts them. It does not separately simulate an offloaded chunk.

## Results and batching

A chunk delta describes the changes produced by one completed simulation tick: blocks, block entities, entities and their state, scheduled block/fluid ticks, and other transferred state needed to continue simulation, including relevant redstone/random state.

Each changed chunk advances one version for its tick's change set, not one version per individual block. Its record identifies the chunk, completed tick, base/new versions, and owner generation. Versions order state updates; tick numbers identify the simulation boundary. A handover changes owner generation without resetting chunk identity or treating old-owner results as current.

Each sub-server packs all of its changed chunk records for that tick into one compressed batch. This is one logical result packet per tick, not a promise about transport fragmentation. A chunk without changes needs no delta. A small per-tick-cycle heartbeat still reports liveness/current progress even when the batch has no changes.

The real server accepts results only from the current owner/session/generation, applies them in order, and relays them. Late output from an old owner and non-authoritative shadows cannot overwrite the current state.

## Inputs from outside

The real server immediately forwards external actions to the affected owner: player actions, cross-border block updates, piston pushes, explosion effects, and entity-transfer requests. These are inputs for the owner to simulate, not a second source of block results.

For each target chunk, the real server assigns a monotonic input sequence and stable action ID before forwarding. Sequence assignment defines the order for that chunk, including actions received during the same server tick; it does not claim a global order across different chunks. A retry of the same action retains its ID and sequence. An effect targeting several chunks has a separately sequenced delivery for each target; applying one target does not consume another's delivery. Source retries identify the original cause so the coordinator does not create a new action for a retransmission.

The owner buffers out-of-order inputs and applies each once, in sequence, at its next simulation tick boundary after all preceding inputs are available. Never skip a missing input or execute a duplicate cause again. Acknowledge the applied sequence with the resulting completed-tick state. A snapshot/final handover state carries the last applied input sequence, pending ordered inputs, and action-ID deduplication state. The coordinator retains unacknowledged inputs and routes them to the committed owner; inputs arriving during the final pause wait for the selected owner. After commitment, the destination continues the same sequence rather than starting a new one. Reject superseded-owner acknowledgements. A snapshot or resend must not re-execute inputs already represented in its state. Failure can still lose state not applied by the real server, as in [disconnect](disconnect.md); sequencing is not a promise of durable exactly-once recovery from a vanished owner.

Only the owner produces block state changes in its chunk. A different owner sends the cause across the border rather than editing the destination chunk directly. Redstone connections that require a single simulation owner use the [whole-set rule](redstone.md). Entity ownership transfers follow [entity handover](entity-handover.md).

## Other chunks and hash checks

Nearby unowned chunks, including the rest of the view area, receive their owner's ready-made versioned results. If another sub-server owns them, the real server relays that owner's results; it does not tick them itself. The receiving cache applies results without simulating those chunks.

Far cached chunks receive no continuous updates. Before active reuse, the client computes cached chunk hashes and the real server compares them with its authoritative stored state. Matching chunks are confirmed; differing chunks receive a fresh snapshot. The [rejoin comparison](rejoin.md#canonical-chunk-hash-format-1) uses SHA-256 and its normative format-1 typed canonical byte encoding for complete chunk state, not native region-file bytes or an independently chosen serializer. Compare only a common completed-state boundary.

The [rejoin](rejoin.md) comparison includes a consistent boundary and subsequent deltas, so a matching hash is not an ownership grant or a replacement for catch-up.

## Ordering and recovery

Apply only the next version after the receiver's installed version and only for the current owner generation. Ignore duplicate records already applied. Buffer later records and request the missing version range rather than skipping it. A gap is evidence of missing logical updates, not permission to simulate them locally.

The producing side keeps a bounded recent resend history. If the requested versions remain available, resend them. Otherwise send a complete fresh chunk snapshot at a known tick/version and track later deltas from that boundary, as in [initial sync](sync.md). The exact history retention is not fixed, so correctness cannot depend on its size or on an unlimited log.

Install a replacement snapshot atomically as the new recovery baseline for its chunk, tick, version and generation, then apply only deltas that continue that baseline. Do not merge its payload into divergent cached state or replay records already represented by it. Ignore a late snapshot from a superseded attempt or owner generation; it cannot overwrite a newer installed baseline. A matching content hash confirms server content, not the client's saved version: adopt the server-confirmed boundary and continue from there. The real server's boundary wins even when a locally edited or ahead-of-server cache has a larger saved version. See [rejoin](rejoin.md).

One result batch per tick does not bound network backlog to one second. On failure, changes never applied by the real server can be lost; [disconnect](disconnect.md) defines reclaim and cache reconciliation.
