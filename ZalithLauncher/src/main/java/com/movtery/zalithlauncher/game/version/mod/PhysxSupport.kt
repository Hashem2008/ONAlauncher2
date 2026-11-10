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

package com.movtery.zalithlauncher.game.version.mod

import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.path.PathManager
import com.movtery.zalithlauncher.ui.screens.content.elements.modsDir
import com.movtery.zalithlauncher.utils.logging.Logger
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * ONA: makes PhysX based mods (Physics Mod and others using fabmax's physx-jni) run on a phone
 * without touching them.
 *
 * They ship PhysX only for desktop Linux / Windows / macOS (x86), so on Android the native
 * library fails to load. physx-jni 2.x reads two system properties,
 * `physxjni.nativeLibLocation` and `physxjni.loadFromResources`, and from 2.3.1 the same author
 * publishes the very same bindings for Android ARM64 (de.fabmax:physx-jni-android, MIT). The
 * launcher downloads that build for the exact version the mod carries and points the loader at
 * it, so the mod loads ARM64 PhysX instead of the x86 one.
 *
 * physx-jni 1.1.0 (Physics Mod for 1.18.2) has neither the properties nor an Android build:
 * ONA ships its own arm64 port of it (see [PORTED_VERSIONS]).
 *
 * physx-jni 2.2.2 (Physics Mod 3.0.x for 1.20.1) has the properties but no Android build: it runs on
 * the Android build of 2.3.1 through ONA's bridge library (tools/physx-compat, see [BRIDGED_VERSIONS]).
 * Other old versions are reported.
 */
object PhysxSupport {
    private const val TAG = "PhysxSupport"
    /** physx-jni loads PhysXJniBindings_64 in a 64-bit JVM, _32 in a 32-bit one (arm 32-bit APK) */
    private val LIB_NAME = if (android.os.Process.is64Bit()) "libPhysXJniBindings_64.so" else "libPhysXJniBindings_32.so"
    /** The CPU type this APK runs as: arm64-v8a, armeabi-v7a or x86_64 */
    private val APP_ABI: String = (if (android.os.Process.is64Bit()) android.os.Build.SUPPORTED_64_BIT_ABIS else android.os.Build.SUPPORTED_32_BIT_ABIS)
        .firstOrNull() ?: "arm64-v8a"
    private const val FIRST_ANDROID_VERSION = "2.3.1"
    private val MAVEN_MIRRORS = listOf(
        "https://repo1.maven.org/maven2",
        "https://maven.aliyun.com/repository/public",
        "https://repo.maven.apache.org/maven2"
    )

    private val VERSION_IN_PATH = Regex("physx-jni-(\\d+\\.\\d+\\.\\d+)")
    private val VERSION_STRING = Regex("(\\d+\\.\\d+\\.\\d+)")

    enum class Status { NONE, READY, PORTED, UNSUPPORTED_OLD, DOWNLOAD_FAILED }

    /**
     * physx-jni versions ONA ships its own arm64 port of: the same PhysX + JNI glue sources,
     * compiled for the phone CPU by tools/physx4-android in CI and bundled in the APK.
     */
    private val PORTED_VERSIONS = setOf("1.1.0")

    /**
     * physx-jni versions that run on the Android build of a newer one through ONA's bridge
     * (libPhysXJniCompat.so, arm64): it answers the few native methods the newer build no longer
     * has, everything else comes from the real library next to it. Checked method by method:
     * 2.2.2 -> 2.3.1 differ in 7 of 4554 (tools/physx-compat/physx_jni_compat.c).
     */
    private val BRIDGED_VERSIONS = mapOf("2.2.2" to "2.3.1")
    private const val BRIDGE_LIB = "libPhysXJniCompat.so"
    /** The name the bridge looks for next to itself (its DT_NEEDED) */
    private const val BRIDGED_REAL_LIB = "libPhysXJniReal.so"

    private fun bundledBridge(): File = File(PathManager.DIR_NATIVE_LIB, BRIDGE_LIB)

    private fun bridgeDir(physxVersion: String): File = File(PathManager.DIR_CACHE, "physx-android/$physxVersion-bridge")

    /** The bridge only exists for arm64 (the Android builds of physx-jni are arm64 only) */
    private fun canBridge(physxVersion: String): Boolean =
        physxVersion in BRIDGED_VERSIONS && APP_ABI == "arm64-v8a" && LIB_NAME == "libPhysXJniBindings_64.so" && bundledBridge().isFile

    /**
     * The folder physx-jni loads from: the bridge under the name physx-jni asks for, the real
     * library of the newer version under the name the bridge needs. Copied again when it changed.
     */
    private fun placeBridge(physxVersion: String): Boolean {
        val realVersion = BRIDGED_VERSIONS[physxVersion] ?: return false
        val real = nativeLib(realVersion)
        if (!real.isFile || !bundledBridge().isFile) return false
        return runCatching {
            val dir = bridgeDir(physxVersion).apply { mkdirs() }
            // size and time, not a checksum: the real library is 60 MB
            fun copyIfChanged(source: File, target: File) {
                if (target.isFile && target.length() == source.length() && target.lastModified() >= source.lastModified()) return
                val temp = File(target.path + ".part")
                source.copyTo(temp, overwrite = true)
                if (!temp.renameTo(target)) {
                    target.delete()
                    if (!temp.renameTo(target)) throw java.io.IOException("Cannot write $target")
                }
                target.setLastModified(maxOf(System.currentTimeMillis(), source.lastModified()))
            }
            copyIfChanged(bundledBridge(), File(dir, LIB_NAME))
            copyIfChanged(real, File(dir, BRIDGED_REAL_LIB))
            true
        }.onFailure { Logger.warning(TAG, "Could not place the PhysX $physxVersion bridge", it) }.getOrDefault(false)
    }

    private fun bundledPort(): File = File(PathManager.DIR_NATIVE_LIB, LIB_NAME)

    /**
     * physx-jni 1.x only copies its desktop library to java.io.tmpdir when no file is there yet,
     * so the port placed there first is what it loads. Replaces an x86 copy left by an earlier try.
     */
    private fun placePort(physxVersion: String): Boolean {
        val source = bundledPort()
        if (physxVersion !in PORTED_VERSIONS || !source.isFile) return false
        return runCatching {
            val target = File(File(PathManager.DIR_CACHE, "de.fabmax.physx-jni"), "$physxVersion/$LIB_NAME")
            if (!(target.isFile && target.length() == source.length() && sameBytes(source, target))) {
                target.parentFile?.mkdirs()
                source.copyTo(target, overwrite = true)
                Logger.info(TAG, "Placed the $APP_ABI PhysX port for physx-jni $physxVersion")
            }
            true
        }.onFailure { Logger.warning(TAG, "Could not place the PhysX port", it) }.getOrDefault(false)
    }

    private fun sameBytes(a: File, b: File): Boolean {
        val digest = { file: File ->
            java.security.MessageDigest.getInstance("SHA-1").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        }
        return digest(a) == digest(b)
    }

    class Result(val status: Status, val physxVersion: String?, val modFile: String?)

    /** Jars worth opening: PhysX mods say so in their name almost always */
    private fun candidates(modsDir: File): List<File> =
        modsDir.listFiles { file ->
            val name = file.name.lowercase()
            file.isFile && name.endsWith(".jar") && (name.contains("physic") || name.contains("physx"))
        }?.toList().orEmpty()

    /** The physx-jni version bundled in a mod jar, nested jars and shaded classes included */
    fun detectVersion(jar: File): String? = runCatching { readVersion(jar) }.getOrNull()

    private fun readVersion(jar: File): String? {
        ZipFile(jar).use { zip ->
            val entries = zip.entries().asSequence().toList()
            // Jar in jar (Forge jarjar / Fabric nested) named physx-jni-x.y.z.jar
            entries.firstNotNullOfOrNull { VERSION_IN_PATH.find(it.name)?.groupValues?.get(1) }?.let { return it }
            // Maven metadata of a shaded copy
            entries.firstOrNull { it.name.endsWith("de.fabmax/physx-jni/pom.properties") }?.let { entry ->
                zip.getInputStream(entry).bufferedReader().readLines()
                    .firstOrNull { it.startsWith("version=") }?.substringAfter("=")?.trim()?.let { return it }
            }
            // The loader class holds its version as a string constant
            // (also when the mod relocated the package)
            for (entry in entries.filter { it.name.endsWith("/NativeLibLinux.class") || it.name.endsWith("physxjni/Loader.class") }) {
                val text = zip.getInputStream(entry).readBytes().toString(Charsets.ISO_8859_1)
                VERSION_STRING.find(text)?.groupValues?.get(1)?.let { return it }
            }
            return null
        }
    }

    private fun isAndroidBuildAvailable(version: String): Boolean = compare(version, FIRST_ANDROID_VERSION) >= 0

    private fun compare(a: String, b: String): Int {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        return 0
    }

    fun nativeDir(version: String): File = File(PathManager.DIR_CACHE, "physx-android/$version")

    private fun nativeLib(version: String): File = File(nativeDir(version), LIB_NAME)

    /**
     * Find a PhysX mod in the version and get its Android library ready. Blocking, network.
     */
    fun prepare(version: Version): Result {
        val modsDir = version.modsDir()
        val (jar, physxVersion) = candidates(modsDir).firstNotNullOfOrNull { jar ->
            detectVersion(jar)?.let { jar to it }
        } ?: return Result(Status.NONE, null, null)
        if (!isAndroidBuildAvailable(physxVersion)) {
            if (placePort(physxVersion)) return Result(Status.PORTED, physxVersion, jar.name)
            if (canBridge(physxVersion)) {
                val realVersion = BRIDGED_VERSIONS.getValue(physxVersion)
                if (!nativeLib(realVersion).isFile && !download(realVersion)) {
                    return Result(Status.DOWNLOAD_FAILED, physxVersion, jar.name)
                }
                if (placeBridge(physxVersion)) {
                    Logger.info(TAG, "${jar.name}: physx-jni $physxVersion runs on Android PhysX $realVersion through ONA's bridge")
                    return Result(Status.READY, "$physxVersion (Android $realVersion)", jar.name)
                }
            }
            Logger.warning(TAG, "${jar.name} uses physx-jni $physxVersion, which has no Android build")
            return Result(Status.UNSUPPORTED_OLD, physxVersion, jar.name)
        }
        if (nativeLib(physxVersion).isFile) return Result(Status.READY, physxVersion, jar.name)
        return if (download(physxVersion)) Result(Status.READY, physxVersion, jar.name)
        else Result(Status.DOWNLOAD_FAILED, physxVersion, jar.name)
    }

    /** JVM arguments that make physx-jni load the ARM64 library, empty when not prepared */
    fun jvmArgs(version: Version): List<String> {
        val physxVersion = candidates(version.modsDir()).firstNotNullOfOrNull { detectVersion(it) } ?: return emptyList()
        if (!isAndroidBuildAvailable(physxVersion)) {
            // 2.2.2: the bridge folder made by prepare()
            if (canBridge(physxVersion) && File(bridgeDir(physxVersion), BRIDGED_REAL_LIB).isFile &&
                File(bridgeDir(physxVersion), LIB_NAME).isFile
            ) {
                return listOf(
                    "-Dphysxjni.nativeLibLocation=${bridgeDir(physxVersion).absolutePath}",
                    "-Dphysxjni.loadFromResources=false"
                )
            }
            // 1.x: no properties, the port just has to be in place (done again here for the game process)
            placePort(physxVersion)
            return emptyList()
        }
        if (!nativeLib(physxVersion).isFile) return emptyList()
        return listOf(
            "-Dphysxjni.nativeLibLocation=${nativeDir(physxVersion).absolutePath}",
            "-Dphysxjni.loadFromResources=false"
        )
    }

    private fun download(physxVersion: String): Boolean {
        val target = nativeLib(physxVersion)
        target.parentFile?.mkdirs()
        val path = "de/fabmax/physx-jni-android/$physxVersion/physx-jni-android-$physxVersion.aar"
        for (mirror in MAVEN_MIRRORS) {
            val ok = runCatching {
                val connection = (URL("$mirror/$path").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 60000
                    setRequestProperty("User-Agent", "ONALauncher2")
                }
                try {
                    if (connection.responseCode != 200) return@runCatching false
                    ZipInputStream(connection.inputStream).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            if (entry.name == "jni/$APP_ABI/$LIB_NAME") {
                                val temp = File(target.path + ".part")
                                temp.outputStream().use { zip.copyTo(it) }
                                return@runCatching temp.renameTo(target)
                            }
                            entry = zip.nextEntry
                        }
                    }
                    false
                } finally {
                    connection.disconnect()
                }
            }.onFailure { Logger.warning(TAG, "PhysX download from $mirror failed", it) }.getOrDefault(false)
            if (ok) {
                Logger.info(TAG, "Prepared Android PhysX $physxVersion")
                return true
            }
        }
        return false
    }
}
