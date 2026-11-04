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

package com.movtery.zalithlauncher.ui.screens.content.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.ui.screens.content.settings.layouts.CardPosition
import com.movtery.zalithlauncher.ui.screens.content.settings.layouts.SettingsCard
import com.movtery.zalithlauncher.utils.device.DeviceProfiler
import com.movtery.zalithlauncher.utils.device.DeviceTier
import com.movtery.zalithlauncher.utils.device.WishAdvisor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ONA: the player writes what they want (kind of pack, version, loader, style) and gets
 * Modrinth modpacks, mods or shader packs that fit the wish and this phone, smooth ones first.
 */
@Composable
fun OnaAdvisorCard(
    modifier: Modifier = Modifier,
    position: CardPosition = CardPosition.Middle
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var wish by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(WishAdvisor.Kind.MODPACK) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<WishAdvisor.Suggestion>?>(null) }

    SettingsCard(modifier = modifier, position = position) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(all = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = stringResource(R.string.ona_advisor_title), style = MaterialTheme.typography.titleSmall)
            Text(text = stringResource(R.string.ona_advisor_summary), style = MaterialTheme.typography.bodySmall)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    WishAdvisor.Kind.MODPACK to R.string.ona_advisor_modpacks,
                    WishAdvisor.Kind.MOD to R.string.ona_advisor_mods,
                    WishAdvisor.Kind.SHADER to R.string.ona_advisor_shaders,
                ).forEach { (value, label) ->
                    FilterChip(
                        selected = kind == value,
                        onClick = { kind = value },
                        label = { Text(stringResource(label)) }
                    )
                }
            }

            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = wish,
                onValueChange = { wish = it },
                label = { Text(stringResource(R.string.ona_advisor_hint)) },
                singleLine = false,
                maxLines = 3
            )

            Button(
                enabled = !loading && wish.isNotBlank(),
                onClick = {
                    loading = true
                    failed = false
                    scope.launch {
                        val found = withContext(Dispatchers.IO) {
                            runCatching {
                                val tier = runCatching { DeviceProfiler.get(context).tier }.getOrDefault(DeviceTier.HIGH)
                                WishAdvisor.suggest(wish, kind, tier)
                            }.getOrNull()
                        }
                        failed = found == null
                        results = found
                        loading = false
                    }
                }
            ) {
                Text(stringResource(R.string.ona_advisor_button))
            }

            if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (failed) {
                Text(
                    text = stringResource(R.string.ona_advisor_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            results?.let { list ->
                if (list.isEmpty()) {
                    Text(text = stringResource(R.string.ona_advisor_none), style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(text = stringResource(R.string.ona_advisor_install_hint), style = MaterialTheme.typography.bodySmall)
                }
                list.forEach { suggestion ->
                    HorizontalDivider()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { runCatching { uriHandler.openUri(suggestion.url) } }
                            .padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = suggestion.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(suggestion.rating.textRes),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = suggestion.description,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
