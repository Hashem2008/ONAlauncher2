/*
 * ONA Boost - adaptive performance for Minecraft on phones (ONA Launcher 2)
 * GPL-3.0-or-later
 */
package com.onalauncher.boost.fabric.mixin;

import com.onalauncher.boost.fabric.OnaCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.Map;

/**
 * Shader packs on phones: a profile or option chosen in the game (Complementary's "Complementary"
 * profile and higher) can switch on parts that need compute shaders and storage buffers, and the
 * pack then does not load at all ("Shader storage buffers/immutable buffer storage is not
 * supported on this graphics card"). Every time Iris loads a pack, those options are handed to it
 * switched off when the OpenGL layer has no compute, whatever profile was picked; everything else
 * of the profile stays. Only applies when Iris is installed.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.Iris", remap = false)
public abstract class IrisPackOptionsMixin {
    @ModifyArg(
        method = "loadExternalShaderpack",
        at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/shaderpack/ShaderPack;<init>(Ljava/nio/file/Path;Ljava/util/Map;Lcom/google/common/collect/ImmutableList;Z)V"),
        index = 1,
        remap = false,
        require = 0
    )
    private static Map<String, String> onaboost$phoneOptions(Map<String, String> changed) {
        return OnaCompat.packOptions(changed);
    }
}
