package gladiatorsarius.coworld.server;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import net.minecraft.resources.Identifier;
import gladiatorsarius.coworld.common.protocol.CoWorldPackets;
import gladiatorsarius.coworld.common.protocol.ProtocolVersion;
import gladiatorsarius.coworld.server.config.CoWorldConfig;
import gladiatorsarius.coworld.server.handshake.HandshakeHandler;
import gladiatorsarius.coworld.server.handshake.SessionManager;
import gladiatorsarius.coworld.server.log.CoWorldLog;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CoWorldServer implements DedicatedServerModInitializer {
	public static final String MOD_ID = "coworld_server";

	private static final Map<ServerPlayer, HandshakeHandler> HANDSHAKES = new ConcurrentHashMap<>();
	private static SessionManager sessions;

	@Override
	public void onInitializeServer() {
		CoWorldConfig config = CoWorldConfig.load();
		CoWorldLog.configure(config.logLevel());
		sessions = new SessionManager(config.maxSubServers());
		ServerPlayConnectionEvents.JOIN.register((listener, sender, server) -> {
			HandshakeHandler handler = new HandshakeHandler(config, sessions, listener,
				ProtocolVersion.V1_SCHEMA_ID, ProtocolVersion.V1_SCHEMA_VERSION);
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
				if (handler.state() == HandshakeHandler.State.NORMAL_PLAY) return;
				handler.tick(tick);
				sendHandshakeResult(player, null, handler.pollKeepAlive());
				CoWorldPackets.Decline decline = handler.expiredResult();
				if (decline != null) sendHandshakeResult(player, null, decline);
			});
		});
		ServerPlayNetworking.registerGlobalReceiver(CoWorldPackets.Hello.TYPE, (payload, context) -> {
			HandshakeHandler handler = HANDSHAKES.computeIfAbsent(context.player(),
				player -> new HandshakeHandler(config, sessions, player.connection,
					ProtocolVersion.V1_SCHEMA_ID, ProtocolVersion.V1_SCHEMA_VERSION));
			sendHandshakeResult(context.player(), context.responseSender(), handler.receiveHello(payload));
		});
	}

	private static void sendHandshakeResult(ServerPlayer player, PacketSender sender, Object result) {
		switch (result) {
			case null -> { }
			case CoWorldPackets.Accept accept -> sendAccepted(player, sender, accept);
			case CoWorldPackets.Decline decline -> sendDeclined(player, sender, decline);
			case ClientboundKeepAlivePacket keepAlive -> sendPacket(player, sender, keepAlive);
			default -> throw new IllegalStateException("invalid handshake result");
		}
	}

	private static void sendAccepted(ServerPlayer player, PacketSender sender, CoWorldPackets.Accept accept) {
		CoWorldLog.info("[handshake] accepted player={} radius={}", player.getGameProfile().name(), accept.requestedRadius());
		sendPayload(player, sender, accept);
	}

	private static void sendDeclined(ServerPlayer player, PacketSender sender, CoWorldPackets.Decline decline) {
		CoWorldLog.info("[handshake] declined player={} reason={}", player.getGameProfile().name(), decline.reasonName());
		sendPayload(player, sender, decline);
	}

	private static void sendPacket(ServerPlayer player, PacketSender sender, Packet<?> packet) {
		if (sender == null) player.connection.send(packet);
		else sender.sendPacket(packet);
	}

	private static void sendPayload(ServerPlayer player, PacketSender sender, CustomPacketPayload payload) {
		if (sender == null) ServerPlayNetworking.send(player, payload);
		else sender.sendPacket(payload);
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
