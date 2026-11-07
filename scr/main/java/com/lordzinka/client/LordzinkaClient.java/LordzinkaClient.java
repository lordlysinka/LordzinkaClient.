package com.lordzinka.client;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LordzinkaClient implements ClientModInitializer {
    private static final Logger LOGGER =
        LoggerFactory.getLogger("lordzinka");

    @Override
    public void onInitializeClient() {
        LOGGER.info("Lordzinka Client loaded successfully!");
    }
}
