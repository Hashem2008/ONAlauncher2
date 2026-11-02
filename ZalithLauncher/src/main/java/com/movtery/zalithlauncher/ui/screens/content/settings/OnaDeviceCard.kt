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

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.game.renderer.renderers.SmartRenderer
import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.setting.enums.ResolutionRule
import com.movtery.zalithlauncher.ui.screens.content.settings.layouts.CardPosition
import com.movtery.zalithlauncher.ui.screens.content.settings.layouts.SettingsCard
import com.movtery.zalithlauncher.utils.device.DeviceProfile
import com.movtery.zalithlauncher.utils.device.DeviceProfiler
import com.movtery.zalithlauncher.utils.device.DeviceTier
import com.movtery.zalithlauncher.utils.device.PerformanceAdvisor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ONA: what the launcher found out about the phone, what runs comfortably on it and a button
 * that applies the settings that fit it.
 */
@Composable
fun OnaDeviceCard(
    modifier: Modifier = Modifier,
    position: CardPosition = CardPosition.Top
) {
    val context = LocalContext.current
    val profile by produceState<DeviceProfile?>(initialValue = DeviceProfiler.peek()) {
        value = withContext(Dispatchers.IO) { runCatching { DeviceProfiler.get(context) }.getOrNull() }
    }

    SettingsCard(modifier = modifier, position = position) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(all = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(text = stringResource(R.string.ona_device_title), style = MaterialTheme.typography.titleSmall)
            val current = profile
            if (current == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                return@Column
            }
            val tierText = stringResource(
                when (current.tier) {
                    DeviceTier.LOW -> R.string.ona_tier_low
                    DeviceTier.MID -> R.string.ona_tier_mid
                    DeviceTier.HIGH -> R.string.ona_tier_high
                    DeviceTier.FLAGSHIP -> R.string.ona_tier_flagship
                }
            )
            Text(
                text = stringResource(
                    R.string.ona_device_summary,
                    current.socName.ifBlank { "?" },
                    current.gpuName,
                    (current.ramMb + 512) / 1024,
                    tierText,
                    current.score
                ),
                style = MaterialTheme.typography.bodySmall
            )

            val recommended = PerformanceAdvisor.recommended(current)
            val driver = if (current.turnipRecommended) "Turnip" else stringResource(R.string.ona_driver_system)
            val shaders = stringResource(
                when (recommended.shaderLevel) {
                    0 -> R.string.ona_shaders_none
                    1 -> R.string.ona_shaders_potato
                    2 -> R.string.ona_shaders_low
                    3 -> R.string.ona_shaders_medium
                    else -> R.string.ona_shaders_high
                }
            )
            val upscaler = stringResource(recommended.upscaler.nameRes)
            Text(
                text = stringResource(
                    R.string.ona_device_recommended,
                    recommended.resolutionPercent,
                    upscaler,
                    driver,
                    recommended.renderDistance,
                    recommended.comfortableMods,
                    shaders
                ),
                style = MaterialTheme.typography.bodySmall
            )

            Button(
                onClick = {
                    AllSettings.renderer.save(SmartRenderer.ID)
                    AllSettings.smartDriver.save(true)
                    AllSettings.resolutionRule.save(ResolutionRule.PERCENTAGE)
                    AllSettings.resolutionRatio.save(recommended.resolutionPercent)
                    AllSettings.upscalerMode.save(recommended.upscaler)
                    AllSettings.gameFpsCap.save(recommended.fpsCap)
                    AllSettings.gameDisableVsync.save(true)
                    AllSettings.autoTuneMemory.save(true)
                    AllSettings.onaRenderDistance.save(recommended.renderDistance)
                    Toast.makeText(context, R.string.ona_device_applied, Toast.LENGTH_SHORT).show()
                }
            ) {
                Text(text = stringResource(R.string.ona_device_apply))
            }
        }
    }
}
