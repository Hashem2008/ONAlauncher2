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

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.game.version.mod.VoxyInstaller
import com.movtery.zalithlauncher.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ONA: one tap install of the plugin apps Voxy needs on a phone. Each one is downloaded from the
 * zalith-voxy release and opened in Android's installer; ONA picks them up after a restart.
 */
@Composable
fun VoxySetupDialog(
    missing: List<VoxyInstaller.Part>,
    onPlay: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloading = remember { mutableStateListOf<VoxyInstaller.Part>() }
    val opened = remember { mutableStateListOf<VoxyInstaller.Part>() }

    fun install(part: VoxyInstaller.Part) {
        if (part in downloading) return
        downloading.add(part)
        scope.launch {
            val apk = withContext(Dispatchers.IO) {
                runCatching { VoxyInstaller.download(context, part) }
                    .onFailure { Logger.warning("VoxySetupDialog", "Download failed: ${part.label}", it) }
                    .getOrNull()
            }
            downloading.remove(part)
            if (apk == null) {
                Toast.makeText(context, R.string.ona_voxy_download_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            runCatching { VoxyInstaller.openInstaller(context, apk) }
                .onSuccess { if (part !in opened) opened.add(part) }
                .onFailure { Toast.makeText(context, R.string.ona_voxy_download_failed, Toast.LENGTH_LONG).show() }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.ona_voxy_setup_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.ona_voxy_setup_body),
                    style = MaterialTheme.typography.bodyMedium
                )
                missing.forEach { part ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            modifier = Modifier.weight(1f),
                            text = part.label,
                            style = MaterialTheme.typography.bodySmall
                        )
                        when {
                            part in downloading -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            else -> TextButton(onClick = { install(part) }) {
                                Text(
                                    text = stringResource(
                                        if (part in opened) R.string.ona_voxy_install_again else R.string.ona_voxy_install
                                    )
                                )
                            }
                        }
                    }
                }
                if (opened.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.ona_voxy_restart_note),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { missing.forEach { install(it) } }) {
                Text(text = stringResource(R.string.ona_voxy_install_all))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = {
                    onDismiss()
                    onPlay()
                }) {
                    Text(text = stringResource(R.string.mod_problems_play_anyway))
                }
                FilledTonalButton(onClick = onDismiss) {
                    Text(text = stringResource(R.string.generic_cancel))
                }
            }
        }
    )
}

