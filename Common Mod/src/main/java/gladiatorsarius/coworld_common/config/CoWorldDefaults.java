package gladiatorsarius.coworld_common.config;

import net.fabricmc.loader.api.FabricLoader;

public final class CoWorldDefaults {
	public static final String LOG_LEVEL = "normal";
	public static final int MAX_PING = 70;
	public static final int HANDSHAKE_HELLO_TIMEOUT_TICKS = 100;
	public static final int PING_PROBE_TIMEOUT_TICKS = 100;
	public static final int MAX_OWNERSHIP_RADIUS = 8;
	public static final long SYNC_SPEED_LIMIT = 10_000_000L;

	private CoWorldDefaults() {
	}

	public static String releaseVersion() {
		return FabricLoader.getInstance()
			.getModContainer("coworld_common")
			.map(container -> container.getMetadata().getVersion().getFriendlyString())
			.orElseThrow(() -> new IllegalStateException("coworld_common mod metadata is unavailable"));
	}
}
