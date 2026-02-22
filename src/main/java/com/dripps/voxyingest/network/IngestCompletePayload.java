package com.dripps.voxyingest.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// sent S2C to signal that the world ingest transfer est fini
public record IngestCompletePayload(int totalSections) implements CustomPacketPayload {

    public static final Type<IngestCompletePayload> TYPE =
            new Type<>(Identifier.parse("voxyingest:complete"));

    public static final StreamCodec<FriendlyByteBuf, IngestCompletePayload> STREAM_CODEC =
            StreamCodec.of(
                    (FriendlyByteBuf buf, IngestCompletePayload payload) -> payload.write(buf),
                    IngestCompletePayload::read
            );

    private static IngestCompletePayload read(FriendlyByteBuf buf) {
        return new IngestCompletePayload(buf.readVarInt());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(totalSections);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
