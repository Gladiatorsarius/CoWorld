package gladiatorsarius.coworld.server.handshake;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class SessionManager {
	private final int maxSubServers;
	private final AtomicInteger reservedSlots = new AtomicInteger();

	public SessionManager(int maxSubServers) {
		this.maxSubServers = maxSubServers;
	}

	private boolean reserveCapacity() {
		int current;
		do {
			current = reservedSlots.get();
			if (current >= maxSubServers) return false;
		} while (!reservedSlots.compareAndSet(current, current + 1));
		return true;
	}

	public void releaseCapacity() {
		reservedSlots.updateAndGet(current -> Math.max(0, current - 1));
	}

	public UUID acceptSession() {
		if (!reserveCapacity()) return null;
		return UUID.randomUUID();
	}

	public int reservedSlots() {
		return reservedSlots.get();
	}
}
