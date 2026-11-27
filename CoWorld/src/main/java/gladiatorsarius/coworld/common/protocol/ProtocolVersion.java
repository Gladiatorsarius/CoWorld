package gladiatorsarius.coworld.common.protocol;

public final class ProtocolVersion {
	public static final int V1 = 1;
	public static final int CURRENT = V1;
	public static final String V1_SCHEMA_ID = "coworld-v1";
	public static final int V1_SCHEMA_VERSION = 1;

	private ProtocolVersion() {
	}

	public static boolean isSupported(int protocolVersion) {
		return protocolVersion == V1;
	}
}
