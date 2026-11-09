/*
 * ONA Boost - adaptive performance for Minecraft on phones (ONA Launcher 2)
 * GPL-3.0-or-later
 */
package com.onalauncher.boost.fabric;

import com.onalauncher.boost.BoostConfig;
import com.onalauncher.boost.GameSettings;
import com.onalauncher.boost.Step;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.server.level.ParticleStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/** The settings of Minecraft 26.x */
final class Options26 implements GameSettings {
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
    }

    private static void restoreInt(OptionInstance<Integer> option, String value) {
        if (value != null) option.set(Integer.valueOf(value));
    }

    @Override
    public List<Step> plan(BoostConfig config) {
        Options o = o();
        List<Step> plan = new ArrayList<>();
        plan.add(step("particles: decreased", o.particles(), v -> v == ParticleStatus.ALL, v -> ParticleStatus.DECREASED, false));
        plan.add(step("shadows of mobs: off", o.entityShadows(), v -> v, v -> false, false));
        plan.add(step("clouds: fast", o.cloudStatus(), v -> v == CloudStatus.FANCY, v -> CloudStatus.FAST, false));
        plan.add(step("rain and snow: closer", o.weatherRadius(), v -> v > 3, v -> Math.max(3, v - 3), false));
        plan.add(step("entities: drawn up to 75% as far", o.entityDistanceScaling(), v -> v > 0.76, v -> 0.75, false));
        for (int i = 0; i < 10; i++) {
            plan.add(step("render distance: 2 chunks less", o.renderDistance(), v -> v - 2 >= config.minRenderDistance, v -> v - 2, true));
        }
        if (mc.hasSingleplayerServer()) {
            for (int i = 0; i < 6; i++) {
                plan.add(step("simulation distance: 2 chunks less", o.simulationDistance(), v -> v - 2 >= config.minSimulationDistance, v -> v - 2, false));
            }
        }
        plan.add(step("particles: minimal", o.particles(), v -> v != ParticleStatus.MINIMAL, v -> ParticleStatus.MINIMAL, false));
        plan.add(step("clouds: off", o.cloudStatus(), v -> v != CloudStatus.OFF, v -> CloudStatus.OFF, false));
        plan.add(step("entities: drawn up to 50% as far", o.entityDistanceScaling(), v -> v > 0.51, v -> 0.5, false));
        return plan;
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

    /** A notch on one option: taken when the current value allows it, undone to the exact old value */
    static <T> Step step(String name, OptionInstance<T> option, Predicate<T> applies, Function<T, T> lighter, boolean heavy) {
        return new Step() {
            private T before;

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
                if (option.get().equals(now)) return false;
                before = now;
                return true;
            }

            @Override
            public void undo() {
                if (before != null) option.set(before);
                before = null;
            }

            @Override
            public boolean heavy() {
                return heavy;
            }
        };
    }
}
