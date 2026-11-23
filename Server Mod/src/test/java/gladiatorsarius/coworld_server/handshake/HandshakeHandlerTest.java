package gladiatorsarius.coworld_server.handshake;

import gladiatorsarius.coworld_common.protocol.CoWorldPackets.PingEcho;
import gladiatorsarius.coworld_server.config.CoWorldConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

class HandshakeHandlerTest {
	@Test
	void ignoresEchoBeforeHello() {
		HandshakeHandler handler = new HandshakeHandler(CoWorldConfig.defaults(),
			"1.1.0", null, 0);
		assertNull(handler.receivePingEcho(new PingEcho(1, 1)));
		assertEquals(HandshakeHandler.State.WAITING_FOR_HELLO, handler.state());
	}

	@Test
	void ignoresPacketsAfterTerminalState() {
		HandshakeHandler handler = new HandshakeHandler(CoWorldConfig.defaults(),
			"1.1.0", null, 0);
		handler.disconnect();
		assertNull(handler.receivePingEcho(new PingEcho(1, 1)));
		assertNull(handler.receiveHello(null));
	}
}
