/*
 * ONA Launcher 2 (based on Zalith Launcher 2)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.zalithlauncher.game.input

import com.movtery.zalithlauncher.utils.logging.Logger
import org.lwjgl.glfw.CallbackBridge
import java.util.concurrent.atomic.AtomicInteger

/**
 * ONA: what the on-screen controls sent to the game, written into the launcher's log of the game
 * process (part of "Send all logs"). Nothing in the game's own log says whether a tap on jump or
 * the hotbar reached it; this does: the key, down / up, the modifier keys held, and whether the
 * game had the cursor (in game) or a menu was open. A few hundred events per game start only.
 */
object InputTrace {
    private const val TAG = "InputTrace"
    private const val MAX_EVENTS = 400
    private val events = AtomicInteger()

    private fun allowed(): Boolean = events.incrementAndGet() <= MAX_EVENTS

    /** @param source "button", "hotbar"... */
    fun key(source: String, keycode: Int, down: Boolean) {
        if (!allowed()) return
        runCatching {
            Logger.info(TAG, "$source key $keycode ${if (down) "down" else "up"}, mods ${CallbackBridge.getCurrentMods()}, ${if (CallbackBridge.isGrabbing()) "in game" else "menu / free cursor"}")
        }
    }

    fun mouse(source: String, button: Int, down: Boolean) {
        if (!allowed()) return
        runCatching {
            Logger.info(TAG, "$source mouse $button ${if (down) "down" else "up"}, ${if (CallbackBridge.isGrabbing()) "in game" else "menu / free cursor"}")
        }
    }

    /** The game took or released the cursor (in game / a menu opened) */
    fun cursor(grabbed: Boolean) {
        if (!allowed()) return
        runCatching { Logger.info(TAG, if (grabbed) "cursor grabbed: in game" else "cursor free: a menu or screen is open") }
    }
}
