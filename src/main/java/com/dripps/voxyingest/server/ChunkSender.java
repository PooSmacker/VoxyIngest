package com.dripps.voxyingest.server;

import com.dripps.voxyingest.Voxyingest;
import com.dripps.voxyingest.network.IngestBeginPayload;
import com.dripps.voxyingest.network.IngestChunkPayload;
import com.dripps.voxyingest.network.IngestCompletePayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.io.*;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

// sends ALL generated world chunk data from server to client todo: add a range
public class ChunkSender {

    private static final int CHUNKS_PER_TICK = 200;
    private static final int QUEUE_HIGH_WATER = 2000;
    private static final Map<UUID, SendSession> activeSessions = new ConcurrentHashMap<>();

    private static final ExecutorService SCANNER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "VoxyIngest-Scanner");
        t.setDaemon(true);
        return t;
    });

    public static boolean startSending(ServerPlayer player) {
        UUID uuid = player.getUUID();
        if (activeSessions.containsKey(uuid)) return false;

        ServerLevel level = (ServerLevel) player.level();
        String dimensionId = level.dimension().identifier().toString();

        SendSession session = new SendSession(player, dimensionId);
        activeSessions.put(uuid, session);

        ServerPlayNetworking.send(player, new IngestBeginPayload(dimensionId, -1));
        SCANNER.submit(() -> scanRegionFiles(level, session));

        Voxyingest.LOGGER.info("started sending world data ({}) to {}",
                dimensionId, player.getGameProfile().name());
        return true;
    }

    public static boolean isSessionActive(ServerPlayer player) {
        return activeSessions.containsKey(player.getUUID());
    }

    public static void cancelSession(ServerPlayer player) {
        SendSession session = activeSessions.remove(player.getUUID());
        if (session != null) session.cancelled = true;
    }

    public static void tick(MinecraftServer server) {
        Iterator<Map.Entry<UUID, SendSession>> it = activeSessions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, SendSession> entry = it.next();
            SendSession session = entry.getValue();

            if (session.player.hasDisconnected()) {
                session.cancelled = true;
                it.remove();
                continue;
            }

            if (!ServerPlayNetworking.canSend(session.player, IngestChunkPayload.TYPE)) {
                session.cancelled = true;
                it.remove();
                continue;
            }

            for (int i = 0; i < CHUNKS_PER_TICK; i++) {
                IngestChunkPayload payload = session.queue.poll();
                if (payload == null) break;
                ServerPlayNetworking.send(session.player, payload);
                session.totalSent++;
            }

            if (!session.scanning && session.queue.isEmpty()) {
                ServerPlayNetworking.send(session.player,
                        new IngestCompletePayload(session.totalSent));
                it.remove();
                Voxyingest.LOGGER.info("finished sending {} chunks to {}",
                        session.totalSent, session.player.getGameProfile().name());
            }
        }
    }

    // we read .mca files ourselves, mc's ioworker is dramatically slower

    private static void scanRegionFiles(ServerLevel level, SendSession session) {
        try {
            Path regionDir = getRegionDir(level);
            if (regionDir == null || !Files.exists(regionDir)) {
                Voxyingest.LOGGER.warn("region directory not found for {}",
                        level.dimension().identifier());
                session.scanning = false;
                return;
            }

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(regionDir, "r.*.*.mca")) {
                for (Path mcaPath : stream) {
                    if (session.cancelled) break;
                    scanSingleRegionFile(mcaPath, session);
                }
            }

            Voxyingest.LOGGER.info("scan complete for {}: {} chunks queued",
                    session.player.getGameProfile().name(), session.totalQueued.get());
        } catch (IOException e) {
            Voxyingest.LOGGER.error("error scanning region files", e);
        } finally {
            session.scanning = false;
        }
    }

    private static void scanSingleRegionFile(Path mcaPath, SendSession session) {
        String name = mcaPath.getFileName().toString();
        String[] parts = name.split("\\.");
        if (parts.length != 4) return;

        int regionX, regionZ;
        try {
            regionX = Integer.parseInt(parts[1]);
            regionZ = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            return;
        }

        try (RandomAccessFile raf = new RandomAccessFile(mcaPath.toFile(), "r")) {
            if (raf.length() < 8192) return; // need at least the 8kb header

            // read the 4kb offset table (1024 entries × 4 bytes)
            byte[] header = new byte[4096];
            raf.readFully(header);

            for (int idx = 0; idx < 1024; idx++) {
                if (session.cancelled) return;

                // wait if send queue is full
                while (session.queue.size() > QUEUE_HIGH_WATER && !session.cancelled) {
                    try { Thread.sleep(20); } catch (InterruptedException ignored) {}
                }
                if (session.cancelled) return;

                // offset table entry - 3 bytes sector offset, 1 byte sector count
                int off0 = header[idx * 4] & 0xFF;
                int off1 = header[idx * 4 + 1] & 0xFF;
                int off2 = header[idx * 4 + 2] & 0xFF;
                int sectorCount = header[idx * 4 + 3] & 0xFF;
                int sectorOffset = (off0 << 16) | (off1 << 8) | off2;

                if (sectorOffset == 0 || sectorCount == 0) continue;

                int chunkX = regionX * 32 + (idx % 32);
                int chunkZ = regionZ * 32 + (idx / 32);

                try {
                    raf.seek(sectorOffset * 4096L);
                    int dataLength = raf.readInt();
                    byte compressionType = raf.readByte();
                    int compressedLength = dataLength - 1;
                    if (compressedLength <= 0 || compressedLength > sectorCount * 4096) continue;

                    byte[] compressedBytes = new byte[compressedLength];
                    raf.readFully(compressedBytes);

                    // decompress  - parse nbt
                    CompoundTag chunkTag;
                    try (DataInputStream dis = new DataInputStream(
                            decompressRegionStream(compressionType, compressedBytes))) {
                        chunkTag = NbtIo.read(dis);
                    }
                    if (chunkTag == null) continue;

                    queueChunkPayload(chunkTag, chunkX, chunkZ, session);
                } catch (Exception e) {
                    Voxyingest.LOGGER.debug("skipping corrupt chunk eek ({},{}) in {}: {}",
                            chunkX, chunkZ, name, e.getMessage());
                }
            }
        } catch (IOException e) {
            Voxyingest.LOGGER.debug("error reading region file {}: {}", name, e.getMessage());
        }
    }

    private static InputStream decompressRegionStream(byte type, byte[] data) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        return switch (type) {
            case 1 -> new GZIPInputStream(bais);
            case 2 -> new InflaterInputStream(bais);
            case 3 -> bais; // uncompressed
            default -> throw new IOException("this should never happen but unknown region compression type: " + type);
        };
    }

    private static void queueChunkPayload(CompoundTag chunkTag, int chunkX, int chunkZ,
                                           SendSession session) {
        if (!chunkTag.contains("Status")) return;
        String status = chunkTag.getStringOr("Status", "");
        if (!status.equals("minecraft:full") && !status.equals("full")) return;

        Optional<ListTag> sectionsOpt = chunkTag.getList("sections");
        if (sectionsOpt.isEmpty()) return;
        ListTag allSections = sectionsOpt.get();

        ListTag filtered = new ListTag();
        for (int i = 0; i < allSections.size(); i++) {
            Optional<CompoundTag> sOpt = allSections.getCompound(i);
            if (sOpt.isEmpty()) continue;
            CompoundTag section = sOpt.get();
            if (section.getCompound("block_states").isEmpty()) continue;
            filtered.add(section);
        }
        if (filtered.isEmpty()) return;

        CompoundTag payload = new CompoundTag();
        payload.put("sections", filtered);
        byte[] compressed = compressNbt(payload);

        session.queue.add(new IngestChunkPayload(chunkX, chunkZ, compressed));
        int total = session.totalQueued.incrementAndGet();
        if (total % 1000 == 0) {
            Voxyingest.LOGGER.info("scanned {} chunks for {}",
                    total, session.player.getGameProfile().name());
        }
    }

    private static byte[] compressNbt(CompoundTag tag) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (DataOutputStream dos = new DataOutputStream(new DeflaterOutputStream(baos))) {
                NbtIo.write(tag, dos);
            }
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("failed to compress nbt", e);
        }
    }


    private static Path getRegionDir(ServerLevel level) {
        Path worldRoot = level.getServer().getWorldPath(LevelResource.ROOT);
        ResourceKey<Level> dim = level.dimension();

        Path dimDir;
        if (dim == Level.OVERWORLD) {
            dimDir = worldRoot;
        } else if (dim == Level.NETHER) {
            dimDir = worldRoot.resolve("DIM-1");
        } else if (dim == Level.END) {
            dimDir = worldRoot.resolve("DIM1");
        } else {
            Identifier id = dim.identifier();
            dimDir = worldRoot.resolve("dimensions")
                    .resolve(id.getNamespace())
                    .resolve(id.getPath());
        }
        return dimDir.resolve("region");
    }


    private static class SendSession {
        final ServerPlayer player;
        final String dimensionId;
        final ConcurrentLinkedQueue<IngestChunkPayload> queue = new ConcurrentLinkedQueue<>();
        final AtomicInteger totalQueued = new AtomicInteger();
        volatile boolean scanning = true;
        volatile boolean cancelled = false;
        int totalSent = 0;

        SendSession(ServerPlayer player, String dimensionId) {
            this.player = player;
            this.dimensionId = dimensionId;
        }
    }
}
