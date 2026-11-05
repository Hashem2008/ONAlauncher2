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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.game.launch.LogTranslator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ONA: "Explain log" - the launcher log and the game log of a version, translated into plain
 * words by [LogTranslator]: problems first, then the lines that only look scary.
 */
@Composable
fun LogTranslatorDialog(
    logs: List<File>,
    onDismiss: () -> Unit
) {
    var findings by remember { mutableStateOf<List<LogTranslator.Finding>?>(null) }
    LaunchedEffect(logs) {
        findings = withContext(Dispatchers.IO) {
            val text = logs.filter { it.isFile }.joinToString("\n") { LogTranslator.readTail(it) }
            LogTranslator.translate(text)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.ona_log_translator_title), style = MaterialTheme.typography.titleLarge)
        },
        text = {
            val list = findings
            when {
                list == null -> CircularProgressIndicator()
                list.isEmpty() -> Text(text = stringResource(R.string.ona_log_translator_nothing))
                else -> LazyColumn(
                    modifier = Modifier.heightIn(max = 480.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(list) { finding -> FindingCard(finding) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.ok))
            }
        }
    )
}

@Composable
private fun FindingCard(finding: LogTranslator.Finding) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (finding.level) {
        LogTranslator.Level.PROBLEM -> colors.errorContainer to colors.onErrorContainer
        LogTranslator.Level.WARNING -> colors.tertiaryContainer to colors.onTertiaryContainer
        LogTranslator.Level.NORMAL -> colors.surfaceVariant to colors.onSurfaceVariant
    }
    val label = when (finding.level) {
        LogTranslator.Level.PROBLEM -> stringResource(R.string.ona_log_translator_problem)
        LogTranslator.Level.WARNING -> stringResource(R.string.ona_log_translator_warning)
        LogTranslator.Level.NORMAL -> stringResource(R.string.ona_log_translator_normal)
    }
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.medium) {
        Column(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "$label · ${finding.title}" + if (finding.count > 1) " (×${finding.count})" else "",
                style = MaterialTheme.typography.titleSmall
            )
            Text(text = finding.meaning, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = finding.line,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 3
            )
        }
    }
}
