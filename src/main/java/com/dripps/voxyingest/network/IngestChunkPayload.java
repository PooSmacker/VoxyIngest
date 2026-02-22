package com.dripps.voxyingest.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// sends all sections of a single chunk in one compressed payload
public record IngestChunkPayload(int chunkX, int chunkZ, byte[] compressedData) implements CustomPacketPayload {

    public static final Type<IngestChunkPayload> TYPE =
            new Type<>(Identifier.parse("voxyingest:chunk"));

    public static final StreamCodec<FriendlyByteBuf, IngestChunkPayload> STREAM_CODEC =
            StreamCodec.of(
                    (FriendlyByteBuf buf, IngestChunkPayload payload) -> payload.write(buf),
                    IngestChunkPayload::read
            );

    private static IngestChunkPayload read(FriendlyByteBuf buf) {
        return new IngestChunkPayload(buf.readVarInt(), buf.readVarInt(), buf.readByteArray());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(chunkX);
        buf.writeVarInt(chunkZ);
        buf.writeByteArray(compressedData);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
