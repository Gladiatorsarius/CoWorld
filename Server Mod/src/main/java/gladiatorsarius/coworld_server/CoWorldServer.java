package gladiatorsarius.coworld_server;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerPlayer;

import net.minecraft.resources.Identifier;
import gladiatorsarius.coworld_common.protocol.CoWorldPackets;
import gladiatorsarius.coworld_server.config.CoWorldConfig;
import gladiatorsarius.coworld_server.handshake.HandshakeHandler;
import gladiatorsarius.coworld_server.handshake.SessionManager;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CoWorldServer implements ModInitializer {
	public static final String MOD_ID = "coworld_server";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private static final Map<ServerPlayer, HandshakeHandler> HANDSHAKES = new ConcurrentHashMap<>();
	private static SessionManager sessions;

	@Override
	public void onInitialize() {
		CoWorldConfig config = CoWorldConfig.load();
		sessions = new SessionManager(config.maxSubServers());
		PayloadTypeRegistry.serverboundPlay().registerLarge(CoWorldPackets.Hello.TYPE, CoWorldPackets.Hello.CODEC,
			CoWorldPackets.HELLO_PAYLOAD_LIMIT);
		PayloadTypeRegistry.serverboundPlay().register(CoWorldPackets.PingEcho.TYPE, CoWorldPackets.PingEcho.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(CoWorldPackets.PingProbe.TYPE, CoWorldPackets.PingProbe.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(CoWorldPackets.Accept.TYPE, CoWorldPackets.Accept.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(CoWorldPackets.Decline.TYPE, CoWorldPackets.Decline.CODEC);
		ServerPlayConnectionEvents.JOIN.register((listener, sender, server) -> {
			HandshakeHandler handler = new HandshakeHandler(config, sessions);
			handler.start(server.getTickCount());
			HANDSHAKES.put(listener.player, handler);
		});
		ServerPlayConnectionEvents.DISCONNECT.register((listener, server) -> {
			HandshakeHandler handler = HANDSHAKES.remove(listener.player);
			if (handler != null) handler.disconnect();
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			long tick = server.getTickCount();
			HANDSHAKES.forEach((player, handler) -> {
				handler.tick(tick);
				CoWorldPackets.Decline decline = handler.expiredResult();
				if (decline != null) ServerPlayNetworking.send(player, decline);
			});
		});
		ServerPlayNetworking.registerGlobalReceiver(CoWorldPackets.Hello.TYPE, (payload, context) -> {
			HandshakeHandler handler = HANDSHAKES.computeIfAbsent(context.player(),
				player -> new HandshakeHandler(config, sessions));
			Object result = handler.receiveHello(payload);
			if (result == null) return;
			context.responseSender().sendPacket(switch (result) {
				case CoWorldPackets.PingProbe probe -> probe;
				case CoWorldPackets.Accept accept -> accept;
				case CoWorldPackets.Decline decline -> decline;
				default -> throw new IllegalStateException("invalid handshake result");
			});
		});
		ServerPlayNetworking.registerGlobalReceiver(CoWorldPackets.PingEcho.TYPE,
			(payload, context) -> {
				HandshakeHandler handler = HANDSHAKES.get(context.player());
				if (handler == null) return;
				Object result = handler.receivePingEcho(payload);
				if (result == null) return;
				context.responseSender().sendPacket(switch (result) {
					case CoWorldPackets.PingProbe probe -> probe;
					case CoWorldPackets.Accept accept -> accept;
					case CoWorldPackets.Decline decline -> decline;
					default -> throw new IllegalStateException("invalid handshake result");
				});
			});
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
