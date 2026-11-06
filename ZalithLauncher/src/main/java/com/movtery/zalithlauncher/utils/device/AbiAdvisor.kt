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

package com.movtery.zalithlauncher.utils.device

import android.os.Build
import com.movtery.zalithlauncher.BuildKeys

/**
 * ONA: is this APK the right one for the device? Every CPU type has its own APK (see
 * tools/ci/APK_GUIDE.md); the 32-bit one runs on 64-bit phones too, and ARM ones run on x86
 * emulators through translation, both much slower than the right APK.
 */
object AbiAdvisor {
    const val ARM64_APK = "ONA-arm64-v8a_64bit-phones.apk"
    const val ARM_APK = "ONA-armeabi-v7a_32bit-old-phones.apk"
    const val X86_64_APK = "ONA-x86_64_emulators-PC.apk"

    /** @return the APK file this device should use, or null when this one already fits */
    fun betterApk(): String? {
        val app = BuildKeys.BUILD_ARCH
        val primary = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        val arm64 = Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a")
        return when {
            // ARM build on a PC / emulator: runs through ARM translation
            (app == "arm64" || app == "arm") && primary == "x86_64" -> X86_64_APK
            // 32-bit build on a 64-bit phone: less memory, slower, no Rust engine
            app == "arm" && arm64 -> ARM64_APK
            else -> null
        }
    }

    /** One key per installed build, so a new build can advise again */
    fun shownKey(): String = "${BuildKeys.BUILD_ARCH}-${Build.SUPPORTED_ABIS.joinToString(",")}"
}
