package gladiatorsarius.coworld_client.config;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public record ClientSettings(boolean offloading, long diskAllowance, int simulationDistance) {
	public ClientSettings {
		if (diskAllowance < 0 || simulationDistance < 0) {
			throw new IllegalArgumentException("invalid client settings");
		}
	}

	public static ClientSettings load(int simulationDistance) {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("coworld-client.properties");
		boolean offloading = true;
		long diskAllowance = 1L << 30;
		if (Files.isRegularFile(file)) {
			Properties properties = new Properties();
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				properties.load(reader);
			} catch (IOException exception) {
				throw new IllegalStateException("Could not read CoWorld client configuration", exception);
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
		}
		return new ClientSettings(offloading, diskAllowance, simulationDistance);
	}
}
