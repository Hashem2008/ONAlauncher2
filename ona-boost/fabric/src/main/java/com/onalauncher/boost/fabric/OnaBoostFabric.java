/*
 * ONA Boost - adaptive performance for Minecraft on phones (ONA Launcher 2)
 * GPL-3.0-or-later
 */
package com.onalauncher.boost.fabric;

import com.onalauncher.boost.OnaBoost;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

public final class OnaBoostFabric implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        OnaBoost.init(FabricLoader.getInstance().getConfigDir(), () -> new Options26(Minecraft.getInstance()));
    }
}
