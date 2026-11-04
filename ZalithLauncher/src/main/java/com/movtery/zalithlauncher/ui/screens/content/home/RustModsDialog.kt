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

package com.movtery.zalithlauncher.ui.screens.content.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.game.horizon.HorizonMods
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class RustFilter(val label: Int) {
    PHONE(R.string.ona_rust_mods_filter_phone),
    MODS(R.string.ona_rust_mods_filter_mods),
    MODPACKS(R.string.ona_rust_mods_filter_modpacks),
    SHADERS(R.string.ona_rust_mods_filter_shaders),
    PACKS(R.string.ona_rust_mods_filter_packs),
    PC(R.string.ona_rust_mods_filter_pc),
    ALL(R.string.ona_rust_mods_filter_all);

    fun accepts(kind: HorizonMods.Kind): Boolean = when (this) {
        PHONE -> kind.runsOnPhone
        MODS -> kind == HorizonMods.Kind.MOD
        MODPACKS -> kind == HorizonMods.Kind.MODPACK
        SHADERS -> kind == HorizonMods.Kind.SHADER
        PACKS -> kind == HorizonMods.Kind.RESOURCE_PACK
        PC -> kind == HorizonMods.Kind.NATIVE_MOD
        ALL -> true
    }
}

private fun openLink(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun kindLabel(kind: HorizonMods.Kind): Int = when (kind) {
    HorizonMods.Kind.MOD -> R.string.ona_rust_mods_kind_mod
    HorizonMods.Kind.NATIVE_MOD -> R.string.ona_rust_mods_kind_native
    HorizonMods.Kind.MODPACK -> R.string.ona_rust_mods_kind_modpack
    HorizonMods.Kind.SHADER -> R.string.ona_rust_mods_kind_shader
    HorizonMods.Kind.RESOURCE_PACK -> R.string.ona_rust_mods_kind_pack
}

/**
 * ONA: the Rust (Horizon) list. Mods, modpacks, shader packs and resource packs made for Horizon,
 * from Horizon's reviewed registry, with what runs on the phone first. Java mods are not here:
 * Horizon does not run them.
 */
@Composable
fun RustModsDialog(onDismiss: () -> Unit, initialKind: HorizonMods.Kind? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf<List<HorizonMods.Project>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var filter by remember {
        mutableStateOf(
            initialKind?.let { kind ->
                RustFilter.entries.firstOrNull { it != RustFilter.PHONE && it != RustFilter.ALL && it.accepts(kind) }
            } ?: RustFilter.PHONE
        )
    }
    var query by remember { mutableStateOf("") }
    val busy = remember { mutableStateMapOf<String, Boolean>() }
    val installed = remember { mutableStateMapOf<String, Boolean>() }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reload) {
        error = null
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val list = HorizonMods.projects(refresh = reload > 0)
                list to list.associate { it.id to runCatching { HorizonMods.isInstalled(context, it) }.getOrDefault(false) }
            }
        }
        result.onSuccess { (list, state) ->
            installed.putAll(state)
            projects = list
        }.onFailure { error = it.message ?: it.toString() }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.ona_rust_mods_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            stringResource(R.string.ona_rust_mods_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { openLink(context, HorizonMods.WEBSITE) }) { Text("playhorizon.dev") }
                    TextButton(onClick = { reload++ }) { Text(stringResource(R.string.ona_rust_mods_refresh)) }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.ona_rust_mods_close)) }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        modifier = Modifier.width(220.dp),
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.ona_rust_mods_search)) },
                    )
                    Spacer(Modifier.width(8.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        RustFilter.entries.forEach { value ->
                            FilterChip(
                                selected = filter == value,
                                onClick = { filter = value },
                                label = { Text(stringResource(value.label)) },
                            )
                        }
                    }
                }
                message?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                val list = projects
                when {
                    error != null && list == null -> Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(stringResource(R.string.ona_rust_mods_error, error ?: ""), color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.size(8.dp))
                        Button(onClick = { reload++ }) { Text(stringResource(R.string.ona_rust_mods_refresh)) }
                    }
                    list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    else -> {
                        val shown = list
                            .filter { filter.accepts(it.kind) }
                            .filter {
                                query.isBlank() || it.name.contains(query, true) || it.summary.contains(query, true) || it.id.contains(query, true)
                            }
                            .sortedWith(compareByDescending<HorizonMods.Project> { it.kind.runsOnPhone }.thenByDescending { it.downloads })
                        if (shown.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    stringResource(if (filter == RustFilter.MODPACKS) R.string.ona_rust_mods_no_modpacks else R.string.ona_rust_mods_empty),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(shown, key = { it.kind.id + ":" + it.id }) { project ->
                                RustModRow(
                                    project = project,
                                    installed = installed[project.id] == true,
                                    busy = busy[project.id] == true,
                                    onInstall = {
                                        busy[project.id] = true
                                        scope.launch {
                                            val result = withContext(Dispatchers.IO) { runCatching { HorizonMods.install(context, project) } }
                                            busy[project.id] = false
                                            result.onSuccess {
                                                installed[project.id] = true
                                                message = context.getString(R.string.ona_rust_mods_installed, project.name)
                                            }.onFailure {
                                                message = context.getString(R.string.ona_rust_mods_install_failed, project.name, it.message ?: "")
                                            }
                                        }
                                    },
                                    onOpen = { (project.source ?: HorizonMods.WEBSITE).let { openLink(context, it) } },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RustModRow(
    project: HorizonMods.Project,
    installed: Boolean,
    busy: Boolean,
    onInstall: () -> Unit,
    onOpen: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable(onClick = onOpen)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (project.icon != null) {
                AsyncImage(model = project.icon, contentDescription = null, modifier = Modifier.fillMaxSize())
            } else {
                Text(project.name.take(1).uppercase(), fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(project.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(kindLabel(project.kind)),
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (project.kind.runsOnPhone) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.tertiaryContainer
                        )
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (project.summary.isNotEmpty()) {
                Text(project.summary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(
                listOfNotNull(
                    project.version,
                    project.game?.let { "MC $it" },
                    project.authors.takeIf { it.isNotEmpty() }?.joinToString(),
                    if (project.downloads > 0) "↓ ${project.downloads}" else null,
                ).joinToString("  •  "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        when {
            !project.kind.runsOnPhone -> OutlinedButton(onClick = onOpen) { Text(stringResource(R.string.ona_rust_mods_pc_only)) }
            busy -> CircularProgressIndicator(modifier = Modifier.size(28.dp))
            installed -> OutlinedButton(onClick = onInstall) { Text(stringResource(R.string.ona_rust_mods_reinstall)) }
            else -> Button(onClick = onInstall) { Text(stringResource(R.string.ona_rust_mods_install)) }
        }
    }
}
