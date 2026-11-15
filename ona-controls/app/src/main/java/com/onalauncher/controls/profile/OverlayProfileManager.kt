/*
 * ONA Launcher 2 - floating overlay controls. GPL-3.0.
 */
package com.onalauncher.controls.profile

import android.content.Context
import android.util.Log
import com.onalauncher.controls.model.OverlayProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.onalauncher.controls.model.profileFromJson
import com.onalauncher.controls.model.toJson
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * JSON profiles + the target-app whitelist.
 *
 * Files (internal storage, nothing needs a storage permission):
 *  files/ona_overlay/profiles/<profileId>.json  one OverlayProfile each
 *  files/ona_overlay/apps.json                  { "<package name>": "<profileId>" }
 *
 * apps.json IS the whitelist: the overlay only appears over packages that are keys in it, and
 * each app gets its own profile. [changes] ticks on every write so the service and the settings
 * screen re-read without polling.
 */
object OverlayProfileManager {
    private const val TAG = "OnaOverlay"


    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> get() = _changes

    private val lock = Any()
    private var cache: MutableMap<String, OverlayProfile>? = null

    private fun root(ctx: Context) = File(ctx.applicationContext.filesDir, "ona_overlay")
    private fun profileDir(ctx: Context) = File(root(ctx), "profiles").apply { mkdirs() }
    private fun appsFile(ctx: Context) = File(root(ctx), "apps.json")

    private fun ensureLoaded(ctx: Context): MutableMap<String, OverlayProfile> = synchronized(lock) {
        cache?.let { return it }
        val map = LinkedHashMap<String, OverlayProfile>()
        profileDir(ctx).listFiles { f -> f.extension == "json" }?.sortedBy { it.name }?.forEach { f ->
            runCatching { profileFromJson(f.readText()) }
                .onSuccess { map[it.id] = it }
                .onFailure { Log.w(TAG, "Bad profile ${f.name}", it) }
        }
        if (map.isEmpty()) {
            DefaultProfiles.all().forEach { p ->
                map[p.id] = p
                writeProfile(ctx, p)
            }
        }
        cache = map
        map
    }

    private fun writeProfile(ctx: Context, p: OverlayProfile) {
        val f = File(profileDir(ctx), "${safeId(p.id)}.json")
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(p.toJson().toString(2))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    private fun safeId(id: String) = id.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    fun profiles(ctx: Context): List<OverlayProfile> = ensureLoaded(ctx).values.toList()

    fun get(ctx: Context, id: String?): OverlayProfile? = id?.let { ensureLoaded(ctx)[it] }

    /** Profile for a package: its mapped profile, else the first one (never null after the first load). */
    fun profileFor(ctx: Context, pkg: String?): OverlayProfile {
        val all = ensureLoaded(ctx)
        val mapped = pkg?.let { appMap(ctx)[it] }
        return mapped?.let { all[it] } ?: all.values.first()
    }

    fun save(ctx: Context, p: OverlayProfile) {
        synchronized(lock) {
            ensureLoaded(ctx)[p.id] = p
            writeProfile(ctx, p)
        }
        _changes.value++
    }

    fun create(ctx: Context, name: String, from: OverlayProfile? = null): OverlayProfile {
        val id = "p_" + UUID.randomUUID().toString().take(8)
        val p = from?.deepCopy(id, name) ?: OverlayProfile(id, name)
        save(ctx, p)
        return p
    }

    fun rename(ctx: Context, id: String, name: String) {
        val p = get(ctx, id) ?: return
        p.name = name
        save(ctx, p)
    }

    fun delete(ctx: Context, id: String) {
        synchronized(lock) {
            val all = ensureLoaded(ctx)
            if (all.size <= 1) return // always keep one profile
            all.remove(id)
            File(profileDir(ctx), "${safeId(id)}.json").delete()
            // apps that used it fall back to the first profile
            val apps = appMap(ctx).toMutableMap()
            val first = all.keys.first()
            apps.replaceAll { _, v -> if (v == id) first else v }
            writeApps(ctx, apps)
        }
        _changes.value++
    }

    fun exportJson(ctx: Context, id: String): String? =
        get(ctx, id)?.toJson()?.toString(2)

    /** Imports a profile (new id so it never overwrites one). Returns null if the text is not a profile. */
    fun importJson(ctx: Context, text: String): OverlayProfile? {
        val p = runCatching { profileFromJson(text) }
            .onFailure { Log.w(TAG, "Import failed", it) }
            .getOrNull() ?: return null
        return create(ctx, p.name.ifBlank { "Imported" }, p)
    }

    // ---------------- whitelist: package -> profile ----------------

    fun appMap(ctx: Context): Map<String, String> = synchronized(lock) {
        val f = appsFile(ctx)
        if (!f.isFile) return emptyMap()
        runCatching {
            val o = JSONObject(f.readText())
            o.keys().asSequence().associateWith { o.getString(it) }
        }.getOrDefault(emptyMap())
    }

    private fun writeApps(ctx: Context, map: Map<String, String>) {
        root(ctx).mkdirs()
        appsFile(ctx).writeText(JSONObject(map).toString(2))
    }

    fun setAppProfile(ctx: Context, pkg: String, profileId: String) {
        synchronized(lock) {
            val m = appMap(ctx).toMutableMap()
            m[pkg] = profileId
            writeApps(ctx, m)
        }
        _changes.value++
    }

    fun removeApp(ctx: Context, pkg: String) {
        synchronized(lock) {
            val m = appMap(ctx).toMutableMap()
            m.remove(pkg)
            writeApps(ctx, m)
        }
        _changes.value++
    }

    fun isTarget(ctx: Context, pkg: String?): Boolean = pkg != null && appMap(ctx).containsKey(pkg)
}
