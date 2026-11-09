/*
 * ONA Boost - adaptive performance for Minecraft on phones (ONA Launcher 2)
 * GPL-3.0-or-later
 */
package com.onalauncher.boost;

import java.lang.reflect.Method;

/** Whether a shader pack is on (Iris / Oculus, asked through their public API when they are there) */
public final class Shaders {
    private static boolean looked;
    private static Object api;
    private static Method inUse;

    private Shaders() {
    }

    public static boolean inUse() {
        try {
            if (!looked) {
                looked = true;
                Class<?> iris = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                api = iris.getMethod("getInstance").invoke(null);
                inUse = iris.getMethod("isShaderPackInUse");
            }
            return inUse != null && Boolean.TRUE.equals(inUse.invoke(api));
        } catch (Throwable notThere) {
            // no Iris / Oculus in this game
            inUse = null;
            return false;
        }
    }

    /**
     * A notch that only fits the game's own look (solid leaves: many shader packs cut the leaf
     * texture out themselves, the tree would show holes): not taken while a shader pack is on, and
     * taken back when one comes on.
     */
    public static Step withoutShaders(Step step) {
        return new Step() {
            @Override
            public String name() {
                return step.name();
            }

            @Override
            public boolean apply() {
                return !inUse() && step.apply();
            }

            @Override
            public void undo() {
                step.undo();
            }

            @Override
            public boolean heavy() {
                return step.heavy();
            }

            @Override
            public boolean valid() {
                return !inUse();
            }
        };
    }
}
