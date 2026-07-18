package th.in.midnight_network.immensa.mixin;

import th.in.midnight_network.immensa.pipeline.SpawnSelector;
import th.in.midnight_network.immensa.world.ImmensaBiomeSource;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ServerLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public class MinecraftServerMixin {

    // 1.21.1: private static void setInitialSpawn(ServerLevel, ServerLevelData, boolean, boolean)
    @Inject(method = "setInitialSpawn", at = @At("HEAD"), cancellable = true)
    private static void overrideWorldSpawn(ServerLevel world, ServerLevelData worldProperties,
                                           boolean bonusChest, boolean debugWorld, CallbackInfo ci) {
        if (!world.dimension().equals(Level.OVERWORLD)
                || !(world.getChunkSource().getGenerator().getBiomeSource()
                instanceof ImmensaBiomeSource)) {
            return;
        }

        BlockPos spawnPos = SpawnSelector.findSpawnBlockPos();
        worldProperties.setSpawn(spawnPos, 0f);
        ci.cancel();
    }
}
