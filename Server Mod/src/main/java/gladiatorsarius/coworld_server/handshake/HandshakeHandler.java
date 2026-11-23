package gladiatorsarius.coworld_server.handshake;

import gladiatorsarius.coworld_common.protocol.CoWorldPackets.Accept;
import gladiatorsarius.coworld_common.protocol.CoWorldPackets.Decline;
import gladiatorsarius.coworld_common.protocol.CoWorldPackets.Hello;
import gladiatorsarius.coworld_common.protocol.ProtocolVersion;
import gladiatorsarius.coworld_common.config.CoWorldDefaults;
import gladiatorsarius.coworld_server.config.CoWorldConfig;

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

	public HandshakeHandler(CoWorldConfig config) {
		this(config, CoWorldDefaults.RELEASE_VERSION, null, 0);
	}

	public HandshakeHandler(CoWorldConfig config, String releaseVersion, String schemaId, int schemaVersion) {
		this.config = config;
		this.sessions = new SessionManager(config.maxSubServers());
		this.prober = new PingProber();
		this.releaseVersion = releaseVersion;
		this.schemaId = schemaId;
		this.schemaVersion = schemaVersion;
	}

	public HandshakeHandler(CoWorldConfig config, SessionManager sessions) {
		this.config = config;
		this.sessions = sessions;
		this.prober = new PingProber();
		this.releaseVersion = CoWorldDefaults.RELEASE_VERSION;
		this.schemaId = null;
		this.schemaVersion = 0;
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
			state = State.NORMAL_PLAY;
			prober.discard();
			return new Decline(Decline.FAILED_NEGOTIATION);
		}
		return null;
	}

	public Object receiveHello(Hello hello) {
		if (state != State.WAITING_FOR_HELLO) return null;
		if (!config.offloadEnabled()) return decline(Decline.OFFLOADING_DISABLED);
		if (!hello.offloading()) return decline(Decline.CLIENT_OPTED_OUT);
		if (!ProtocolVersion.isSupported(hello.protocolVersion()) || hello.diskAllowance() <= 0) {
			return decline(ProtocolVersion.isSupported(hello.protocolVersion())
				? Decline.OFFLOADING_DISABLED : Decline.INCOMPATIBLE_VERSIONS);
		}
		if (!releaseVersion.equals(hello.releaseVersion())) return decline(Decline.INCOMPATIBLE_VERSIONS);
		if (schemaId == null || hello.schemaId() == null
			|| !schemaId.equals(hello.schemaId()) || schemaVersion != hello.schemaVersion()) {
			return decline(Decline.INCOMPATIBLE_VERSIONS);
		}
		requestedRadius = Math.min(hello.simulationDistance(), config.maxOwnershipRadius());
		if (requestedRadius <= 0) return decline(Decline.OFFLOADING_DISABLED);
		state = State.PROBING;
		pingDeadlineTick = currentServerTick + config.pingProbeTimeoutTicks();
		return prober.sendNext();
	}

	public Object receivePingEcho(gladiatorsarius.coworld_common.protocol.CoWorldPackets.PingEcho echo) {
		if (state == State.ACCEPTED || state == State.NORMAL_PLAY) {
			return null;
		}
		if (state != State.PROBING) {
			return decline(Decline.FAILED_NEGOTIATION);
		}
		if (pingDeadlineTick >= 0 && currentServerTick >= pingDeadlineTick) {
			prober.discard();
			return decline(Decline.FAILED_NEGOTIATION);
		}
		if (!prober.accept(echo)) {
			return decline(Decline.FAILED_NEGOTIATION);
		}
		if (!prober.complete()) {
			pingDeadlineTick = currentServerTick + config.pingProbeTimeoutTicks();
			return prober.sendNext();
		}
		if (prober.medianRttMillis() > config.maxPing()) {
			return decline(Decline.PING_ABOVE_CAP);
		}
		UUID sessionId = sessions.acceptSession();
		if (sessionId == null) return decline(Decline.CAPACITY_REACHED);
		sessionAccepted = true;
		activeSession = sessionId;
		state = State.ACCEPTED;
		return new Accept(ProtocolVersion.V1, requestedRadius(), config.syncSpeedLimit(), sessionId);
	}

	public void disconnect() {
		if (sessionAccepted) sessions.releaseCapacity();
		sessionAccepted = false;
		activeSession = null;
		sessions.disconnect();
		prober.discard();
		state = State.NORMAL_PLAY;
	}

	public UUID activeSession() {
		return activeSession;
	}

	private Decline decline(int reason) {
		state = State.NORMAL_PLAY;
		return new Decline(reason);
	}

	private int requestedRadius() {
		return requestedRadius;
	}
}
