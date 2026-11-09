/*
 * ONA Boost - adaptive performance for Minecraft on phones (ONA Launcher 2)
 * GPL-3.0-or-later
 */
package com.onalauncher.boost.fabric.mixin;

import com.onalauncher.boost.fabric.OnaCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Shader packs on phones (Complementary, BSL, Solas...): Iris decides "compute shaders" and
 * "images" from OpenGL function addresses, which an OpenGL ES layer hands out even when it cannot
 * run those paths. When the layer has no compute, Iris is told so and the packs take their normal
 * path instead of failing ("Shader compilation failed for shadowcomp", "all images have to define
 * layout format"). Only applies when Iris is installed.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.gl.IrisRenderSystem", remap = false)
public abstract class IrisRenderSystemMixin {
    @Inject(method = "supportsCompute", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void onaboost$noCompute(CallbackInfoReturnable<Boolean> cir) {
        if (OnaCompat.noCompute()) cir.setReturnValue(false);
    }

    @Inject(method = "supportsImageLoadStore", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void onaboost$noImages(CallbackInfoReturnable<Boolean> cir) {
        if (OnaCompat.noCompute()) cir.setReturnValue(false);
    }
}
