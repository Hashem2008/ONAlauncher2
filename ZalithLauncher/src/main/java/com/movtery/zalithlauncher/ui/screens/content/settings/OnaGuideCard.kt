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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.ui.screens.content.settings.layouts.CardPosition
import com.movtery.zalithlauncher.ui.screens.content.settings.layouts.SettingsCard

/** One feature: what it does and how to use it */
private class GuideEntry(val title: Int, val what: Int, val how: Int)

private val GUIDE = listOf(
    GuideEntry(R.string.ona_guide_hybrid_title, R.string.ona_guide_hybrid_what, R.string.ona_guide_hybrid_how),
    GuideEntry(R.string.ona_guide_upscaler_title, R.string.ona_guide_upscaler_what, R.string.ona_guide_upscaler_how),
    GuideEntry(R.string.ona_guide_device_title, R.string.ona_guide_device_what, R.string.ona_guide_device_how),
    GuideEntry(R.string.ona_guide_best_title, R.string.ona_guide_best_what, R.string.ona_guide_best_how),
    GuideEntry(R.string.ona_guide_mods_title, R.string.ona_guide_mods_what, R.string.ona_guide_mods_how),
    GuideEntry(R.string.ona_guide_shaders_title, R.string.ona_guide_shaders_what, R.string.ona_guide_shaders_how),
    GuideEntry(R.string.ona_guide_deps_title, R.string.ona_guide_deps_what, R.string.ona_guide_deps_how),
    GuideEntry(R.string.ona_guide_crash_title, R.string.ona_guide_crash_what, R.string.ona_guide_crash_how),
)

/**
 * ONA: a short, plain guide to what the launcher does by itself and how to use each feature.
 * Closed by default, a tap opens it.
 */
@Composable
fun OnaGuideCard(
    modifier: Modifier = Modifier,
    position: CardPosition = CardPosition.Top
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    SettingsCard(modifier = modifier, position = position) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(all = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(text = stringResource(R.string.ona_guide_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = stringResource(R.string.ona_guide_summary),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(text = if (expanded) "▲" else "▼", style = MaterialTheme.typography.titleSmall)
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GUIDE.forEachIndexed { index, entry ->
                        if (index > 0) HorizontalDivider()
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(entry.title),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(text = stringResource(entry.what), style = MaterialTheme.typography.bodySmall)
                            Text(
                                text = stringResource(R.string.ona_guide_how_prefix) + " " + stringResource(entry.how),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}
