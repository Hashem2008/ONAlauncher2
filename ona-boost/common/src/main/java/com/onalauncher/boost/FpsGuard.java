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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Keeps the game at the target FPS: when it stays below 85% of it for 3 seconds the next lighter
 * notch is taken (particles, shadows of mobs, clouds, rain radius, entity distance, render and
 * simulation distance...), when it stays above 130% the last notch comes back. With an FPS limit
 * in the game that 130% can never be reached: then staying at the limit is the sign, watched for
 * three times as long. The player's own value of everything that is lighter is kept in a file, so
 * it comes back even after a crash; a value the player chose in between is never overwritten.
 */
public final class FpsGuard {
    private static final Gson GSON = new Gson();
    private static final Type MAP = new TypeToken<Map<String, String>>() { }.getType();
    /** The first seconds in a world load chunks: slow for everyone, no decision then */
    private static final long WARM_UP_MS = 15000;
    /** Another dimension, or the app back from the background: chunks load again */
    private static final long SETTLE_MS = 8000;
    /** No tick for this long: the app was in the background or the game froze */
    private static final long STALL_MS = 2000;
    /** In the file: what ONA Boost set a setting to, next to the player's own value of it */
    private static final String SET = "_set.";

    private final GameSettings settings;
    private final BoostConfig config;
    private final Path baselineFile;
    private final Consumer<String> log;

    private List<Step> plan;
    private int planIndex;
    private final Deque<Integer> applied = new ArrayDeque<>();
    /** The player's own value of every setting that is lighter now */
    private final Map<String, String> own = new LinkedHashMap<>();
    /** What ONA Boost set those settings to */
    private final Map<String, String> set = new LinkedHashMap<>();
    private long worldSince = -1;
    private int world;
    private long lastTickAt = -1;
    private boolean wasThrottled;
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
        Map<String, String> file = readBaseline();
        if (file == null) {
            deleteBaseline();
            return;
        }
        Map<String, String> now = settings.snapshot();
        Map<String, String> back = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : file.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith(SET) || entry.getValue() == null) continue;
            // only what is still the way ONA Boost left it (a file of version 1.1 does not say: all)
            String left = file.get(SET + key);
            if (left == null || left.equals(now.get(key))) back.put(key, entry.getValue());
        }
        if (!back.isEmpty()) {
            settings.restore(back);
            settings.save();
            log.accept("your settings from before the last session's changes are back");
        }
        deleteBaseline();
    }

    public void tick(boolean inWorld, boolean paused, int fps, long now) {
        boolean stalled = lastTickAt >= 0 && now - lastTickAt > STALL_MS;
        lastTickAt = now;
        if (!inWorld) {
            worldSince = -1;
            if (!applied.isEmpty()) restoreAll("left the world");
            return;
        }
        if (worldSince < 0) {
            worldSince = now;
            world = settings.worldId();
            nextSampleAt = now + 1000;
            lowSeconds = 0;
            highSeconds = 0;
        }
        if (stalled) settle(now, SETTLE_MS);
        if (paused) {
            lowSeconds = 0;
            highSeconds = 0;
            return;
        }
        if (now < nextSampleAt) return;
        nextSampleAt = now + 1000;
        if (settings.throttled()) {
            // the game limits itself (nobody touched it for a while): a low FPS means nothing
            wasThrottled = true;
            lowSeconds = 0;
            highSeconds = 0;
            return;
        }
        if (wasThrottled) {
            wasThrottled = false;
            settle(now, 3000);
        }
        if (!applied.isEmpty()) dropInvalid(now);
        int worldNow = settings.worldId();
        if (worldNow != world) {
            world = worldNow;
            settle(now, SETTLE_MS);
        }
        if (now - worldSince < WARM_UP_MS || now < cooldownUntil) return;
        int target = config.targetFps;
        if (target <= 0) return;

        int cap = cap();
        // a limit under the target: more than the limit is not possible, so that is what is kept
        if (cap > 0 && cap < target) target = cap;
        double low = target * 0.85;
        double high = target * 1.30;
        int hold = holdSeconds;
        if (cap > 0 && cap * 0.96 < high) {
            high = cap * 0.96;
            hold = holdSeconds * 3;
        }

        if (fps < low) {
            lowSeconds++;
            highSeconds = 0;
        } else if (!applied.isEmpty() && fps > high) {
            highSeconds++;
            lowSeconds = 0;
        } else {
            lowSeconds = 0;
            highSeconds = 0;
        }
        if (lowSeconds >= 3) {
            lowSeconds = 0;
            lighter(fps, now, low);
        } else if (highSeconds >= hold) {
            highSeconds = 0;
            heavier(fps, now, high);
        } else if (holdSeconds > 10 && lastRaiseAt > 0 && now - lastRaiseAt > 120000) {
            // calm for two minutes: the next notch may come back sooner again
            holdSeconds = Math.max(10, holdSeconds - 5);
            lastRaiseAt = now;
        }
    }

    /** The FPS the game cannot go above: its own limit, or the screen's rate with VSync on. 0 = none */
    private int cap() {
        int cap = settings.fpsCap();
        if (config.displayHz > 0 && settings.vsync() && (cap <= 0 || config.displayHz < cap)) cap = config.displayHz;
        return Math.max(0, cap);
    }

    /** Chunks are loading again: no decision for a moment */
    private void settle(long now, long ms) {
        lowSeconds = 0;
        highSeconds = 0;
        cooldownUntil = Math.max(cooldownUntil, now + ms);
    }

    private void lighter(int fps, long now, double low) {
        if (applied.isEmpty()) {
            plan = settings.plan(config);
            planIndex = 0;
            own.clear();
            set.clear();
        }
        if (plan == null || planIndex >= plan.size()) return;
        // the notch that came back last was too much: wait longer before the next try
        if (lastRaiseAt > 0 && now - lastRaiseAt < 30000) holdSeconds = Math.min(120, holdSeconds * 2);
        Map<String, String> before = settings.snapshot();
        while (planIndex < plan.size()) {
            int index = planIndex++;
            Step step = plan.get(index);
            boolean taken;
            try {
                taken = step.apply();
            } catch (Throwable t) {
                // half done: what it changed is noted, so it goes back with the rest
                try {
                    track(before, settings.snapshot());
                    writeBaseline();
                } catch (Throwable ignored) {
                    // nothing more to note
                }
                throw t;
            }
            if (taken) {
                applied.push(index);
                track(before, settings.snapshot());
                writeBaseline();
                cooldownUntil = now + (step.heavy() ? 12000 : 4000);
                note("FPS " + fps + ", under " + Math.round(low) + ": " + step.name());
                return;
            }
        }
        if (applied.isEmpty()) plan = null;
    }

    private void heavier(int fps, long now, double high) {
        Integer index = applied.peek();
        if (index == null || plan == null) return;
        Step step = plan.get(index);
        Map<String, String> before = settings.snapshot();
        step.undo();
        track(before, settings.snapshot());
        applied.poll();
        planIndex = index;
        lastRaiseAt = now;
        cooldownUntil = now + (step.heavy() ? 15000 : 6000);
        note("FPS " + fps + ", over " + Math.round(high) + ": back from " + step.name());
        if (applied.isEmpty()) {
            plan = null;
            own.clear();
            set.clear();
            deleteBaseline();
        } else {
            writeBaseline();
        }
    }

    /** A notch that must not stay (solid leaves once a shader pack is on) goes back right away */
    private void dropInvalid(long now) {
        if (plan == null) return;
        boolean dropped = false;
        for (Iterator<Integer> it = applied.iterator(); it.hasNext(); ) {
            Step step = plan.get(it.next());
            if (step.valid()) continue;
            Map<String, String> before = settings.snapshot();
            step.undo();
            track(before, settings.snapshot());
            it.remove();
            dropped = true;
            cooldownUntil = Math.max(cooldownUntil, now + (step.heavy() ? 12000 : 4000));
            note("back from " + step.name() + ": it does not fit the shader pack");
        }
        if (!dropped) return;
        if (applied.isEmpty()) {
            plan = null;
            own.clear();
            set.clear();
            deleteBaseline();
        } else {
            writeBaseline();
        }
    }

    /** Everything back to the player's own settings */
    public void restoreAll(String why) {
        boolean clean = true;
        if (plan != null) {
            while (!applied.isEmpty()) clean &= undo(plan.get(applied.pop()));
        }
        applied.clear();
        // a notch that could not undo itself: its settings go back from the noted values
        clean &= restoreOwn();
        plan = null;
        planIndex = 0;
        holdSeconds = 10;
        lastRaiseAt = -1;
        if (clean) {
            deleteBaseline();
        } else {
            // not everything went back now: the file puts it right at the next start
            try {
                writeBaseline();
            } catch (Throwable ignored) {
                // the file from the last notch stays
            }
        }
        own.clear();
        set.clear();
        note("your settings are back (" + why + ")");
    }

    private boolean undo(Step step) {
        try {
            Map<String, String> before = settings.snapshot();
            step.undo();
            track(before, settings.snapshot());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Whatever is still the way ONA Boost set it goes back to the player's value */
    private boolean restoreOwn() {
        if (own.isEmpty()) return true;
        try {
            Map<String, String> now = settings.snapshot();
            Map<String, String> back = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : own.entrySet()) {
                String left = set.get(entry.getKey());
                if (left != null && left.equals(now.get(entry.getKey()))) back.put(entry.getKey(), entry.getValue());
            }
            if (!back.isEmpty()) {
                settings.restore(back);
                for (String key : back.keySet()) {
                    own.remove(key);
                    set.remove(key);
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Notes what a notch (or its undo) changed: the player's value and the value set */
    private void track(Map<String, String> before, Map<String, String> after) {
        for (Map.Entry<String, String> entry : after.entrySet()) {
            String key = entry.getKey();
            String now = entry.getValue();
            String was = before.get(key);
            if (now == null || was == null || now.equals(was)) continue;
            // the first change of this setting, or the player changed it in between: their value
            if (!was.equals(set.get(key))) own.put(key, was);
            if (now.equals(own.get(key))) {
                own.remove(key);
                set.remove(key);
            } else {
                set.put(key, now);
            }
        }
    }

    private void note(String text) {
        if (config.log) log.accept(text);
    }

    private void writeBaseline() {
        if (own.isEmpty()) {
            deleteBaseline();
            return;
        }
        Map<String, String> values = new LinkedHashMap<>(own);
        for (Map.Entry<String, String> entry : set.entrySet()) values.put(SET + entry.getKey(), entry.getValue());
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
