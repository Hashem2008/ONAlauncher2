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

package com.movtery.zalithlauncher.game.launch

import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/**
 * ONA: log translator. Reads a game / launcher log and says in plain words (Arabic or English,
 * after the phone's language) what each important line means: real problems first, then the
 * lines that look scary but are normal on a phone. Works offline, nothing is sent anywhere.
 */
object LogTranslator {

    enum class Level { PROBLEM, WARNING, NORMAL }

    class Finding(
        val level: Level,
        val title: String,
        val meaning: String,
        /** The first log line it was found on */
        val line: String,
        val count: Int
    )

    private class Rule(
        val level: Level,
        val pattern: Regex,
        /** (title, meaning) in Arabic and English; $1.. are the pattern's groups */
        val ar: Pair<String, String>,
        val en: Pair<String, String>
    )

    private fun rx(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

    private val RULES = listOf(
        Rule(Level.PROBLEM, rx("Mod (\\S+) requires (\\S+) (\\S+) or above"),
            "مود ناقص: $2" to "المود $1 بحتاج المود $2 (نسخة $3 أو أحدث) وهو مش منزّل. ONA بنزله لحاله من Modrinth أو CurseForge قبل التشغيل الجاي.",
            "Missing mod: $2" to "$1 needs $2 ($3 or newer), which is not installed. ONA downloads it from Modrinth or CurseForge before the next launch."),
        Rule(Level.PROBLEM, rx("requires (?:any version|version \\S+)(?: or later)? of (\\S+), which is missing"),
            "مود ناقص: $1" to "مود بحتاج $1 وهو مش موجود. ONA بحاول ينزله لحاله قبل التشغيل.",
            "Missing mod: $1" to "A mod needs $1, which is missing. ONA tries to download it before the launch."),
        Rule(Level.PROBLEM, rx("Invalid characters in username"),
            "الاسم فيه حروف ممنوعة" to "اسم الحساب فيه مسافة أو حروف عربية/رموز، فالعالم رفض يدخلك. ONA صار يصلّح الاسم لحاله (حروف إنجليزي وأرقام و _ بس).",
            "Invalid player name" to "The account name has a space or other characters Minecraft refuses, so no world opened. ONA now cleans the name (A-Z, 0-9 and _)."),
        Rule(Level.PROBLEM, rx("Failed to locate library: (lib\\S+\\.so)"),
            "مكتبة جوال ناقصة: $1" to "مود جايب مكتبة للكمبيوتر بس، ما في نسخة للجوال. لو $1 هي liblwjgl_zstd.so (Voxy) فـ ONA صار يجيبها معه.",
            "Missing phone library: $1" to "A mod brings this library for PCs only. For liblwjgl_zstd.so (Voxy) ONA now ships a phone build."),
        Rule(Level.PROBLEM, rx("creating framebuffer.*Status: 36054|Status: 36054"),
            "الشيدر ما اشتغل (36054)" to "الشيدر طلب صيغة ألوان ما بيقدر الجوال يرسم فيها. ONA صار يحولها لصيغة بتشتغل مع MobileGlues و GL4ES.",
            "Shader pack failed (36054)" to "The shader pack asked for a colour format phones can't draw into. ONA now swaps it for one that works on MobileGlues / GL4ES."),
        Rule(Level.PROBLEM, rx("Outputs declared as arrays may only be indexed|variable indexing fragment shader output array|Failed to load required shader programs"),
            "شاشة سودا بعد التحميل (شيدر 26.3)" to "شيدر الشفافية الجديد بماينكرافت 26.3 بيكتب على مخرجات بمؤشر متغير، وكرت الجوال (Mali وغيره) رفضه. ONA صار يعدل الشيدر قبل ما يوصل للكرت. إذا لسا موجود حدّث اللانشر.",
            "Black screen after loading (26.3 shader)" to "Minecraft 26.3's new transparency shader indexes its outputs with a loop variable, phone GPUs (Mali and others) refuse it. ONA now rewrites the shader before the GPU sees it; update the launcher if this still shows."),
        Rule(Level.PROBLEM, rx("java\\.lang\\.OutOfMemoryError"),
            "الذاكرة خلصت" to "اللعبة احتاجت رام أكثر من المسموح. التشغيل الجاي بياخذ 1 GB زيادة، أو نزّل مسافة الرؤية.",
            "Out of memory" to "The game needed more RAM than it had. The next launch gets 1 GB more; a lower render distance helps too."),
        Rule(Level.PROBLEM, rx("(?:Mixin apply .* failed|MixinApplyError|InvalidMixinException|InjectionError)"),
            "تعارض بين المودات" to "مود حاول يعدل كود اللعبة وفشل، غالبًا لأن نسخته مش لنفس نسخة اللعبة أو في مود ثاني متعارض معه.",
            "Mods conflict" to "A mod failed to patch the game, usually a build for another game version or a conflicting mod."),
        Rule(Level.PROBLEM, rx("(?:java\\.lang\\.NoClassDefFoundError|Caused by: java\\.lang\\.ClassNotFoundException): (\\S+)"),
            "ملف ناقص: $1" to "اللعبة دورت على $1 وما لقته. يعني مكتبة أو مود ناقص أو نسخته غلط.",
            "Missing class: $1" to "The game looked for $1 and did not find it: a library or mod is missing or the wrong build."),
        Rule(Level.PROBLEM, rx("java\\.lang\\.UnsatisfiedLinkError: (.*)"),
            "مكتبة ما اشتغلت" to "مكتبة نيتف ما انفتحت على الجوال: $1",
            "Native library failed" to "A native library did not load on the phone: $1"),
        Rule(Level.PROBLEM, rx("Replace mod '([^']+)' \\(([^)]+)\\) (\\S+) with version (\\S+?)\\.?(?:\\s|$)"),
            "نسخة مود غلط: $1" to "مود ثاني بده $1 بالنسخة $4 والموجود $3. ONA بينزل النسخة الصح لحاله بالتشغيل الجاي.",
            "Wrong mod version: $1" to "Another mod needs $1 $4, $3 is installed. ONA installs the right version by itself on the next launch."),
        Rule(Level.PROBLEM, rx("Mod Loading has failed|Incompatible mods found|Incompatible mod set|Missing or unsupported mandatory dependencies"),
            "تحميل المودات فشل" to "في مود ناقص أو نسخته مش مناسبة. شوف السطور اللي تحت هذا بالترجمة.",
            "Mod loading failed" to "A mod is missing or does not fit this version. See the other findings."),
        Rule(Level.PROBLEM, rx("Found duplicate mods?|Duplicate mod"),
            "مود مكرر" to "نفس المود موجود مرتين بمجلد mods. لازم وحدة بس.",
            "Duplicate mod" to "The same mod is in the mods folder twice; only one may stay."),
        Rule(Level.PROBLEM, rx("SIGSEGV|Fatal signal|A fatal error has been detected by the Java Runtime"),
            "كراش من الرندر أو الدرايفر" to "اللعبة طفت داخل الرندر. ONA Hybrid بجرب رندر ثاني بالتشغيل الجاي.",
            "Renderer / driver crash" to "The game stopped inside the renderer. ONA Hybrid tries another renderer next time."),
        Rule(Level.WARNING, rx("Can't keep up! Is the server overloaded\\? Running (\\d+)ms"),
            "العالم متأخر $1 ms" to "السيرفر الداخلي (العالم) أبطأ من اللازم: مودات ثقيلة أو مسافة محاكاة كبيرة.",
            "World is $1 ms behind" to "The built-in server is too slow: heavy mods or a big simulation distance."),
        Rule(Level.WARNING, rx("waiting for (\\d+) chunks to unload"),
            "الخروج من العالم بطيء ($1 chunk)" to "مسافة الرؤية كبيرة كثير، فالعالم بحفظ آلاف القطع وقت الخروج. مع Voxy خليها 16 أو أقل (Voxy بيرسم البعيد لحاله).",
            "Slow world exit ($1 chunks)" to "The render distance is very high, so leaving saves thousands of chunks. With Voxy keep it at 16 or less."),
        Rule(Level.WARNING, rx("Light updates disabled|light_updates.*false"),
            "الإضاءة مطفية" to "إعداد بمود Sodium/Embeddium Extra طافي تحديث الإضاءة، فبتطلع بلوكات سودا. ONA برجعه شغال.",
            "Light updates off" to "A Sodium / Embeddium Extra option stops light updates (black blocks). ONA turns it back on."),
        Rule(Level.WARNING, rx("OverlappingFileLockException|session\\.lock"),
            "العالم مقفول" to "العالم كان لسا مفتوح (خروج قبله ما خلص). استنى شوي أو سكّر اللعبة وافتحها.",
            "World is locked" to "The world was still open from a previous exit. Wait a moment or restart the game."),
        Rule(Level.WARNING, rx("Shaders are disabled because enableShaders is set to false"),
            "الشيدر مطفي" to "الشيدر مطفي من إعدادات Iris/Oculus. شغله من Options > Video > Shader Packs.",
            "Shaders are off" to "Shaders are turned off in Iris / Oculus settings."),
        Rule(Level.NORMAL, rx("Failed to fetch user properties|Realms|SignedJWT|Status: 401"),
            "عادي: حساب أوفلاين" to "اللعبة حاولت توصل سيرفرات مايكروسوفت/Realms وحسابك أوفلاين. ما بأثر على اللعب.",
            "Normal: offline account" to "The game tried Microsoft / Realms services with an offline account. Harmless."),
        Rule(Level.NORMAL, rx("Reference map '.*' .*could not be read"),
            "عادي: refmap" to "رسالة عادية من المودات، ما بتعني مشكلة.",
            "Normal: refmap" to "A normal mod message, not a problem."),
        Rule(Level.NORMAL, rx("is not supported on Android"),
            "عادي: مود بطفي ميزته على أندرويد" to "المود بعرف إنه على أندرويد فبطفي الجزء اللي ما بيشتغل، والباقي شغال.",
            "Normal: mod skips a feature" to "The mod knows it runs on Android and skips that part."),
        Rule(Level.NORMAL, rx("Could not find any graphics adapters|File not found or not readable: /(?:proc|etc)/|udev library"),
            "عادي على الجوال" to "مكتبات الكمبيوتر بتدور على ملفات النظام ما بتلاقيها على الجوال. ما في مشكلة.",
            "Normal on phones" to "PC libraries look for system files phones don't have. Harmless."),
        Rule(Level.NORMAL, rx("CrashAssistantApp failed to start|dalvik-cache ownership"),
            "عادي: Crash Assistant" to "نافذة Crash Assistant ما بتشتغل على الجوال، اللعبة نفسها ما بتتأثر.",
            "Normal: Crash Assistant" to "Crash Assistant's window can't open on phones; the game is fine."),
        Rule(Level.NORMAL, rx("Error loading class: |@Mixin target .* was not found"),
            "عادي: دعم لمود مش موجود" to "مود عنده إضافات لمودات ثانية مش منزلة، فبتجاهلها.",
            "Normal: optional mod support" to "A mod has add-ons for mods you don't have and skips them.")
    )

    /** Read the end of a log (big logs keep the cause near the end) */
    fun readTail(file: File, maxBytes: Long = 3L * 1024 * 1024): String = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val start = maxOf(0L, raf.length() - maxBytes)
            raf.seek(start)
            val bytes = ByteArray((raf.length() - start).toInt())
            raf.readFully(bytes)
            String(bytes, Charsets.UTF_8)
        }
    }.getOrDefault("")

    fun translate(text: String, arabic: Boolean = Locale.getDefault().language == "ar"): List<Finding> {
        val results = LinkedHashMap<String, Finding>()
        val lines = text.lineSequence().toList()
        // Forge prints "Mod X requires Y" and "Currently, Y is not installed" on separate lines
        for (line in lines) {
            for (rule in RULES) {
                val match = rule.pattern.find(line) ?: continue
                val (title, meaning) = if (arabic) rule.ar else rule.en
                val filledTitle = fill(title, match)
                val key = "${rule.level}|$filledTitle"
                val old = results[key]
                results[key] = if (old == null) {
                    Finding(rule.level, filledTitle, fill(meaning, match), line.trim().take(300), 1)
                } else {
                    Finding(old.level, old.title, old.meaning, old.line, old.count + 1)
                }
                break
            }
        }
        return results.values.sortedBy { it.level.ordinal }
    }

    private fun fill(template: String, match: MatchResult): String {
        var out = template
        for (i in match.groupValues.indices.reversed()) {
            if (i == 0) continue
            out = out.replace("$$i", match.groupValues[i])
        }
        return out
    }
}
