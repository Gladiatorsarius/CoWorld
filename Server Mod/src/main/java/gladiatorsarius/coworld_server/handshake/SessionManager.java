package gladiatorsarius.coworld_server.handshake;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class SessionManager {
	private final int maxSubServers;
	private final AtomicInteger reservedSlots = new AtomicInteger();
	private int highPingTicks;

	public SessionManager(int maxSubServers) {
		this.maxSubServers = maxSubServers;
	}

	public boolean reserveCapacity() {
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

	public boolean observePing(int pingMillis, int pingCap, int handbackTicks) {
		if (pingMillis <= pingCap) {
			highPingTicks = 0;
			return false;
		}
		highPingTicks++;
		return highPingTicks >= handbackTicks;
	}

	public void disconnect() {
		highPingTicks = 0;
	}

	public int reservedSlots() {
		return reservedSlots.get();
	}
}
