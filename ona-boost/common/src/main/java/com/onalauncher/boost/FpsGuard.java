/*
 * ONA Boost - adaptive performance for Minecraft on phones (ONA Launcher 2)
 * GPL-3.0-or-later
 */
package com.onalauncher.boost;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Keeps the game at the target FPS: when it stays below 85% of it for 3 seconds the next lighter
 * notch is taken (particles, shadows of mobs, clouds, rain radius, entity distance, render and
 * simulation distance...), when it stays above 130% the last notch comes back. The player's own
 * settings are kept in a file while anything is lighter, so they come back even after a crash.
 */
public final class FpsGuard {
    private static final Gson GSON = new Gson();
    private static final Type MAP = new TypeToken<Map<String, String>>() { }.getType();
    /** The first seconds in a world load chunks: slow for everyone, no decision then */
    private static final long WARM_UP_MS = 15000;

    private final GameSettings settings;
    private final BoostConfig config;
    private final Path baselineFile;
    private final Consumer<String> log;

    private List<Step> plan;
    private int planIndex;
    private final Deque<Integer> applied = new ArrayDeque<>();
    private long worldSince = -1;
    private long nextSampleAt;
    private long cooldownUntil;
    private int lowSeconds;
    private int highSeconds;
    /** Seconds the FPS must stay high before a notch comes back; doubles when it came back too soon */
    private int holdSeconds = 10;
    private long lastRaiseAt = -1;

    public FpsGuard(GameSettings settings, BoostConfig config, Path baselineFile, Consumer<String> log) {
        this.settings = settings;
        this.config = config;
        this.baselineFile = baselineFile;
        this.log = log;
    }

    /** The last session ended while settings were lighter (crash, phone killed the app): back */
    public void restoreLeftovers() {
        Map<String, String> baseline = readBaseline();
        if (baseline == null) return;
        settings.restore(baseline);
        settings.save();
        deleteBaseline();
        log.accept("your settings from before the last session's changes are back");
    }

    public void tick(boolean inWorld, boolean paused, int fps, long now) {
        if (!inWorld) {
            worldSince = -1;
            if (!applied.isEmpty()) restoreAll("left the world");
            return;
        }
        if (worldSince < 0) {
            worldSince = now;
            nextSampleAt = now + 1000;
            lowSeconds = 0;
            highSeconds = 0;
        }
        if (paused) {
            lowSeconds = 0;
            highSeconds = 0;
            return;
        }
        if (now < nextSampleAt) return;
        nextSampleAt = now + 1000;
        if (now - worldSince < WARM_UP_MS || now < cooldownUntil) return;
        int target = config.targetFps;
        if (target <= 0) return;

        if (fps < target * 0.85) {
            lowSeconds++;
            highSeconds = 0;
        } else if (!applied.isEmpty() && fps > target * 1.30) {
            highSeconds++;
            lowSeconds = 0;
        } else {
            lowSeconds = 0;
            highSeconds = 0;
        }
        if (lowSeconds >= 3) {
            lowSeconds = 0;
            lighter(fps, now);
        } else if (highSeconds >= holdSeconds) {
            highSeconds = 0;
            heavier(fps, now);
        } else if (holdSeconds > 10 && lastRaiseAt > 0 && now - lastRaiseAt > 120000) {
            // calm for two minutes: the next notch may come back sooner again
            holdSeconds = Math.max(10, holdSeconds - 5);
            lastRaiseAt = now;
        }
    }

    private void lighter(int fps, long now) {
        if (applied.isEmpty()) {
            writeBaseline(settings.snapshot());
            plan = settings.plan(config);
            planIndex = 0;
        }
        if (plan == null) return;
        // the notch that came back last was too much: wait longer before the next try
        if (lastRaiseAt > 0 && now - lastRaiseAt < 20000) holdSeconds = Math.min(120, holdSeconds * 2);
        while (planIndex < plan.size()) {
            int index = planIndex++;
            Step step = plan.get(index);
            if (step.apply()) {
                applied.push(index);
                cooldownUntil = now + (step.heavy() ? 12000 : 4000);
                note("FPS " + fps + ", under " + Math.round(config.targetFps * 0.85) + ": " + step.name());
                return;
            }
        }
        if (applied.isEmpty()) {
            // nothing could be made lighter: nothing to keep
            deleteBaseline();
            plan = null;
        }
    }

    private void heavier(int fps, long now) {
        Integer index = applied.poll();
        if (index == null || plan == null) return;
        Step step = plan.get(index);
        step.undo();
        planIndex = index;
        lastRaiseAt = now;
        cooldownUntil = now + (step.heavy() ? 15000 : 6000);
        note("FPS " + fps + ", over " + Math.round(config.targetFps * 1.3) + ": back from " + step.name());
        if (applied.isEmpty()) {
            plan = null;
            deleteBaseline();
        }
    }

    /** Everything back to the player's own settings */
    public void restoreAll(String why) {
        if (plan != null) {
            while (!applied.isEmpty()) {
                int index = applied.pop();
                try {
                    plan.get(index).undo();
                } catch (RuntimeException ignored) {
                    // the baseline below puts it right
                }
            }
        }
        applied.clear();
        Map<String, String> baseline = readBaseline();
        if (baseline != null) settings.restore(baseline);
        plan = null;
        planIndex = 0;
        holdSeconds = 10;
        lastRaiseAt = -1;
        deleteBaseline();
        note("your settings are back (" + why + ")");
    }

    private void note(String text) {
        if (config.log) log.accept(text);
    }

    private void writeBaseline(Map<String, String> values) {
        try {
            Files.createDirectories(baselineFile.getParent());
            Files.writeString(baselineFile, GSON.toJson(values, MAP), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // the in-game undo still works without the file
        }
    }

    private Map<String, String> readBaseline() {
        try {
            if (!Files.isRegularFile(baselineFile)) return null;
            return GSON.fromJson(Files.readString(baselineFile, StandardCharsets.UTF_8), MAP);
        } catch (Exception e) {
            return null;
        }
    }

    private void deleteBaseline() {
        try {
            Files.deleteIfExists(baselineFile);
        } catch (Exception ignored) {
            // read-only folder
        }
    }
}
