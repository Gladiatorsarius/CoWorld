package gladiatorsarius.coworld_server.handshake;

import gladiatorsarius.coworld_common.protocol.CoWorldPackets.PingEcho;
import gladiatorsarius.coworld_common.protocol.CoWorldPackets.PingProbe;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class PingProber {
	private final Random random;
	private PingProbe current;
	private int nextSequence;
	private final List<Long> rtts = new ArrayList<>(3);
	private long sentAtNanos;

	public PingProber() {
		this(new Random());
	}

	PingProber(Random random) {
		this.random = random;
	}

	public PingProbe sendNext() {
		if (rtts.size() >= 3) throw new IllegalStateException("all probes already sent");
		nextSequence = rtts.size() + 1;
		current = new PingProbe(random.nextLong(), nextSequence);
		sentAtNanos = System.nanoTime();
		return current;
	}

	public boolean accept(PingEcho echo) {
		if (current == null || echo.nonce() != current.nonce() || echo.sequence() != current.sequence()) {
			return false;
		}
		rtts.add(Math.max(0L, (System.nanoTime() - sentAtNanos) / 1_000_000L));
		current = null;
		return true;
	}

	public boolean complete() {
		return rtts.size() == 3;
	}

	public long medianRttMillis() {
		if (!complete()) throw new IllegalStateException("not all probes completed");
		List<Long> sorted = rtts.stream().sorted().toList();
		return sorted.get(1);
	}

	public void discard() {
		current = null;
		rtts.clear();
		nextSequence = 0;
	}
}
