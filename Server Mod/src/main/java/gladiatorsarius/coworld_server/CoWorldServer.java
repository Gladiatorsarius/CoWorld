package gladiatorsarius.coworld_server;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import net.minecraft.resources.Identifier;
import gladiatorsarius.coworld_common.protocol.CoWorldPackets;
import gladiatorsarius.coworld_common.protocol.ProtocolVersion;
import gladiatorsarius.coworld_server.config.CoWorldConfig;
import gladiatorsarius.coworld_server.handshake.HandshakeHandler;
import gladiatorsarius.coworld_server.handshake.SessionManager;
import gladiatorsarius.coworld_server.log.CoWorldLog;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CoWorldServer implements ModInitializer {
	public static final String MOD_ID = "coworld_server";

	private static final Map<ServerPlayer, HandshakeHandler> HANDSHAKES = new ConcurrentHashMap<>();
	private static SessionManager sessions;

	@Override
	public void onInitialize() {
		CoWorldConfig config = CoWorldConfig.load();
		CoWorldLog.configure(config.logLevel());
		sessions = new SessionManager(config.maxSubServers());
		PayloadTypeRegistry.serverboundPlay().registerLarge(CoWorldPackets.Hello.TYPE, CoWorldPackets.Hello.CODEC,
			CoWorldPackets.HELLO_PAYLOAD_LIMIT);
		PayloadTypeRegistry.clientboundPlay().register(CoWorldPackets.Accept.TYPE, CoWorldPackets.Accept.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(CoWorldPackets.Decline.TYPE, CoWorldPackets.Decline.CODEC);
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
		if (result == null) return;
		String playerName = player.getGameProfile().name();
		if (result instanceof CoWorldPackets.Accept accept) {
			CoWorldLog.info("[handshake] accepted player={} radius={}", playerName, accept.requestedRadius());
		} else if (result instanceof CoWorldPackets.Decline decline) {
			CoWorldLog.info("[handshake] declined player={} reason={}", playerName, decline.reasonName());
		}
		if (result instanceof Packet<?> packet) {
			if (sender == null) player.connection.send(packet);
			else sender.sendPacket(packet);
		} else if (result instanceof CustomPacketPayload payload) {
			if (sender == null) ServerPlayNetworking.send(player, payload);
			else sender.sendPacket(payload);
		} else {
			throw new IllegalStateException("invalid handshake result");
		}
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
