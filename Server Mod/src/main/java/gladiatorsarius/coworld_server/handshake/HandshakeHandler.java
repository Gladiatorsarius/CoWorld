package gladiatorsarius.coworld_server.handshake;

import gladiatorsarius.coworld_common.protocol.CoWorldPackets.Accept;
import gladiatorsarius.coworld_common.protocol.CoWorldPackets.Decline;
import gladiatorsarius.coworld_common.protocol.CoWorldPackets.Hello;
import gladiatorsarius.coworld_common.protocol.ProtocolVersion;
import gladiatorsarius.coworld_common.config.CoWorldDefaults;
import gladiatorsarius.coworld_server.config.CoWorldConfig;
import gladiatorsarius.coworld_server.log.CoWorldLog;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import java.util.UUID;

public final class HandshakeHandler {
	public enum State {
		WAITING_FOR_HELLO,
		PROBING,
		ACCEPTED,
		NORMAL_PLAY
	}

	private final CoWorldConfig config;
	private final SessionManager sessions;
	private final PingProber prober;
	private final String player;
	private State state = State.WAITING_FOR_HELLO;
	private int requestedRadius;
	private final String releaseVersion;
	private final String schemaId;
	private final int schemaVersion;
	private long helloDeadlineTick = -1;
	private long pingDeadlineTick = -1;
	private long currentServerTick;
	private boolean sessionAccepted;
	private UUID activeSession;

	public HandshakeHandler(CoWorldConfig config, SessionManager sessions,
							ServerGamePacketListenerImpl listener, String schemaId, int schemaVersion) {
		this(config, sessions, listener, CoWorldDefaults.releaseVersion(), schemaId, schemaVersion);
	}

	public HandshakeHandler(CoWorldConfig config, SessionManager sessions,
							ServerGamePacketListenerImpl listener, String releaseVersion,
							String schemaId, int schemaVersion) {
		this.config = config;
		this.sessions = sessions;
		this.prober = new PingProber(listener);
		this.player = listener.player.getGameProfile().name();
		this.releaseVersion = releaseVersion;
		this.schemaId = schemaId;
		this.schemaVersion = schemaVersion;
	}

	public State state() {
		return state;
	}

	public void start(long serverTick) {
		currentServerTick = serverTick;
		helloDeadlineTick = serverTick + config.helloTimeoutTicks();
	}

	public void tick(long serverTick) {
		currentServerTick = serverTick;
	}

	public Decline expiredResult() {
		if (state == State.WAITING_FOR_HELLO && helloDeadlineTick >= 0
			&& currentServerTick >= helloDeadlineTick) {
			state = State.NORMAL_PLAY;
			return null;
		}
		if (state == State.PROBING && pingDeadlineTick >= 0
			&& currentServerTick >= pingDeadlineTick) {
			CoWorldLog.debug("[handshake] probe deadline expired player={} sequence={} tick={}",
				player, prober.expectedSequence(), currentServerTick);
			state = State.NORMAL_PLAY;
			prober.discard();
			return new Decline(Decline.FAILED_NEGOTIATION);
		}
		return null;
	}

	public Object receiveHello(Hello hello) {
		if (state != State.WAITING_FOR_HELLO) return null;
		if (!hello.valid()) return decline(Decline.FAILED_NEGOTIATION);
		if (!config.offloadEnabled()) return decline(Decline.OFFLOADING_DISABLED);
		if (!hello.offloading()) return decline(Decline.CLIENT_OPTED_OUT);
		if (!ProtocolVersion.isSupported(hello.protocolVersion()) || hello.diskAllowance() <= 0) {
			return decline(ProtocolVersion.isSupported(hello.protocolVersion())
				? Decline.OFFLOADING_DISABLED : Decline.INCOMPATIBLE_VERSIONS);
		}
		if (!releaseVersion.equals(hello.releaseVersion())) return decline(Decline.INCOMPATIBLE_VERSIONS);
		if (schemaId != null || hello.schemaId() != null) {
			if (schemaId == null || hello.schemaId() == null
				|| !schemaId.equals(hello.schemaId()) || schemaVersion != hello.schemaVersion()) {
				return decline(Decline.INCOMPATIBLE_VERSIONS);
			}
		}
		requestedRadius = Math.min(hello.simulationDistance(), config.maxOwnershipRadius());
		if (requestedRadius <= 0) return decline(Decline.OFFLOADING_DISABLED);
		CoWorldLog.debug("[handshake] hello accepted player={} radius={} schema={}:{} protocol={}",
			player, requestedRadius, hello.schemaId(), hello.schemaVersion(), hello.protocolVersion());
		state = State.PROBING;
		pingDeadlineTick = currentServerTick + config.pingProbeTimeoutTicks();
		return prober.sendNext();
	}

	public Object pollKeepAlive() {
		if (state != State.PROBING) return null;
		PingProber.Echo echo = prober.pollEcho();
		if (echo == null) return null;
		CoWorldLog.debug("[handshake] probe echoed player={} sequence={} id={} echo_ns={}",
			player, echo.sequence(), echo.id(), echo.echoedAtNanos());
		if (echo.sequence() != prober.expectedSequence()) {
			CoWorldLog.debug("[handshake] probe rejected player={} sequence={} reason=unexpected sequence",
				player, echo.sequence());
			return decline(Decline.FAILED_NEGOTIATION);
		}
		if (prober.accept(echo) < 0) {
			return decline(Decline.FAILED_NEGOTIATION);
		}
		if (!prober.complete()) {
			pingDeadlineTick = currentServerTick + config.pingProbeTimeoutTicks();
			return prober.sendNext();
		}
		long median = prober.medianRttMillis();
		CoWorldLog.debug("[handshake] decision player={} samples_ms={} median_ms={} max_ping_ms={} result={}",
			player, prober.rttSamples(), median, config.maxPing(), median > config.maxPing() ? "decline" : "accept");
		if (median > config.maxPing()) {
			return decline(Decline.PING_ABOVE_CAP);
		}
		UUID sessionId = sessions.acceptSession();
		if (sessionId == null) {
			return decline(Decline.CAPACITY_REACHED);
		}
		sessionAccepted = true;
		activeSession = sessionId;
		state = State.ACCEPTED;
		prober.discard();
		return new Accept(ProtocolVersion.V1, requestedRadius, config.syncSpeedLimit(), sessionId);
	}

	public void disconnect() {
		CoWorldLog.debug("[handshake] discarded player={} reason=disconnect", player);
		if (sessionAccepted) sessions.releaseCapacity();
		sessionAccepted = false;
		activeSession = null;
		prober.close();
		state = State.NORMAL_PLAY;
	}

	public UUID activeSession() {
		return activeSession;
	}

	private Decline decline(int reason) {
		Decline decline = new Decline(reason);
		CoWorldLog.debug("[handshake] decline player={} reason={}", player, decline.reasonName());
		state = State.NORMAL_PLAY;
		prober.discard();
		return decline;
	}
}
