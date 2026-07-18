package th.in.midnight_network.immensa.fabric;

import th.in.midnight_network.immensa.Immensa;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * Fabric entry point: registers the codecs and wires Fabric lifecycle events
 * to the loader-neutral handlers in {@link Immensa}.
 */
public class ImmensaFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        Registry.register(BuiltInRegistries.BIOME_SOURCE,
                Immensa.IMMENSA_BIOME_SOURCE_ID,
                Immensa.IMMENSA_BIOME_SOURCE_CODEC);
        Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE,
                Immensa.IMMENSA_DENSITY_FUNCTION_ID,
                Immensa.IMMENSA_DENSITY_FUNCTION_CODEC);

        Immensa.init();

        ServerLifecycleEvents.SERVER_STARTING.register(Immensa::onServerStarting);
        ServerLifecycleEvents.SERVER_STARTED.register(Immensa::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(Immensa::onServerStopping);
        ServerWorldEvents.LOAD.register((server, world) -> Immensa.onOverworldLoad(world));
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                Immensa.registerCommands(dispatcher));
    }
}
