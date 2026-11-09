/*
 * ONA Boost - adaptive performance for Minecraft on phones (ONA Launcher 2)
 * GPL-3.0-or-later
 */
package com.onalauncher.boost;

import java.util.List;
import java.util.Map;

/** The game's settings, as one Minecraft version exposes them */
public interface GameSettings {
    /** Current values of everything ONA Boost may touch, as text */
    Map<String, String> snapshot();

    /** Puts values from {@link #snapshot()} back */
    void restore(Map<String, String> values);

    /** Lighter notches, the least visible first */
    List<Step> plan(BoostConfig config);

    /** Phone-friendly values for the settings that cost a lot on a phone; true when one changed */
    boolean applyPhoneDefaults();

    /** Writes options.txt */
    void save();
}
