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

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.setting.AllSettings

/** Gold of the ONA icon, used for the Play button and the menu icons */
private val OnaGold = Color(0xFFE6AE42)
private val OnaGoldLight = Color(0xFFFFD27A)
private val OnaGoldDark = Color(0xFFB57B22)
private val OnaInk = Color(0xFF1A1206)

/**
 * ONA home (the default style): simple and clear. One side has the account and four big menu
 * tiles, the other the chosen version and a large Play button. Phones held upright get the same
 * parts stacked. Nothing moves on its own, so it stays light on every device.
 */
@Composable
internal fun OnaHome(data: HomeData, actions: HomeActions) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(scheme.background, scheme.surfaceContainerLow)))
    ) {
        PixelBackdrop()
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp)) {
            if (maxWidth >= maxHeight) {
                Row(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        OnaHeader(data, actions)
                        Spacer(Modifier.weight(1f))
                        OnaTiles(actions)
                    }
                    Spacer(Modifier.width(16.dp))
                    OnaPlayCard(
                        data = data,
                        actions = actions,
                        modifier = Modifier.width(300.dp).fillMaxHeight(),
                    )
                }
            } else {
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    OnaHeader(data, actions)
                    Spacer(Modifier.height(16.dp))
                    OnaPlayCard(data = data, actions = actions, modifier = Modifier.fillMaxWidth().height(330.dp))
                    Spacer(Modifier.height(16.dp))
                    OnaTiles(actions)
                }
            }
        }
    }
}

/** Faint pixel blocks in the corners, like the icon's night map. Drawn once, no animation. */
@Composable
private fun PixelBackdrop() {
    val tint = MaterialTheme.colorScheme.primary
    val cells = remember {
        val rnd = java.util.Random(7)
        List(36) { Triple(rnd.nextInt(14), rnd.nextInt(6), 0.03f + rnd.nextFloat() * 0.05f) }
    }
    Canvas(modifier = Modifier.fillMaxSize()) {
        val cell = 22.dp.toPx()
        cells.forEach { (x, y, alpha) ->
            // top right and bottom left corners
            drawRect(tint.copy(alpha = alpha), Offset(size.width - (x + 1) * cell, y * cell), Size(cell, cell))
            drawRect(tint.copy(alpha = alpha), Offset(x * cell, size.height - (y + 1) * cell), Size(cell, cell))
        }
    }
}

@Composable
private fun OnaHeader(data: HomeData, actions: HomeActions) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(R.drawable.img_launcher),
            contentDescription = null,
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = data.account?.username?.let { stringResource(R.string.ona_home_hello, it) }
                    ?: stringResource(R.string.ona_home_hello_guest),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = clockText("EEEE, d MMMM"),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        // account: tap to switch or add one
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(scheme.surfaceContainerHigh)
                .clickable(onClick = actions.accounts)
                .padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(30.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
                AccountFace(data.account, 30.dp)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = data.account?.username ?: stringResource(R.string.ona_home_accounts),
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 110.dp),
            )
        }
    }
}


@Composable
private fun OnaTiles(actions: HomeActions) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OnaTile(R.drawable.ic_sports_esports_outlined, R.string.ona_home_versions, actions.versions)
        OnaTile(R.drawable.ic_download, R.string.ona_home_download, actions.download)
        OnaTile(R.drawable.ic_group_filled, R.string.ona_home_multiplayer, actions.multiplayer)
        OnaTile(R.drawable.ic_settings_filled, R.string.ona_home_settings, actions.settings)
    }
}

@Composable
private fun RowScope.OnaTile(icon: Int, label: Int, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, tween(90), label = "tile")
    Column(
        modifier = Modifier
            .weight(1f)
            .height(92.dp)
            .scale(scale)
            .clip(RoundedCornerShape(18.dp))
            .background(scheme.surfaceContainerHigh)
            .border(1.dp, scheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
            .clickable(interactionSource = source, indication = androidx.compose.material3.ripple(), onClick = onClick)
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(icon), null, tint = scheme.primary, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun OnaPlayCard(data: HomeData, actions: HomeActions, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(scheme.surfaceContainer)
            .border(1.dp, scheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
            .padding(18.dp),
    ) {
        // the chosen version: tap to pick another one
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(scheme.surfaceContainerHigh)
                .clickable(onClick = actions.versions)
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(scheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                com.movtery.zalithlauncher.ui.screens.content.elements.VersionIconImage(
                    version = data.current,
                    modifier = Modifier.size(38.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = data.current.title().ifEmpty { stringResource(R.string.ona_home_no_version) },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = data.current.subtitle(),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(painterResource(R.drawable.ic_arrow_drop_down_rounded), null, tint = scheme.onSurfaceVariant)
        }
        if (data.current != null) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = actions.versionSettings)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_build_outlined), null, tint = scheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.ona_home_version_settings), style = MaterialTheme.typography.labelLarge, color = scheme.primary)
            }
        }
        Spacer(Modifier.weight(1f))
        EngineSwitch(modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(12.dp))
        OnaPlayButton(onClick = { actions.launch(null) })
    }
}

@Composable
private fun ColumnScope.OnaPlayButton(onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, tween(90), label = "play")
    val light = AllSettings.lightLauncher.state
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp)
            .scale(scale)
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (light) Brush.verticalGradient(listOf(OnaGold, OnaGold))
                else Brush.verticalGradient(listOf(OnaGoldLight, OnaGold, OnaGoldDark))
            )
            .clickable(interactionSource = source, indication = androidx.compose.material3.ripple(), onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_play_arrow_filled), null, tint = OnaInk, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = playLabel(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Black,
            color = OnaInk,
        )
    }
}
