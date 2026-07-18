package th.in.midnight_network.immensa;

import th.in.midnight_network.immensa.explorer.ExplorerServer;
import th.in.midnight_network.immensa.pipeline.LocalTerrainProvider;
import th.in.midnight_network.immensa.pipeline.ModelAssetManager;
import th.in.midnight_network.immensa.pipeline.PipelineModels;
import th.in.midnight_network.immensa.world.ImmensaBiomeSource;
import th.in.midnight_network.immensa.world.ImmensaDensityFunction;
import th.in.midnight_network.immensa.world.WorldScaleManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.serialization.MapCodec;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.GameRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.minecraft.commands.Commands.literal;

/**
 * Loader-neutral bootstrap for immensa. Each loader module wires
 * its own registry/event/command plumbing to the handlers exposed here.
 */
public final class Immensa {
    public static final String MOD_ID = "immensa";
    private static final Logger LOG = LoggerFactory.getLogger(Immensa.class);

    public static final ResourceLocation IMMENSA_BIOME_SOURCE_ID =
            ResourceLocation.fromNamespaceAndPath(MOD_ID, "terrain_diffusion");
    public static final ResourceLocation IMMENSA_DENSITY_FUNCTION_ID =
            ResourceLocation.fromNamespaceAndPath(MOD_ID, "terrain_diffusion");
    public static final MapCodec<ImmensaBiomeSource> IMMENSA_BIOME_SOURCE_CODEC =
            ImmensaBiomeSource.CODEC;
    public static final MapCodec<ImmensaDensityFunction> IMMENSA_DENSITY_FUNCTION_CODEC =
            ImmensaDensityFunction.CODEC;

    private Immensa() {
    }

    /**
     * Mod-construction-time initialization: prepares model assets and loads
     * the inference pipeline. Registry/event wiring is loader-specific.
     */
    public static void init() {
        LOG.info("Initializing immensa");
        ModelAssetManager.ensureAssetsReady();
        PipelineModels.load();
    }

    public static void onServerStarting(MinecraftServer server) {
        LocalTerrainProvider.clearCache();
    }

    public static void onServerStarted(MinecraftServer server) {
        LocalTerrainProvider.markServerReady();
    }

    public static void onServerStopping(MinecraftServer server) {
        LocalTerrainProvider.markServerNotReady();
        ExplorerServer.stop();
    }

    public static void onOverworldLoad(ServerLevel level) {
        if (level.dimension() == Level.OVERWORLD
                && level.getChunkSource().getGenerator().getBiomeSource()
                instanceof ImmensaBiomeSource) {
            // 1.21.1 API: GameRules.Key + getRule().set(value, server).
            level.getGameRules().getRule(GameRules.RULE_WATER_SOURCE_CONVERSION)
                    .set(false, level.getServer());
            LOG.info("Disabled water source conversion for Terrain Diffusion world");
            WorldScaleManager.initializeForWorld(level);
            LocalTerrainProvider.init(level.getSeed());
        }
    }

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(literal("immensa-explore").executes(Immensa::executeExplore));
    }

    private static int executeExplore(CommandContext<CommandSourceStack> ctx) {
        try {
            int port = ExplorerServer.startIfNotRunning();
            String url = "http://localhost:" + port;
            MutableComponent link = Component.literal(url)
                    .withStyle(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                                  .withUnderlined(true));
            ctx.getSource().sendSuccess(
                    () -> Component.literal("Terrain Explorer: ").append(link),
                    false);
        } catch (Exception e) {
            LOG.error("Failed to start terrain explorer", e);
            ctx.getSource().sendFailure(Component.literal("Failed to start terrain explorer: " + e.getMessage()));
        }
        return 1;
    }
}
