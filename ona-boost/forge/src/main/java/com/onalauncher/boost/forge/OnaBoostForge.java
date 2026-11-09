/*
 * ONA Boost - adaptive performance for Minecraft on phones (ONA Launcher 2)
 * GPL-3.0-or-later
 */
package com.onalauncher.boost.forge;

import com.onalauncher.boost.OnaBoost;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;

@Mod(OnaBoost.ID)
public final class OnaBoostForge {
    public OnaBoostForge() {
        // client only: a server never loads the client classes
        if (FMLEnvironment.dist == Dist.CLIENT) ClientHooks.init();
    }
}
