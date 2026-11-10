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

package com.movtery.zalithlauncher.game.renderer.renderers

import com.movtery.zalithlauncher.game.renderer.RendererInterface
import com.movtery.zalithlauncher.game.renderer.Renderers
import com.movtery.zalithlauncher.game.renderer.config.ConfigScalar
import com.movtery.zalithlauncher.path.PathManager
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.game.version.mod.VoxySupport
import com.movtery.zalithlauncher.game.version.mod.MobileModRules
import com.movtery.zalithlauncher.game.version.installed.utils.isBiggerVer
import com.movtery.zalithlauncher.game.version.installed.utils.isLowerVer
import com.movtery.zalithlauncher.utils.device.DeviceProfiler
import com.movtery.zalithlauncher.utils.logging.Logger
import java.io.File

/**
 * ONA Smart: not a renderer of its own but a per launch choice between the bundled ones, from
 * what each of them is good at:
 *  - GL4ES: oldest and lightest, best on 1.16.5 and older
 *  - Krypton Wrapper (NG GL4ES): fast GL 3.x on GLES, best for 1.17 - 1.21.4 without shaders
 *  - MobileGlues: GL 4.x on GLES 3.2, what Iris / Oculus shaders and 1.21.5+ need
 *  - Kopper Zink: desktop GL on Vulkan through Mesa, the most compatible fallback when the
 *    GPU has a good Vulkan driver (Turnip on Adreno 6xx/7xx)
 * The first candidate that supports the game version wins.
 */
object SmartRenderer : RendererInterface {
    const val ID = "6f0c2a1e-ona-smart-auto"
    private const val TAG = "SmartRenderer"

    override fun getRendererId(): String = NGGL4ESRenderer.getRendererId()
    override fun getUniqueIdentifier(): String = ID
    override fun getRendererName(): String = "ONA Renderer"
    override fun getRendererSummary(): String =
        "ONA's own engine (MobileGlues built and tuned by ONA): one engine for every version from 1.17, with ONA Graphics"
    override fun getRendererEnv(): Lazy<Map<String, String>> = NGGL4ESRenderer.getRendererEnv()
    override fun getDlopenLibrary(): Lazy<List<String>> = NGGL4ESRenderer.getDlopenLibrary()
    override fun getRendererLibrary(): String = NGGL4ESRenderer.getRendererLibrary()

    private val SHADER_MODS = listOf("iris", "oculus", "optifine")
    private val SODIUM_LIKE = listOf("sodium", "embeddium", "rubidium", "xenon")

    /** @return the unique identifier of the renderer this version should start with */
    fun choose(version: Version): String {
        // Voxy needs desktop GL 4.6, only Kopper Zink on Turnip has it
        if (VoxySupport.hasVoxy(version)) {
            val zink = VoxySupport.preferredRenderer()
            lastReason = "${version.getVersionName()} has Voxy -> ${zink.getRendererName()}"
            Logger.info(TAG, lastReason)
            return zink.getUniqueIdentifier()
        }
        val situation = situation(version)
        val mc = situation.mc ?: return NGGL4ESRenderer.getUniqueIdentifier()
        val shaders = situation.shaders
        // Sodium 0.6+ (1.21+) wants more than the GL 3.1 Krypton exposes: geometry spikes there
        val modernSodium = situation.sodiumLike && !mc.isLowerVer("1.21")
        val profile = DeviceProfiler.current()
        val goodVulkan = profile?.let { it.vulkan && it.turnipRecommended } ?: false
        // Any Vulkan 1.1 GPU can run Zink on its own driver, kept as a later fallback
        val anyVulkan = profile?.vulkan ?: false

        // Old packs whose mods compile their own GLSL: Krypton rejects what GL4ES half converts
        val ownShaders = if (mc.isLowerVer("1.17")) {
            runCatching { MobileModRules.modsWithOwnShaders(version) }.getOrDefault(emptyList())
        } else emptyList()
        if (ownShaders.isNotEmpty()) Logger.info(TAG, "Mods with their own shaders: $ownShaders -> Krypton before GL4ES")

        // Physics Mod (1.20+) draws its debris with instanced UBO meshes that come out as long
        // stretched triangles on the GLES translators; desktop GL through Mesa draws them right
        val physicsOnZink = situation.physicsMod && !mc.isLowerVer("1.20") && anyVulkan
        if (physicsOnZink) Logger.info(TAG, "Physics Mod on $mc -> Kopper Zink first")

        val candidates = buildList {
            if (physicsOnZink) add(KopperZinkRenderer)
            when {
                mc.isLowerVer("1.17") && ownShaders.isNotEmpty() -> {
                    add(NGGL4ESRenderer)
                    add(GL4ESRenderer)
                    if (anyVulkan) add(KopperZinkRenderer)
                    add(MobileGluesRenderer)
                }
                mc.isLowerVer("1.17") -> {
                    add(GL4ESRenderer)
                    add(NGGL4ESRenderer)
                    // Not GL4ES based, so a crash in GL4ES' array handling does not follow
                    if (anyVulkan) add(KopperZinkRenderer)
                    add(MobileGluesRenderer)
                }
                // ONA Renderer: one engine for every 1.17+ version, ONA's own MobileGlues build
                // (tools/ona-renderer). The others are only used after it crashed on this version.
                else -> {
                    add(MobileGluesRenderer)
                    add(NGGL4ESRenderer)
                    if (goodVulkan) add(KopperZinkRenderer)
                }
            }
            if (goodVulkan) add(KopperZinkRenderer)
            add(MobileGluesRenderer)
            add(NGGL4ESRenderer)
            if (anyVulkan) add(KopperZinkRenderer)
        }
        val available = Renderers.getRenderers()
        // Render mods that break the GL4ES family keep running: pick another renderer for them
        val avoidGl4es = MobileModRules.avoidGl4es(version)
        // ...without ever leaving the version with nothing: GL4ES stays as the last resort
        val ordered = if (avoidGl4es) {
            val (gl4es, others) = candidates.distinct().partition { MobileModRules.isGl4esFamily(it) }
            val extra = listOf(KopperZinkRenderer, MobileGluesRenderer).filter { it !in others }
            others + extra + gl4es
        } else candidates.distinct()
        val usable = ordered.filter { renderer ->
            available.any { it.getUniqueIdentifier() == renderer.getUniqueIdentifier() } &&
                    renderer.getMinMCVersion()?.let { mc.isLowerVer(it) } != true &&
                    renderer.getMaxMCVersion()?.let { mc.isBiggerVer(it) } != true
        }
        // Skip the renderers that already crashed natively on this version
        val crashed = crashedRenderers(version)
        val chosen = usable.firstOrNull { it.getUniqueIdentifier() !in crashed }
            ?: usable.firstOrNull()
            ?: candidates.first()
        if (usable.isNotEmpty() && usable.all { it.getUniqueIdentifier() in crashed }) {
            // Everything crashed once: start over instead of being stuck
            clearCrashes(version)
        }
        lastReason = "${version.getVersionName()} ($mc, shaders=$shaders, sodium=${situation.sodiumLike}, mods=${situation.modCount}, physics=${situation.physicsMod}, own-shader mods=${ownShaders.size}, vulkan=$goodVulkan, skipped=${crashed.size}) -> ${chosen.getRendererName()}"
        Logger.info(TAG, lastReason)
        return chosen.getUniqueIdentifier()
    }

    /** What a version runs, everything the choice and the per engine tuning depend on */
    data class Situation(
        val mc: String?,
        val shaders: Boolean,
        val sodiumLike: Boolean,
        val modCount: Int,
        val physicsMod: Boolean = false,
        /** the active shader pack is ONA Mobile Shaders */
        val onaShaders: Boolean = false,
        /**
         * the active pack has compute programs (.csh) and gives every image it writes a format, so
         * it can run on OpenGL 4.3 over OpenGL ES (false when the pack could not be looked into)
         */
        val computeShaders: Boolean = false
    )

    fun situation(version: Version): Situation {
        val gameDir = version.getGameDir()
        val modNames = File(gameDir, "mods").list()?.filter { it.endsWith(".jar") }?.map { it.lowercase() }.orEmpty()
        val sodiumLike = modNames.any { name -> SODIUM_LIKE.any { name.contains(it) } }
        val shaderMod = modNames.any { name -> SHADER_MODS.any { name.contains(it) } }
        val shaderPacks = File(gameDir, "shaderpacks").listFiles()?.any { !it.name.endsWith(".txt") } == true
        val activePack = runCatching { com.movtery.zalithlauncher.utils.device.ShaderBoost.activeShaderPack(gameDir) }.getOrNull()
        return Situation(
            mc = version.getVersionInfo()?.minecraftVersion,
            shaders = shaderMod || shaderPacks && sodiumLike,
            sodiumLike = sodiumLike,
            modCount = modNames.size,
            physicsMod = modNames.any { it.contains("physics-mod") || it.contains("physicsmod") },
            onaShaders = com.movtery.zalithlauncher.game.graphics.OnaGraphics.isOnaPack(activePack),
            computeShaders = activePack?.let { gl43Safe(File(File(gameDir, "shaderpacks"), it)) } ?: false
        )
    }

    private val SHADER_FILE = Regex("""\.(glsl|fsh|vsh|csh|gsh)$""")
    private val IMAGE_UNIFORM = Regex(
        """^[^\n/]*\buniform\s+(?:\w+\s+)*u?i?image(?:1D|2D|3D|Cube|2DRect|Buffer|1DArray|2DArray|CubeArray|2DMS|2DMSArray)\b[^\n;]*""",
        RegexOption.MULTILINE
    )
    private val IMAGE_FORMAT = Regex("""layout\s*\([^)]*\b(rgba|rg|r)(8|16|32)""")

    /**
     * OpenGL 4.3 (compute shaders, images) is only safe for a pack that has compute programs and
     * names the format of every image: OpenGL ES needs it and desktop GLSL does not (writeonly
     * images may leave it out). Complementary, BSL, Photon and Solas leave it out: on 4.3 they turn
     * on their voxel / colored light paths and the conversion to OpenGL ES fails ("Unsupported or
     * invalid OpenGL ES version"); on 4.0 Iris keeps those paths off and the packs load.
     */
    fun gl43Safe(pack: File): Boolean = runCatching {
        val sources: Sequence<Pair<String, () -> String>> = when {
            pack.isFile -> {
                val zip = java.util.zip.ZipFile(pack)
                try {
                    val entries = zip.entries().asSequence().filter { SHADER_FILE.containsMatchIn(it.name) }.toList()
                    if (entries.none { it.name.endsWith(".csh") }) return@runCatching false
                    return@runCatching entries.none { entry ->
                        val text = zip.getInputStream(entry).use { it.readBytes().decodeToString() }
                        IMAGE_UNIFORM.findAll(text).any { !IMAGE_FORMAT.containsMatchIn(it.value) }
                    }
                } finally {
                    zip.close()
                }
            }
            pack.isDirectory -> pack.walkTopDown().filter { it.isFile && SHADER_FILE.containsMatchIn(it.name) }
                .map { file -> file.name to { file.readText() } }
            else -> return@runCatching false
        }
        val files = sources.toList()
        files.any { it.first.endsWith(".csh") } &&
            files.none { (_, read) -> IMAGE_UNIFORM.findAll(read()).any { !IMAGE_FORMAT.containsMatchIn(it.value) } }
    }.getOrDefault(false)

    /**
     * Hybrid: MobileGlues settings for the engine ONA Hybrid picked. Only what the user did not
     * set themself, their explicit renderer settings always win.
     */
    fun mobileGluesTuning(situation: Situation): Map<String, ConfigScalar> = buildMap {
        // A bigger translated shader cache: shader packs and big packs stutter less after the first run
        val cacheMb = when {
            situation.onaShaders -> 128
            situation.shaders -> 256
            situation.modCount >= 80 -> 128
            else -> 64
        }
        put("maxGlslCacheSize", ConfigScalar.IntValue(cacheMb))
        // ONA Mobile Shaders uses no compute shaders: no need to report 4.3 for it
        if (situation.shaders && !situation.onaShaders && situation.computeShaders) {
            // A pack whose compute programs and images run on OpenGL ES (Bliss names every image
            // format): expose MobileGlues' compute support and report 4.3. Every other pack keeps the
            // 4.0 path (see gl43Safe)
            put("enableExtComputeShader", ConfigScalar.IntValue(1))
            put("customGLVersion", ConfigScalar.IntValue(43))
        }
    }

    /** Hybrid: environment for the GL4ES family (GL4ES, Krypton Wrapper) */
    fun gl4esTuning(): Map<String, String> = buildMap {
        // Keep the precompiled shader archive on internal storage instead of the slower shared one
        val psa = File(PathManager.DIR_FILES_PRIVATE, "gl4es")
        if (psa.isDirectory || psa.mkdirs()) put("LIBGL_PSA_FOLDER", psa.absolutePath)
    }

    /** Why the last choice was made, printed in the launch log */
    @Volatile var lastReason: String = ""
        private set

    private fun crashFile(version: Version) = File(version.getVersionPath(), "ona_crashed_renderers.txt")

    private fun crashedRenderers(version: Version): Set<String> =
        runCatching { crashFile(version).readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet() }.getOrDefault(emptySet())

    private fun clearCrashes(version: Version) {
        runCatching { crashFile(version).delete() }
    }

    /**
     * The game crashed inside the renderer or the driver: the next launch of this version with
     * ONA Smart moves on to the next renderer that suits it.
     */
    fun recordCrash(version: Version, rendererId: String) {
        if (rendererId == ID) return
        runCatching {
            val known = crashedRenderers(version)
            if (rendererId !in known) crashFile(version).appendText("$rendererId\n")
            Logger.info(TAG, "Marked $rendererId as crashing on ${version.getVersionName()}")
        }
    }
}
