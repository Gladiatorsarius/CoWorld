package gladiatorsarius.coworld_common.config;

import net.fabricmc.loader.api.FabricLoader;

public final class CoWorldDefaults {
	public static final String RELEASE_VERSION = FabricLoader.getInstance()
		.getModContainer("coworld_common")
		.map(container -> container.getMetadata().getVersion().getFriendlyString())
		.orElse("1.1.0");
	public static final int MAX_PING = 70;
	public static final int HIGH_PING_TICKS = 100;
	public static final int HANDSHAKE_HELLO_TIMEOUT_TICKS = 100;
	public static final int PING_PROBE_TIMEOUT_TICKS = 100;
	public static final int MAX_OWNERSHIP_RADIUS = 8;
	public static final long SYNC_SPEED_LIMIT = 10_000_000L;

	private CoWorldDefaults() {
	}
}
