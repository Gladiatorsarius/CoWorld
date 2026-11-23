package gladiatorsarius.coworld_server.config;

import gladiatorsarius.coworld_common.config.CoWorldDefaults;

public record CoWorldConfig(boolean offloadEnabled, int maxSubServers, int maxPing,
							int highPingTicks, int maxOwnershipRadius, long syncSpeedLimit,
							int helloTimeoutTicks, int pingProbeTimeoutTicks) {
	public CoWorldConfig {
		if (maxSubServers < 0 || maxPing < 0 || highPingTicks < 0 || maxOwnershipRadius < 0
			|| syncSpeedLimit < 0 || helloTimeoutTicks < 0 || pingProbeTimeoutTicks < 0) {
			throw new IllegalArgumentException("invalid CoWorld server configuration");
		}
	}

	public static CoWorldConfig defaults() {
		return new CoWorldConfig(true, 20, CoWorldDefaults.MAX_PING, CoWorldDefaults.HIGH_PING_TICKS,
			CoWorldDefaults.MAX_OWNERSHIP_RADIUS, CoWorldDefaults.SYNC_SPEED_LIMIT,
			CoWorldDefaults.HANDSHAKE_HELLO_TIMEOUT_TICKS, CoWorldDefaults.PING_PROBE_TIMEOUT_TICKS);
	}
}
