package gladiatorsarius.coworld_client.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ClientSettingsTest {
	@Test
	void rejectsNegativeDiskAllowance() {
		assertThrows(IllegalArgumentException.class,
			() -> new ClientSettings(true, -1, 8));
	}
}
