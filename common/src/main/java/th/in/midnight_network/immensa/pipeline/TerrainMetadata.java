package th.in.midnight_network.immensa.pipeline;

/** Compact per-column metadata shared by terrain, biome, water and decoration stages. */
public final class TerrainMetadata {
    public static final short FLOODPLAIN = 1;
    public static final short WETLAND = 1 << 1;
    public static final short DELTA = 1 << 2;
    public static final short ESTUARY = 1 << 3;
    public static final short WATERFALL = 1 << 4;
    public static final short OXBOW = 1 << 5;
    public static final short COAST = 1 << 6;
    public static final short STRUCTURE_SUITABLE = 1 << 7;
    public static final short BRIDGE_SITE = 1 << 8;
    public static final short PORT_SITE = 1 << 9;
    public static final short SCREE = 1 << 10;
    public static final short GREAT_RIVER = 1 << 11;
    public static final short CLIFF = 1 << 12;
    private static final int CLIFF_DIRECTION_SHIFT = 13;
    private static final int CLIFF_DIRECTION_MASK = 3 << CLIFF_DIRECTION_SHIFT;
    public static final short MAJOR_CLIFF = (short) (1 << 15);

    public static final int CLIFF_NORTH = 0;
    public static final int CLIFF_EAST = 1;
    public static final int CLIFF_SOUTH = 2;
    public static final int CLIFF_WEST = 3;

    public static final byte GEO_SEDIMENTARY = 0;
    public static final byte GEO_GRANITIC = 1;
    public static final byte GEO_LIMESTONE = 2;
    public static final byte GEO_VOLCANIC = 3;
    public static final byte GEO_GLACIAL = 4;

    public static short withCliff(short flags, int highSideDirection, boolean major) {
        int encoded = (flags & 0xffff) | CLIFF
                | ((highSideDirection & 3) << CLIFF_DIRECTION_SHIFT);
        if (major) encoded |= MAJOR_CLIFF & 0xffff;
        return (short) encoded;
    }

    public static int cliffDirection(short flags) {
        return ((flags & 0xffff) & CLIFF_DIRECTION_MASK) >>> CLIFF_DIRECTION_SHIFT;
    }

    private TerrainMetadata() {
    }
}
