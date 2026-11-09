/*
 * ONA Boost - adaptive performance for Minecraft on phones (ONA Launcher 2)
 * GPL-3.0-or-later
 */
package com.onalauncher.boost;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** config/onaboost.json (ONA Launcher writes it too) */
public final class BoostConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public boolean enabled = true;
    /** The FPS kept: below 85% of it settings get lighter, above 130% they come back. 0 = off */
    public int targetFps = 60;
    /** Render distance is never taken below this (chunks). 6 = by the device, see {@link #renderDistanceFloor()} */
    public int minRenderDistance = 6;
    /** Simulation distance is never taken below this (chunks, single player) */
    public int minSimulationDistance = 5;
    /** Once: biome blend and mipmaps to values a phone carries */
    public boolean phoneDefaults = true;
    public boolean phoneDefaultsApplied = false;
    /** Every change written to the game log */
    public boolean log = true;
    /** LOW / MID / HIGH / FLAGSHIP: written by ONA Launcher (null: not known) */
    public String deviceClass;
    /** The screen's refresh rate, written by ONA Launcher (0: not known): the FPS limit with VSync on */
    public int displayHz;

    /** A weak phone may go lower than the 6 chunks of a strong one; a value the player wrote wins */
    public int renderDistanceFloor() {
        if (minRenderDistance != 6) return Math.max(2, minRenderDistance);
        if ("LOW".equals(deviceClass)) return 4;
        if ("MID".equals(deviceClass)) return 5;
        return 6;
    }

    public static BoostConfig load(Path file) {
        try {
            if (Files.isRegularFile(file)) {
                BoostConfig config = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), BoostConfig.class);
                if (config != null) return config;
            }
        } catch (Exception ignored) {
            // a broken file: start from the defaults, it is written again below
        }
        BoostConfig config = new BoostConfig();
        config.save(file);
        return config;
    }

    public void save(Path file) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // read-only folder: the game runs on without the file
        }
    }
}
