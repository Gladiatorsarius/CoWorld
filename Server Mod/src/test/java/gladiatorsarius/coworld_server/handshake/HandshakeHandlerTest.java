package gladiatorsarius.coworld_server.handshake;

import gladiatorsarius.coworld_common.protocol.CoWorldPackets.PingEcho;
import gladiatorsarius.coworld_common.protocol.CoWorldPackets.PingProbe;
import gladiatorsarius.coworld_common.protocol.CoWorldPackets.Hello;
import gladiatorsarius.coworld_common.protocol.CoWorldPackets.Accept;
import gladiatorsarius.coworld_common.protocol.ProtocolVersion;
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

	@Test
	void nullSnapshotSchemaIsCompatibleWhenNeitherSideIsConfigured() {
		HandshakeHandler handler = new HandshakeHandler(CoWorldConfig.defaults(),
			"1.1.0", null, 0);
		handler.start(0);
		Hello hello = new Hello(
				"1.1.0", 1, true, 1024, 8, null, 0, null);
		Object probe1 = handler.receiveHello(hello);
		assertEquals(PingProbe.class, probe1.getClass());
	}

	@Test
	void helloThreeEchoesAcceptWithConcreteV1Schema() {
		HandshakeHandler handler = new HandshakeHandler(CoWorldConfig.defaults(),
			"1.1.0", ProtocolVersion.V1_SCHEMA_ID, ProtocolVersion.V1_SCHEMA_VERSION);
		handler.start(0);
		Hello hello = new Hello("1.1.0", 1, true, 1024, 8,
			ProtocolVersion.V1_SCHEMA_ID, ProtocolVersion.V1_SCHEMA_VERSION, null);

		PingProbe probe1 = (PingProbe) handler.receiveHello(hello);
		PingProbe probe2 = (PingProbe) handler.receivePingEcho(
			new PingEcho(probe1.nonce(), probe1.sequence()));
		PingProbe probe3 = (PingProbe) handler.receivePingEcho(
			new PingEcho(probe2.nonce(), probe2.sequence()));
		Object result = handler.receivePingEcho(
			new PingEcho(probe3.nonce(), probe3.sequence()));

		assertEquals(Accept.class, result.getClass());
		assertEquals(HandshakeHandler.State.ACCEPTED, handler.state());
	}
}
