package gladiatorsarius.coworld_common.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoWorldPacketsTest {
	@Test
	void rejectsNegativeSchemaVersions() {
		assertThrows(IllegalArgumentException.class,
			() -> CoWorldPackets.validateSchema("snapshot", -1));
	}

	@Test
	void rejectsUnsafeManifestPaths() {
		assertThrows(IllegalArgumentException.class,
			() -> CoWorldPackets.validateManifestPath("../mods.jar"));
		assertThrows(IllegalArgumentException.class,
			() -> CoWorldPackets.validateManifestPath("mods.jar\0"));
	}

	@Test
	void protocolVersionRequiresExactV1() {
		assertTrue(ProtocolVersion.isSupported(ProtocolVersion.V1));
		assertThrows(IllegalArgumentException.class,
			() -> new CoWorldPackets.Hello("1.1.0", 2, true, 1, 8, null, 0, null));
	}
}
