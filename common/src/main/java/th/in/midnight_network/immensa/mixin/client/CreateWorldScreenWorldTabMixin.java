package th.in.midnight_network.immensa.mixin.client;

import th.in.midnight_network.immensa.client.WorldScaleSettingsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * Reuses vanilla's World tab "Customize" button for Terrain Diffusion worlds.
 *
 * <p><b>Dual-loader constraint:</b> this mixin is compiled once in {@code common} against
 * official (mojmap) names and runs on both Fabric (intermediary runtime, remapped by Loom)
 * and NeoForge (mojmap dev and production runtime, no remapping at all). It must therefore
 * reference <b>only</b> members that have a stable name in every mapping set:
 * <ul>
 *   <li>constructor {@code <init>} and mojmap-named members ({@code openPresetEditor},
 *       {@code customizeTypeButton}, {@code SwitchGrid$SwitchBuilder#withIsActiveCondition},
 *       {@code Minecraft#screen}, {@code CreateWorldScreen#getUiState()}) — these remap
 *       cleanly to intermediary on Fabric and resolve as-is on NeoForge;</li>
 *   <li><b>no</b> compiler-synthetic members. The outer-this field and the lambda methods
 *       of {@code CreateWorldScreen$WorldTab} are unnamed in mojmap (obfuscated to
 *       single letters in the vanilla jar, e.g. {@code a}, {@code e}, {@code f}; restored
 *       as {@code this$0}/{@code lambda$new$N} only inside the MDG dev jar; known as
 *       {@code field_42182}/{@code method_4867x}/{@code method_4868x} in Fabric
 *       intermediary). Referencing any of those names cannot resolve on both loaders,
 *       so this mixin deliberately avoids them:
 *   <li>instead of shadowing the outer-this field, the {@code CreateWorldScreen} instance
 *       is obtained from the constructor parameter or {@code Minecraft#getInstance().screen}
 *       (the World tab's callbacks only ever fire while its screen is open);</li>
 *   <li>instead of injecting into the uiState-change lambda (the one vanilla uses to
 *       enable/disable the customize button), we register our own
 *       {@link WorldCreationUiState#addListener} at the tail of the constructor. Vanilla
 *       registers its listener earlier in the same constructor, and listeners run in
 *       registration order, so ours runs last and wins;</li>
 *   <li>instead of forcing the two no-arg boolean lambdas that back the SwitchGrid
 *       "is active" conditions (generate structures / bonus chest), we rewrite the
 *       {@code BooleanSupplier} argument of the two {@code withIsActiveCondition} calls
 *       in the constructor, wrapping the original suppliers.</li>
 * </ul>
 */
@Mixin(targets = "net.minecraft.client.gui.screens.worldselection.CreateWorldScreen$WorldTab")
public abstract class CreateWorldScreenWorldTabMixin {
    @Shadow
    private Button customizeTypeButton;

    private static final ResourceKey<WorldPreset> IMMENSA_PRESET_KEY =
            ResourceKey.create(Registries.WORLD_PRESET, Identifier.fromNamespaceAndPath("immensa", "terrain_diffusion"));

    /**
     * Vanilla updates {@code customizeTypeButton.active} from a {@link WorldCreationUiState}
     * listener registered in this constructor; for a custom world type without a vanilla
     * {@code PresetEditor} it leaves the button disabled. Registering our own listener at
     * TAIL makes it run after vanilla's, re-enabling the button for our preset.
     */
    @Inject(method = "<init>", at = @At("TAIL"))
    private void immensa$enableCustomizeButtonForImmensa(CreateWorldScreen parent, CallbackInfo callbackInfo) {
        parent.getUiState().addListener(uiState -> {
            if (immensa$isImmensaWorldType(uiState)) {
                customizeTypeButton.active = true;
            }
        });
    }

    /**
     * Keeps the World tab's generate-structures / bonus-chest switches active when our
     * preset is selected, preserving the behavior previously implemented by overriding
     * the two (unnamed) boolean supplier lambdas. Matches both {@code withIsActiveCondition}
     * calls in the constructor and wraps their supplier argument; only the
     * {@link BooleanSupplier} parameter type appears here because {@code SwitchGrid}
     * itself is package-private.
     */
    @ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/worldselection/SwitchGrid$SwitchBuilder;withIsActiveCondition(Ljava/util/function/BooleanSupplier;)Lnet/minecraft/client/gui/screens/worldselection/SwitchGrid$SwitchBuilder;"), index = 0)
    private BooleanSupplier immensa$forceSwitchesActiveForImmensa(BooleanSupplier originalCondition) {
        return () -> isImmensaWorldTypeSelected() || originalCondition.getAsBoolean();
    }

    @Inject(method = "openPresetEditor", at = @At("HEAD"), cancellable = true)
    private void immensa$openTerrainScaleScreen(CallbackInfo callbackInfo) {
        if (!isImmensaWorldTypeSelected()) {
            return;
        }
        Minecraft minecraftClient = Minecraft.getInstance();
        // The customize button can only be pressed while this tab's screen is open, so the
        // current screen is the enclosing CreateWorldScreen. This replaces shadowing the
        // (unnamed) outer-this field, which has no loader-independent name.
        if (minecraftClient != null && minecraftClient.screen instanceof CreateWorldScreen createWorldScreen) {
            minecraftClient.setScreen(new WorldScaleSettingsScreen(createWorldScreen));
            callbackInfo.cancel();
        }
    }

    private boolean isImmensaWorldTypeSelected() {
        Minecraft minecraftClient = Minecraft.getInstance();
        if (minecraftClient == null || !(minecraftClient.screen instanceof CreateWorldScreen createWorldScreen)) {
            return false;
        }
        return immensa$isImmensaWorldType(createWorldScreen.getUiState());
    }

    private static boolean immensa$isImmensaWorldType(WorldCreationUiState worldCreator) {
        if (worldCreator == null) {
            return false;
        }
        WorldCreationUiState.WorldTypeEntry worldType = worldCreator.getWorldType();
        if (worldType == null) {
            return false;
        }
        if (IMMENSA_PRESET_KEY.equals(worldType.preset())) {
            return true;
        }
        String presetName = worldType.describePreset().getString();
        return "immensa".equalsIgnoreCase(presetName) || "terrain diffusion".equalsIgnoreCase(presetName);
    }
}
