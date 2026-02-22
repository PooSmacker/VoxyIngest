package com.dripps.voxyingest.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// sent S2C to signal the start of a world ingest transfer
public record IngestBeginPayload(String dimensionId, int totalChunks) implements CustomPacketPayload {

    public static final Type<IngestBeginPayload> TYPE =
            new Type<>(Identifier.parse("voxyingest:begin"));

    public static final StreamCodec<FriendlyByteBuf, IngestBeginPayload> STREAM_CODEC =
            StreamCodec.of(
                    (FriendlyByteBuf buf, IngestBeginPayload payload) -> payload.write(buf),
                    IngestBeginPayload::read
            );

    private static IngestBeginPayload read(FriendlyByteBuf buf) {
        return new IngestBeginPayload(buf.readUtf(256), buf.readVarInt());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUtf(dimensionId, 256);
        buf.writeVarInt(totalChunks);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
