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

package com.movtery.zalithlauncher.game.horizon

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.lukagejak.horizon.HorizonActivity
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.components.jre.Jre
import com.movtery.zalithlauncher.game.multirt.RuntimesManager
import com.movtery.zalithlauncher.game.path.getGameHome
import com.movtery.zalithlauncher.utils.logging.Logger
import java.io.File

/**
 * ONA: Horizon, Minecraft Java 26.3 rewritten in Rust (MocLG/horizon-android, MIT), built into the
 * APK by .github/workflows/horizon.yml. It runs in its own process with its own launcher page
 * (sign in, install 26.3, prepare once, play). ONA hands it the Java 25 runtime it already has,
 * for the one-time preparation, and its .minecraft, where 26.3 may already be installed.
 */
object HorizonEngine {
    private const val TAG = "HorizonEngine"

    enum class State(val textRes: Int) {
        READY(R.string.ona_engine_rust_ready),
        NOT_IN_BUILD(R.string.ona_engine_rust_not_in_build),
        NEEDS_ARM64(R.string.ona_engine_rust_needs_arm64),
        NEEDS_VULKAN(R.string.ona_engine_rust_needs_vulkan),
    }

    fun state(context: Context): State = when {
        !Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a") -> State.NEEDS_ARM64
        !File(context.applicationInfo.nativeLibraryDir, "libhorizon.so").isFile -> State.NOT_IN_BUILD
        !hasVulkan11(context) -> State.NEEDS_VULKAN
        else -> State.READY
    }

    private fun hasVulkan11(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, 0x401000)

    /** Opens Horizon. Returns the reason when it cannot start, else null. */
    fun launch(context: Context): State? {
        val state = state(context)
        if (state != State.READY) return state
        val intent = Intent(context, HorizonActivity::class.java)
        runCatching { RuntimesManager.getRuntimeHome(Jre.JRE_25.jreName) }
            .onFailure { Logger.warning(TAG, "Java 25 runtime is not installed", it) }
            .getOrNull()
            ?.takeIf { File(it, "lib").isDirectory }
            ?.let { intent.putExtra("jre_dir", it.absolutePath) }
        runCatching { getGameHome() }.getOrNull()
            ?.takeIf { File(it).isDirectory }
            ?.let { intent.putExtra("minecraft_dir", it) }
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return null
    }
}
