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

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import com.movtery.zalithlauncher.game.addons.modloader.ModLoader
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.game.version.mod.CurseForgeModFinder
import com.movtery.zalithlauncher.game.version.mod.ModAutoResolver
import com.movtery.zalithlauncher.game.version.mod.ModCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the mods of this version have to fit, or null for vanilla/OptiFine-only versions */
fun Version.modEnvironment(): ModCompat.Environment? {
    val info = getVersionInfo() ?: return null
    val primary = info.primaryLoader ?: return null
    val loader = when (primary.loader) {
        ModLoader.FORGE -> "forge"
        ModLoader.NEOFORGE -> "neoforge"
        ModLoader.FABRIC, ModLoader.LEGACY_FABRIC, ModLoader.BABRIC -> "fabric"
        ModLoader.QUILT -> "quilt"
        else -> return null
    }
    return ModCompat.of(info.minecraftVersion, loader, primary.version.takeIf { it.isNotBlank() })
}

fun Version.modsDir(): File = File(getGameDir(), "mods")

/** Scan the mods folder off the main thread; empty when nothing is wrong or it is not modded */
suspend fun Version.findModProblems(): List<ModCompat.Problem> {
    val environment = modEnvironment() ?: return emptyList()
    return withContext(Dispatchers.IO) {
        runCatching { ModAutoResolver.analyzeWithSolution(this@findModProblems, modsDir(), environment) }.getOrDefault(emptyList())
    }
}

private fun describe(context: Context, problem: ModCompat.Problem): String = when (problem.kind) {
    ModCompat.PROBLEM_MISSING ->
        context.getString(R.string.mod_problem_missing, problem.modName, problem.requiredId)
    ModCompat.PROBLEM_BREAKS ->
        context.getString(R.string.mod_problem_breaks, problem.modName, problem.requiredId, problem.actualVersion)
    ModCompat.PROBLEM_WRONG_LOADER ->
        context.getString(R.string.mod_problem_wrong_loader, problem.modName, problem.requiredId, problem.actualVersion)
    else ->
        context.getString(R.string.mod_problem_version, problem.modName, problem.requiredId, problem.requiredRange, problem.actualVersion)
}

/**
 * Lists what would stop the game from loading the version's mods (missing dependencies, wrong
 * game or loader version) that the automatic resolver could not handle, with a Fix button for
 * each one (adds requirements or swaps in a build that fits, never turns a mod off).
 * @param onPlay launch anyway; null hides the button
 */
@Composable
fun ModProblemsDialog(
    version: Version,
    initialProblems: List<ModCompat.Problem>,
    onPlay: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val environment = remember(version) { version.modEnvironment() }
    var problems by remember { mutableStateOf(initialProblems) }
    var working by remember { mutableStateOf(false) }

    fun finish(remaining: List<ModCompat.Problem>, failed: Int) {
        working = false
        problems = remaining
        if (remaining.isEmpty()) {
            Toast.makeText(context, R.string.mod_problems_all_fixed, Toast.LENGTH_LONG).show()
            onDismiss()
            onPlay?.invoke()
        } else if (failed > 0) {
            Toast.makeText(context, R.string.mod_problems_some_failed, Toast.LENGTH_LONG).show()
        }
    }

    fun fixOne(problem: ModCompat.Problem) {
        val env = environment ?: return
        working = true
        scope.launch {
            val (remaining, failed) = withContext(Dispatchers.IO) {
                var result = ModCompat.fix(problem, version.modsDir(), env)
                if (result == ModCompat.FIX_FAILED && problem.kind == ModCompat.PROBLEM_MISSING &&
                    CurseForgeModFinder.install(problem.requiredId, version.modsDir(), env) != null
                ) {
                    result = ModCompat.FIX_DOWNLOADED
                }
                ModCompat.analyze(version.modsDir(), env) to if (result == ModCompat.FIX_FAILED) 1 else 0
            }
            finish(remaining, failed)
        }
    }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = {
            Text(text = stringResource(R.string.mod_problems_title), style = MaterialTheme.typography.titleLarge)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (working) stringResource(R.string.mod_problems_working)
                    else stringResource(R.string.mod_problems_header, problems.size)
                )
                if (working) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(problems) { problem ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = describe(context, problem),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            TextButton(
                                enabled = !working && environment != null,
                                onClick = { fixOne(problem) }
                            ) {
                                Text(text = stringResource(R.string.mod_problems_fix))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (onPlay != null) {
                Button(enabled = !working, onClick = {
                    onDismiss()
                    onPlay()
                }) {
                    Text(text = stringResource(R.string.mod_problems_play_anyway))
                }
            }
        },
        dismissButton = {
            FilledTonalButton(enabled = !working, onClick = onDismiss) {
                Text(text = stringResource(R.string.generic_cancel))
            }
        }
    )
}
