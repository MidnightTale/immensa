package th.in.midnight_network.immensa.pipeline;

import th.in.midnight_network.immensa.platform.Platforms;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Versioned, compressed cache for fully post-processed regional terrain tiles. */
final class TerrainTileDiskCache {
    private static final Logger LOG = LoggerFactory.getLogger(TerrainTileDiskCache.class);
    private static final int MAGIC = 0x54444d43; // TDMC
    /** Increment whenever generation semantics or the on-disk layout changes. */
    private static final int VERSION = 13;

    private TerrainTileDiskCache() {}

    static LocalTerrainProvider.HeightmapData load(long seed, int scale,
                                                    int i1, int j1, int i2, int j2) {
        return load(cacheRoot(), seed, scale, i1, j1, i2, j2);
    }

    static LocalTerrainProvider.HeightmapData load(Path root, long seed, int scale,
                                                    int i1, int j1, int i2, int j2) {
        Path path = path(root, seed, scale, i1, j1, i2, j2);
        if (!Files.isRegularFile(path)) return null;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(Files.newInputStream(path), 64 * 1024), 64 * 1024))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION
                    || in.readLong() != seed || in.readInt() != scale
                    || in.readInt() != i1 || in.readInt() != j1
                    || in.readInt() != i2 || in.readInt() != j2) {
                return null;
            }
            int height = in.readInt();
            int width = in.readInt();
            if (height != i2 - i1 || width != j2 - j1 || height <= 0 || width <= 0
                    || (long) height * width > 4_194_304L) return null;

            short[][] elevation = new short[height][width];
            short[][] biomes = new short[height][width];
            short[][] water = new short[height][width];
            boolean[][] lakes = new boolean[height][width];
            short[][] landforms = new short[height][width];
            byte[][] geology = new byte[height][width];
            byte[][] soil = new byte[height][width];
            byte[][] riverWidth = new byte[height][width];
            for (int r = 0; r < height; r++) for (int c = 0; c < width; c++) {
                elevation[r][c] = in.readShort();
                biomes[r][c] = in.readShort();
                water[r][c] = in.readShort();
                landforms[r][c] = in.readShort();
                geology[r][c] = in.readByte();
                soil[r][c] = in.readByte();
                riverWidth[r][c] = in.readByte();
                lakes[r][c] = in.readBoolean();
            }
            return new LocalTerrainProvider.HeightmapData(elevation, biomes, water, lakes,
                    landforms, geology, soil, riverWidth, width, height);
        } catch (IOException | RuntimeException e) {
            LOG.warn("Ignoring unreadable terrain tile cache {}: {}", path, e.getMessage());
            try { Files.deleteIfExists(path); } catch (IOException ignored) {}
            return null;
        }
    }

    static void store(long seed, int scale, int i1, int j1, int i2, int j2,
                      LocalTerrainProvider.HeightmapData data) {
        store(cacheRoot(), seed, scale, i1, j1, i2, j2, data);
    }

    static void store(Path root, long seed, int scale, int i1, int j1, int i2, int j2,
                      LocalTerrainProvider.HeightmapData data) {
        Path path = path(root, seed, scale, i1, j1, i2, j2);
        Path temp = path.resolveSibling(path.getFileName() + "." + Thread.currentThread().threadId() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                    new GZIPOutputStream(Files.newOutputStream(temp), 64 * 1024), 64 * 1024))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeLong(seed);
                out.writeInt(scale);
                out.writeInt(i1); out.writeInt(j1); out.writeInt(i2); out.writeInt(j2);
                out.writeInt(data.height); out.writeInt(data.width);
                for (int r = 0; r < data.height; r++) for (int c = 0; c < data.width; c++) {
                    out.writeShort(data.heightmap[r][c]);
                    out.writeShort(data.biomeIds[r][c]);
                    out.writeShort(data.waterSurface[r][c]);
                    out.writeShort(data.landforms[r][c]);
                    out.writeByte(data.geology[r][c]);
                    out.writeByte(data.soilDepth[r][c]);
                    out.writeByte(data.riverWidth[r][c]);
                    out.writeBoolean(data.lakeMask[r][c]);
                }
            }
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOG.warn("Could not persist terrain tile {}: {}", path, e.getMessage());
            try { Files.deleteIfExists(temp); } catch (IOException ignored) {}
        }
    }

    private static Path path(Path root, long seed, int scale, int i1, int j1, int i2, int j2) {
        String seedDir = Long.toUnsignedString(seed, 16) + "-s" + scale + "-v" + VERSION;
        String name = i1 + "_" + j1 + "_" + i2 + "_" + j2 + ".tdmc.gz";
        return root.resolve(seedDir).resolve(name);
    }

    private static Path cacheRoot() {
        return Platforms.get().gameDir().resolve("immensa-cache");
    }
}
