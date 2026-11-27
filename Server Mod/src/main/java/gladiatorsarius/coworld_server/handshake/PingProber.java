package gladiatorsarius.coworld_server.handshake;

import gladiatorsarius.coworld_server.log.CoWorldLog;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

public final class PingProber {
	private static final Map<Long, Pending> ACTIVE = new ConcurrentHashMap<>();
	private static final Map<Long, Pending> RETIRED = new ConcurrentHashMap<>();

	public record Echo(int sequence, long id, long echoedAtNanos) {
	}

	private static final class Pending {
		private final PingProber prober;
		private final ServerGamePacketListenerImpl listener;
		private final int sequence;
		private final long id;
		private final long queuedAtNanos;
		private final AtomicLong writtenAtNanos = new AtomicLong(-1);

		private Pending(PingProber prober, ServerGamePacketListenerImpl listener, int sequence,
						long id, long queuedAtNanos) {
			this.prober = prober;
			this.listener = listener;
			this.sequence = sequence;
			this.id = id;
			this.queuedAtNanos = queuedAtNanos;
		}
	}

	private final Random random;
	private final ServerGamePacketListenerImpl listener;
	private final String player;
	private final Queue<Echo> echoes = new ConcurrentLinkedQueue<>();
	private final List<Pending> pendings = new ArrayList<>(3);
	private final List<Long> rtts = new ArrayList<>(3);
	private int sequence;

	public PingProber(ServerGamePacketListenerImpl listener) {
		this(listener, new Random());
	}

	PingProber(ServerGamePacketListenerImpl listener, Random random) {
		this.listener = listener;
		this.random = random;
		this.player = listener.player.getGameProfile().name();
	}

	public ClientboundKeepAlivePacket sendNext() {
		if (sequence >= 3) throw new IllegalStateException("all probes already sent");
		long id;
		Pending pending;
		long queuedAtNanos = System.nanoTime();
		do {
			id = Long.MIN_VALUE | (random.nextLong() & Long.MAX_VALUE);
			pending = new Pending(this, listener, sequence + 1, id, queuedAtNanos);
		} while (ACTIVE.putIfAbsent(id, pending) != null);
		pendings.add(pending);
		CoWorldLog.debug("[handshake] probe queued player={} sequence={} id={} queued_ns={}",
			player, pending.sequence, id, queuedAtNanos);
		return new ClientboundKeepAlivePacket(id);
	}

	public int expectedSequence() {
		return sequence + 1;
	}

	public Echo pollEcho() {
		return echoes.poll();
	}

	public long accept(Echo echo) {
		Pending pending = ACTIVE.get(echo.id());
		long writtenAtNanos = pending == null ? -1 : pending.writtenAtNanos.get();
		if (writtenAtNanos < 0) {
			CoWorldLog.debug("[handshake] probe echo without write player={} sequence={} id={}",
				player, echo.sequence(), echo.id());
			return -1;
		}
		long rtt = Math.max(0L, (echo.echoedAtNanos() - writtenAtNanos) / 1_000_000L);
		rtts.add(rtt);
		sequence++;
		CoWorldLog.debug("[handshake] probe accepted player={} sequence={} id={} rtt_ms={} samples={}/3",
			player, echo.sequence(), echo.id(), rtt, rtts.size());
		return rtt;
	}

	public boolean complete() {
		return rtts.size() == 3;
	}

	public List<Long> rttSamples() {
		return rtts;
	}

	public long medianRttMillis() {
		if (!complete()) throw new IllegalStateException("not all probes completed");
		List<Long> sorted = rtts.stream().sorted().toList();
		return sorted.get(1);
	}

	public void discard() {
		for (Pending pending : pendings) {
			// Retire first so an echo can never fall between the two maps.
			RETIRED.put(pending.id, pending);
			ACTIVE.remove(pending.id, pending);
		}
		echoes.clear();
		rtts.clear();
		sequence = 0;
	}

	public void close() {
		for (Pending pending : pendings) {
			ACTIVE.remove(pending.id, pending);
			RETIRED.remove(pending.id, pending);
		}
	}

	public static void onPacketSent(Packet<?> packet) {
		if (!(packet instanceof ClientboundKeepAlivePacket keepAlive)) return;
		Pending pending = ACTIVE.get(keepAlive.getId());
		if (pending == null) pending = RETIRED.get(keepAlive.getId());
		if (pending == null) return;
		long now = System.nanoTime();
		if (pending.writtenAtNanos.compareAndSet(-1, now)) {
			CoWorldLog.debug("[handshake] probe written player={} sequence={} id={} delay_ms={}",
				pending.prober.player, pending.sequence, keepAlive.getId(),
				(now - pending.queuedAtNanos) / 1_000_000L);
		}
	}

	public static boolean onServerboundKeepAlive(ServerCommonPacketListenerImpl listener, long id) {
		Pending pending = ACTIVE.get(id);
		if (pending == null) pending = RETIRED.get(id);
		String player = listener instanceof ServerGamePacketListenerImpl game && game.player != null
			? game.player.getGameProfile().name() : "unknown";
		if (pending == null || pending.listener != listener) {
			CoWorldLog.debug("[handshake] echo forwarded to vanilla player={} id={}", player, id);
			return false;
		}
		if (!ACTIVE.containsKey(id)) {
			CoWorldLog.debug("[handshake] echo consumed after discard player={} id={}", player, id);
			return true;
		}
		pending.prober.echoes.add(new Echo(pending.sequence, id, System.nanoTime()));
		return true;
	}
}
