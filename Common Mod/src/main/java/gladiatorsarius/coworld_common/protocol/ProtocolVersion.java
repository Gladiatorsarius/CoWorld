package gladiatorsarius.coworld_common.protocol;

public final class ProtocolVersion {
	public static final int V1 = 1;
	public static final int CURRENT = V1;

	private ProtocolVersion() {
	}

	public static boolean isSupported(int protocolVersion) {
		return protocolVersion == V1;
	}

	public static boolean matches(int protocolVersion) {
		return isSupported(protocolVersion);
	}
}
