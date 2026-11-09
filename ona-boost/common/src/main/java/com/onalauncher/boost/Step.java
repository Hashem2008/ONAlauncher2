/*
 * ONA Boost - adaptive performance for Minecraft on phones (ONA Launcher 2)
 * GPL-3.0-or-later
 */
package com.onalauncher.boost;

/**
 * One notch of lighter settings. {@link #apply()} makes it and returns false when it does not
 * apply (already that light, or not possible in this game); {@link #undo()} puts back exactly what
 * apply() changed, unless the player chose another value since: that one stays.
 */
public interface Step {
    String name();

    boolean apply();

    void undo();

    /** Changing it rebuilds the world's chunks (render distance): the game gets longer to settle */
    default boolean heavy() {
        return false;
    }

    /** False when a taken notch must not stay any longer: it is undone right away */
    default boolean valid() {
        return true;
    }
}
