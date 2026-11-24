package gladiatorsarius.coworld_client.client;

import gladiatorsarius.coworld_common.protocol.CoWorldPackets.Accept;
import gladiatorsarius.coworld_common.protocol.CoWorldPackets.Decline;
import gladiatorsarius.coworld_common.protocol.CoWorldPackets.Hello;
import gladiatorsarius.coworld_common.protocol.CoWorldPackets.PingEcho;
import gladiatorsarius.coworld_common.protocol.CoWorldPackets.PingProbe;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import gladiatorsarius.coworld_common.protocol.ProtocolVersion;
import gladiatorsarius.coworld_client.config.ClientSettings;
import gladiatorsarius.coworld_client.handshake.HelloSender;
import gladiatorsarius.coworld_common.config.CoWorldDefaults;

public final class ClientNetworkBridge {
	public void register() {
		ClientPlayConnectionEvents.JOIN.register((listener, sender, client) -> {
			int simulationDistance = client.options.simulationDistance().get();
			sendHello(new HelloSender(CoWorldDefaults.releaseVersion(),
				ProtocolVersion.V1_SCHEMA_ID, ProtocolVersion.V1_SCHEMA_VERSION)
				.build(ClientSettings.load(simulationDistance)));
		});
		ClientPlayNetworking.registerGlobalReceiver(PingProbe.TYPE, (payload, context) ->
			context.responseSender().sendPacket(new PingEcho(payload.nonce(), payload.sequence())));
		ClientPlayNetworking.registerGlobalReceiver(Accept.TYPE, (payload, context) ->
			CoWorldClientClient.LOGGER.info("CoWorld offloading accepted for session {}", payload.sessionId()));
		ClientPlayNetworking.registerGlobalReceiver(Decline.TYPE, (payload, context) ->
			CoWorldClientClient.LOGGER.info("CoWorld offloading declined: {}", payload.reason()));
	}

	public void sendHello(Hello hello) {
		ClientPlayNetworking.send(hello);
	}
}
