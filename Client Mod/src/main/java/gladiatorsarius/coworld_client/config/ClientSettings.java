package gladiatorsarius.coworld_client.config;

public record ClientSettings(boolean offloading, long diskAllowance, int simulationDistance) {
	public ClientSettings {
		if (diskAllowance < 0 || simulationDistance < 0) {
			throw new IllegalArgumentException("invalid client settings");
		}
	}
}
