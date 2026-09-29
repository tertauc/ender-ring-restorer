package dev.enderring.restorer;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EnderRingRestorer implements ModInitializer {
	public static final String MOD_ID = "ender-ring-restorer";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("Ender Ring Restorer loaded - the pre-26.3 End island integer overflow (MC-159283) is back.");
	}
}
