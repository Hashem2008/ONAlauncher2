/*
 * ONA Boost - adaptive performance for Minecraft on phones (ONA Launcher 2)
 * GPL-3.0-or-later
 */
package com.onalauncher.boost.fabric.mixin;

import com.onalauncher.boost.OnaBoost;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The end of every client tick: one tiny hook, no Fabric API needed */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void onaboost$afterTick(CallbackInfo ci) {
        Minecraft mc = (Minecraft) (Object) this;
        OnaBoost.tick(mc.level != null, mc.isPaused(), mc.getFps());
    }
}
