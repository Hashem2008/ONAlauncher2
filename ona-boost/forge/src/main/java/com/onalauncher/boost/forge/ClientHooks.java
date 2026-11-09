/*
 * ONA Boost - adaptive performance for Minecraft on phones (ONA Launcher 2)
 * GPL-3.0-or-later
 */
package com.onalauncher.boost.forge;

import com.onalauncher.boost.OnaBoost;
import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.loading.FMLPaths;

/** Forge's own client tick event: no mixin, nothing for other mods to collide with */
final class ClientHooks {
    private ClientHooks() {
    }

    static void init() {
        OnaBoost.init(FMLPaths.CONFIGDIR.get(), () -> new Options120(Minecraft.getInstance()));
        MinecraftForge.EVENT_BUS.addListener(ClientHooks::onClientTick);
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        OnaBoost.tick(mc.level != null, mc.isPaused(), mc.getFps());
    }
}
