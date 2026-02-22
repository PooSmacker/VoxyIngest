package com.dripps.voxyingest.client;

import com.dripps.voxyingest.network.IngestBeginPayload;
import com.dripps.voxyingest.network.IngestChunkPayload;
import com.dripps.voxyingest.network.IngestCompletePayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public class VoxyingestClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(IngestBeginPayload.TYPE, IngestHandler::handleBegin);
        ClientPlayNetworking.registerGlobalReceiver(IngestChunkPayload.TYPE, IngestHandler::handleChunk);
        ClientPlayNetworking.registerGlobalReceiver(IngestCompletePayload.TYPE, IngestHandler::handleComplete);
    }
}
