package gladiatorsarius.coworld.common;

import gladiatorsarius.coworld.common.protocol.CoWorldPackets;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CoWorldCommon implements ModInitializer {
	public static final String MOD_ID = "coworld";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		PayloadTypeRegistry.serverboundPlay().registerLarge(CoWorldPackets.Hello.TYPE, CoWorldPackets.Hello.CODEC,
			CoWorldPackets.HELLO_PAYLOAD_LIMIT);
		PayloadTypeRegistry.clientboundPlay().register(CoWorldPackets.Accept.TYPE, CoWorldPackets.Accept.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(CoWorldPackets.Decline.TYPE, CoWorldPackets.Decline.CODEC);
		LOGGER.info("[common] CoWorld common protocol loaded");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
