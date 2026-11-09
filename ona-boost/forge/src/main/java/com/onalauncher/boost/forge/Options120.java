/*
 * ONA Boost - adaptive performance for Minecraft on phones (ONA Launcher 2)
 * GPL-3.0-or-later
 */
package com.onalauncher.boost.forge;

import com.onalauncher.boost.BoostConfig;
import com.onalauncher.boost.GameSettings;
import com.onalauncher.boost.Shaders;
import com.onalauncher.boost.Step;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.multiplayer.ClientLevel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/** The settings of Minecraft 1.20.1 */
final class Options120 implements GameSettings {
    /** The game's "max FPS" slider at its end: no limit */
    private static final int UNLIMITED_FPS = 260;

    private final Minecraft mc;

    Options120(Minecraft mc) {
        this.mc = mc;
    }

    private Options o() {
        return mc.options;
    }

    @Override
    public Map<String, String> snapshot() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("renderDistance", String.valueOf(o().renderDistance().get()));
        m.put("simulationDistance", String.valueOf(o().simulationDistance().get()));
        m.put("entityDistanceScaling", String.valueOf(o().entityDistanceScaling().get()));
        m.put("cloudStatus", o().cloudStatus().get().name());
        m.put("particles", o().particles().get().name());
        m.put("entityShadows", String.valueOf(o().entityShadows().get()));
        m.put("graphicsMode", o().graphicsMode().get().name());
        return m;
    }

    @Override
    public void restore(Map<String, String> v) {
        restoreInt(o().renderDistance(), v.get("renderDistance"));
        restoreInt(o().simulationDistance(), v.get("simulationDistance"));
        if (v.get("entityDistanceScaling") != null) o().entityDistanceScaling().set(Double.valueOf(v.get("entityDistanceScaling")));
        if (v.get("cloudStatus") != null) o().cloudStatus().set(CloudStatus.valueOf(v.get("cloudStatus")));
        if (v.get("particles") != null) o().particles().set(ParticleStatus.valueOf(v.get("particles")));
        if (v.get("entityShadows") != null) o().entityShadows().set(Boolean.valueOf(v.get("entityShadows")));
        if (v.get("graphicsMode") != null) {
            GraphicsStatus graphics = GraphicsStatus.valueOf(v.get("graphicsMode"));
            if (o().graphicsMode().get() != graphics) {
                o().graphicsMode().set(graphics);
                graphicsChanged();
            }
        }
    }

    /** The game's own settings screen does this after a change of "Graphics": chunks are built again */
    private void graphicsChanged() {
        mc.levelRenderer.allChanged();
    }

    private static void restoreInt(OptionInstance<Integer> option, String value) {
        if (value != null) option.set(Integer.valueOf(value));
    }

    @Override
    public List<Step> plan(BoostConfig config) {
        Options o = o();
        // render distance: down to 10 chunks early, under that only after the less visible notches
        int floor = config.renderDistanceFloor();
        int comfortable = Math.max(floor, 10);
        int minSimulation = Math.max(5, config.minSimulationDistance);
        List<Step> plan = new ArrayList<>();
        plan.add(step("particles: decreased", o.particles(), v -> v == ParticleStatus.ALL, v -> ParticleStatus.DECREASED, false, null));
        plan.add(step("shadows of mobs: off", o.entityShadows(), v -> v, v -> false, false, null));
        plan.add(step("clouds: fast", o.cloudStatus(), v -> v == CloudStatus.FANCY, v -> CloudStatus.FAST, false, null));
        // Fabulous draws everything see-through a second time: costs a phone GPU a lot, hardly seen
        plan.add(step("graphics: fancy", o.graphicsMode(), v -> v == GraphicsStatus.FABULOUS, v -> GraphicsStatus.FANCY, true, this::graphicsChanged));
        plan.add(step("entities: drawn up to 75% as far", o.entityDistanceScaling(), v -> v > 0.76, v -> 0.75, false, null));
        for (int i = 0; i < 12; i++) {
            plan.add(step("render distance: 2 chunks less", o.renderDistance(), v -> v > comfortable, v -> Math.max(comfortable, v - 2), true, null));
        }
        if (mc.hasSingleplayerServer()) {
            for (int i = 0; i < 6; i++) {
                plan.add(step("simulation distance: 2 chunks less", o.simulationDistance(), v -> v > minSimulation, v -> Math.max(minSimulation, v - 2), false, null));
            }
        }
        // solid leaves: no faces between them and nothing drawn behind them
        plan.add(Shaders.withoutShaders(step("graphics: fast", o.graphicsMode(), v -> v == GraphicsStatus.FANCY, v -> GraphicsStatus.FAST, true, this::graphicsChanged)));
        for (int i = 0; i < 4; i++) {
            plan.add(step("render distance: 2 chunks less", o.renderDistance(), v -> v > floor, v -> Math.max(floor, v - 2), true, null));
        }
        plan.add(step("particles: minimal", o.particles(), v -> v != ParticleStatus.MINIMAL, v -> ParticleStatus.MINIMAL, false, null));
        plan.add(step("clouds: off", o.cloudStatus(), v -> v != CloudStatus.OFF, v -> CloudStatus.OFF, false, null));
        plan.add(step("entities: drawn up to 50% as far", o.entityDistanceScaling(), v -> v > 0.51, v -> 0.5, false, null));
        return plan;
    }

    @Override
    public int fpsCap() {
        int limit = o().framerateLimit().get();
        return limit > 0 && limit < UNLIMITED_FPS ? limit : 0;
    }

    @Override
    public boolean vsync() {
        return o().enableVsync().get();
    }

    @Override
    public int worldId() {
        ClientLevel level = mc.level;
        return level == null ? 0 : System.identityHashCode(level);
    }

    @Override
    public boolean applyPhoneDefaults() {
        boolean changed = false;
        // biome blending is one of the most costly parts of building a chunk on a phone CPU
        if (o().biomeBlendRadius().get() > 2) {
            o().biomeBlendRadius().set(2);
            changed = true;
        }
        if (o().mipmapLevels().get() > 2) {
            o().mipmapLevels().set(2);
            changed = true;
        }
        return changed;
    }

    @Override
    public void save() {
        o().save();
    }

    /**
     * A notch on one option: taken when the current value allows it, undone to the exact old value
     * when the option is still the way the notch left it (a value the player chose since stays).
     * @param changed run after the option was set, for options the game does not apply by itself
     */
    static <T> Step step(String name, OptionInstance<T> option, Predicate<T> applies, Function<T, T> lighter, boolean heavy, Runnable changed) {
        return new Step() {
            private T before;
            private T after;

            @Override
            public String name() {
                return name;
            }

            @Override
            public boolean apply() {
                T now = option.get();
                if (!applies.test(now)) return false;
                T next = lighter.apply(now);
                if (next.equals(now)) return false;
                option.set(next);
                // the game refuses values outside the option's range: then this notch does not exist
                T got = option.get();
                if (got.equals(now)) return false;
                before = now;
                after = got;
                if (changed != null) changed.run();
                return true;
            }

            @Override
            public void undo() {
                if (before != null && option.get().equals(after)) {
                    option.set(before);
                    if (changed != null) changed.run();
                }
                before = null;
                after = null;
            }

            @Override
            public boolean heavy() {
                return heavy;
            }
        };
    }
}
