package gladiatorsarius.coworld.client;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CoWorldClientClient implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("coworld_client");

	@Override
	public void onInitializeClient() {
		new ClientNetworkBridge().register();
	}
}