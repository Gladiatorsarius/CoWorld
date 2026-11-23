package gladiatorsarius.coworld_client.handshake;

import gladiatorsarius.coworld_common.protocol.CoWorldPackets.PingEcho;
import gladiatorsarius.coworld_common.protocol.CoWorldPackets.PingProbe;

public final class PingEchoResponder {
	public PingEcho echo(PingProbe probe) {
		return new PingEcho(probe.nonce(), probe.sequence());
	}

	public void discard() {
	}
}
