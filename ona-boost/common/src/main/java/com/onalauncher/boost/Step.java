/*
 * ONA Boost - adaptive performance for Minecraft on phones (ONA Launcher 2)
 * GPL-3.0-or-later
 */
package com.onalauncher.boost;

/**
 * One notch of lighter settings. {@link #apply()} makes it and returns false when it does not
 * apply (already that light, or not possible in this game); {@link #undo()} puts back exactly what
 * apply() changed.
 */
public interface Step {
    String name();

    boolean apply();

    void undo();

    /** Changing it rebuilds the world's chunks (render distance): the game gets longer to settle */
    default boolean heavy() {
        return false;
    }
}
