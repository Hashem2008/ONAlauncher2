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

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.game.account.Account
import com.movtery.zalithlauncher.game.account.AccountsManager
import com.movtery.zalithlauncher.game.horizon.HorizonEngine
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.game.version.installed.VersionsManager
import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.setting.enums.GameEngine
import com.movtery.zalithlauncher.ui.screens.content.elements.PlayerFace
import com.movtery.zalithlauncher.ui.screens.content.elements.VersionIconImage
import com.movtery.zalithlauncher.ui.theme.HomeLayout
import com.movtery.zalithlauncher.ui.theme.LauncherStyle
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/** ONA: what the styled home screens can open */
class HomeActions(
    val launch: (Version?) -> Unit,
    val versions: () -> Unit,
    val versionSettings: () -> Unit,
    val accounts: () -> Unit,
    val settings: () -> Unit,
    val download: () -> Unit,
    val multiplayer: () -> Unit,
)

/**
 * ONA: the home screen of every style other than Zalith's grid. Each layout puts things in its own
 * place and draws its own buttons, like the menu its style is named after; all of them reach the
 * same features (play, versions, accounts, download, multiplayer, settings, the engine switch).
 */
@Composable
fun StyledHome(
    style: LauncherStyle,
    actions: HomeActions,
    modifier: Modifier = Modifier,
) {
    val versions by VersionsManager.versions.collectAsStateWithLifecycle()
    val current by VersionsManager.currentVersion.collectAsStateWithLifecycle()
    val account by AccountsManager.currentAccountFlow.collectAsStateWithLifecycle()
    val data = HomeData(versions.filter { it.isValid() }, current, account, style)

    Box(modifier = modifier.fillMaxSize()) {
        when (style.home) {
            HomeLayout.ONA -> OnaHome(data, actions)
            HomeLayout.PS5 -> Ps5Home(data, actions)
            HomeLayout.PS4 -> Ps4Home(data, actions)
            HomeLayout.XMB -> XmbHome(data, actions)
            HomeLayout.SIDEBAR -> SidebarHome(data, actions)
            HomeLayout.RAIL -> RailHome(data, actions)
            HomeLayout.DASHBOARD -> DashboardHome(data, actions)
            HomeLayout.MATERIAL -> MaterialHome(data, actions)
            HomeLayout.RETRO -> RetroHome(data, actions)
            HomeLayout.GRID -> Unit
        }
    }
}

internal class HomeData(
    val versions: List<Version>,
    val current: Version?,
    val account: Account?,
    val style: LauncherStyle,
)

internal fun select(version: Version) {
    VersionsManager.saveVersion(version)
}

@Composable
internal fun clockText(pattern: String): String {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(15_000)
        }
    }
    return remember(now, pattern) { SimpleDateFormat(pattern, Locale.getDefault()).format(now) }
}

internal fun Version?.title(): String = this?.getVersionName() ?: ""

@Composable
internal fun Version?.subtitle(): String =
    this?.getVersionSummary() ?: stringResource(R.string.ona_home_add_version)

/** Java | Rust: what Play starts. Rust opens Horizon (Minecraft 26.3 rewritten in Rust). */
@Composable
fun EngineSwitch(
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
) {
    val context = LocalContext.current
    val engine = AllSettings.gameEngine.state
    var message by remember { mutableIntStateOf(0) }
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier
                .clip(shape)
                .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                .padding(2.dp),
        ) {
            GameEngine.entries.forEach { value ->
                val selected = engine == value
                Box(
                    modifier = Modifier
                        .clip(shape)
                        .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .clickable {
                            if (value == GameEngine.RUST) {
                                val state = HorizonEngine.state(context)
                                if (state != HorizonEngine.State.READY) {
                                    message = state.textRes
                                    return@clickable
                                }
                                message = R.string.ona_engine_rust_ready
                            } else {
                                message = 0
                            }
                            AllSettings.gameEngine.save(value)
                        }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = stringResource(value.textRes),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        if (engine == GameEngine.RUST) {
            var showRustMods by remember { mutableStateOf(false) }
            Text(
                text = stringResource(R.string.ona_rust_mods_open),
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .clickable { showRustMods = true }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            if (showRustMods) RustModsDialog(onDismiss = { showRustMods = false })
        }
        if (message != 0) {
            Text(
                modifier = Modifier.widthIn(max = 280.dp).padding(top = 4.dp),
                text = stringResource(message),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun playLabel(): String =
    if (AllSettings.gameEngine.state == GameEngine.RUST) stringResource(R.string.ona_home_play_rust)
    else stringResource(R.string.ona_home_play)

private class MenuEntry(val icon: Int, val label: Int, val onClick: () -> Unit)

private fun menuEntries(actions: HomeActions) = listOf(
    MenuEntry(R.drawable.ic_sports_esports_outlined, R.string.ona_home_versions, actions.versions),
    MenuEntry(R.drawable.ic_download, R.string.ona_home_download, actions.download),
    MenuEntry(R.drawable.ic_group_filled, R.string.ona_home_multiplayer, actions.multiplayer),
    MenuEntry(R.drawable.ic_person_outlined, R.string.ona_home_accounts, actions.accounts),
    MenuEntry(R.drawable.ic_settings_filled, R.string.ona_home_settings, actions.settings),
)

@Composable
internal fun AccountFace(account: Account?, size: Dp) {
    if (account != null) {
        PlayerFace(account = account, avatarSize = size)
    } else {
        Icon(
            modifier = Modifier.size(size),
            painter = painterResource(R.drawable.ic_person_outlined),
            contentDescription = null,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// PS5: row of game tiles on top, big title, white pill button, round side buttons
// ---------------------------------------------------------------------------------------------

@Composable
private fun Ps5Home(data: HomeData, actions: HomeActions) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(scheme.primary.copy(alpha = 0.35f), scheme.background, scheme.background)))
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ona_home_games), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(20.dp))
                Text(
                    modifier = Modifier.clip(CircleShape).clickable(onClick = actions.multiplayer).padding(4.dp),
                    text = stringResource(R.string.ona_home_multiplayer),
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onBackground.copy(alpha = 0.6f),
                )
                Spacer(Modifier.weight(1f))
                Ps5RoundIcon(R.drawable.ic_search, actions.download, 36.dp)
                Spacer(Modifier.width(10.dp))
                Ps5RoundIcon(R.drawable.ic_settings_filled, actions.settings, 36.dp)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.clip(CircleShape).clickable(onClick = actions.accounts)) { AccountFace(data.account, 34.dp) }
                Spacer(Modifier.width(14.dp))
                Text(clockText("HH:mm"), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(14.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                contentPadding = PaddingValues(vertical = 6.dp),
            ) {
                items(data.versions, key = { it.getVersionName() }) { version ->
                    val selected = version == data.current
                    val size by animateDpAsState(if (selected) 96.dp else 70.dp, label = "ps5Tile")
                    Box(
                        modifier = Modifier
                            .size(size)
                            .clip(RoundedCornerShape(18.dp))
                            .background(scheme.surfaceContainerHigh)
                            .border(if (selected) 3.dp else 0.dp, if (selected) Color.White else Color.Transparent, RoundedCornerShape(18.dp))
                            .clickable { if (selected) actions.launch(version) else select(version) },
                        contentAlignment = Alignment.Center,
                    ) {
                        VersionIconImage(version = version, modifier = Modifier.size(size * 0.6f))
                    }
                }
                item {
                    Box(
                        modifier = Modifier.size(70.dp).clip(RoundedCornerShape(18.dp))
                            .background(scheme.surfaceContainer).clickable(onClick = actions.versions),
                        contentAlignment = Alignment.Center,
                    ) { Icon(painterResource(R.drawable.ic_add), null) }
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = data.current.title().ifEmpty { stringResource(R.string.ona_home_no_version) },
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(data.current.subtitle(), style = MaterialTheme.typography.bodyLarge, color = scheme.onBackground.copy(alpha = 0.7f))
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .height(54.dp)
                        .widthIn(min = 170.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable { actions.launch(null) }
                        .padding(horizontal = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(playLabel(), color = Color.Black, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Ps5RoundIcon(R.drawable.ic_more_horiz, actions.versionSettings, 54.dp)
                Spacer(Modifier.width(12.dp))
                Ps5RoundIcon(R.drawable.ic_folder_outlined, actions.versions, 54.dp)
                Spacer(Modifier.weight(1f))
                EngineSwitch()
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun Ps5RoundIcon(icon: Int, onClick: () -> Unit, size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.8f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), null, modifier = Modifier.size(size * 0.45f))
    }
}

// ---------------------------------------------------------------------------------------------
// PS4: function icons on top, square tiles with the chosen one larger, rectangular Start button
// ---------------------------------------------------------------------------------------------

@Composable
private fun Ps4Home(data: HomeData, actions: HomeActions) {
    val scheme = MaterialTheme.colorScheme
    val entries = menuEntries(actions)
    var focused by remember { mutableIntStateOf(-1) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(scheme.primary.copy(alpha = 0.45f), scheme.background, scheme.primaryContainer.copy(alpha = 0.4f))))
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AccountFace(data.account, 28.dp)
                Spacer(Modifier.width(8.dp))
                Text(data.account?.username ?: "", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.weight(1f))
                Text(clockText("HH:mm"), style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                entries.forEachIndexed { index, entry ->
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 10.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (focused == index) scheme.primary.copy(alpha = 0.35f) else Color.Transparent)
                            .clickable { focused = index; entry.onClick() }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(painterResource(entry.icon), null, modifier = Modifier.size(26.dp))
                        Text(stringResource(entry.label), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                items(data.versions, key = { it.getVersionName() }) { version ->
                    val selected = version == data.current
                    val size by animateDpAsState(if (selected) 132.dp else 96.dp, label = "ps4Tile")
                    Column {
                        Box(
                            modifier = Modifier
                                .size(size)
                                .background(if (selected) scheme.primary else scheme.surfaceContainerHigh)
                                .border(if (selected) 2.dp else 0.dp, if (selected) Color.White else Color.Transparent)
                                .clickable { if (selected) actions.launch(version) else select(version) },
                            contentAlignment = Alignment.Center,
                        ) {
                            VersionIconImage(version = version, modifier = Modifier.size(size * 0.55f))
                        }
                        if (selected) {
                            Text(
                                modifier = Modifier.width(size).padding(top = 4.dp),
                                text = version.getVersionName(),
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                item {
                    Box(
                        modifier = Modifier.size(96.dp).background(scheme.surfaceContainer).clickable(onClick = actions.download),
                        contentAlignment = Alignment.Center,
                    ) { Icon(painterResource(R.drawable.ic_download), null) }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .height(44.dp)
                        .widthIn(min = 150.dp)
                        .background(scheme.primary)
                        .border(1.dp, Color.White.copy(alpha = 0.6f))
                        .clickable { actions.launch(null) }
                        .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(playLabel(), color = scheme.onPrimary, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier.height(44.dp).border(1.dp, scheme.outline).clickable(onClick = actions.versionSettings).padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(R.string.ona_home_version_settings), style = MaterialTheme.typography.labelLarge) }
                Spacer(Modifier.weight(1f))
                EngineSwitch(shape = RoundedCornerShape(3.dp))
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// XMB (PS3): categories across, their items down, slow waves behind
// ---------------------------------------------------------------------------------------------

private class XmbItem(val icon: Int?, val version: Version?, val text: String, val sub: String?, val onClick: () -> Unit)

@Composable
private fun XmbHome(data: HomeData, actions: HomeActions) {
    val scheme = MaterialTheme.colorScheme
    var category by remember { mutableIntStateOf(2) }
    val wave = rememberInfiniteTransition(label = "xmbWave")
    val wavePhase by wave.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Restart),
        label = "xmbPhase",
    )
    // ONA: light launcher keeps the waves still (an endless animation redraws the screen all the time)
    val phase = if (com.movtery.zalithlauncher.setting.AllSettings.lightLauncher.state) 0f else wavePhase
    val waveColor = scheme.primary
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(scheme.background, scheme.primaryContainer.copy(alpha = 0.35f), scheme.background)))
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            for (line in 0 until 3) {
                val path = Path()
                val amp = size.height * (0.06f + line * 0.02f)
                val base = size.height * (0.55f + line * 0.05f)
                var x = 0f
                path.moveTo(0f, base)
                while (x <= size.width) {
                    val y = base + amp * sin(x / size.width * 2 * PI.toFloat() * (1 + line * 0.3f) + phase + line).toFloat()
                    path.lineTo(x, y)
                    x += 8f
                }
                drawPath(path, waveColor.copy(alpha = 0.25f - line * 0.06f), style = Stroke(width = 2f + line))
            }
        }
        Text(
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
            text = clockText("d/M  HH:mm"),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Light,
        )
        val categories = listOf(
            R.drawable.ic_person_outlined to R.string.ona_home_users,
            R.drawable.ic_settings_filled to R.string.ona_home_settings,
            R.drawable.ic_sports_esports_outlined to R.string.ona_home_games,
            R.drawable.ic_public to R.string.ona_home_network,
            R.drawable.ic_download to R.string.ona_home_download,
        )
        val rows: List<XmbItem> = when (category) {
            0 -> listOf(
                XmbItem(R.drawable.ic_person_outlined, null, data.account?.username ?: stringResource(R.string.ona_home_accounts), null, actions.accounts),
            )
            1 -> listOf(
                XmbItem(R.drawable.ic_settings_filled, null, stringResource(R.string.ona_home_settings), null, actions.settings),
                XmbItem(R.drawable.ic_more_horiz, null, stringResource(R.string.ona_home_version_settings), null, actions.versionSettings),
            )
            3 -> listOf(XmbItem(R.drawable.ic_group_filled, null, stringResource(R.string.ona_home_multiplayer), null, actions.multiplayer))
            4 -> listOf(XmbItem(R.drawable.ic_download, null, stringResource(R.string.ona_home_download), null, actions.download))
            else -> data.versions.map { version ->
                XmbItem(null, version, version.getVersionName(), version.getVersionSummary()) {
                    if (version == data.current) actions.launch(version) else select(version)
                }
            } + XmbItem(R.drawable.ic_add, null, stringResource(R.string.ona_home_versions), null, actions.versions)
        }
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val step = 110.dp
            val left = maxWidth * 0.18f
            // The chosen category stays in the same place; the others slide past it
            val shift by animateDpAsState(left - step * category, label = "xmbShift")
            Row(
                modifier = Modifier.offset(x = shift, y = maxHeight * 0.16f),
                verticalAlignment = Alignment.Top,
            ) {
                categories.forEachIndexed { index, (icon, label) ->
                    val selected = index == category
                    Column(
                        modifier = Modifier.width(step).clickable { category = index },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            painterResource(icon), null,
                            modifier = Modifier.size(if (selected) 48.dp else 34.dp),
                            tint = if (selected) Color.White else scheme.onBackground.copy(alpha = 0.55f),
                        )
                        Text(
                            text = if (selected) stringResource(label) else "",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Light,
                        )
                    }
                }
            }
            LazyColumn(
                modifier = Modifier
                    .offset(x = left + 18.dp, y = maxHeight * 0.16f + 84.dp)
                    .width(maxWidth * 0.62f)
                    .height(maxHeight * 0.66f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(rows) { item ->
                    val selected = item.version != null && item.version == data.current
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable(onClick = item.onClick).padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (item.version != null) {
                            VersionIconImage(version = item.version, modifier = Modifier.size(if (selected) 52.dp else 38.dp))
                        } else if (item.icon != null) {
                            Icon(painterResource(item.icon), null, modifier = Modifier.size(38.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(
                                item.text,
                                style = if (selected) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Light,
                                color = if (selected) Color.White else scheme.onBackground.copy(alpha = 0.8f),
                            )
                            if (selected && item.sub != null) {
                                Text(item.sub, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Light)
                                Text(playLabel(), style = MaterialTheme.typography.labelSmall, color = scheme.primary)
                            }
                        }
                    }
                }
                if (category == 2) {
                    item { EngineSwitch(modifier = Modifier.padding(top = 6.dp), shape = RoundedCornerShape(2.dp)) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// SIDEBAR (desktop launcher): labelled side menu, banner, Play bar at the bottom
// ---------------------------------------------------------------------------------------------

@Composable
private fun SidebarHome(data: HomeData, actions: HomeActions) {
    val scheme = MaterialTheme.colorScheme
    val shape = if (data.style.cut) CutCornerShape(10.dp) else MaterialTheme.shapes.small
    Row(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .width(190.dp)
                .fillMaxHeight()
                .background(scheme.surfaceContainerLow)
                .padding(10.dp),
        ) {
            Text(
                modifier = Modifier.padding(8.dp),
                text = stringResource(R.string.ona_home_brand),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                color = scheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            menuEntries(actions).forEach { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .clickable(onClick = entry.onClick)
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(entry.icon), null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(entry.label), style = MaterialTheme.typography.labelLarge)
                }
            }
            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier.fillMaxWidth().clip(shape).clickable(onClick = actions.accounts).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AccountFace(data.account, 30.dp)
                Spacer(Modifier.width(8.dp))
                Text(data.account?.username ?: stringResource(R.string.ona_home_accounts), maxLines = 1, style = MaterialTheme.typography.labelLarge)
            }
        }
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(14.dp)
                    .clip(shape)
                    .background(Brush.linearGradient(listOf(scheme.primary, scheme.tertiary.copy(alpha = 0.7f), scheme.surfaceContainer))),
            ) {
                Column(modifier = Modifier.align(Alignment.BottomStart).padding(22.dp)) {
                    Text(
                        data.current.title().ifEmpty { stringResource(R.string.ona_home_no_version) },
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = scheme.onPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(data.current.subtitle(), color = scheme.onPrimary.copy(alpha = 0.85f))
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(scheme.surfaceContainer)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(shape)
                        .border(1.dp, scheme.outlineVariant, shape)
                        .clickable(onClick = actions.versions)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    VersionIconImage(version = data.current, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(data.current.title().ifEmpty { stringResource(R.string.ona_home_no_version) }, maxLines = 1, style = MaterialTheme.typography.labelLarge)
                        Text(data.current.subtitle(), maxLines = 1, style = MaterialTheme.typography.labelSmall)
                    }
                    Icon(painterResource(R.drawable.ic_list_down), null)
                }
                Spacer(Modifier.width(12.dp))
                EngineSwitch(shape = shape)
                Spacer(Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .height(52.dp)
                        .widthIn(min = 200.dp)
                        .clip(shape)
                        .background(scheme.primary)
                        .clickable { actions.launch(null) }
                        .padding(horizontal = 28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(playLabel().uppercase(), color = scheme.onPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// RAIL (instance app): icon rail and a list of instances, each with its own Play button
// ---------------------------------------------------------------------------------------------

@Composable
private fun RailHome(data: HomeData, actions: HomeActions) {
    val scheme = MaterialTheme.colorScheme
    val card = MaterialTheme.shapes.medium
    Row(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.width(64.dp).fillMaxHeight().background(scheme.surfaceContainerLow).padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            menuEntries(actions).forEach { entry ->
                Box(
                    modifier = Modifier.size(44.dp).clip(card).clickable(onClick = entry.onClick),
                    contentAlignment = Alignment.Center,
                ) { Icon(painterResource(entry.icon), stringResource(entry.label), modifier = Modifier.size(22.dp)) }
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.clip(CircleShape).clickable(onClick = actions.accounts)) { AccountFace(data.account, 36.dp) }
        }
        Column(modifier = Modifier.weight(1f).fillMaxHeight().padding(horizontal = 18.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ona_home_your_versions), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                EngineSwitch()
                Spacer(Modifier.width(10.dp))
                Row(
                    modifier = Modifier.clip(card).background(scheme.primary).clickable(onClick = actions.versions).padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(R.drawable.ic_add), null, tint = scheme.onPrimary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ona_home_add_version), color = scheme.onPrimary, style = MaterialTheme.typography.labelLarge)
                }
            }
            Spacer(Modifier.height(10.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(data.versions, key = { it.getVersionName() }) { version ->
                    val selected = version == data.current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(card)
                            .background(if (selected) scheme.secondaryContainer else scheme.surfaceContainer)
                            .clickable { select(version) }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        VersionIconImage(version = version, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(version.getVersionName(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(version.getVersionSummary(), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Box(
                            modifier = Modifier.size(40.dp).clip(CircleShape).background(scheme.primary).clickable { actions.launch(version) },
                            contentAlignment = Alignment.Center,
                        ) { Icon(painterResource(R.drawable.ic_play_arrow_filled), playLabel(), tint = scheme.onPrimary) }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// DASHBOARD (console): one big tile, smaller coloured tiles around it
// ---------------------------------------------------------------------------------------------

@Composable
private fun DashboardHome(data: HomeData, actions: HomeActions) {
    val scheme = MaterialTheme.colorScheme
    val tile = MaterialTheme.shapes.small
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clip(CircleShape).clickable(onClick = actions.accounts)) { AccountFace(data.account, 38.dp) }
            Spacer(Modifier.width(10.dp))
            Text(data.account?.username ?: stringResource(R.string.ona_home_accounts), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            EngineSwitch(shape = tile)
            Spacer(Modifier.width(14.dp))
            Text(clockText("HH:mm"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .weight(1.6f)
                    .fillMaxHeight()
                    .clip(tile)
                    .background(Brush.linearGradient(listOf(scheme.primary, scheme.primaryContainer)))
                    .clickable { actions.launch(null) },
            ) {
                VersionIconImage(version = data.current, modifier = Modifier.align(Alignment.Center).size(96.dp))
                Column(modifier = Modifier.align(Alignment.BottomStart).padding(14.dp)) {
                    Text(
                        data.current.title().ifEmpty { stringResource(R.string.ona_home_no_version) },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = scheme.onPrimary,
                        maxLines = 1,
                    )
                    Text(playLabel(), color = scheme.onPrimary, style = MaterialTheme.typography.labelLarge)
                }
            }
            Column(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val colors = listOf(scheme.secondaryContainer, scheme.tertiaryContainer, scheme.surfaceContainerHighest)
                val entries = menuEntries(actions)
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DashTile(entries[0], colors[0], tile)
                    DashTile(entries[1], colors[1], tile)
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DashTile(entries[2], colors[2], tile)
                    DashTile(entries[4], colors[0], tile)
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DashTile(MenuEntry(R.drawable.ic_more_horiz, R.string.ona_home_version_settings, actions.versionSettings), colors[1], tile)
                    DashTile(entries[3], colors[2], tile)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(data.versions, key = { it.getVersionName() }) { version ->
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .clip(tile)
                        .background(if (version == data.current) scheme.primary else scheme.surfaceContainerHigh)
                        .clickable { select(version) },
                    contentAlignment = Alignment.Center,
                ) { VersionIconImage(version = version, modifier = Modifier.size(36.dp)) }
            }
        }
    }
}

@Composable
private fun RowScope.DashTile(entry: MenuEntry, color: Color, shape: Shape) {
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(shape)
            .background(color)
            .clickable(onClick = entry.onClick)
            .padding(10.dp),
    ) {
        Icon(painterResource(entry.icon), null, modifier = Modifier.size(26.dp))
        Text(
            modifier = Modifier.align(Alignment.BottomStart),
            text = stringResource(entry.label),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// MATERIAL (Pixel / Material You): greeting, big round card, chips, large Play button
// ---------------------------------------------------------------------------------------------

@Composable
private fun MaterialHome(data: HomeData, actions: HomeActions) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp)) {
        Column(modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            Text(clockText("EEEE, d MMMM"), style = MaterialTheme.typography.titleMedium, color = scheme.primary)
            Text(
                text = data.account?.username?.let { stringResource(R.string.ona_home_hello, it) } ?: stringResource(R.string.ona_home_hello_guest),
                style = MaterialTheme.typography.displaySmall,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                menuEntries(actions).take(3).forEach { MaterialChip(it) }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                menuEntries(actions).drop(3).forEach { MaterialChip(it) }
            }
            Spacer(Modifier.height(16.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(data.versions, key = { it.getVersionName() }) { version ->
                    val selected = version == data.current
                    Column(
                        modifier = Modifier
                            .width(120.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(if (selected) scheme.secondaryContainer else scheme.surfaceContainerHigh)
                            .clickable { select(version) }
                            .padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        VersionIconImage(version = version, modifier = Modifier.size(44.dp))
                        Spacer(Modifier.height(6.dp))
                        Text(version.getVersionName(), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Spacer(Modifier.width(18.dp))
        Column(
            modifier = Modifier
                .width(280.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(36.dp))
                .background(scheme.primaryContainer)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(84.dp).clip(CircleShape).background(scheme.surface), contentAlignment = Alignment.Center) {
                VersionIconImage(version = data.current, modifier = Modifier.size(56.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text(
                data.current.title().ifEmpty { stringResource(R.string.ona_home_no_version) },
                style = MaterialTheme.typography.headlineSmall,
                color = scheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(data.current.subtitle(), style = MaterialTheme.typography.bodyMedium, color = scheme.onPrimaryContainer, maxLines = 1)
            Spacer(Modifier.weight(1f))
            EngineSwitch()
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(scheme.primary)
                    .clickable { actions.launch(null) },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_play_arrow_filled), null, tint = scheme.onPrimary, modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(8.dp))
                Text(playLabel(), color = scheme.onPrimary, style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

@Composable
private fun MaterialChip(entry: MenuEntry) {
    Surface(
        onClick = entry.onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(entry.icon), null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(entry.label), style = MaterialTheme.typography.labelLarge)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// RETRO (block game title menu): centred logo, yellow splash text, stacked stone buttons
// ---------------------------------------------------------------------------------------------

@Composable
private fun RetroHome(data: HomeData, actions: HomeActions) {
    val scheme = MaterialTheme.colorScheme
    val pulse = rememberInfiniteTransition(label = "splash")
    val pulseScale by pulse.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(450), RepeatMode.Reverse),
        label = "splashScale",
    )
    val scale = if (com.movtery.zalithlauncher.setting.AllSettings.lightLauncher.state) 1f else pulseScale
    val face = when (data.style) {
        LauncherStyle.NETHER -> Color(0xFF6B2A23)
        LauncherStyle.THE_END -> Color(0xFF4A3B66)
        else -> Color(0xFF6F6F6F)
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(scheme.background, face.copy(alpha = 0.35f), scheme.background))),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box {
                Text(
                    text = stringResource(R.string.ona_home_brand).uppercase(),
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 40.sp, color = Color.Black),
                    modifier = Modifier.offset(3.dp, 3.dp),
                )
                Text(
                    text = stringResource(R.string.ona_home_brand).uppercase(),
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 40.sp, color = Color(0xFFE0E0E0)),
                )
                Text(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 40.dp, y = 14.dp)
                        .rotate(-18f)
                        .size(width = 170.dp, height = 24.dp)
                        .padding(0.dp),
                    text = stringResource(R.string.ona_home_splash),
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = (13 * scale).sp,
                        color = Color(0xFFFFFF55),
                    ),
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(22.dp))
            RetroButton(
                text = playLabel() + (data.current?.let { "  -  " + it.getVersionName() } ?: ""),
                face = face,
                width = 360.dp,
                onClick = { actions.launch(null) },
            )
            Spacer(Modifier.height(8.dp))
            RetroButton(stringResource(R.string.ona_home_versions), face, 360.dp, actions.versions)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RetroButton(stringResource(R.string.ona_home_download), face, 176.dp, actions.download)
                RetroButton(stringResource(R.string.ona_home_multiplayer), face, 176.dp, actions.multiplayer)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RetroButton(stringResource(R.string.ona_home_settings), face, 176.dp, actions.settings)
                RetroButton(stringResource(R.string.ona_home_accounts), face, 176.dp, actions.accounts)
            }
            Spacer(Modifier.height(12.dp))
            EngineSwitch(shape = RoundedCornerShape(0.dp))
        }
        Text(
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
            text = data.account?.username ?: "",
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.White),
        )
    }
}

/** A stone button: flat face, light top and left edges, dark bottom and right edges, black outline */
@Composable
private fun RetroButton(text: String, face: Color, width: Dp, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(width)
            .height(40.dp)
            .border(BorderStroke(2.dp, Color.Black))
            .background(face)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(2.dp)) {
            val edge = 3f
            drawRect(Color.White.copy(alpha = 0.35f), topLeft = Offset.Zero, size = androidx.compose.ui.geometry.Size(size.width, edge))
            drawRect(Color.White.copy(alpha = 0.35f), topLeft = Offset.Zero, size = androidx.compose.ui.geometry.Size(edge, size.height))
            drawRect(Color.Black.copy(alpha = 0.4f), topLeft = Offset(0f, size.height - edge), size = androidx.compose.ui.geometry.Size(size.width, edge))
            drawRect(Color.Black.copy(alpha = 0.4f), topLeft = Offset(size.width - edge, 0f), size = androidx.compose.ui.geometry.Size(edge, size.height))
        }
        Text(
            modifier = Modifier.offset(2.dp, 2.dp),
            text = text,
            maxLines = 1,
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp, color = Color(0xFF3F3F3F)),
        )
        Text(
            text = text,
            maxLines = 1,
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp, color = Color.White),
        )
    }
}
