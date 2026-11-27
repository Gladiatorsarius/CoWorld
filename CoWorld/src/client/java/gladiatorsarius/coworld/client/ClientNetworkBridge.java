package gladiatorsarius.coworld.client;

import gladiatorsarius.coworld.common.protocol.CoWorldPackets.Accept;
import gladiatorsarius.coworld.common.protocol.CoWorldPackets.Decline;
import gladiatorsarius.coworld.common.protocol.CoWorldPackets.Hello;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import gladiatorsarius.coworld.common.protocol.ProtocolVersion;
import gladiatorsarius.coworld.client.config.ClientSettings;
import gladiatorsarius.coworld.client.handshake.HelloSender;
import gladiatorsarius.coworld.common.config.CoWorldDefaults;

public final class ClientNetworkBridge {
	public void register() {
		ClientPlayConnectionEvents.JOIN.register((listener, sender, client) -> {
			int simulationDistance = client.options.simulationDistance().get();
			sendHello(new HelloSender(CoWorldDefaults.releaseVersion(),
				ProtocolVersion.V1_SCHEMA_ID, ProtocolVersion.V1_SCHEMA_VERSION)
				.build(ClientSettings.load(simulationDistance)));
		});
		ClientPlayNetworking.registerGlobalReceiver(Accept.TYPE, (payload, context) ->
			CoWorldClientClient.LOGGER.info("[handshake] accepted"));
		ClientPlayNetworking.registerGlobalReceiver(Decline.TYPE, (payload, context) ->
			CoWorldClientClient.LOGGER.info("[handshake] declined reason={}", payload.reasonName()));
	}

	public void sendHello(Hello hello) {
		ClientPlayNetworking.send(hello);
	}
}
