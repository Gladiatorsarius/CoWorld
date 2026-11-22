# Redstone

## What it's for

A redstone contraption is one connected system with brutal timing (update order, quasi-connectivity, piston behavior). Two owners ticking different halves of one contraption would break it, so a contraption is always ticked entirely by one side.

## Detection and flagging

The real server tracks redstone contraptions as connected sets of chunks and flags each set as one unit. Whenever a redstone component is placed or broken, it checks what the component connects to in both directions (what it can power and what can power it), looking two blocks in each direction. That covers power through one solid block (repeater into a block, dust behind it) and quasi-connectivity above pistons. A chunk also gets scanned when it loads.

## How sets grow and shrink

- A placed component that connects to nothing starts a new set of its own.
- A connection inside the same chunk changes nothing.
- A connection across a chunk border merges the two whole sets into one.
- On break, the reverse: if removing the component splits a set into two disconnected pieces, it splits.
- Pistons move blocks, and a moved block can complete or break a connection. Re-checking every piston move would be a check storm in big builds, so this only runs when a moved block enters or leaves the two-block band along a chunk border, since only those moves can change a cross-border connection. Interior piston work costs nothing.

## The ownership rule

A flagged set is all-or-nothing. A sub-server can only own ALL of a contraption's chunks, never some. When a bubble hands over chunks, a contraption that lies half inside either moves completely to the sub-server or stays completely with the real server.

## Why this kills the problem by construction

Since a contraption is never split, a signal never crosses a border inside one. No takeover mid-signal, no delayed first signal, no broken timing, ever. Cross-border redstone simply can't happen, because anything connected is one set on one side.

## Moving contraptions (slime-block flying machines)

The flag follows the machine as it grows into the next chunk, and ownership moves with the set.

## Two bubbles wanting the same contraption

The real server decides who gets the whole set (see multi-client arbitration).

## The cap

Sets bigger than `redstone_takeover_max_chunks` (default 64) can't move to a sub-server at all, so the real server keeps the whole contraption. All-or-nothing makes that safe automatically.
