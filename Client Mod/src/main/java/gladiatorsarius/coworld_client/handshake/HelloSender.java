package gladiatorsarius.coworld_client.handshake;

import gladiatorsarius.coworld_client.config.ClientSettings;
import gladiatorsarius.coworld_common.protocol.CoWorldPackets.CacheDeclaration;
import gladiatorsarius.coworld_common.protocol.CoWorldPackets.Hello;
import gladiatorsarius.coworld_common.protocol.ProtocolVersion;

public final class HelloSender {
	private final String releaseVersion;
	private final String schemaId;
	private final int schemaVersion;

	public HelloSender(String releaseVersion, String schemaId, int schemaVersion) {
		this.releaseVersion = releaseVersion;
		this.schemaId = schemaId;
		this.schemaVersion = schemaVersion;
	}

	public Hello build(ClientSettings settings) {
		return build(settings, null);
	}

	public Hello build(ClientSettings settings, CacheDeclaration cache) {
		return new Hello(releaseVersion, ProtocolVersion.V1, settings.offloading(),
			settings.diskAllowance(), settings.simulationDistance(), schemaId, schemaVersion, cache);
	}
}
