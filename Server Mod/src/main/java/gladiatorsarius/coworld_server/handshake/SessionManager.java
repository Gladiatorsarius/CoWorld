package gladiatorsarius.coworld_server.handshake;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class SessionManager {
	private final int maxSubServers;
	private static final AtomicInteger reservedSlots = new AtomicInteger();
	private int highPingTicks;
	private UUID activeSession;

	public SessionManager(int maxSubServers) {
		this.maxSubServers = maxSubServers;
	}

	public static void resetCapacity() {
		reservedSlots.set(0);
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
		if (activeSession != null) throw new IllegalStateException("connection already accepted");
		if (!reserveCapacity()) return null;
		activeSession = UUID.randomUUID();
		return activeSession;
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
		if (activeSession != null) releaseCapacity();
		activeSession = null;
		highPingTicks = 0;
	}

	public UUID activeSession() {
		return activeSession;
	}

	public int reservedSlots() {
		return reservedSlots.get();
	}
}
