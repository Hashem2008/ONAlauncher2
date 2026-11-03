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

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.setting.unit.floatRange
import com.movtery.zalithlauncher.ui.screens.content.elements.DisabledAlpha
import com.movtery.zalithlauncher.ui.screens.content.settings.layouts.CardPosition
import com.movtery.zalithlauncher.ui.screens.content.settings.layouts.IntSliderSettingsCard
import com.movtery.zalithlauncher.ui.screens.content.settings.layouts.SettingsCard
import com.movtery.zalithlauncher.ui.screens.content.settings.layouts.SwitchSettingsCard
import com.movtery.zalithlauncher.ui.theme.LauncherStyle

/**
 * ONA: "Launcher style" picker, a folding list of every [LauncherStyle] with a colour dot, plus
 * corner / text size / font controls when Custom is picked.
 */
@Composable
fun LauncherStyleCard(
    modifier: Modifier = Modifier,
) {
    val selected = AllSettings.launcherStyle.state
    var expanded by rememberSaveable { mutableStateOf(true) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SettingsCard(
            modifier = Modifier.fillMaxWidth(),
            position = if (selected == LauncherStyle.CUSTOM) CardPosition.Top else CardPosition.Single,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .padding(all = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(text = stringResource(R.string.ona_style_title), style = MaterialTheme.typography.titleSmall)
                        Text(text = stringResource(R.string.ona_style_summary), style = MaterialTheme.typography.labelSmall)
                        Text(
                            text = stringResource(R.string.ona_style_selected, stringResource(selected.titleRes)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(text = if (expanded) "▲" else "▼", style = MaterialTheme.typography.labelMedium)
                }
                AnimatedVisibility(visible = expanded) {
                    Column(modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, bottom = 12.dp)) {
                        LauncherStyle.entries.forEach { style ->
                            val enabled = !style.dynamic || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = enabled) { AllSettings.launcherStyle.save(style) }
                                    .alpha(if (enabled) 1f else DisabledAlpha),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = style == selected,
                                    enabled = enabled,
                                    onClick = { AllSettings.launcherStyle.save(style) }
                                )
                                Text(
                                    text = stringResource(style.titleRes),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                StyleDot(style)
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                        }
                    }
                }
            }
        }

        if (selected == LauncherStyle.CUSTOM) {
            IntSliderSettingsCard(
                modifier = Modifier.fillMaxWidth(),
                position = CardPosition.Middle,
                unit = AllSettings.launcherStyleRadius,
                title = stringResource(R.string.ona_style_custom_radius),
                valueRange = AllSettings.launcherStyleRadius.floatRange,
                suffix = "dp"
            )
            IntSliderSettingsCard(
                modifier = Modifier.fillMaxWidth(),
                position = CardPosition.Middle,
                unit = AllSettings.launcherStyleFontScale,
                title = stringResource(R.string.ona_style_custom_font),
                valueRange = AllSettings.launcherStyleFontScale.floatRange,
                suffix = "%"
            )
            SwitchSettingsCard(
                modifier = Modifier.fillMaxWidth(),
                position = CardPosition.Bottom,
                unit = AllSettings.launcherStyleMono,
                title = stringResource(R.string.ona_style_custom_mono)
            )
        }
    }
}

/** Little preview of the style: its main colour (or the current one) in its corner shape */
@Composable
private fun StyleDot(style: LauncherStyle) {
    val color = style.seed ?: MaterialTheme.colorScheme.primary
    val background = when {
        style.pureBlack -> Color.Black
        style.glass -> color.copy(alpha = 0.4f)
        else -> color
    }
    Spacer(
        modifier = Modifier
            .size(18.dp)
            .background(background, CircleShape)
            .border(width = 2.dp, color = color, shape = CircleShape)
    )
}
