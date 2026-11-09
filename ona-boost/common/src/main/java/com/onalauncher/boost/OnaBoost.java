/*
 * ONA Boost - adaptive performance for Minecraft on phones (ONA Launcher 2)
 * GPL-3.0-or-later
 */
package com.onalauncher.boost;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Entry shared by the Fabric and Forge builds: they call {@link #init} once and {@link #tick} at
 * the end of every client tick. Nothing here may ever break the game: any error switches ONA Boost
 * off for the session and the game goes on with the player's settings.
 */
public final class OnaBoost {
    public static final String ID = "onaboost";
    private static final Logger LOG = LoggerFactory.getLogger("ONA Boost");

    private static Path configDir;
    private static Supplier<GameSettings> factory;
    private static FpsGuard guard;
    private static boolean started;
    private static boolean off;

    private OnaBoost() {
    }

    public static void init(Path dir, Supplier<GameSettings> settingsFactory) {
        configDir = dir;
        factory = settingsFactory;
        if (!Phone.isPhone()) {
            off = true;
            LOG.info("ONA Boost is made for phones: not active on this computer");
        }
    }

    public static void tick(boolean inWorld, boolean paused, int fps) {
        if (off) return;
        try {
            if (!started) {
                started = true;
                start();
                if (off) return;
            }
            guard.tick(inWorld, paused, fps, System.currentTimeMillis());
        } catch (Throwable t) {
            off = true;
            LOG.warn("ONA Boost switched itself off after an error, the game goes on normally", t);
            try {
                if (guard != null) guard.restoreAll("error");
            } catch (Throwable ignored) {
                // nothing more to do
            }
        }
    }

    /** First tick: the options are loaded by now */
    private static void start() {
        BoostConfig config = BoostConfig.load(configDir.resolve("onaboost.json"));
        if (!config.enabled) {
            off = true;
            LOG.info("ONA Boost is turned off in config/onaboost.json");
            return;
        }
        GameSettings settings = factory.get();
        guard = new FpsGuard(settings, config, configDir.resolve("onaboost-baseline.json"), LOG::info);
        guard.restoreLeftovers();
        if (config.phoneDefaults && !config.phoneDefaultsApplied) {
            if (settings.applyPhoneDefaults()) {
                settings.save();
                LOG.info("phone defaults set (biome blend, mipmaps)");
            }
            config.phoneDefaultsApplied = true;
            config.save(configDir.resolve("onaboost.json"));
        }
        LOG.info("ONA Boost active: keeping {} FPS (lighter under {}, back over {}), render distance not under {} chunks{}",
            config.targetFps, Math.round(config.targetFps * 0.85), Math.round(config.targetFps * 1.3),
            config.renderDistanceFloor(), config.deviceClass == null ? "" : " (" + config.deviceClass + " phone)");
        int limit = settings.fpsCap();
        if (limit > 0 && limit * 0.96 < config.targetFps * 1.3) {
            LOG.info("the game's FPS limit is {}: settings come back when the FPS stays at the limit", limit);
        }
    }
}
