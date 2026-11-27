package gladiatorsarius.coworld_server.config;

import gladiatorsarius.coworld_common.config.CoWorldDefaults;
import gladiatorsarius.coworld_server.log.CoWorldLog;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public record CoWorldConfig(boolean offloadEnabled, int maxSubServers, int maxPing,
							int maxOwnershipRadius, long syncSpeedLimit,
							int helloTimeoutTicks, int pingProbeTimeoutTicks, String logLevel) {
	public CoWorldConfig {
		if (maxSubServers < 0 || maxPing < 0 || maxOwnershipRadius < 0
			|| syncSpeedLimit < 0 || helloTimeoutTicks < 0 || pingProbeTimeoutTicks < 0
			|| !isLogLevel(logLevel)) {
			throw new IllegalArgumentException("invalid CoWorld server configuration");
		}
	}

	public static CoWorldConfig defaults() {
		return new CoWorldConfig(true, 20, CoWorldDefaults.MAX_PING,
			CoWorldDefaults.MAX_OWNERSHIP_RADIUS, CoWorldDefaults.SYNC_SPEED_LIMIT,
			CoWorldDefaults.HANDSHAKE_HELLO_TIMEOUT_TICKS, CoWorldDefaults.PING_PROBE_TIMEOUT_TICKS,
			CoWorldDefaults.LOG_LEVEL);
	}

	public static CoWorldConfig load() {
		CoWorldConfig defaults = defaults();
		Path file = FabricLoader.getInstance().getConfigDir().resolve("coworld-server.properties");
		if (!Files.isRegularFile(file)) {
			writeDefaults(file, defaults);
			return defaults;
		}
		Properties properties = new Properties();
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			properties.load(reader);
		} catch (IOException | IllegalArgumentException exception) {
			CoWorldLog.error("[config] invalid CoWorld server config {}: {}", file, exception.getMessage());
			throw new IllegalStateException("Could not read CoWorld server configuration " + file, exception);
		}
		try {
			boolean offloadEnabled = booleanProperty(properties, "offload_enabled", defaults.offloadEnabled());
			int maxSubServers = intProperty(properties, "max_sub_servers", defaults.maxSubServers(), 0, 10_000);
			int maxPing = intProperty(properties, "max_ping", defaults.maxPing(), 0, 60_000);
			int maxOwnershipRadius = intProperty(properties, "max_ownership_radius", defaults.maxOwnershipRadius(), 0, 512);
			long syncSpeedLimit = longProperty(properties, "sync_speed_limit", defaults.syncSpeedLimit(), 0, 1_000_000_000_000L);
			int helloTimeout = intProperty(properties, "handshake_hello_timeout_ticks", defaults.helloTimeoutTicks(), 0, 1_000_000);
			int pingTimeout = intProperty(properties, "ping_probe_timeout_ticks", defaults.pingProbeTimeoutTicks(), 0, 1_000_000);
			String logLevel = stringProperty(properties, "log_level", defaults.logLevel());
			return new CoWorldConfig(offloadEnabled, maxSubServers, maxPing,
				maxOwnershipRadius, syncSpeedLimit, helloTimeout, pingTimeout, logLevel);
		} catch (IllegalArgumentException exception) {
			CoWorldLog.error("[config] invalid CoWorld server config {}: {}", file, exception.getMessage());
			throw new IllegalStateException("Invalid CoWorld server configuration " + file, exception);
		}
	}

	private static boolean isLogLevel(String level) {
		return "normal".equals(level) || "debug".equals(level);
	}

	private static void writeDefaults(Path file, CoWorldConfig defaults) {
		try {
			Files.createDirectories(file.getParent());
			String text = """
				offload_enabled=%s
				max_sub_servers=%d
				max_ping=%d
				max_ownership_radius=%d
				sync_speed_limit=%d
				handshake_hello_timeout_ticks=%d
				ping_probe_timeout_ticks=%d
				log_level=%s
				""".formatted(defaults.offloadEnabled(), defaults.maxSubServers(), defaults.maxPing(),
					defaults.maxOwnershipRadius(), defaults.syncSpeedLimit(),
					defaults.helloTimeoutTicks(), defaults.pingProbeTimeoutTicks(), defaults.logLevel());
			Files.writeString(file, text, StandardCharsets.UTF_8);
		} catch (IOException exception) {
			CoWorldLog.error("[config] could not write CoWorld server config {}", file, exception);
		}
	}

	private static boolean booleanProperty(Properties properties, String key, boolean defaultValue) {
		String value = properties.getProperty(key);
		if (value == null) return defaultValue;
		if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
			return Boolean.parseBoolean(value);
		}
		throw new IllegalArgumentException("Invalid boolean CoWorld server setting: " + key);
	}

	private static int intProperty(Properties properties, String key, int defaultValue, int minimum, int maximum) {
		String value = properties.getProperty(key);
		if (value == null) return defaultValue;
		try {
			int parsed = Integer.parseInt(value.trim());
			if (parsed < minimum || parsed > maximum) throw new IllegalArgumentException("out of range");
			return parsed;
		} catch (NumberFormatException exception) {
			throw new IllegalArgumentException("Invalid integer CoWorld server setting: " + key, exception);
		}
	}

	private static long longProperty(Properties properties, String key, long defaultValue, long minimum, long maximum) {
		String value = properties.getProperty(key);
		if (value == null) return defaultValue;
		try {
			long parsed = Long.parseLong(value.trim());
			if (parsed < minimum || parsed > maximum) throw new IllegalArgumentException("out of range");
			return parsed;
		} catch (NumberFormatException exception) {
			throw new IllegalArgumentException("Invalid integer CoWorld server setting: " + key, exception);
		}
	}

	private static String stringProperty(Properties properties, String key, String defaultValue) {
		String value = properties.getProperty(key);
		if (value == null) return defaultValue;
		String trimmed = value.trim();
		if (!isLogLevel(trimmed)) throw new IllegalArgumentException("Invalid string CoWorld server setting: " + key);
		return trimmed;
	}
}
