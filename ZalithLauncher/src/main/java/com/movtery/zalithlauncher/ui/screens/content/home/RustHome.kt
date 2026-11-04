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

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.game.horizon.HorizonEngine
import com.movtery.zalithlauncher.game.horizon.HorizonMods
import com.movtery.zalithlauncher.game.horizon.HorizonSettings
import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.setting.unit.floatRange
import com.movtery.zalithlauncher.ui.screens.content.settings.layouts.CardPosition
import com.movtery.zalithlauncher.ui.screens.content.settings.layouts.IntSliderSettingsCard
import com.movtery.zalithlauncher.ui.screens.content.settings.layouts.SwitchSettingsCard
import com.movtery.zalithlauncher.utils.file.shareFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Horizon's colours: a dark steel page with a rust orange accent */
private val RustAccent = Color(0xFFE2622B)
private val RustAccentLight = Color(0xFFFF9A5C)
private val RustBackground = Brush.linearGradient(listOf(Color(0xFF15100E), Color(0xFF231915), Color(0xFF120F10)))
private val RustCard = Color(0xFF2A201C)
private val RustCardBorder = Color(0xFF4A3329)
private val RustText = Color(0xFFF3E9E4)
private val RustTextDim = Color(0xFFB9A69C)
private val Good = Color(0xFF5BD68A)
private val Bad = Color(0xFFFF6B6B)

private enum class RustDialog { NONE, MODS, SHADERS, PACKS, MODPACKS, CONTROLS, ABOUT }

/**
 * ONA: the Rust engine's own home, shown instead of the Java home while the engine switch is on
 * Rust. Everything here is Horizon's (Minecraft 26.3 rewritten in Rust): its status, Play, its
 * mods, shader and resource packs, its touch controls and its logs. The switch at the bottom
 * goes back to Java.
 */
@Composable
fun RustHome(
    modifier: Modifier = Modifier,
    openSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { HorizonEngine.state(context) }
    val java25 = remember { HorizonEngine.hasJava25() }
    var dialog by remember { mutableStateOf(RustDialog.NONE) }

    Box(modifier = modifier.background(RustBackground)) {
        Row(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Left: what Horizon is, whether it can run, Play
            Column(
                modifier = Modifier.weight(0.42f).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "HORIZON",
                        color = RustText,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 3.sp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "RUST",
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(RustAccent)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                    )
                }
                Text(
                    text = stringResource(R.string.ona_rust_home_subtitle),
                    color = RustTextDim,
                    style = MaterialTheme.typography.bodyMedium,
                )

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = RustCard,
                    border = BorderStroke(1.dp, RustCardBorder),
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusLine(
                            ok = state == HorizonEngine.State.READY,
                            text = stringResource(if (state == HorizonEngine.State.READY) R.string.ona_rust_home_ready else state.textRes),
                        )
                        StatusLine(
                            ok = state != HorizonEngine.State.NEEDS_VULKAN && state != HorizonEngine.State.NEEDS_ARM64,
                            text = stringResource(R.string.ona_rust_home_vulkan),
                        )
                        StatusLine(
                            ok = java25,
                            text = stringResource(if (java25) R.string.ona_rust_home_java_ok else R.string.ona_rust_home_java_missing),
                        )
                        StatusLine(
                            ok = AllSettings.rustTouchControls.state,
                            text = stringResource(if (AllSettings.rustTouchControls.state) R.string.ona_rust_home_touch_on else R.string.ona_rust_home_touch_off),
                        )
                    }
                }

                // Play
                val ready = state == HorizonEngine.State.READY
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (ready) Brush.horizontalGradient(listOf(RustAccent, RustAccentLight))
                            else Brush.horizontalGradient(listOf(Color(0xFF4A403C), Color(0xFF5A4E49)))
                        )
                        .clickable {
                            HorizonEngine.launch(context)?.let { reason ->
                                Toast.makeText(context, context.getString(reason.textRes), Toast.LENGTH_LONG).show()
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.ic_play_arrow_filled),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(30.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.ona_rust_home_play),
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.ona_rust_home_play_hint),
                    color = RustTextDim,
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.height(4.dp))
                EngineSwitch(modifier = Modifier.align(Alignment.CenterHorizontally))
            }

            // Right: everything for Horizon
            Column(
                modifier = Modifier.weight(0.58f).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SectionTitle(stringResource(R.string.ona_rust_home_content))
                TileRow(
                    Tile(R.drawable.ic_extension_outlined, R.string.ona_rust_mods_filter_mods, R.string.ona_rust_home_mods_hint) { dialog = RustDialog.MODS },
                    Tile(R.drawable.ic_package_2_outlined, R.string.ona_rust_mods_filter_modpacks, R.string.ona_rust_home_modpacks_hint) { dialog = RustDialog.MODPACKS },
                )
                TileRow(
                    Tile(R.drawable.ic_video_settings, R.string.ona_rust_mods_filter_shaders, R.string.ona_rust_home_shaders_hint) { dialog = RustDialog.SHADERS },
                    Tile(R.drawable.ic_image_outlined, R.string.ona_rust_mods_filter_packs, R.string.ona_rust_home_packs_hint) { dialog = RustDialog.PACKS },
                )
                SectionTitle(stringResource(R.string.ona_rust_home_game))
                TileRow(
                    Tile(R.drawable.ic_touch_app_outlined, R.string.ona_rust_home_controls, R.string.ona_rust_home_controls_hint) { dialog = RustDialog.CONTROLS },
                    Tile(R.drawable.ic_article_outlined, R.string.ona_rust_home_logs, R.string.ona_rust_home_logs_hint) {
                        scope.launch {
                            val file = withContext(Dispatchers.IO) { HorizonSettings.exportLogs(context) }
                            if (file == null) {
                                Toast.makeText(context, R.string.ona_rust_home_logs_failed, Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, context.getString(R.string.ona_rust_home_logs_saved, file.absolutePath), Toast.LENGTH_LONG).show()
                                runCatching { shareFile(context, file) }
                            }
                        }
                    },
                )
                TileRow(
                    Tile(R.drawable.ic_settings_filled, R.string.ona_rust_home_launcher_settings, R.string.ona_rust_home_launcher_settings_hint, openSettings),
                    Tile(R.drawable.ic_info_outlined, R.string.ona_rust_home_about, R.string.ona_rust_home_about_hint) { dialog = RustDialog.ABOUT },
                )
            }
        }
    }

    when (dialog) {
        RustDialog.MODS -> RustModsDialog(onDismiss = { dialog = RustDialog.NONE }, initialKind = HorizonMods.Kind.MOD)
        RustDialog.MODPACKS -> RustModsDialog(onDismiss = { dialog = RustDialog.NONE }, initialKind = HorizonMods.Kind.MODPACK)
        RustDialog.SHADERS -> RustModsDialog(onDismiss = { dialog = RustDialog.NONE }, initialKind = HorizonMods.Kind.SHADER)
        RustDialog.PACKS -> RustModsDialog(onDismiss = { dialog = RustDialog.NONE }, initialKind = HorizonMods.Kind.RESOURCE_PACK)
        RustDialog.CONTROLS -> RustControlsDialog(onDismiss = { dialog = RustDialog.NONE })
        RustDialog.ABOUT -> RustAboutDialog(onDismiss = { dialog = RustDialog.NONE })
        RustDialog.NONE -> Unit
    }
}

@Composable
private fun StatusLine(ok: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(if (ok) Good else Bad))
        Spacer(Modifier.width(8.dp))
        Text(text = text, color = RustText, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text.uppercase(),
        color = RustAccentLight,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp,
        modifier = Modifier.padding(top = 2.dp),
    )
}

private class Tile(val icon: Int, val title: Int, val hint: Int, val onClick: () -> Unit)

@Composable
private fun TileRow(vararg tiles: Tile) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.forEach { tile ->
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(84.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(onClick = tile.onClick),
                shape = RoundedCornerShape(14.dp),
                color = RustCard,
                border = BorderStroke(1.dp, RustCardBorder),
            ) {
                Row(modifier = Modifier.fillMaxSize().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(RustAccent.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(painterResource(tile.icon), contentDescription = null, tint = RustAccentLight, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(stringResource(tile.title), color = RustText, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(tile.hint),
                            color = RustTextDim,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** Horizon's touch controls, written to its horizon.txt when Play starts it */
@Composable
private fun RustControlsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.ona_rust_home_controls), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            stringResource(R.string.ona_rust_controls_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.ona_rust_mods_close)) }
                }
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    SwitchSettingsCard(
                        modifier = Modifier.fillMaxWidth(),
                        position = CardPosition.Top,
                        unit = AllSettings.rustTouchControls,
                        title = stringResource(R.string.ona_rust_controls_touch),
                        summary = stringResource(R.string.ona_rust_controls_touch_summary),
                    )
                    IntSliderSettingsCard(
                        modifier = Modifier.fillMaxWidth(),
                        position = CardPosition.Middle,
                        unit = AllSettings.rustTouchSize,
                        title = stringResource(R.string.ona_rust_controls_size),
                        summary = stringResource(R.string.ona_rust_controls_size_summary),
                        valueRange = AllSettings.rustTouchSize.floatRange,
                        suffix = "%",
                    )
                    IntSliderSettingsCard(
                        modifier = Modifier.fillMaxWidth(),
                        position = CardPosition.Middle,
                        unit = AllSettings.rustTouchOpacity,
                        title = stringResource(R.string.ona_rust_controls_opacity),
                        valueRange = AllSettings.rustTouchOpacity.floatRange,
                        suffix = "%",
                    )
                    SwitchSettingsCard(
                        modifier = Modifier.fillMaxWidth(),
                        position = CardPosition.Middle,
                        unit = AllSettings.rustTouchGestures,
                        title = stringResource(R.string.ona_rust_controls_gestures),
                        summary = stringResource(R.string.ona_rust_controls_gestures_summary),
                    )
                    SwitchSettingsCard(
                        modifier = Modifier.fillMaxWidth(),
                        position = CardPosition.Middle,
                        unit = AllSettings.rustTouchGyroscope,
                        title = stringResource(R.string.ona_rust_controls_gyroscope),
                        summary = stringResource(R.string.ona_rust_controls_gyroscope_summary),
                    )
                    SwitchSettingsCard(
                        modifier = Modifier.fillMaxWidth(),
                        position = CardPosition.Bottom,
                        unit = AllSettings.rustLargerInterface,
                        title = stringResource(R.string.ona_rust_controls_larger),
                        summary = stringResource(R.string.ona_rust_controls_larger_summary),
                    )
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            HorizonSettings.resetMenuBall(context)
                            Toast.makeText(context, R.string.ona_rust_controls_done, Toast.LENGTH_SHORT).show()
                        }) { Text(stringResource(R.string.ona_rust_controls_reset_ball)) }
                        TextButton(onClick = {
                            HorizonSettings.resetLayout(context)
                            Toast.makeText(context, R.string.ona_rust_controls_done, Toast.LENGTH_SHORT).show()
                        }) { Text(stringResource(R.string.ona_rust_controls_reset_layout)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RustAboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.ona_rust_home_about), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.ona_rust_about_text), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://playhorizon.dev")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }) { Text("playhorizon.dev") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.ona_rust_mods_close)) }
                }
            }
        }
    }
}
