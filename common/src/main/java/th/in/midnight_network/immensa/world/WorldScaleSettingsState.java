package th.in.midnight_network.immensa.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Persisted per-world settings for terrain diffusion.
 *
 * <p>This is stored in the world save via Minecraft's persistent state manager.
 */
public final class WorldScaleSettingsState extends SavedData {
    private static final Codec<WorldScaleSettingsState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("scale", WorldScaleManager.DEFAULT_SCALE).forGetter(WorldScaleSettingsState::getScale),
            Codec.BOOL.optionalFieldOf("explicit_scale", false).forGetter(WorldScaleSettingsState::hasExplicitScale)
    ).apply(instance, WorldScaleSettingsState::new));

    /** Save-file id used by the dimension data storage. */
    public static final String ID = "immensa_world_settings";

    private int scale;
    private boolean explicitScale;

    private WorldScaleSettingsState(int configuredScale, boolean hasExplicitScale) {
        this.scale = WorldScaleManager.clampScale(configuredScale);
        this.explicitScale = hasExplicitScale;
    }

    /**
     * Creates a default state for worlds that do not yet have saved terrain diffusion settings.
     */
    public static WorldScaleSettingsState createDefault() {
        return new WorldScaleSettingsState(WorldScaleManager.DEFAULT_SCALE, false);
    }

    private static WorldScaleSettingsState load(CompoundTag tag, HolderLookup.Provider registries) {
        return CODEC.parse(NbtOps.INSTANCE, tag)
                .result()
                .orElseGet(WorldScaleSettingsState::createDefault);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        return CODEC.encodeStart(NbtOps.INSTANCE, this)
                .result()
                .filter(CompoundTag.class::isInstance)
                .map(CompoundTag.class::cast)
                .orElse(tag);
    }

    /**
     * Factory descriptor used by the persistent state manager.
     * 1.21.1 form: SavedData.Factory + DimensionDataStorage.computeIfAbsent(factory, id).
     */
    public static final SavedData.Factory<WorldScaleSettingsState> FACTORY =
            new SavedData.Factory<>(WorldScaleSettingsState::createDefault,
                    WorldScaleSettingsState::load, null);

    /**
     * Returns the currently persisted world scale.
     */
    public int getScale() {
        return scale;
    }

    /**
     * Returns whether this world has an explicitly chosen scale.
     */
    public boolean hasExplicitScale() {
        return explicitScale;
    }

    /**
     * Applies a new persisted world scale and marks the state dirty.
     */
    public void setScale(int configuredScale) {
        this.scale = WorldScaleManager.clampScale(configuredScale);
        this.explicitScale = true;
        setDirty();
    }
}
