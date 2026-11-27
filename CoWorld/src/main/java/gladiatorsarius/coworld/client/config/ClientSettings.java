package gladiatorsarius.coworld.client.config;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public record ClientSettings(boolean offloading, long diskAllowance, int simulationDistance) {
	private static final Logger LOGGER = LoggerFactory.getLogger("coworld");
	private static final long DEFAULT_DISK_ALLOWANCE = 1L << 30;
	public ClientSettings {
		if (diskAllowance < 0 || simulationDistance < 0) {
			throw new IllegalArgumentException("invalid client settings");
		}
	}

	public static ClientSettings load(int simulationDistance) {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("coworld-client.properties");
		boolean offloading = true;
		long diskAllowance = DEFAULT_DISK_ALLOWANCE;
		Properties properties = new Properties();
		if (Files.isRegularFile(file)) {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				properties.load(reader);
			} catch (IOException | IllegalArgumentException | IllegalStateException exception) {
				LOGGER.warn("[config] invalid CoWorld client config {}; offloading disabled", file);
				return new ClientSettings(false, 0, simulationDistance);
			}
		} else {
			try {
				Files.createDirectories(file.getParent());
				Files.writeString(file,
					"offloading=true\ndisk_allowance=" + DEFAULT_DISK_ALLOWANCE + "\n",
					StandardCharsets.UTF_8);
			} catch (IOException exception) {
				LOGGER.warn("[config] could not write CoWorld client config {}", file, exception);
			}
			properties.setProperty("offloading", "true");
			properties.setProperty("disk_allowance", Long.toString(DEFAULT_DISK_ALLOWANCE));
		}
		String preference = properties.getProperty("offloading");
		if (preference != null) {
			if (!preference.equalsIgnoreCase("true") && !preference.equalsIgnoreCase("false")) {
				offloading = false;
			} else {
				offloading = Boolean.parseBoolean(preference);
			}
		}
		String allowance = properties.getProperty("disk_allowance");
		if (allowance != null) {
			try {
				long parsedAllowance = Long.parseLong(allowance.trim());
				diskAllowance = parsedAllowance < 0 ? 0 : parsedAllowance;
			} catch (NumberFormatException exception) {
				diskAllowance = 0;
			}
		}
		return new ClientSettings(offloading, diskAllowance, simulationDistance);
	}
}
