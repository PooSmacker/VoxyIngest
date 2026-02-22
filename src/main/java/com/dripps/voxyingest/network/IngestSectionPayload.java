package com.dripps.voxyingest.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// sent S2C containing one chunk sections data

public record IngestSectionPayload(int x, int y, int z, CompoundTag data) implements CustomPacketPayload {

    public static final Type<IngestSectionPayload> TYPE =
            new Type<>(Identifier.parse("voxyingest:section"));

    public static final StreamCodec<FriendlyByteBuf, IngestSectionPayload> STREAM_CODEC =
            StreamCodec.of(
                    (FriendlyByteBuf buf, IngestSectionPayload payload) -> payload.write(buf),
                    IngestSectionPayload::read
            );

    private static IngestSectionPayload read(FriendlyByteBuf buf) {
        int x = buf.readInt();
        int y = buf.readInt();
        int z = buf.readInt();
        CompoundTag data = buf.readNbt();
        return new IngestSectionPayload(x, y, z, data != null ? data : new CompoundTag());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeNbt(data);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
