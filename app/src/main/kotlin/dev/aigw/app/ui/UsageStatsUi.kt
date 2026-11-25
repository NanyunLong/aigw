package dev.aigw.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aigw.app.ui.theme.LocalAppColors
import dev.aigw.app.ui.theme.DeepInk
import dev.aigw.core.usage.UsageStats
import dev.aigw.core.usage.formatMicroUsd
import dev.aigw.core.usage.plus
import dev.aigw.core.util.startOfDay
import java.util.Calendar
import java.util.Date

/** 用量页时间范围。 */
enum class UsageRange(val label: String, val days: Int) {
    TODAY("今日", 1),
    WEEK("7天", 7),
    MONTH("30天", 30),
    ALL("全部", 0),
}

internal fun dayLabel(millis: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = millis }
    return String.format("%02d-%02d", c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
}

internal fun fullDayLabel(millis: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = millis }
    return String.format("%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
}

internal fun todayMillis(): Long = startOfDay(System.currentTimeMillis())

/** 范围对应的日列表：ALL 取 daily 全量（含今日），其余取末 N 天。 */
fun rangeDays(range: UsageRange, daily: List<UsageStats>): List<UsageStats> = when (range) {
    UsageRange.ALL -> daily
    else -> daily.takeLast(range.days)
}

fun rangeSum(days: List<UsageStats>): UsageStats = days.fold(UsageStats()) { a, b -> a + b }

/** 范围 Tab（今日/7天/30天/全部），样式与 Minis 的胶囊一致。 */
@Composable
fun RangeTabs(selected: UsageRange, onSelect: (UsageRange) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        UsageRange.entries.forEach { r ->
            val active = r == selected
            val color = if (active) LocalAppColors.current.goldEnd else MaterialTheme.colorScheme.onSurfaceVariant
            val bg = if (active) LocalAppColors.current.goldEnd.copy(alpha = 0.14f) else Color.Transparent
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(bg)
                    .clickable { onSelect(r) }.padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(r.label, fontSize = 13.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal, color = color)
            }
        }
    }
}

/**
 * Minis 风格的堆叠趋势图：输入（金）+ 输出（深墨）逐日堆叠，点选某天查看详情；
 * 无数据的天画虚线占位。选中天的列高亮。
 */
@Composable
fun StackedTrendChart(
    days: List<UsageStats>,
    selectedMillis: Long?,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
    barHeightDp: Int = 120,
    showXLabels: Boolean = true,
) {
    val gold = LocalAppColors.current.goldEnd
    val ink = DeepInk
    val muted = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    val track = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    val today = todayMillis()
    val maxTotal = (days.maxOfOrNull { it.totalTokens } ?: 0L).coerceAtLeast(1L)

    Column(modifier.fillMaxWidth()) {
        // 柱体：每列一层透明可点 Box，叠在 Canvas 上
        Box(Modifier.fillMaxWidth().height(barHeightDp.dp)) {
            Canvas(Modifier.fillMaxWidth().height(barHeightDp.dp)) {
                if (days.isEmpty()) return@Canvas
                val n = days.size
                val step = size.width / n
                val barW = (step * 0.52f).coerceIn(6.dp.toPx(), 30.dp.toPx())
                val bottom = size.height
                days.forEachIndexed { i, d ->
                    val cx = step * i + step / 2
                    val selected = selectedMillis != null && d.dayMillis == selectedMillis
                    val isToday = d.dayMillis == today
                    if (d.totalTokens <= 0L) {
                        drawLine(
                            color = track,
                            start = Offset(cx, bottom * 0.2f),
                            end = Offset(cx, bottom),
                            strokeWidth = 2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                        )
                    } else {
                        val totalH = (d.totalTokens.toFloat() / maxTotal) * (bottom * 0.92f)
                        val inH = totalH * (d.promptTokens.toFloat() / d.totalTokens.toFloat().coerceAtLeast(1f))
                        val outH = (totalH - inH).coerceAtLeast(0f)
                        val alpha = if (selectedMillis == null || selected) 1f else 0.45f
                        if (outH > 0f) {
                            drawRect(
                                color = ink.copy(alpha = alpha),
                                topLeft = Offset(cx - barW / 2, bottom - totalH),
                                size = androidx.compose.ui.geometry.Size(barW, outH),
                            )
                        }
                        if (inH > 0f) {
                            drawRect(
                                color = gold.copy(alpha = alpha),
                                topLeft = Offset(cx - barW / 2, bottom - inH),
                                size = androidx.compose.ui.geometry.Size(barW, inH),
                            )
                        }
                    }
                    if (selected) {
                        drawLine(color = gold, start = Offset(cx, 0f), end = Offset(cx, bottom), strokeWidth = 1.dp.toPx())
                    } else if (isToday) {
                        drawCircle(color = gold, radius = 2.dp.toPx(), center = Offset(cx, bottom - 2.dp.toPx()))
                    }
                }
            }
            // 点击热区
            if (days.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().height(barHeightDp.dp)) {
                    days.forEach { d ->
                        Box(
                            Modifier.weight(1f).height(barHeightDp.dp)
                                .clickable { onSelect(d.dayMillis) },
                        )
                    }
                }
            }
        }
        if (showXLabels && days.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                days.forEach { d ->
                    val selected = selectedMillis != null && d.dayMillis == selectedMillis
                    Text(
                        text = if (days.size <= 8) dayLabel(d.dayMillis) else dayLabel(d.dayMillis).takeLast(2),
                        fontSize = 9.sp,
                        color = if (selected) gold else muted,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.weight(1f).padding(vertical = 2.dp),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Minis 风格排行榜（对齐 UsageStatsScreen.kt 的 GroupList/GroupRow）。 */
private const val TOP_N = 8

@Composable
fun UsageRankList(
    title: String,
    entries: List<Pair<String, UsageStats>>,
    nameOf: (String) -> String,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) return
    val gold = LocalAppColors.current.goldEnd
    val onVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val track = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
    val grandTotal = entries.sumOf { it.second.totalTokens }.coerceAtLeast(1L)
    val known = entries.filter { it.first.isNotBlank() }
    val unknown = entries.filter { it.first.isBlank() }
    val cut = if (known.size == TOP_N + 1) known.size else TOP_N
    val top = known.take(cut)
    val rest = known.drop(cut)
    var showRest by remember { mutableStateOf(false) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        (if (showRest) known else top).forEach { (key, s) ->
            MinisGroupRow(
                name = nameOf(key),
                stats = s,
                share = s.totalTokens.toFloat() / grandTotal,
                gold = gold, track = track, onVariant = onVariant,
            )
        }
        if (rest.isNotEmpty() && !showRest) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { showRest = true }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("其他 ${rest.size} 项", fontSize = 12.sp, color = gold, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(formatTokens(rest.sumOf { it.second.totalTokens }), fontSize = 12.sp, color = onVariant)
            }
        }
        unknown.forEach { (key, s) ->
            MinisGroupRow(
                name = nameOf(key).ifBlank { "未知" },
                stats = s,
                share = s.totalTokens.toFloat() / grandTotal,
                gold = gold, track = track, onVariant = onVariant,
            )
        }
    }
}

@Composable
private fun MinisGroupRow(
    name: String,
    stats: UsageStats,
    share: Float,
    gold: Color,
    track: Color,
    onVariant: Color,
) {
    val total = stats.totalTokens
    // AIGW: promptTokens 不含缓存, cachedTokens = cacheRead；无 cacheWrite 字段
    val input = stats.promptTokens
    val cacheRead = stats.cachedTokens
    val cacheWrite = 0L
    val output = stats.completionTokens
    val nonCached = input + cacheWrite + output
    val inputInclCache = input + cacheRead + cacheWrite
    val hitRate = if (inputInclCache > 0) cacheRead.toDouble() / inputInclCache else null

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                formatTokens(total),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = gold,
            )
        }
        Spacer(Modifier.height(4.dp))
        // 进度条 + 占比
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(track)
            ) {
                Box(
                    Modifier.fillMaxWidth(share.coerceIn(0f, 1f)).height(4.dp)
                        .clip(RoundedCornerShape(2.dp)).background(gold)
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "${(share * 100).toInt()}%",
                fontSize = 11.sp,
                color = onVariant,
            )
        }
        Spacer(Modifier.height(3.dp))
        // 字段行1：总（不含缓存读）· 输入（含缓存）
        Text(
            "总(不含缓存读) ${formatTokens(nonCached)} · 输入(含缓存) ${formatTokens(inputInclCache)}",
            fontSize = 11.sp,
            color = onVariant,
            lineHeight = 16.sp,
        )
        // 字段行2：输出 · 缓存读 · 命中率
        Text(
            buildString {
                append("输出 ${formatTokens(output)} · 缓存读 ${formatTokens(cacheRead)}")
                if (cacheWrite > 0) append(" · 缓存写 ${formatTokens(cacheWrite)}")
                hitRate?.let { append(" · 命中率 ${(it * 100).toInt()}%") }
                append(" · ${stats.requests} 次")
                if (stats.costMicros > 0) append(" · ${formatMicroUsd(stats.costMicros)}")
            },
            fontSize = 11.sp,
            color = onVariant,
            lineHeight = 16.sp,
        )
    }
}
