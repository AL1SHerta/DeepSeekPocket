package com.ybdsj.deeppocket.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/**
 * 三段式开关的一项。
 */
data class GlassTab(
    val label: String,
    val icon: ImageVector,
)

private val TabBarHeight = 66.dp
private val ThumbHeight = 56.dp
private val TabBarPadding = 4.dp

/**
 * 液态玻璃三段式开关。
 *
 * 滑块底下其实是「页面背景 + 一层隐形的、被染成主色的图标副本」的合成结果，
 * 滑块只做 lens 折射不做模糊，所以透出来的是被放大、带色散的清晰图标——
 * 这正是 iOS 26 那种液态玻璃的手感，也让拖动时"内容真的被玻璃挤动"。
 */
@Composable
fun GlassTabBar(
    tabs: List<GlassTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pageBackdrop = LocalGlassBackdrop.current
    val dark = isSystemInDarkTheme()
    val glassEnabled = LocalLiquidGlassEnabled.current

    // 没有采样源、或用户在设置里关掉了液态玻璃，都退回普通胶囊开关
    if (pageBackdrop == null || !glassEnabled) {
        SimpleTabBar(tabs, selectedIndex, onSelect, dark, modifier)
        return
    }

    val accent = if (dark) Color(0xFF9FB6FF) else MaterialTheme.colorScheme.primary
    val containerColor =
        if (dark) Color(0xFF121826).copy(alpha = 0.42f) else Color(0xFFFAFAFA).copy(alpha = 0.46f)
    val shape: Shape = RoundedCornerShape(percent = 50)

    // 只有图标（染色后）的采样层，专门喂给滑块做折射
    val tabsBackdrop = rememberLayerBackdrop()
    val thumbBackdrop = rememberCombinedBackdrop(pageBackdrop, tabsBackdrop)

    val animationScope = rememberCoroutineScope()
    val density = LocalDensity.current

    var currentIndex by remember(selectedIndex) { mutableIntStateOf(selectedIndex) }

    BoxWithConstraints(
        modifier = modifier.height(TabBarHeight),
        // 滑块比底板矮 10dp，必须垂直居中（水平仍靠左，位置再靠 translationX 偏移）
        contentAlignment = Alignment.CenterStart,
    ) {
        val innerWidthPx = with(density) { (maxWidth - TabBarPadding * 2).toPx() }
        val tabWidthPx = innerWidthPx / tabs.size

        val offsetAnimation = remember { Animatable(0f) }
        // 拖动时整条面板被"拽"一点点，松手回弹
        val panelOffset by remember(density) {
            derivedStateOf {
                val fraction =
                    (offsetAnimation.value / constraints.maxWidth.toFloat()).fastCoerceIn(-1f, 1f)
                with(density) { 4.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction)) }
            }
        }

        val drag = remember(tabs.size) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedIndex.toFloat(),
                valueRange = 0f..(tabs.size - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 1.06f,
                onDragStarted = {},
                onDragStopped = {
                    val target = targetValue.fastRoundToInt().fastCoerceIn(0, tabs.size - 1)
                    currentIndex = target
                    animateToValue(target.toFloat())
                    animationScope.launch {
                        offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                    }
                },
                onDrag = { _, dragAmount ->
                    updateValue((targetValue + dragAmount.x / tabWidthPx).fastCoerceIn(0f, (tabs.size - 1).toFloat()))
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
                },
            )
        }

        // 外部选中态变化（点图标 / 代码切换）时把滑块运过去
        LaunchedEffect(selectedIndex) {
            snapshotFlow { selectedIndex }.collectLatest { currentIndex = it }
        }
        LaunchedEffect(drag) {
            snapshotFlow { currentIndex }
                .drop(1)
                .collectLatest { index ->
                    drag.animateToValue(index.toFloat())
                    onSelect(index)
                }
        }

        // 滑块当前压住的格子：这一格的真实图标要藏起来，
        // 否则会和滑块里折射出来的那份叠在一起出现重影
        val coveredIndex by remember {
            derivedStateOf { drag.value.fastRoundToInt().fastCoerceIn(0, tabs.size - 1) }
        }

        // ④ 手势层：点按与拖动在同一个 awaitEachGesture 里分流。
        // 不能拆成 detectTapGestures + detectDragGestures 两个独立手势——
        // 它们抢同一条 pointer 流，真机上必然有一个失灵（先出现的是「只能滑不能点」）。
        Box(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(tabs.size) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val downX = down.position.x
                        var lastX = downX
                        var dragging = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.fastFirstOrNull { it.id == down.id } ?: break
                            if (change.changedToUpIgnoreConsumed()) {
                                // 从头到尾没超过 slop，就当一次点按
                                if (!dragging) {
                                    val slot = (downX / (size.width.toFloat() / tabs.size))
                                        .toInt()
                                        .fastCoerceIn(0, tabs.size - 1)
                                    if (slot != currentIndex) currentIndex = slot
                                }
                                break
                            }
                            val dx = change.position.x - lastX
                            lastX = change.position.x
                            if (!dragging && abs(change.position.x - downX) > slop) {
                                dragging = true
                                drag.startDrag(change.position)
                            }
                            if (dragging) {
                                change.consume()
                                drag.dragBy(dx)
                            }
                        }
                        if (dragging) drag.endDrag()
                    }
                },
        )

        // ① 底板：模糊 + 轻微折射的玻璃胶囊，上面是真实图标
        Row(
            modifier = Modifier
                .graphicsLayer { translationX = panelOffset }
                .drawBackdrop(
                    backdrop = pageBackdrop,
                    shape = { shape },
                    effects = {
                        vibrancy()
                        blur(10.dp.toPx())
                        lens(12.dp.toPx(), 14.dp.toPx())
                    },
                    highlight = { Highlight.Default },
                    shadow = { Shadow(radius = 16.dp, alpha = 0.14f) },
                    onDrawSurface = { drawRect(containerColor) },
                )
                .height(TabBarHeight)
                .fillMaxWidth()
                .padding(TabBarPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, tab ->
                TabCell(
                    tab = tab,
                    selected = index == currentIndex,
                    dark = dark,
                    modifier = Modifier
                        .weight(1f)
                        .alpha(if (index == coveredIndex) 0f else 1f),
                )
            }
        }

        // ② 隐形采样层：同一排图标染成主色后写进 tabsBackdrop。
        // 这里刻意不画玻璃底板——只留图标，滑块透过它看到的就是"压在背景上的放大图标"
        Row(
            modifier = Modifier
                .alpha(0f)
                .layerBackdrop(tabsBackdrop)
                .height(TabBarHeight)
                .fillMaxWidth()
                .padding(TabBarPadding)
                .graphicsLayer(colorFilter = ColorFilter.tint(accent)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                TabCell(
                    tab = tab,
                    selected = false,
                    dark = dark,
                    // 采样层必须用全不透明的纯色，否则 tint 之后会带上多层透明度，透出来灰蒙蒙
                    forceOpaqueColor = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // ③ 滑块：采样「页面背景 + 染色图标层」，只折射不模糊
        Box(
            modifier = Modifier
                .padding(horizontal = TabBarPadding)
                .graphicsLayer {
                    translationX = drag.value * tabWidthPx + panelOffset
                }
                .drawBackdrop(
                    backdrop = thumbBackdrop,
                    shape = { shape },
                    effects = {
                        val progress = drag.pressProgress
                        // 静止时不做折射（否则图标会被色散糊成双层）；
                        // 按下去才把玻璃"压厚"，图标跟着被挤动
                        lens(
                            12.dp.toPx() * progress,
                            16.dp.toPx() * progress,
                            chromaticAberration = progress > 0.02f,
                        )
                    },
                    highlight = { Highlight.Default.copy(alpha = 0.26f + 0.74f * drag.pressProgress) },
                    shadow = { Shadow(alpha = 0.10f + 0.25f * drag.pressProgress) },
                    innerShadow = {
                        InnerShadow(radius = 6.dp * drag.pressProgress, alpha = drag.pressProgress)
                    },
                    layerBlock = {
                        scaleX = drag.scaleX
                        scaleY = drag.scaleY
                        // 甩得越快，滑块被拉得越长——液体的感觉就在这一行
                        val velocity = drag.velocity / 10f
                        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val progress = drag.pressProgress
                        drawRect(
                            if (dark) Color.White.copy(0.08f) else Color.White.copy(0.12f),
                            alpha = 1f - progress,
                        )
                        drawRect(Color.White.copy(alpha = 0.06f * progress))
                    },
                )
                .height(ThumbHeight)
                .fillMaxWidth(1f / tabs.size),
        )

    }
}

@Composable
private fun TabCell(
    tab: GlassTab,
    selected: Boolean,
    dark: Boolean,
    forceOpaqueColor: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val contentColor = when {
        forceOpaqueColor -> Color.Black
        selected && dark -> Color(0xFF9FB6FF)
        selected -> MaterialTheme.colorScheme.primary
        dark -> Color.White.copy(alpha = 0.58f)
        else -> Color(0xFF3A4256).copy(alpha = 0.66f)
    }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = tab.label,
            modifier = Modifier.size(20.dp),
            tint = contentColor,
        )
        Text(
            text = tab.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = contentColor,
        )
    }
}

/** 无 RenderEffect 时的降级版本：普通半透明胶囊 + 弹性胶囊指示器。 */
@Composable
private fun SimpleTabBar(
    tabs: List<GlassTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    dark: Boolean,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier.height(TabBarHeight),
        contentAlignment = Alignment.CenterStart,
    ) {
        val tabWidth = maxWidth / tabs.size
        val indicatorOffset by androidx.compose.animation.core.animateDpAsState(
            targetValue = tabWidth * selectedIndex,
            animationSpec = spring(dampingRatio = 0.72f, stiffness = 480f),
            label = "indicator",
        )
        val selectedTint =
            if (dark) Color.White.copy(alpha = 0.26f) else Color.White.copy(alpha = 0.80f)
        val selectedBorder =
            if (dark) Color.White.copy(alpha = 0.40f) else Color.White.copy(alpha = 0.95f)

        GlassPanel(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(percent = 50),
        ) {
            Box(
                modifier = Modifier
                    .padding(TabBarPadding)
                    .offset(x = indicatorOffset)
                    .width(tabWidth - TabBarPadding * 2)
                    .fillMaxHeight()
                    .background(selectedTint, RoundedCornerShape(percent = 50))
                    .border(1.dp, selectedBorder, RoundedCornerShape(percent = 50)),
            )
            Row(
                modifier = Modifier.fillMaxSize().padding(TabBarPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEachIndexed { index, tab ->
                    TabCell(
                        tab = tab,
                        selected = index == selectedIndex,
                        dark = dark,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onSelect(index) },
                    )
                }
            }
        }
    }
}

/**
 * 跟手拖动 + 阻尼回弹。为多段开关量身改造：值域是「第几格」，
 * 拖动时立即跟手，松手后带临界阻尼弹簧吸附到最近的整格。
 */
private class DampedDragAnimation(
    private val animationScope: CoroutineScope,
    initialValue: Float,
    private val valueRange: ClosedRange<Float>,
    private val visibilityThreshold: Float,
    private val initialScale: Float,
    private val pressedScale: Float,
    private val onDragStarted: (Offset) -> Unit,
    private val onDragStopped: DampedDragAnimation.() -> Unit,
    private val onDrag: DampedDragAnimation.(IntSize, Offset) -> Unit,
) {

    private val valueSpec = spring(1f, 1000f, visibilityThreshold)
    private val velocitySpec = spring(0.5f, 300f, visibilityThreshold * 10f)
    private val pressSpec = spring(1f, 1000f, 0.001f)
    private val scaleXSpec = spring(0.6f, 250f, 0.001f)
    private val scaleYSpec = spring(0.7f, 250f, 0.001f)

    private val valueAnimation = Animatable(initialValue, visibilityThreshold)
    private val velocityAnimation = Animatable(0f, 5f)
    private val pressAnimation = Animatable(0f, 0.001f)
    private val scaleXAnimation = Animatable(initialScale, 0.001f)
    private val scaleYAnimation = Animatable(initialScale, 0.001f)
    private val mutatorMutex = MutatorMutex()
    private val velocityTracker = VelocityTracker()

    val value: Float get() = valueAnimation.value
    val targetValue: Float get() = valueAnimation.targetValue
    val pressProgress: Float get() = pressAnimation.value
    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value
    val velocity: Float get() = velocityAnimation.value

    /** 手指按下并越过 slop，开始跟手。 */
    fun startDrag(offset: Offset) {
        onDragStarted(offset)
        press()
    }

    /** 拖动过程中的增量（只关心 x 方向）。 */
    fun dragBy(dx: Float) {
        onDrag(IntSize.Zero, Offset(dx, 0f))
    }

    /** 松手或取消：吸附到最近的整格。 */
    fun endDrag() {
        onDragStopped()
        release()
    }

    private fun press() {
        velocityTracker.resetTracking()
        animationScope.launch {
            launch { pressAnimation.animateTo(1f, pressSpec) }
            launch { scaleXAnimation.animateTo(pressedScale, scaleXSpec) }
            launch { scaleYAnimation.animateTo(pressedScale, scaleYSpec) }
        }
    }

    private fun release() {
        animationScope.launch {
            launch { pressAnimation.animateTo(0f, pressSpec) }
            launch { scaleXAnimation.animateTo(initialScale, scaleXSpec) }
            launch { scaleYAnimation.animateTo(initialScale, scaleYSpec) }
        }
    }

    fun updateValue(newValue: Float) {
        val target = newValue.coerceIn(valueRange)
        animationScope.launch {
            valueAnimation.animateTo(target, valueSpec) { updateVelocity() }
        }
    }

    fun animateToValue(newValue: Float) {
        animationScope.launch {
            mutatorMutex.mutate {
                press()
                val target = newValue.coerceIn(valueRange)
                launch { valueAnimation.animateTo(target, valueSpec) }
                if (velocity != 0f) {
                    launch { velocityAnimation.animateTo(0f, velocitySpec) }
                }
                release()
            }
        }
    }

    private fun updateVelocity() {
        velocityTracker.addPosition(
            System.currentTimeMillis(),
            Offset(value, 0f),
        )
        val span = valueRange.endInclusive - valueRange.start
        val targetVelocity = velocityTracker.calculateVelocity().x / span
        animationScope.launch { velocityAnimation.animateTo(targetVelocity, velocitySpec) }
    }
}
