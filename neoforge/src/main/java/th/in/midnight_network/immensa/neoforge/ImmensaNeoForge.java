package th.in.midnight_network.immensa.neoforge;

import th.in.midnight_network.immensa.Immensa;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * NeoForge entry point: registers the codecs via DeferredRegister and wires
 * NeoForge events to the loader-neutral handlers in {@link Immensa}.
 *
 * <p>The single mod id {@link Immensa#MOD_ID} is valid both as an FML mod id and
 * as a ResourceLocation namespace, so one identifier serves both loaders.
 */
@Mod(Immensa.MOD_ID)
public class ImmensaNeoForge {

    public ImmensaNeoForge(IEventBus modEventBus) {
        DeferredRegister<MapCodec<? extends BiomeSource>> biomeSources =
                DeferredRegister.create(BuiltInRegistries.BIOME_SOURCE, Immensa.MOD_ID);
        biomeSources.register(Immensa.IMMENSA_BIOME_SOURCE_ID.getPath(),
                () -> Immensa.IMMENSA_BIOME_SOURCE_CODEC);

        DeferredRegister<MapCodec<? extends DensityFunction>> densityFunctions =
                DeferredRegister.create(BuiltInRegistries.DENSITY_FUNCTION_TYPE, Immensa.MOD_ID);
        densityFunctions.register(Immensa.IMMENSA_DENSITY_FUNCTION_ID.getPath(),
                () -> Immensa.IMMENSA_DENSITY_FUNCTION_CODEC);

        biomeSources.register(modEventBus);
        densityFunctions.register(modEventBus);

        Immensa.init();

        NeoForge.EVENT_BUS.addListener((ServerStartingEvent event) ->
                Immensa.onServerStarting(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) ->
                Immensa.onServerStarted(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) ->
                Immensa.onServerStopping(event.getServer()));
        NeoForge.EVENT_BUS.addListener((LevelEvent.Load event) -> {
            if (event.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD) {
                Immensa.onOverworldLoad(level);
            }
        });
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) ->
                Immensa.registerCommands(event.getDispatcher()));
    }
}
