package gladiatorsarius.coworld_server.log;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CoWorldLog {
	private static final Logger LOGGER = LoggerFactory.getLogger("coworld_server");
	private static volatile boolean debugEnabled;

	private CoWorldLog() {
	}

	public static void configure(String level) {
		debugEnabled = "debug".equals(level);
	}

	public static void debug(String format, Object... args) {
		// Info level keeps debug lines visible without depending on the logging backend.
		if (debugEnabled) LOGGER.info(format, args);
	}

	public static void info(String format, Object... args) {
		LOGGER.info(format, args);
	}

	public static void warn(String format, Object... args) {
		LOGGER.warn(format, args);
	}

	public static void error(String format, Object... args) {
		LOGGER.error(format, args);
	}
}
