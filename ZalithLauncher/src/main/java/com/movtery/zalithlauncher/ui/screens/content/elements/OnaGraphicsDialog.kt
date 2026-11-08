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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.game.graphics.OnaGraphics
import com.movtery.zalithlauncher.game.graphics.OnaGraphicsLevel
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.utils.device.DeviceProfiler
import com.movtery.zalithlauncher.utils.device.DeviceTier
import kotlinx.coroutines.launch

/**
 * ONA Graphics: pick a level (the one for this device is marked) or turn it off; the shader pack
 * and the PBR resource pack are installed and set together.
 */
@Composable
fun OnaGraphicsDialog(
    version: Version,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val auto = remember { OnaGraphics.autoLevel(DeviceProfiler.current()?.tier ?: DeviceTier.MID) }
    // null = off
    var choice by remember { mutableStateOf<OnaGraphicsLevel?>(auto) }
    var working by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<OnaGraphics.Result?>(null) }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(stringResource(R.string.ona_graphics_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val done = result
                when {
                    working -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                        Text(progress.ifEmpty { stringResource(R.string.ona_graphics_working) })
                    }
                    done != null -> done.lines.forEach { line ->
                        Text(text = "• $line", style = MaterialTheme.typography.bodyMedium)
                    }
                    else -> {
                        Text(
                            text = stringResource(R.string.ona_graphics_summary),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        (OnaGraphicsLevel.entries + listOf<OnaGraphicsLevel?>(null)).forEach { level ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { choice = level },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = choice == level, onClick = { choice = level })
                                val title = level?.let { stringResource(it.titleRes) }
                                    ?: stringResource(R.string.ona_graphics_off)
                                val mark = if (level == auto) "  " + stringResource(R.string.ona_graphics_recommended) else ""
                                Text(text = title + mark)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (result == null) {
                TextButton(
                    enabled = !working,
                    onClick = {
                        working = true
                        scope.launch {
                            result = OnaGraphics.apply(context, version, choice) { text -> progress = text }
                            working = false
                        }
                    }
                ) { Text(stringResource(R.string.ona_graphics_apply)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.generic_confirm)) }
            }
        },
        dismissButton = {
            if (result == null && !working) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.generic_cancel)) }
            }
        }
    )
}
