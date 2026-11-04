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

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * ONA: the player describes what they want ("realistic survival with shaders", "تكنو خفيف
 * 1.20.1 fabric") and gets modpacks, mods or shader packs from Modrinth that fit the wish and
 * run well on this phone: the wish becomes Modrinth categories, a game version and a loader, the
 * results are rated against the device tier and the ones that run smoothly come first.
 */
object WishAdvisor {
    enum class Kind(val projectType: String) { MODPACK("modpack"), MOD("mod"), SHADER("shader") }

    data class Suggestion(
        val title: String,
        val description: String,
        val url: String,
        val downloads: Int,
        val rating: PerformanceRating,
    )

    private const val API = "https://api.modrinth.com/v2/search"
    private const val USER_AGENT = "ONALauncher2 (github.com/Hashem2008/ONAlauncher2)"

    /** Words (English and Arabic) that mean a Modrinth category */
    private val CATEGORY_WORDS = mapOf(
        "technology" to listOf("tech", "technology", "machines", "تكنو", "تقني", "تكنولوجيا", "مكائن", "آلات"),
        "magic" to listOf("magic", "سحر"),
        "adventure" to listOf("adventure", "rpg", "مغامرة", "مغامرات"),
        "quests" to listOf("quest", "quests", "مهام", "كويست"),
        "exploration" to listOf("explore", "exploration", "استكشاف"),
        "lightweight" to listOf("light", "lightweight", "small", "خفيف", "خفيفة", "صغير"),
        "optimization" to listOf("fps", "performance", "optimization", "optimisation", "فريمات", "أداء", "اداء", "تسريع"),
        "multiplayer" to listOf("multiplayer", "server", "اونلاين", "أونلاين", "سيرفر"),
        "challenging" to listOf("hard", "hardcore", "challenge", "صعب", "تحدي"),
        "combat" to listOf("combat", "pvp", "قتال"),
        "decoration" to listOf("decoration", "building", "build", "بناء", "ديكور"),
        "worldgen" to listOf("worldgen", "biomes", "terrain", "عالم", "تضاريس", "بيومات"),
        "mobs" to listOf("mobs", "creatures", "وحوش", "مخلوقات"),
        "horror" to listOf("horror", "scary", "رعب"),
        "vanilla-like" to listOf("vanilla", "فانيلا"),
        "realistic" to listOf("realistic", "realism", "واقعي", "واقعية"),
        "potato" to listOf("potato", "بطاطا"),
    )

    /** Free words for the text search (Arabic wishes are translated to the usual English terms) */
    private val QUERY_WORDS = mapOf(
        "survival" to listOf("survival", "سرفايفل", "نجاة"),
        "realistic" to listOf("realistic", "واقعي", "واقعية"),
        "zombie" to listOf("zombie", "زومبي"),
        "dragon" to listOf("dragon", "dragons", "تنين", "تنانين"),
        "medieval" to listOf("medieval", "قرون وسطى"),
        "space" to listOf("space", "فضاء"),
        "pirates" to listOf("pirate", "pirates", "قراصنة"),
        "skyblock" to listOf("skyblock", "سكاي بلوك"),
        "create" to listOf("create", "كريت"),
        "pokemon" to listOf("pokemon", "cobblemon", "بوكيمون"),
        "guns" to listOf("guns", "weapons", "اسلحة", "أسلحة"),
    )

    private val LOADERS = listOf("fabric", "forge", "neoforge", "quilt")
    private val VERSION = Regex("""\b1\.\d{1,2}(\.\d{1,2})?\b|\b2\d\.\d{1,2}(\.\d{1,2})?\b""")

    /**
     * Blocking, call it off the main thread.
     * @throws java.io.IOException when Modrinth cannot be reached
     */
    fun suggest(wish: String, kind: Kind, tier: DeviceTier, limit: Int = 8): List<Suggestion> {
        val lower = wish.lowercase()
        // Shader packs use performance tags instead of these categories
        val categories = if (kind == Kind.SHADER) emptyList() else CATEGORY_WORDS
            .filter { (_, words) -> words.any { it in lower } }
            .keys.filter { it != "realistic" && it != "potato" || kind == Kind.SHADER }
        val queryWords = QUERY_WORDS.filter { (_, words) -> words.any { it in lower } }.keys.toMutableList()
        // Latin words the tables do not know are searched as they are (mod names, pack names)
        val known = (CATEGORY_WORDS.values.flatten() + QUERY_WORDS.values.flatten() + LOADERS).toSet()
        lower.split(Regex("""[^a-z0-9]+""")).filter { it.length > 2 && it !in known && !it.all(Char::isDigit) }
            .forEach { queryWords.add(it) }
        if (kind == Kind.SHADER && listOf("realistic", "واقعي").any { it in lower }) queryWords.add("realistic")

        val facets = mutableListOf(listOf("project_type:${kind.projectType}"))
        categories.forEach { facets.add(listOf("categories:$it")) }
        LOADERS.firstOrNull { Regex("""\b$it\b""").containsMatchIn(lower) }?.let { facets.add(listOf("categories:$it")) }
        VERSION.find(lower)?.value?.let { facets.add(listOf("versions:$it")) }

        var hits = search(queryWords.joinToString(" "), facets)
        // Too narrow: keep the type, the version and the loader, drop the extra categories
        if (hits.isEmpty() && categories.isNotEmpty()) {
            hits = search((queryWords + categories).joinToString(" "), facets.filterNot { f -> f[0].removePrefix("categories:") in categories })
        }
        return hits.map { hit ->
            val tags = hit.getAsJsonArray("categories")?.map { it.asString }.orEmpty() +
                    hit.getAsJsonArray("display_categories")?.map { it.asString }.orEmpty()
            Suggestion(
                title = hit.get("title")?.asString.orEmpty(),
                description = hit.get("description")?.asString.orEmpty(),
                url = "https://modrinth.com/${kind.projectType}/${hit.get("slug")?.asString.orEmpty()}",
                downloads = hit.get("downloads")?.asInt ?: 0,
                rating = rate(kind, tags.toSet(), tier),
            )
        }.sortedWith(compareBy<Suggestion> { it.rating.ordinal }.thenByDescending { it.downloads })
            .take(limit)
    }

    private fun capacity(tier: DeviceTier) = when (tier) {
        DeviceTier.LOW -> 1
        DeviceTier.MID -> 2
        DeviceTier.HIGH -> 3
        DeviceTier.FLAGSHIP -> 4
    }

    private fun rate(kind: Kind, tags: Set<String>, tier: DeviceTier): PerformanceRating {
        val weight = when (kind) {
            Kind.MOD -> if ("optimization" in tags) return PerformanceRating.BOOST else 1
            Kind.MODPACK -> when {
                "kitchen-sink" in tags -> 4
                "optimization" in tags && "lightweight" in tags -> return PerformanceRating.BOOST
                "lightweight" in tags -> 1
                else -> {
                    val big = listOf("technology", "magic", "quests", "adventure").count { it in tags }
                    if (big >= 3) 4 else if (big >= 1) 3 else 2
                }
            }
            Kind.SHADER -> when {
                "screenshot" in tags || "path-tracing" in tags -> 5
                "high" in tags -> 4
                "medium" in tags -> 3
                "low" in tags -> 2
                "potato" in tags -> 1
                else -> 3
            }
        }
        val cap = capacity(tier)
        return when {
            weight <= cap -> PerformanceRating.SMOOTH
            weight == cap + 1 -> PerformanceRating.MAY_LAG
            else -> PerformanceRating.HEAVY
        }
    }

    private fun search(query: String, facets: List<List<String>>): List<JsonObject> {
        val facetJson = facets.joinToString(",", "[", "]") { group -> group.joinToString(",", "[", "]") { "\"$it\"" } }
        val url = "$API?limit=30&index=relevance&query=${encode(query)}&facets=${encode(facetJson)}"
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("User-Agent", USER_AGENT)
        try {
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            return JsonParser.parseString(text).asJsonObject.getAsJsonArray("hits")?.map { it.asJsonObject }.orEmpty()
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
