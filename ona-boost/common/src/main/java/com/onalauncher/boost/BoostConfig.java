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
    /** Render distance is never taken below this (chunks) */
    public int minRenderDistance = 6;
    /** Simulation distance is never taken below this (chunks, single player) */
    public int minSimulationDistance = 5;
    /** Once: biome blend and mipmaps to values a phone carries */
    public boolean phoneDefaults = true;
    public boolean phoneDefaultsApplied = false;
    /** Every change written to the game log */
    public boolean log = true;

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
