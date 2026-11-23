package gladiatorsarius.coworld_client.handshake;

import gladiatorsarius.coworld_common.protocol.CoWorldPackets.PingEcho;
import gladiatorsarius.coworld_common.protocol.CoWorldPackets.PingProbe;

public final class PingEchoResponder {
	private PingProbe outstanding;

	public PingEcho echo(PingProbe probe) {
		if (outstanding != null) return null;
		outstanding = probe;
		PingEcho echo = new PingEcho(probe.nonce(), probe.sequence());
		outstanding = null;
		return echo;
	}

	public void discard() {
		outstanding = null;
	}
}
