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

package com.movtery.layer_controller.utils

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * ONA: the look of every on-screen button, whatever layout is in use.
 * CLASSIC draws the style of the layout as before; the others paint the button themselves.
 */
enum class ButtonSkin { CLASSIC, LEGO, PIXEL, GLASS, NEON, BUBBLE }

/** ONA: what a button does while a finger is on it */
enum class PressAnimation { NONE, SHRINK, BOUNCE, SINK }

/** ONA: the look chosen in the launcher settings (read by every button while it is drawn) */
object ButtonLook {
    var skin by mutableStateOf(ButtonSkin.CLASSIC)
    var press by mutableStateOf(PressAnimation.NONE)

    /** Names as saved in the settings; an unknown name is the plain look */
    fun set(skinName: String?, pressName: String?) {
        skin = ButtonSkin.entries.firstOrNull { it.name == skinName } ?: ButtonSkin.CLASSIC
        press = PressAnimation.entries.firstOrNull { it.name == pressName } ?: PressAnimation.NONE
    }

    /** True when buttons are drawn exactly as the layout says, without any animation */
    val isPlain: Boolean get() = skin == ButtonSkin.CLASSIC && press == PressAnimation.NONE
}

// brick, light and candy colours: each button takes one by its id, so a layout is not one flat colour
private val LEGO_COLORS = listOf(
    Color(0xFFC62828), Color(0xFF1565C0), Color(0xFF2E7D32), Color(0xFFEF6C00), Color(0xFF455A64), Color(0xFF6A1B9A)
)
private val NEON_COLORS = listOf(Color(0xFF00E5FF), Color(0xFFFF2BD6), Color(0xFF76FF03), Color(0xFFFFAB00))
private val BUBBLE_COLORS = listOf(
    Color(0xFF29B6F6), Color(0xFFEC407A), Color(0xFF66BB6A), Color(0xFFFFA726), Color(0xFF7E57C2)
)

private fun pick(colors: List<Color>, seed: Int): Color = colors[Math.floorMod(seed, colors.size)]

/**
 * A button drawn with the chosen look and press animation.
 * @param seed the button's own number (colour of the brick / light / candy)
 */
@Composable
internal fun Modifier.skinnedButton(
    skin: ButtonSkin,
    press: PressAnimation,
    isPressed: Boolean,
    buttonAlpha: Float,
    backgroundColor: Color,
    borderWidth: Dp,
    borderColor: Color,
    borderRadius: RoundedCornerShape,
    seed: Int
): Modifier {
    val spec: AnimationSpec<Float> = if (press == PressAnimation.BOUNCE) {
        spring(dampingRatio = 0.35f, stiffness = 700f)
    } else {
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 1400f)
    }
    val progress by animateFloatAsState(
        targetValue = if (isPressed) 1f else 0f,
        animationSpec = spec,
        label = "pressAnimation"
    )
    val moved = this.graphicsLayer {
        alpha = buttonAlpha
        when (press) {
            PressAnimation.SHRINK, PressAnimation.BOUNCE -> {
                val scale = 1f - 0.10f * progress
                scaleX = scale
                scaleY = scale
            }
            PressAnimation.SINK -> {
                translationY = size.height * 0.07f * progress
            }
            PressAnimation.NONE -> {}
        }
    }
    return if (skin == ButtonSkin.CLASSIC) {
        moved
            .clip(borderRadius)
            .background(backgroundColor)
            .border(width = borderWidth, color = borderColor, shape = borderRadius)
    } else {
        moved.drawBehind {
            val pressed = progress.coerceIn(0f, 1f)
            when (skin) {
                ButtonSkin.LEGO -> drawLego(pressed, seed)
                ButtonSkin.PIXEL -> drawPixel(pressed)
                ButtonSkin.GLASS -> drawGlass(pressed)
                ButtonSkin.NEON -> drawNeon(pressed, seed)
                ButtonSkin.BUBBLE -> drawBubble(pressed, seed)
                ButtonSkin.CLASSIC -> {}
            }
        }
    }
}

/** A toy brick seen from above: its side below, the top face that goes down when pressed, studs on it */
private fun DrawScope.drawLego(pressed: Float, seed: Int) {
    val w = size.width
    val h = size.height
    val unit = min(w, h)
    val base = pick(LEGO_COLORS, seed).copy(alpha = 0.88f)
    val depth = unit * 0.12f
    val radius = CornerRadius(unit * 0.10f, unit * 0.10f)
    val faceHeight = h - depth
    if (faceHeight <= 0f) return
    drawRoundRect(color = lerp(base, Color.Black, 0.40f), topLeft = Offset(0f, depth), size = Size(w, faceHeight), cornerRadius = radius)
    val top = depth * pressed
    drawRoundRect(color = lerp(base, Color.Black, 0.12f * pressed), topLeft = Offset(0f, top), size = Size(w, faceHeight), cornerRadius = radius)
    val count = (w / faceHeight).roundToInt().coerceIn(1, 4)
    val studRadius = min(faceHeight * 0.30f, w / (count * 2.6f))
    for (i in 0 until count) {
        val center = Offset(w * (i + 0.5f) / count, top + faceHeight * 0.5f)
        drawCircle(color = lerp(base, Color.Black, 0.28f), radius = studRadius, center = Offset(center.x, center.y + studRadius * 0.12f))
        drawCircle(color = lerp(base, Color.White, 0.18f), radius = studRadius * 0.92f, center = center)
    }
}

/** A button of the game's own menus: black outline, light top and left edge, dark bottom and right edge */
private fun DrawScope.drawPixel(pressed: Float) {
    val w = size.width
    val h = size.height
    val px = max(2f, (min(w, h) / 14f).roundToInt().toFloat())
    val down = pressed > 0.5f
    val face = if (down) Color(0xE07A84C0) else Color(0xD9727272)
    if (w <= px * 5f || h <= px * 5f) {
        drawRect(color = face)
        return
    }
    val light = if (down) Color(0xE04A5390) else Color(0xE6C6C6C6)
    val dark = if (down) Color(0xE0B4BCEA) else Color(0xE63F3F3F)
    drawRect(color = Color(0xF0000000))
    drawRect(color = face, topLeft = Offset(px, px), size = Size(w - px * 2f, h - px * 2f))
    drawRect(color = light, topLeft = Offset(px, px), size = Size(w - px * 2f, px))
    drawRect(color = light, topLeft = Offset(px, px), size = Size(px, h - px * 2f))
    drawRect(color = dark, topLeft = Offset(px, h - px * 3f), size = Size(w - px * 2f, px * 2f))
    drawRect(color = dark, topLeft = Offset(w - px * 2f, px), size = Size(px, h - px * 2f))
}

/** Frosted glass: a see-through pane that is brighter at the top, with a bright rim */
private fun DrawScope.drawGlass(pressed: Float) {
    val w = size.width
    val h = size.height
    val unit = min(w, h)
    val radius = CornerRadius(unit * 0.28f, unit * 0.28f)
    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.30f + 0.20f * pressed), Color.White.copy(alpha = 0.08f + 0.14f * pressed))
        ),
        cornerRadius = radius
    )
    drawRoundRect(color = Color.White.copy(alpha = 0.10f), size = Size(w, h * 0.5f), cornerRadius = radius)
    val stroke = max(2f, unit * 0.035f)
    if (w > stroke * 2f && h > stroke * 2f) {
        drawRoundRect(
            color = Color.White.copy(alpha = 0.60f),
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(w - stroke, h - stroke),
            cornerRadius = radius,
            style = Stroke(width = stroke)
        )
    }
}

/** A neon sign: dark pane, a bright tube around it and its glow; pressed, the pane lights up */
private fun DrawScope.drawNeon(pressed: Float, seed: Int) {
    val w = size.width
    val h = size.height
    val unit = min(w, h)
    val glow = pick(NEON_COLORS, seed)
    val radius = CornerRadius(unit * 0.22f, unit * 0.22f)
    drawRoundRect(color = lerp(Color.Black.copy(alpha = 0.45f), glow.copy(alpha = 0.45f), pressed), cornerRadius = radius)
    val core = max(2f, unit * 0.04f)
    if (w <= core * 3f || h <= core * 3f) return
    val topLeft = Offset(core, core)
    val inner = Size(w - core * 2f, h - core * 2f)
    for (i in 3 downTo 1) {
        drawRoundRect(
            color = glow.copy(alpha = 0.10f + 0.05f * pressed),
            topLeft = topLeft,
            size = inner,
            cornerRadius = radius,
            style = Stroke(width = core * (1f + i * (1.2f + pressed)))
        )
    }
    drawRoundRect(color = lerp(glow, Color.White, 0.35f), topLeft = topLeft, size = inner, cornerRadius = radius, style = Stroke(width = core))
}

/** A glossy candy: round, lighter on top, with a white shine */
private fun DrawScope.drawBubble(pressed: Float, seed: Int) {
    val w = size.width
    val h = size.height
    val unit = min(w, h)
    val base = pick(BUBBLE_COLORS, seed)
    val radius = CornerRadius(unit / 2f, unit / 2f)
    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(
                lerp(base, Color.White, 0.45f - 0.25f * pressed).copy(alpha = 0.86f),
                lerp(base, Color.Black, 0.10f + 0.15f * pressed).copy(alpha = 0.86f)
            )
        ),
        cornerRadius = radius
    )
    drawOval(
        color = Color.White.copy(alpha = 0.45f * (1f - 0.5f * pressed)),
        topLeft = Offset(w * 0.18f, h * 0.10f),
        size = Size(w * 0.64f, h * 0.30f)
    )
    drawRoundRect(color = Color.White.copy(alpha = 0.55f), cornerRadius = radius, style = Stroke(width = max(2f, unit * 0.03f)))
}
