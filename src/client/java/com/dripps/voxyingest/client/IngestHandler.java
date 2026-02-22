package com.dripps.voxyingest.client;

import com.dripps.voxyingest.Voxyingest;
import com.dripps.voxyingest.network.IngestBeginPayload;
import com.dripps.voxyingest.network.IngestChunkPayload;
import com.dripps.voxyingest.network.IngestCompletePayload;
import com.mojang.serialization.Codec;
import me.cortex.voxy.common.voxelization.ILightingSupplier;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldConversionFactory;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldUpdater;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Strategy;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.zip.InflaterInputStream;

 // client side handler for receiving world data from the server and ingesting it into voxy
public class IngestHandler {

    private static final byte[] EMPTY = new byte[0];
    private static final ThreadLocal<VoxelizedSection> SECTION_CACHE =
            ThreadLocal.withInitial(VoxelizedSection::createEmpty);

    private static final ExecutorService INGEST_EXECUTOR = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() - 1),
            r -> {
                Thread t = new Thread(r, "VoxyIngest-Client-" + Thread.activeCount());
                t.setDaemon(true);
                return t;
            });

    // State for the current ingest session
    private static volatile WorldEngine currentEngine;
    private static volatile Codec<PalettedContainer<BlockState>> blockStateCodec;
    private static volatile Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec;
    private static volatile PalettedContainerRO<Holder<Biome>> defaultBiomeProvider;
    private static final AtomicInteger sectionsIngested = new AtomicInteger();
    private static final AtomicInteger chunksReceived = new AtomicInteger();
    private static volatile int totalChunks;
    private static volatile long rateWindowStart;
    private static volatile int rateWindowChunks;
    private static volatile int displayRate;

    /**
     * Handle the begin payload — prepare for ingest.
     */
    public static void handleBegin(IngestBeginPayload payload, ClientPlayNetworking.Context context) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) {
            showMessage(context, "§cvant ingest: not in a world");
            return;
        }

        var voxyInstance = VoxyCommon.getInstance();
        if (voxyInstance == null) {
            showMessage(context, "§cvoxy not found! make sure u have voxy installed");
            return;
        }

        WorldIdentifier worldId;
        try {
            worldId = WorldIdentifier.of(mc.level);
        } catch (Exception e) {
            showMessage(context, "§ccoukldnt get world identifier: " + e.getMessage());
            return;
        }

        if (worldId == null) {
            showMessage(context, "§ccouldnt determine world identifier");
            return;
        }

        currentEngine = voxyInstance.getOrCreate(worldId);
        if (currentEngine == null) {
            showMessage(context, "§ccouldnt create voxy world engine");
            return;
        }

        var factory = PalettedContainerFactory.create(mc.level.registryAccess());
        blockStateCodec = factory.blockStatesContainerCodec();
        biomeCodec = factory.biomeContainerCodec();

        var biomeRegistry = mc.level.registryAccess().lookupOrThrow(Registries.BIOME);
        var defaultBiome = biomeRegistry.getOrThrow(Biomes.PLAINS);
        defaultBiomeProvider = createDefaultBiomeProvider(defaultBiome);

        sectionsIngested.set(0);
        chunksReceived.set(0);
        totalChunks = payload.totalChunks();
        rateWindowStart = System.nanoTime();
        rateWindowChunks = 0;
        displayRate = 0;

        showMessage(context, "§astarting voxy world ingest from server ("
                + payload.dimensionId() + ", " + totalChunks + " chunks)...");
    }

    public static void handleChunk(IngestChunkPayload payload, ClientPlayNetworking.Context context) {
        if (currentEngine == null || blockStateCodec == null) return;

        int received = chunksReceived.incrementAndGet();
        updateRate(received);

        final WorldEngine engine = currentEngine;
        final Codec<PalettedContainer<BlockState>> bsCodec = blockStateCodec;
        final Codec<PalettedContainerRO<Holder<Biome>>> bmCodec = biomeCodec;
        final PalettedContainerRO<Holder<Biome>> defaultBiome = defaultBiomeProvider;
        final int chunkX = payload.chunkX();
        final int chunkZ = payload.chunkZ();
        final byte[] compressed = payload.compressedData();

        INGEST_EXECUTOR.submit(() -> {
            try {
                CompoundTag data = decompressNbt(compressed);
                if (data == null) return;

                Optional<ListTag> sectionsOpt = data.getList("sections");
                if (sectionsOpt.isEmpty()) return;
                ListTag sections = sectionsOpt.get();

                for (int i = 0; i < sections.size(); i++) {
                    Optional<CompoundTag> sOpt = sections.getCompound(i);
                    if (sOpt.isEmpty()) continue;
                    CompoundTag section = sOpt.get();
                    int y = section.getIntOr("Y", Integer.MIN_VALUE);
                    if (y == Integer.MIN_VALUE) continue;
                    ingestSection(engine, bsCodec, bmCodec, defaultBiome, section, chunkX, y, chunkZ);
                }
            } catch (Exception e) {
                Voxyingest.LOGGER.error("error processing chunk uh oh ({}, {})", chunkX, chunkZ, e);
            }
        });
    }

    private static CompoundTag decompressNbt(byte[] compressed) {
        try (DataInputStream dis = new DataInputStream(
                new InflaterInputStream(new ByteArrayInputStream(compressed)))) {
            return NbtIo.read(dis);
        } catch (IOException e) {
            Voxyingest.LOGGER.error("couldnt decompress chunk data", e);
            return null;
        }
    }

    public static void handleComplete(IngestCompletePayload payload, ClientPlayNetworking.Context context) {
        int ingested = sectionsIngested.get();
        int chunks = chunksReceived.get();
        showMessage(context, "§avoxy world ingest complete! §f" + chunks + "§a chunks (§f" + ingested + "§a sections) ingested. woohoo");

        var mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.empty(), true);
        }

        currentEngine = null;
        blockStateCodec = null;
        biomeCodec = null;
        defaultBiomeProvider = null;
    }

    private static void updateRate(int totalReceived) {
        long now = System.nanoTime();
        long elapsed = now - rateWindowStart;
        if (elapsed >= 1_000_000_000L) {
            int delta = totalReceived - rateWindowChunks;
            double seconds = elapsed / 1_000_000_000.0;
            displayRate = (int) (delta / seconds);
            rateWindowStart = now;
            rateWindowChunks = totalReceived;

            var mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.execute(() -> mc.player.displayClientMessage(
                        Component.literal("§eVoxyIngest §7| §f" + totalReceived + " chunks §7| §f"
                                + displayRate + " chunks/s §7| §f" + sectionsIngested.get() + " sections"),
                        true));
            }
        }
    }

    private static void ingestSection(WorldEngine engine,
                                      Codec<PalettedContainer<BlockState>> bsCodec,
                                      Codec<PalettedContainerRO<Holder<Biome>>> bmCodec,
                                      PalettedContainerRO<Holder<Biome>> defaultBiome,
                                      CompoundTag data, int x, int y, int z) {
        try {
            if (!engine.isLive()) return;

            var blockStatesOpt = data.getCompound("block_states");
            if (blockStatesOpt.isEmpty()) return;

            var blockStatesResult = bsCodec.parse(NbtOps.INSTANCE, blockStatesOpt.get());
            if (!blockStatesResult.hasResultOrPartial()) return;
            var blockStates = blockStatesResult.getPartialOrThrow();

            PalettedContainerRO<Holder<Biome>> biomes = defaultBiome;
            var biomesOpt = data.getCompound("biomes");
            if (biomesOpt.isPresent()) {
                biomes = bmCodec.parse(NbtOps.INSTANCE, biomesOpt.get())
                        .result().orElse(defaultBiome);
            }

            byte[] blockLightData = data.getByteArray("BlockLight").orElse(EMPTY);
            byte[] skyLightData = data.getByteArray("SkyLight").orElse(EMPTY);
            DataLayer blockLight = blockLightData.length != 0 ? new DataLayer(blockLightData) : null;
            DataLayer skyLight = skyLightData.length != 0 ? new DataLayer(skyLightData) : null;

            VoxelizedSection csec = WorldConversionFactory.convert(
                    SECTION_CACHE.get().setPosition(x, y, z),
                    engine.getMapper(),
                    blockStates,
                    biomes,
                    createLightingSupplier(blockLight, skyLight)
            );
            WorldConversionFactory.mipSection(csec, engine.getMapper());
            WorldUpdater.insertUpdate(engine, csec);

            int total = sectionsIngested.incrementAndGet();
        } catch (Exception e) {
            Voxyingest.LOGGER.error("error ingesting section at ({}, {}, {})", x, y, z, e);
        }
    }

    private static ILightingSupplier createLightingSupplier(DataLayer blockLight, DataLayer skyLight) {
        boolean bl = blockLight != null && !blockLight.isEmpty();
        boolean sl = skyLight != null && !skyLight.isEmpty();

        if (bl && sl) {
            return (bx, by, bz) -> {
                int block = Math.min(15, blockLight.get(bx, by, bz));
                int sky = Math.min(15, skyLight.get(bx, by, bz));
                return (byte) (sky | (block << 4));
            };
        } else if (bl) {
            return (bx, by, bz) -> (byte) (Math.min(15, blockLight.get(bx, by, bz)) << 4);
        } else if (sl) {
            return (bx, by, bz) -> (byte) Math.min(15, skyLight.get(bx, by, bz));
        }
        return (bx, by, bz) -> (byte) 0;
    }


    @SuppressWarnings("unchecked")
    private static PalettedContainerRO<Holder<Biome>> createDefaultBiomeProvider(Holder<Biome> defaultBiome) {
        return new PalettedContainerRO<>() {
            @Override public Holder<Biome> get(int x, int y, int z) { return defaultBiome; }
            @Override public void getAll(Consumer<Holder<Biome>> action) {}
            @Override public void write(FriendlyByteBuf buf) {}
            @Override public int getSerializedSize() { return 0; }
            @Override public int bitsPerEntry() { return 0; }
            @Override public boolean maybeHas(Predicate<Holder<Biome>> predicate) { return false; }
            @Override public void count(PalettedContainer.CountConsumer<Holder<Biome>> counter) {}
            @Override public PalettedContainer<Holder<Biome>> copy() { return null; }
            @Override public PalettedContainer<Holder<Biome>> recreate() { return null; }
            @Override public PackedData<Holder<Biome>> pack(Strategy<Holder<Biome>> provider) { return null; }
        };
    }

    private static void showMessage(ClientPlayNetworking.Context context, String message) {
        context.player().displayClientMessage(Component.literal(message), false);
    }
}
