/*
 * ONA Boost - adaptive performance for Minecraft on phones (ONA Launcher 2)
 * GPL-3.0-or-later
 */
package com.onalauncher.boost.fabric;

import com.onalauncher.boost.BoostConfig;
import com.onalauncher.boost.GameSettings;
import com.onalauncher.boost.Shaders;
import com.onalauncher.boost.Step;
import com.mojang.blaze3d.platform.FramerateLimitTracker;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.level.ParticleStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/** The settings of Minecraft 26.x */
final class Options26 implements GameSettings {
    /** The game's "max FPS" slider at its end: no limit */
    private static final int UNLIMITED_FPS = 260;

    private final Minecraft mc;

    Options26(Minecraft mc) {
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
        m.put("weatherRadius", String.valueOf(o().weatherRadius().get()));
        m.put("improvedTransparency", String.valueOf(o().improvedTransparency().get()));
        m.put("cutoutLeaves", String.valueOf(o().cutoutLeaves().get()));
        return m;
    }

    @Override
    public void restore(Map<String, String> v) {
        restoreInt(o().renderDistance(), v.get("renderDistance"));
        restoreInt(o().simulationDistance(), v.get("simulationDistance"));
        restoreInt(o().weatherRadius(), v.get("weatherRadius"));
        if (v.get("entityDistanceScaling") != null) o().entityDistanceScaling().set(Double.valueOf(v.get("entityDistanceScaling")));
        if (v.get("cloudStatus") != null) o().cloudStatus().set(CloudStatus.valueOf(v.get("cloudStatus")));
        if (v.get("particles") != null) o().particles().set(ParticleStatus.valueOf(v.get("particles")));
        if (v.get("entityShadows") != null) o().entityShadows().set(Boolean.valueOf(v.get("entityShadows")));
        if (v.get("improvedTransparency") != null) o().improvedTransparency().set(Boolean.valueOf(v.get("improvedTransparency")));
        if (v.get("cutoutLeaves") != null) o().cutoutLeaves().set(Boolean.valueOf(v.get("cutoutLeaves")));
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
        plan.add(step("particles: decreased", o.particles(), v -> v == ParticleStatus.ALL, v -> ParticleStatus.DECREASED, false));
        plan.add(step("shadows of mobs: off", o.entityShadows(), v -> v, v -> false, false));
        plan.add(step("clouds: fast", o.cloudStatus(), v -> v == CloudStatus.FANCY, v -> CloudStatus.FAST, false));
        plan.add(step("rain and snow: closer", o.weatherRadius(), v -> v > 3, v -> Math.max(3, v - 3), false));
        // a second pass over everything see-through: costs a phone GPU a lot, hardly seen
        plan.add(step("improved transparency: off", o.improvedTransparency(), v -> v, v -> false, true));
        plan.add(step("entities: drawn up to 75% as far", o.entityDistanceScaling(), v -> v > 0.76, v -> 0.75, false));
        for (int i = 0; i < 12; i++) {
            plan.add(step("render distance: 2 chunks less", o.renderDistance(), v -> v > comfortable, v -> Math.max(comfortable, v - 2), true));
        }
        if (mc.hasSingleplayerServer()) {
            for (int i = 0; i < 6; i++) {
                plan.add(step("simulation distance: 2 chunks less", o.simulationDistance(), v -> v > minSimulation, v -> Math.max(minSimulation, v - 2), false));
            }
        }
        // solid leaves: no faces between them and nothing drawn behind them
        plan.add(Shaders.withoutShaders(step("leaves: fast", o.cutoutLeaves(), v -> v, v -> false, true)));
        for (int i = 0; i < 4; i++) {
            plan.add(step("render distance: 2 chunks less", o.renderDistance(), v -> v > floor, v -> Math.max(floor, v - 2), true));
        }
        plan.add(step("particles: minimal", o.particles(), v -> v != ParticleStatus.MINIMAL, v -> ParticleStatus.MINIMAL, false));
        plan.add(step("clouds: off", o.cloudStatus(), v -> v != CloudStatus.OFF, v -> CloudStatus.OFF, false));
        plan.add(step("entities: drawn up to 50% as far", o.entityDistanceScaling(), v -> v > 0.51, v -> 0.5, false));
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
    public boolean throttled() {
        // no input for a minute: the game itself drops to 30 FPS (10 after ten minutes)
        return mc.getFramerateLimitTracker().getThrottleReason() != FramerateLimitTracker.FramerateThrottleReason.NONE;
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
     */
    static <T> Step step(String name, OptionInstance<T> option, Predicate<T> applies, Function<T, T> lighter, boolean heavy) {
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
                return true;
            }

            @Override
            public void undo() {
                if (before != null && option.get().equals(after)) option.set(before);
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
