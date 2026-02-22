package com.dripps.voxyingest;

import com.dripps.voxyingest.network.IngestBeginPayload;
import com.dripps.voxyingest.network.IngestChunkPayload;
import com.dripps.voxyingest.network.IngestCompletePayload;
import com.dripps.voxyingest.server.ChunkSender;
import com.dripps.voxyingest.server.IngestCommand;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Voxyingest implements ModInitializer {
    public static final String MOD_ID = "voxyingest";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.playS2C().register(IngestBeginPayload.TYPE, IngestBeginPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(IngestChunkPayload.TYPE, IngestChunkPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(IngestCompletePayload.TYPE, IngestCompletePayload.STREAM_CODEC);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                IngestCommand.register(dispatcher));

        ServerTickEvents.END_SERVER_TICK.register(ChunkSender::tick);

        LOGGER.info("VoxyIngest started, use /voxyingest <player> to send world data");
    }
}
