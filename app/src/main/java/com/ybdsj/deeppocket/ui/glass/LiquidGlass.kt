package com.ybdsj.deeppocket.ui.glass

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow

/**
 * 液态玻璃依赖 RenderEffect（API 31+）做实时背景模糊。
 * 低版本自动降级成半透明卡片，视觉朴素但不会崩。
 */
val liquidGlassSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** 全局背景采样源，由 MainActivity 提供；为空表示当前处于降级模式。 */
val LocalGlassBackdrop = compositionLocalOf<Backdrop?> { null }

/**
 * 液态玻璃总开关，由设置页控制，MainActivity 下发。
 * 关掉后所有玻璃一律换成不透明配色，不再做实时模糊与折射。
 */
val LocalLiquidGlassEnabled = compositionLocalOf { true }

private val DeepBlue = Color(0xFF4D6BFE)
private val IceBlue = Color(0xFF3FC1FF)
private val SeaGreen = Color(0xFF35E0C8)

@Composable
private fun backdropColors(): Pair<List<Color>, List<Color>> {
    return if (isSystemInDarkTheme()) {
        listOf(Color(0xFF07091A), Color(0xFF0D1430), Color(0xFF080B18)) to
            listOf(DeepBlue.copy(alpha = 0.55f), SeaGreen.copy(alpha = 0.26f), IceBlue.copy(alpha = 0.30f))
    } else {
        listOf(Color(0xFFF4F7FF), Color(0xFFE6EDFF), Color(0xFFF2F8FF)) to
            listOf(DeepBlue.copy(alpha = 0.30f), SeaGreen.copy(alpha = 0.20f), IceBlue.copy(alpha = 0.24f))
    }
}

/**
 * 全屏氛围背景：深海蓝渐变 + 三颗光斑。
 * 同时这段绘制内容被记录到 [backdrop]，供上层玻璃做实时采样。
 * 关掉液态玻璃时不再挂 layerBackdrop——省掉一张全屏合成层。
 */
@Composable
fun AmbientBackdropLayer(backdrop: LayerBackdrop, modifier: Modifier = Modifier) {
    val (base, spots) = backdropColors()
    val glassEnabled = LocalLiquidGlassEnabled.current
    Box(
        (if (glassEnabled) modifier.fillMaxSize().layerBackdrop(backdrop) else modifier.fillMaxSize()),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.verticalGradient(base))
            val w = size.width
            val h = size.height
            val r = size.minDimension
            // 右上：品牌蓝主光斑
            drawSpot(spots[0], Offset(w * 0.88f, h * 0.04f), r * 0.78f)
            // 左下：海水青
            drawSpot(spots[1], Offset(w * 0.08f, h * 0.92f), r * 0.70f)
            // 右中：冰蓝辅助光斑
            drawSpot(spots[2], Offset(w * 0.78f, h * 0.62f), r * 0.55f)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSpot(
    color: Color,
    center: Offset,
    radius: Float,
) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color, Color.Transparent),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}

/**
 * 玻璃表面色：浅色偏白、深色偏深蓝，都保持低不透明度让背景透出来。
 * 关掉开关时换成同色系的实色，跟着氛围背景走，不会突然跳成主题里的紫灰。
 */
@Composable
fun glassSurfaceColor(): Color {
    if (!LocalLiquidGlassEnabled.current) {
        return if (isSystemInDarkTheme()) Color(0xFF141A2C) else Color(0xFFF7F9FF)
    }
    return if (isSystemInDarkTheme()) {
        Color(0xFF16203D).copy(alpha = 0.44f)
    } else {
        Color.White.copy(alpha = 0.58f)
    }
}

@Composable
fun glassBorderColor(): Color {
    if (!LocalLiquidGlassEnabled.current) {
        return if (isSystemInDarkTheme()) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f)
    }
    return if (isSystemInDarkTheme()) {
        Color.White.copy(alpha = 0.20f)
    } else {
        Color.White.copy(alpha = 0.85f)
    }
}

/**
 * 液态玻璃容器。有背景采样源时走真实模糊 + 折射 + 高光，否则降级为半透明卡片。
 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    surfaceColor: Color = glassSurfaceColor(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    refraction: Boolean = true,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val backdrop = LocalGlassBackdrop.current
    // 三种降级：用户手动关掉玻璃、设备不支持 RenderEffect、没有背景采样源
    if (backdrop == null || !liquidGlassSupported || !LocalLiquidGlassEnabled.current) {
        Box(
            modifier
                .clip(shape)
                .background(surfaceColor)
                .border(1.dp, glassBorderColor(), shape)
                .padding(contentPadding),
            content = content,
        )
        return
    }
    Box(
        modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    vibrancy()
                    blur(20.dp.toPx())
                    if (refraction) {
                        // 边缘折射：把玻璃下方的内容沿边缘挤压，形成透镜感
                        lens(22.dp.toPx(), 34.dp.toPx(), chromaticAberration = true)
                    }
                },
                highlight = { Highlight.Default },
                shadow = { Shadow(radius = 20.dp, alpha = 0.16f) },
                innerShadow = { InnerShadow(radius = 12.dp, alpha = 0.14f) },
                onDrawSurface = { drawRect(surfaceColor) },
            )
            .border(1.dp, glassBorderColor(), shape)
            .padding(contentPadding),
        content = content,
    )
}

/** 供 MainActivity 创建背景采样源。 */
@Composable
fun rememberAmbientBackdrop(): LayerBackdrop = rememberLayerBackdrop()
