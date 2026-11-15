/*
 * ONA Launcher 2 - floating overlay controls. GPL-3.0.
 */
package com.onalauncher.controls.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.core.os.LocaleListCompat
import com.onalauncher.controls.model.MouseMode
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.onalauncher.controls.R
import com.onalauncher.controls.BackendType
import com.onalauncher.controls.OverlayPrefs
import com.onalauncher.controls.monitor.ForegroundAppMonitor
import com.onalauncher.controls.profile.OverlayProfileManager
import com.onalauncher.controls.service.OverlayControllerService
import com.onalauncher.controls.shizuku.ShizukuKeys
import rikka.shizuku.Shizuku
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings for the floating controls: permissions, target apps (App Picker), per-app profile,
 * Winlator-like profile options (cursor speed, opacity, disable mouse, import / export), output
 * backend, start / stop, and "edit layout now".
 */
class OverlayControlsActivity : AppCompatActivity() {

    /** Bumped in onResume so permission states are re-read after returning from Settings. */
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    OverlayScreen(resumeTick.intValue)
                }
            }
        }
    }

    private val shizukuResult = Shizuku.OnRequestPermissionResultListener { _, _ ->
        resumeTick.intValue++
        ShizukuKeys.ensureBound()
        if (OverlayControllerService.running) OverlayControllerService.start(this, OverlayControllerService.ACTION_RELOAD)
    }
    private val shizukuBinder = Shizuku.OnBinderReceivedListener { runOnUiThread { resumeTick.intValue++ } }

    override fun onStart() {
        super.onStart()
        ShizukuKeys.init(this)
        Shizuku.addRequestPermissionResultListener(shizukuResult)
        Shizuku.addBinderReceivedListenerSticky(shizukuBinder)
    }

    override fun onStop() {
        Shizuku.removeRequestPermissionResultListener(shizukuResult)
        Shizuku.removeBinderReceivedListener(shizukuBinder)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }
}

data class LaunchableApp(val pkg: String, val label: String)

@Suppress("DEPRECATION")
private fun loadLaunchableApps(ctx: Context): List<LaunchableApp> {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val list = if (Build.VERSION.SDK_INT >= 33) {
        pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
    } else {
        pm.queryIntentActivities(intent, 0)
    }
    return list.asSequence()
        .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
        .filter { it.first != ctx.packageName }
        .distinctBy { it.first }
        .map { LaunchableApp(it.first, it.second) }
        .sortedBy { it.label.lowercase() }
        .toList()
}

@Composable
private fun AppIcon(pkg: String, size: Int = 40) {
    val ctx = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, pkg) {
        value = withContext(Dispatchers.IO) {
            runCatching { ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        }
    }
    Box(Modifier.size(size.dp)) {
        icon?.let { Image(bitmap = it, contentDescription = null, modifier = Modifier.size(size.dp)) }
    }
}

private fun appLabel(ctx: Context, pkg: String): String = runCatching {
    val pm = ctx.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
}.getOrDefault(pkg)

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.size(8.dp))
            content()
        }
    }
}

@Composable
private fun OverlayScreen(resumeTick: Int) {
    val ctx = LocalContext.current
    // re-read on every resume and every profile / whitelist change
    val changes by OverlayProfileManager.changes.collectAsState()
    var localTick by remember { mutableIntStateOf(0) }
    val key = resumeTick + changes + localTick
    fun refresh() { localTick++ }

    val canOverlay = remember(key) { Settings.canDrawOverlays(ctx) }
    val a11y = remember(key) { ForegroundAppMonitor.isAccessibilityEnabled(ctx) }
    val usage = remember(key) { ForegroundAppMonitor.hasUsageAccess(ctx) }
    val notify = remember(key) {
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val notifyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    val apps = remember(key) { OverlayProfileManager.appMap(ctx) }
    val profiles = remember(key) { OverlayProfileManager.profiles(ctx) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected = profiles.firstOrNull { it.id == selectedId } ?: profiles.firstOrNull()

    var showPicker by remember { mutableStateOf(false) }
    var renameDialog by remember { mutableStateOf<String?>(null) } // "new", "rename", "dup"

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val text = runCatching { ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
        val p = text?.let { OverlayProfileManager.importJson(ctx, it) }
        if (p == null) Toast.makeText(ctx, R.string.ona_overlay_import_failed, Toast.LENGTH_LONG).show() else selectedId = p.id
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        val id = selected?.id
        if (uri != null && id != null) {
            val json = OverlayProfileManager.exportJson(ctx, id)
            runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(json!!.toByteArray()) } }
        }
    }

    val shizuku = remember(key) { ShizukuKeys.state(ctx) }
    var serviceOn by remember(key) { mutableStateOf(OverlayControllerService.running) }
    var showAdvanced by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // ---------------- header + language
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.ona_overlay_summary), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            val current = remember(key) { AppCompatDelegate.getApplicationLocales().toLanguageTags() }
            FilterChip(selected = current.startsWith("en"), onClick = {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
            }, label = { Text("English") })
            FilterChip(selected = current.startsWith("ar"), onClick = {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ar"))
            }, label = { Text("عربي") })
        }
        Spacer(Modifier.size(6.dp))

        // ---------------- quick start: numbered steps, each with its state and one button
        SectionCard(stringResource(R.string.ona_overlay_quick_start)) {
            StepRow(1, stringResource(R.string.ona_overlay_step_overlay), stringResource(R.string.ona_overlay_step_overlay_desc), canOverlay,
                stringResource(R.string.ona_overlay_perm_grant)) {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
            }
            StepRow(2, stringResource(R.string.ona_overlay_step_a11y), stringResource(R.string.ona_overlay_step_a11y_desc), a11y,
                stringResource(R.string.ona_overlay_perm_grant)) {
                ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            StepRow(3, stringResource(R.string.ona_overlay_step_shizuku), stringResource(R.string.ona_overlay_shizuku_help),
                shizuku == ShizukuKeys.State.READY,
                stringResource(when (shizuku) {
                    ShizukuKeys.State.NOT_INSTALLED -> R.string.ona_overlay_shizuku_get
                    ShizukuKeys.State.NOT_RUNNING -> R.string.ona_overlay_shizuku_open
                    else -> R.string.ona_overlay_perm_grant
                })) {
                when (shizuku) {
                    ShizukuKeys.State.NOT_INSTALLED -> runCatching {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + ShizukuKeys.SHIZUKU_PACKAGE)))
                    }
                    ShizukuKeys.State.NOT_RUNNING -> ctx.packageManager.getLaunchIntentForPackage(ShizukuKeys.SHIZUKU_PACKAGE)?.let { ctx.startActivity(it) }
                    else -> ShizukuKeys.requestPermission()
                }
            }
            StepRow(4, stringResource(R.string.ona_overlay_step_game), stringResource(R.string.ona_overlay_step_game_desc), apps.isNotEmpty(),
                stringResource(R.string.ona_overlay_add_app)) { showPicker = true }
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                StepNumber(5, serviceOn)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(stringResource(R.string.ona_overlay_step_on), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.ona_overlay_step_on_desc), style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = serviceOn, enabled = canOverlay, onCheckedChange = {
                    serviceOn = it
                    OverlayPrefs.setEnabled(ctx, it)
                    if (it) OverlayControllerService.start(ctx) else OverlayControllerService.stop(ctx)
                })
            }
            if (!notify) TextButton(onClick = {
                if (Build.VERSION.SDK_INT >= 33) notifyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }) { Text(stringResource(R.string.ona_overlay_perm_notify)) }
        }

        // ---------------- your games + their profile
        SectionCard(stringResource(R.string.ona_overlay_apps)) {
            if (apps.isEmpty()) Text(stringResource(R.string.ona_overlay_apps_empty), style = MaterialTheme.typography.bodySmall)
            apps.forEach { (pkg, profileId) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(pkg)
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(remember(pkg) { appLabel(ctx, pkg) }, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                        Text(stringResource(R.string.ona_overlay_uses_profile), style = MaterialTheme.typography.labelSmall)
                    }
                    ProfileDropdown(
                        current = profiles.firstOrNull { it.id == profileId }?.name ?: "?",
                        names = profiles.map { it.id to it.name },
                        onPick = { OverlayProfileManager.setAppProfile(ctx, pkg, it) }
                    )
                    IconButton(onClick = { OverlayProfileManager.removeApp(ctx, pkg) }) {
                        Icon(painterResource(R.drawable.ic_delete_outlined), contentDescription = null)
                    }
                }
            }
            OutlinedButton(onClick = { showPicker = true }) { Text(stringResource(R.string.ona_overlay_add_app)) }
        }

        // ---------------- profile
        SectionCard(stringResource(R.string.ona_overlay_profile)) {
            if (selected != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProfileDropdown(selected.name, profiles.map { it.id to it.name }) { selectedId = it }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { renameDialog = "new" }) { Icon(painterResource(R.drawable.ic_add), null) }
                    IconButton(onClick = { renameDialog = "rename" }) { Icon(painterResource(R.drawable.ic_edit_outlined), null) }
                    IconButton(onClick = { renameDialog = "dup" }) { Icon(painterResource(R.drawable.ic_add_box_outlined), null) }
                    IconButton(enabled = profiles.size > 1, onClick = {
                        OverlayProfileManager.delete(ctx, selected.id); selectedId = null
                    }) { Icon(painterResource(R.drawable.ic_delete_outlined), null) }
                }
                Button(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), enabled = canOverlay, onClick = {
                    OverlayPrefs.setEnabled(ctx, true)
                    OverlayControllerService.start(ctx, OverlayControllerService.ACTION_EDIT, apps.keys.firstOrNull())
                }) { Text(stringResource(R.string.ona_overlay_edit_now)) }
                Text(stringResource(R.string.ona_overlay_edit_hint), style = MaterialTheme.typography.bodySmall)

                var opacity by remember(selected.id, key) { mutableStateOf(selected.opacity) }
                Text(stringResource(R.string.ona_overlay_opacity) + "  ${(opacity * 100).toInt()}%", modifier = Modifier.padding(top = 8.dp))
                Slider(value = opacity, valueRange = 0.05f..1f, onValueChange = { opacity = it }, onValueChangeFinished = {
                    selected.opacity = opacity; OverlayProfileManager.save(ctx, selected)
                })

                // mouse: two modes
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(stringResource(R.string.ona_overlay_mouse_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                ChoiceRow(selected.mouseMode == MouseMode.OFF, stringResource(R.string.ona_overlay_mouse_off), stringResource(R.string.ona_overlay_mouse_off_desc)) {
                    selected.mouseMode = MouseMode.OFF; OverlayProfileManager.save(ctx, selected)
                }
                ChoiceRow(selected.mouseMode == MouseMode.SWIPE, stringResource(R.string.ona_overlay_mouse_swipe), stringResource(R.string.ona_overlay_mouse_swipe_desc)) {
                    selected.mouseMode = MouseMode.SWIPE; OverlayProfileManager.save(ctx, selected)
                }
                if (selected.mouseMode == MouseMode.SWIPE) {
                    var speed by remember(selected.id, key) { mutableStateOf(selected.cursorSpeed) }
                    Text(stringResource(R.string.ona_overlay_cursor_speed) + "  ${(speed * 100).toInt()}%")
                    Slider(value = speed, valueRange = 0.1f..3f, onValueChange = { speed = it }, onValueChangeFinished = {
                        selected.cursorSpeed = speed; OverlayProfileManager.save(ctx, selected)
                    })
                    if (shizuku != ShizukuKeys.State.READY) {
                        Text(stringResource(R.string.ona_overlay_mouse_needs), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(modifier = Modifier.weight(1f), onClick = { importer.launch(arrayOf("application/json", "text/*", "*/*")) }) {
                        Text(stringResource(R.string.ona_overlay_import))
                    }
                    OutlinedButton(modifier = Modifier.weight(1f), onClick = { exporter.launch("${selected.name}.json") }) {
                        Text(stringResource(R.string.ona_overlay_export))
                    }
                }
            }
        }

        // ---------------- how to use
        SectionCard(stringResource(R.string.ona_overlay_guide)) {
            listOf(
                R.string.ona_overlay_guide_1, R.string.ona_overlay_guide_2, R.string.ona_overlay_guide_3,
                R.string.ona_overlay_guide_4, R.string.ona_overlay_guide_5, R.string.ona_overlay_guide_6
            ).forEach { res ->
                Text("•  " + stringResource(res), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 3.dp))
            }
        }

        // ---------------- advanced (folded)
        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Column(Modifier.padding(14.dp)) {
                Row(Modifier.fillMaxWidth().clickable { showAdvanced = !showAdvanced }, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ona_overlay_advanced), style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(if (showAdvanced) "▲" else "▼")
                }
                if (showAdvanced) {
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.ona_overlay_output), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    var backend by remember(key) { mutableStateOf(OverlayPrefs.backend(ctx)) }
                    fun pick(b: BackendType) {
                        backend = b
                        OverlayPrefs.setBackend(ctx, b)
                        if (OverlayControllerService.running) OverlayControllerService.start(ctx, OverlayControllerService.ACTION_RELOAD)
                    }
                    BackendRow(backend == BackendType.ACCESSIBILITY_TOUCH, R.string.ona_overlay_backend_touch, R.string.ona_overlay_backend_touch_summary) { pick(BackendType.ACCESSIBILITY_TOUCH) }
                    BackendRow(backend == BackendType.PC_BRIDGE, R.string.ona_overlay_backend_pc, R.string.ona_overlay_backend_pc_summary) { pick(BackendType.PC_BRIDGE) }
                    if (backend == BackendType.PC_BRIDGE) {
                        var host by remember { mutableStateOf(OverlayPrefs.host(ctx)) }
                        var port by remember { mutableStateOf(OverlayPrefs.port(ctx).toString()) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 40.dp)) {
                            OutlinedTextField(host, { host = it; OverlayPrefs.setHost(ctx, it) }, Modifier.weight(2f),
                                label = { Text(stringResource(R.string.ona_overlay_host)) }, singleLine = true)
                            OutlinedTextField(port, { v ->
                                port = v.filter(Char::isDigit).take(5)
                                port.toIntOrNull()?.let { OverlayPrefs.setPort(ctx, it) }
                            }, Modifier.weight(1f), label = { Text(stringResource(R.string.ona_overlay_port)) }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        }
                        TextButton(modifier = Modifier.padding(start = 32.dp), onClick = {
                            if (OverlayControllerService.running) OverlayControllerService.start(ctx, OverlayControllerService.ACTION_RELOAD)
                        }) { Text(stringResource(R.string.ona_overlay_reconnect)) }
                    }
                    BackendRow(backend == BackendType.ROOT_SHELL, R.string.ona_overlay_backend_root, R.string.ona_overlay_backend_root_summary) { pick(BackendType.ROOT_SHELL) }
                    var haptics by remember { mutableStateOf(OverlayPrefs.haptics(ctx)) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.ona_overlay_haptics), Modifier.weight(1f))
                        Switch(checked = haptics, onCheckedChange = { haptics = it; OverlayPrefs.setHaptics(ctx, it) })
                    }
                    PermRow(stringResource(R.string.ona_overlay_perm_usage), usage || a11y) {
                        ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    }
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.ona_overlay_how_text), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    if (showPicker) {
        AppPickerDialog(
            exclude = apps.keys,
            onDismiss = { showPicker = false },
            onPick = { pkg ->
                showPicker = false
                OverlayProfileManager.setAppProfile(ctx, pkg, (selected ?: profiles.first()).id)
            }
        )
    }

    renameDialog?.let { mode ->
        var name by remember(mode) {
            mutableStateOf(if (mode == "rename") selected?.name.orEmpty() else (selected?.name.orEmpty() + if (mode == "dup") " (2)" else ""))
        }
        AlertDialog(
            onDismissRequest = { renameDialog = null },
            title = { Text(stringResource(if (mode == "rename") R.string.ona_overlay_rename else R.string.ona_overlay_new_profile)) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    val n = name.ifBlank { "Profile" }
                    when (mode) {
                        "rename" -> selected?.let { OverlayProfileManager.rename(ctx, it.id, n) }
                        "dup" -> selectedId = OverlayProfileManager.create(ctx, n, selected).id
                        else -> selectedId = OverlayProfileManager.create(ctx, n).id
                    }
                    renameDialog = null
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { renameDialog = null }) { Text(stringResource(android.R.string.cancel)) } }
        )
    }
}

@Composable
private fun StepNumber(n: Int, done: Boolean) {
    Box(
        Modifier.size(30.dp).background(
            if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, CircleShape
        ),
        contentAlignment = Alignment.Center
    ) {
        Text(if (done) "✓" else n.toString(), fontWeight = FontWeight.Bold,
            color = if (done) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One quick-start step: number (or ✓), title, short help, and its button while not done. */
@Composable
private fun StepRow(n: Int, title: String, desc: String, done: Boolean, action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        StepNumber(n, done)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(desc, style = MaterialTheme.typography.bodySmall)
        }
        if (!done) Button(onClick = onAction) { Text(action, maxLines = 1) }
    }
}

@Composable
private fun ChoiceRow(selected: Boolean, title: String, desc: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(desc, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PermRow(title: String, granted: Boolean, onGrant: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (granted) "✓" else "✗", color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.width(24.dp), fontWeight = FontWeight.Bold)
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (!granted) TextButton(onClick = onGrant) { Text(stringResource(R.string.ona_overlay_perm_grant)) }
    }
}

@Composable
private fun BackendRow(selected: Boolean, title: Int, summary: Int, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(summary), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ProfileDropdown(current: String, names: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text("$current  ▾", maxLines = 1) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            names.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onPick(id) })
            }
        }
    }
}

/** App Picker: every launchable app (icon, name, package) with search. */
@Composable
private fun AppPickerDialog(exclude: Set<String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    val all by produceState<List<LaunchableApp>?>(null) {
        value = withContext(Dispatchers.IO) { loadLaunchableApps(ctx) }
    }
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ona_overlay_add_app)) },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.ona_overlay_search)) })
                Spacer(Modifier.size(8.dp))
                val list = all
                if (list == null) {
                    Text("…")
                } else {
                    val q = query.trim().lowercase()
                    val shown = list.filter { it.pkg !in exclude && (q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.contains(q)) }
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(shown, key = { it.pkg }) { app ->
                            Row(Modifier.fillMaxWidth().clickable { onPick(app.pkg) }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                AppIcon(app.pkg, 36)
                                Column(Modifier.padding(start = 10.dp)) {
                                    Text(app.label, style = MaterialTheme.typography.bodyLarge)
                                    Text(app.pkg, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } }
    )
}
