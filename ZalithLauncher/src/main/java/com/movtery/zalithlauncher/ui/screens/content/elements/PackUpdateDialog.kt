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

package com.movtery.zalithlauncher.ui.screens.content.elements

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.game.download.modpack.install.ModPackInstaller
import com.movtery.zalithlauncher.game.download.modpack.update.PackUpdater
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.game.version.installed.VersionsManager
import com.movtery.zalithlauncher.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ONA: "Update modpack" on the version page. Checks the newest release of the pack, installs it
 * as a new version and moves the player's worlds, settings, server list and own mods over.
 * The old version stays as it is.
 */
@Composable
fun PackUpdateDialog(
    version: Version,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var retry by remember { mutableIntStateOf(0) }
    var check by remember { mutableStateOf<PackUpdater.Check?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var installer by remember { mutableStateOf<ModPackInstaller?>(null) }
    var newName by remember { mutableStateOf<String?>(null) }
    var migration by remember { mutableStateOf<PackUpdater.Migration?>(null) }
    var moving by remember { mutableStateOf(false) }

    LaunchedEffect(retry) {
        check = null
        error = null
        runCatching { PackUpdater.check(version) }
            .onSuccess { check = it }
            .onFailure {
                Logger.warning("PackUpdateDialog", "Pack check failed", it)
                error = it.message ?: it.toString()
            }
    }

    // installing: the normal modpack task list
    val running = installer
    if (running != null && migration == null && error == null) {
        val tasks = running.tasksFlow.collectAsStateWithLifecycle()
        val log = running.logOutput.collectAsStateWithLifecycle()
        if (tasks.value.isNotEmpty()) {
            TitleTaskFlowDialog(
                title = stringResource(R.string.ona_pack_update_installing),
                tasks = tasks.value,
                onCancel = {
                    running.cancelInstall()
                    installer = null
                },
                logOutput = log.value
            )
            return
        }
    }

    fun startUpdate(found: PackUpdater.Check) {
        val versionsDir = version.getVersionPath().parentFile ?: return
        val name = PackUpdater.newVersionName(versionsDir, version.getVersionName(), found.latestLabel)
        newName = name
        val inst = ModPackInstaller(
            context = context,
            version = found.latest,
            iconUrl = found.iconUrl,
            scope = scope,
            waitForVersionName = { name },
            waitForConfirmMobileData = { true }
        )
        installer = inst
        inst.installModPack(
            onInstalled = { installedName ->
                scope.launch {
                    moving = true
                    val result = runCatching {
                        PackUpdater.migrate(
                            oldDir = version.getGameDir(),
                            newDir = File(versionsDir, installedName)
                        )
                    }
                    moving = false
                    result.onSuccess { migration = it }
                        .onFailure { error = it.message ?: it.toString() }
                    withContext(Dispatchers.Main) {
                        VersionsManager.refresh("[Modpack] PackUpdateDialog", installedName)
                    }
                }
            },
            onCancelled = { installer = null },
            onError = { th ->
                Logger.warning("PackUpdateDialog", "Pack update failed", th)
                error = th.message ?: th.toString()
                installer = null
            }
        )
    }

    val busy = moving || (installer != null && migration == null && error == null)
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.ona_pack_update_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val found = check
                val done = migration
                val err = error
                when {
                    done != null -> {
                        Text(stringResource(R.string.ona_pack_update_done, newName ?: ""))
                        Text("• " + stringResource(R.string.ona_pack_update_moved_worlds, done.worlds))
                        Text("• " + stringResource(R.string.ona_pack_update_moved_settings, done.settings))
                        Text("• " + stringResource(R.string.ona_pack_update_moved_mods, done.mods))
                        Text("• " + stringResource(R.string.ona_pack_update_moved_packs, done.packs + done.other))
                        if (done.modsUnknown) {
                            Text(
                                text = stringResource(R.string.ona_pack_update_mods_unknown),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Text(
                            text = stringResource(R.string.ona_pack_update_old_kept, version.getVersionName()),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    err != null -> {
                        Text(stringResource(R.string.ona_pack_update_failed))
                        Text(text = err, style = MaterialTheme.typography.bodySmall)
                    }
                    moving || installer != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                        Text(stringResource(R.string.ona_pack_update_moving))
                    }
                    found == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                        Text(stringResource(R.string.ona_pack_update_checking))
                    }
                    else -> {
                        Text(text = found.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(
                                R.string.ona_pack_update_installed,
                                found.installed.ifEmpty { stringResource(R.string.ona_pack_update_unknown) }
                            )
                        )
                        Text(stringResource(R.string.ona_pack_update_latest, found.latestLabel, found.latestMinecraft))
                        Text(
                            text = when (found.upToDate) {
                                true -> stringResource(R.string.ona_pack_update_up_to_date)
                                false -> stringResource(R.string.ona_pack_update_available)
                                null -> stringResource(R.string.ona_pack_update_maybe)
                            },
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = stringResource(R.string.ona_pack_update_how),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = stringResource(R.string.ona_pack_update_server_hint),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            val found = check
            when {
                migration != null -> TextButton(onClick = onDismiss) { Text(stringResource(R.string.generic_confirm)) }
                error != null -> TextButton(onClick = { installer = null; retry++ }) {
                    Text(stringResource(R.string.ona_pack_update_retry))
                }
                found != null && !busy -> TextButton(onClick = { startUpdate(found) }) {
                    Text(
                        stringResource(
                            if (found.upToDate == true) R.string.ona_pack_update_reinstall
                            else R.string.ona_pack_update_button
                        )
                    )
                }
                else -> {}
            }
        },
        dismissButton = {
            if (!busy && migration == null) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.generic_cancel)) }
            }
        }
    )
}
