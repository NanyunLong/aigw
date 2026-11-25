package dev.aigw.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.sp
import dev.aigw.app.data.CrashReporter
import dev.aigw.app.ui.theme.DeepInk
import dev.aigw.app.ui.theme.LocalAppColors
import dev.aigw.app.ui.theme.formatHex
import dev.aigw.core.usage.StorageAudit
import dev.aigw.core.usage.StorageEntry
import dev.aigw.core.usage.StorageReport
import dev.aigw.core.usage.UsageStats
import dev.aigw.core.usage.formatMicroUsd
import dev.aigw.core.usage.plus
import dev.aigw.core.provider.custom.ProviderPresets
import dev.aigw.core.util.startOfDay
import java.util.Locale

@Composable
fun MeScreen(state: AppUiState, viewModel: AppViewModel, open: (SubPage) -> Unit) {
    val context = LocalContext.current
    // 点标题「我的」弹出作者信息
    var showAbout by remember { mutableStateOf(false) }
    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
    }

    PageScaffold(
        title = "我的",
        subtitle = "网关与偏好设置",
        onTitleClick = { showAbout = true },
    ) {
        // 顶部事实陈述：全部取自可核实的运行状态
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = if (state.running) "本地网关 · 运行中" else "本地网关 · 已停止",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "监听 :${state.port} · 供应商 ${state.providers.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "${state.providers.size} 项",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Light,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        EditorialGroupHeader("01", "外观与主题 · APPEARANCE")
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            EditorialRow(
                "01",
                "主题设置",
                "明暗模式、配色、底栏样式",
                appearanceMeta(state),
            ) { open(SubPage.ThemeSettings) }
        }

        EditorialGroupHeader("02", "核心功能偏好 · CORE FUNCTIONS")
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            EditorialRow("02.1", "接入设置 · ENDPOINTS", "端口与 API Key", ":${state.port}") { open(SubPage.ApiSettings) }
            EditorialRow(
                "02.2",
                "安全防护 · SECURITY",
                "风控提示词替换、反审核脱敏与账号限速",
                when {
                    state.security.promptRewriteEnabled -> "提示词替换开启"
                    state.security.sanitizeEnabled -> "脱敏开启"
                    else -> "全部关闭"
                },
            ) { open(SubPage.SecurityProtection) }
            EditorialRow(
                "02.3",
                "并发与容错 · RESILIENCE",
                "并发闸门、重试退避与熔断器",
                concurrencySummary(state.failover),
            ) { open(SubPage.Resilience) }
            EditorialRow(
                "02.4",
                "额度中心 · CREDITS",
                "按供应商查看额度包明细",
                if (state.pool.creditsKnown > 0) formatCount(state.pool.totalCredits) else "",
            ) { open(SubPage.CreditCenter) }
            EditorialRow("02.5", "用量统计 · TELEMETRY", "请求数、token 与成功率", formatTokens(state.today.totalTokens)) {
                open(SubPage.Usage)
            }
            EditorialRow(
                "02.6",
                "代理设置 · NETWORK PROXY",
                "多套配置，按供应商或区域决定是否走代理",
                when {
                    state.proxy.profiles.isEmpty() -> "未配置"
                    state.proxy.usable -> "使用中 ${state.proxy.activeProfile?.name.orEmpty()}"
                    else -> "${state.proxy.profiles.size} 套配置"
                },
            ) { open(SubPage.Proxy) }
            EditorialRow(
                "02.7",
                "保活与权限 · BACKGROUND",
                "通知、电池优化与后台限制",
                if (state.keepAlive?.allGood == true) "就绪" else "待配置",
            ) { open(SubPage.KeepAlive) }
            EditorialSwitchRow(
                index = "02.8",
                title = "实时状态通知 · LIVE STATUS",
                subtitle = "仅网关有请求时显示，点击回到 App",
                checked = state.liveNotification,
                onCheckedChange = { viewModel.setLiveNotification(it) },
            )
            EditorialRow(
                "02.9",
                "账号导入导出 · ACCOUNTS BACKUP",
                "按供应商与账号备份、迁移凭证",
                "${state.pool.total} 个账号",
            ) { open(SubPage.AccountTransfer) }
            EditorialRow(
                "02.10",
                "客户端密钥 · CLIENT KEYS",
                "谁能调用网关，可限制模型与用量",
                if (state.clientKeys.isEmpty()) "未创建" else "${state.clientKeys.size} 把",
            ) { open(SubPage.ClientKeys) }
            EditorialRow(
                "02.11",
                "路由组 · ROUTING GROUPS",
                "多个模型合成一个对外模型名",
                if (state.routingGroups.groups.isEmpty()) "未创建" else "${state.routingGroups.groups.size} 组",
            ) { open(SubPage.RoutingGroups) }
            EditorialRow(
                "02.12",
                "模型价格 · PRICING",
                "按 token 计算调用费用",
                if (state.pricing.prices.isEmpty()) "未配置" else "${state.pricing.prices.size} 个模型",
            ) { open(SubPage.ModelPricing) }
            EditorialRow(
                "02.13",
                "请求改写 · REWRITE",
                "模型别名、system 追加与思考标签",
                rewriteSummary(state.middleware),
            ) { open(SubPage.RequestRewrite) }
            EditorialRow(
                "02.14",
                "崩溃日志 · CRASH",
                "异常退出时的堆栈记录",
                crashSummary(),
            ) { open(SubPage.CrashLog) }
        }

        EditorialGroupHeader("03", "架构关于 · ABOUT THE ENGINE")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "ai-gateway · 本地 OpenAI 兼容网关",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "把多个上游统一成一个 OpenAI 兼容端点，供任意客户端调用。" +
                    "内置 Trae / Loomy / WorkBuddy(CodeBuddy) / Antigravity / ChatGPT / 小浣熊，" +
                    "另收录 ${ProviderPresets.all.size} 家常⻅商家（DeepSeek、Kimi、智谱、Gemini、OpenRouter 等）" +
                    "与任意自定义 OpenAI 兼容接口，填 Key 即可反代。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "应用版本 ${appVersion(context)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        EditorialRow(
            "04.1",
            "检查更新 · CHECK UPDATE",
            "从 GitHub Releases 获取最新版本",
            if (state.updateChecking) "检查中…" else "",
        ) { if (!state.updateChecking) viewModel.checkUpdate() }

        EditorialRow(
            "04.2",
            "项目地址 · GITHUB",
            "在浏览器打开源码仓库",
            "GitHub",
        ) { viewModel.openProjectRepo() }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text("自动检查更新", style = MaterialTheme.typography.titleSmall)
                Text(
                    "打开应用时后台检测，每天最多一次",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.autoCheckUpdate,
                onCheckedChange = { viewModel.setAutoCheckUpdate(it) },
            )
        }
    }
}

/**
 * 编辑式条目：左侧等宽编号 + 标题 + 灰色副标题，右侧真实元数据（为空则不显示）+ 金色箭头。
 */
@Composable
private fun EditorialRow(
    index: String,
    title: String,
    subtitle: String,
    meta: String,
    onClick: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().pressScale().clickable(onClick = onClick).padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = index,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // 标题固定单行：「中文 · ENGLISH」被挤成两行会破坏栅格（英文标签跑到第二行）
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(text = "→", style = MaterialTheme.typography.bodyMedium, color = LocalAppColors.current.goldStart)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** 编辑式开关条目：编号 + 标题 + 副标题 + 右侧 Switch（与 [EditorialRow] 同一栅格）。 */
@Composable
private fun EditorialSwitchRow(
    index: String,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = index,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** 外观主题条目的元信息：当前模式 + 是否自定义金色。 */
private fun appearanceMeta(state: AppUiState): String {
    val mode = when (state.themeMode) {
        dev.aigw.app.ui.theme.ThemeMode.Light -> "浅色"
        dev.aigw.app.ui.theme.ThemeMode.Dark -> "深色"
        dev.aigw.app.ui.theme.ThemeMode.System -> "自动"
    }
    val customGold = formatHex(state.goldStart) != "#D4A843" || formatHex(state.goldEnd) != "#B88A20"
    return mode + " · " + (if (customGold) formatHex(state.goldStart) else "默认金")
}

private fun appVersion(context: android.content.Context): String = runCatching {
    val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
    val name = pkg.versionName.orEmpty()
    @Suppress("DEPRECATION")
    val code = pkg.versionCode
    "${name.ifEmpty { "—" }}（build $code）"
}.getOrDefault("—")

/** 点「我的」标题弹出的作者信息：两行居中，点任意处关闭。 */
@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        text = {
            DialogContentColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "夕白",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "3884021834",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}

@Composable
fun UsageScreen(state: AppUiState, viewModel: AppViewModel, onBack: () -> Unit) {
    // 存储占用不挂在全局 refresh 里（要扫全量存储键并解密，代价高），
    // 本页「存储」卡片读的是 state 里的同一份，不主动刷新会恒为 0，故进入本页时算一次。
    LaunchedEffect(Unit) { viewModel.refreshStorage() }
    var range by remember { mutableStateOf(UsageRange.WEEK) }
    var selectedDay by remember { mutableStateOf<Long?>(null) }
    val days = remember(state.daily, range) { rangeDays(range, state.daily) }
    val sum = remember(days) { rangeSum(days) }
    val providerName = remember(state.providers) {
        val m = state.providers.associate { it.id to it.displayName }
        val f: (String) -> String = { id -> m[id] ?: id.removePrefix("custom:") }
        f
    }

    PageScaffold(
        title = "用量统计",
        subtitle = "趋势、分组与累计",
        leading = {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.clickable(onClick = onBack).padding(6.dp),
            )
        },
    ) {
        RangeTabs(range) { r ->
            range = r
            selectedDay = null
        }

        // 范围总览
        SectionCard(enterIndex = 3) {
            SectionLabel(if (range == UsageRange.ALL) "总览" else range.label)
            OverviewGrid(sum)
            CostLine(sum)
        }

        // 趋势（可点选某天）
        SectionCard(enterIndex = 4) {
            SectionLabel("趋势")
            if (range == UsageRange.TODAY) {
                Text(
                    text = "今日用量按天粒度展示，点开「7天/30天」可看逐日趋势",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                StackedTrendChart(days = days, selectedMillis = selectedDay, onSelect = { day ->
                    selectedDay = if (selectedDay == day) null else day
                })
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    LegendDot(LocalAppColors.current.goldStart, "输入")
                    LegendDot(DeepInk, "输出")
                    val total = days.sumOf { it.totalTokens }
                    Text(
                        text = "共 ${formatTokens(total)} tokens",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.End,
                    )
                }
                selectedDay?.let { day ->
                    val s = days.firstOrNull { it.dayMillis == day }
                    if (s != null) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text(
                            text = fullDayLabel(day),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = LocalAppColors.current.goldStart,
                        )
                        SelectedDayStats(s)
                    }
                }
            }
        }

        // 服务商 / 模型排行
        val groupsInRange = remember(state.groups, days) {
            if (range == UsageRange.ALL) state.groups else state.groups.takeLast(days.size)
        }
        val byProvider = remember(groupsInRange) {
            groupsInRange.flatMap { it.byProvider.entries }
                .groupBy({ it.key }, { it.value })
                .mapValues { (_, list) -> list.fold(UsageStats()) { a, b -> a + b } }
                .entries.sortedByDescending { it.value.totalTokens }
        }
        val byModel = remember(groupsInRange) {
            groupsInRange.flatMap { it.byModel.entries }
                .groupBy({ it.key }, { it.value })
                .mapValues { (_, list) -> list.fold(UsageStats()) { a, b -> a + b } }
                .entries.sortedByDescending { it.value.totalTokens }
        }
        SectionCard(enterIndex = 5) {
            SectionLabel("服务商")
            if (byProvider.isEmpty()) {
                Text(
                    text = "该范围暂无调用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                UsageRankList("", byProvider.map { it.key to it.value }, providerName)
            }
        }
        SectionCard(enterIndex = 6) {
            SectionLabel("模型")
            if (byModel.isEmpty()) {
                Text(
                    text = "该范围暂无调用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val id: (String) -> String = { s -> s }
                UsageRankList("", byModel.map { it.key to it.value }, id)
            }
        }

        SectionCard(enterIndex = 8) {
            SectionLabel("存储")
            val report = state.storage
            if (report == null) {
                Text(
                    text = "正在统计…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                StorageDetail(state, report, viewModel)
            }
        }
    }
}

/** 点选某天后的详细统计（复用 AIGW 现有的字段）。 */
@Composable
private fun SelectedDayStats(stats: UsageStats) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            StatCell(formatTokens(stats.promptTokens), "输入")
            StatCell(formatTokens(stats.completionTokens), "输出")
            StatCell(formatTokens(stats.cachedTokens), "缓存命中")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            StatCell(formatCount(stats.requests), "请求")
            StatCell(formatCount(stats.success), "成功")
            StatCell(formatCount(stats.failed), "失败")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            StatCell(
                value = if (stats.cacheHitPercent != null) String.format(Locale.US, "%.0f%%", stats.cacheHitPercent) else "—",
                label = "命中率",
            )
            StatCell(formatCount(stats.retried), "换号救回")
            StatCell(formatMicroUsd(stats.costMicros), "费用（美元）")
        }
    }
}


/**
 * 总览：累计大数字两排六格，用现有 StatCell 保持视觉一致。
 * 第一排：输入/输出/请求；第二排：命中率/缓存命中/费用。
 */
@Composable
private fun OverviewGrid(stats: UsageStats) {
    val gold = LocalAppColors.current.goldStart
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth()) {
            StatCell(formatTokens(stats.promptTokens), "输入合计", Modifier.weight(1f))
            StatCell(formatTokens(stats.completionTokens), "输出合计", Modifier.weight(1f))
            StatCell(formatCount(stats.requests), "累计请求", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth()) {
            StatCell(
                value = if (stats.cacheHitPercent != null) String.format(Locale.US, "%.0f%%", stats.cacheHitPercent) else "—",
                label = "命中率",
                modifier = Modifier.weight(1f),
            )
            StatCell(formatTokens(stats.cachedTokens), "缓存命中", Modifier.weight(1f))
            StatCell(formatMicroUsd(stats.costMicros), "费用（美元）", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth()) {
            StatCell(formatCount(stats.success), "成功", Modifier.weight(1f))
            StatCell(formatCount(stats.failed), "失败", Modifier.weight(1f))
            StatCell(formatCount(stats.aborted), "中断", Modifier.weight(1f))
        }
    }
}

/**
 * 存储明细与清理。
 *
 * 光报一个总量没有用——用户看到「应用占了 84MB」却无从下手。这里把占用拆到每一项，
 * 并标注哪些是可安全删除的缓存，让「清什么」变成确定的选择。
 */
@Composable
private fun StorageDetail(state: AppUiState, report: StorageReport, viewModel: AppViewModel) {
    var confirmCaches by remember { mutableStateOf(false) }
    var confirmCalls by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = StorageAudit.formatSize(report.totalBytes),
            style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
            fontWeight = FontWeight.Light,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "应用当前占用",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 3.dp),
        )
    }

    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        report.entries.forEachIndexed { index, entry ->
            if (index > 0) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(vertical = 9.dp),
                )
            }
            StorageEntryRow(entry)
        }
    }

    if (report.clearableBytes > 0) {
        OutlineActionButton(
            text = "清理缓存 · ${StorageAudit.formatSize(report.clearableBytes)}",
            modifier = Modifier.padding(top = 14.dp),
            onClick = { confirmCaches = true },
        )
    }
    OutlineActionButton(
        text = "清空调用记录",
        modifier = Modifier.padding(top = if (report.clearableBytes > 0) 8.dp else 14.dp),
        onClick = { confirmCalls = true },
    )

    if (confirmCaches) {
        ConfirmDestructiveDialog(
            title = "清理缓存？",
            body = "将删除下载的更新包、崩溃日志与调用记录的完整原文副本，" +
                "释放约 ${StorageAudit.formatSize(report.clearableBytes)}。" +
                "调用记录列表、用量统计与账号不受影响。",
            confirmText = "清理",
            onConfirm = { viewModel.clearCaches() },
            onDismiss = { confirmCaches = false },
        )
    }
    if (confirmCalls) {
        ConfirmDestructiveDialog(
            title = "清空全部调用记录？",
            body = "将删除本机保存的 ${state.storedCalls} 条调用记录（含完整原文），不可恢复。",
            confirmText = "清空",
            onConfirm = { viewModel.clearCalls() },
            onDismiss = { confirmCalls = false },
        )
    }
}

/** 一条占用明细：左对齐名称 + 说明，右对齐体积与条数。 */
@Composable
private fun StorageEntryRow(entry: StorageEntry) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // 「可清理」只是提示「删了不影响功能」，不是警告，所以不用高亮色
                if (entry.clearable) {
                    Text(
                        text = "可清理",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
            if (entry.detail.isNotEmpty()) {
                Text(
                    text = entry.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = StorageAudit.formatSize(entry.bytes),
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        if (entry.items > 0) {
            Text(
                text = "${entry.items} 项",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun LegendDot(color: Color, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 费用与重试两行。
 *
 * 未定价的调用不能算进金额，也不能默默省略——并排告知「另有 N 次未定价」，
 * 否则总额看起来偏低而用户不知道原因。
 */
@Composable
private fun CostLine(stats: UsageStats) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StatCell(formatCount(stats.retried), "换号救回")
        StatCell(formatMicroUsd(stats.costMicros), "费用（美元）")
        if (stats.unpriced > 0) {
            StatCell(formatCount(stats.unpriced), "未定价调用")
        }
    }
}


/**
 * 「并发与容错」入口的右侧元信息。
 *
 * 写成紧凑形式（不带空格）：这一行的标题是全页最长（「并发与容错 · RESILIENCE」），
 * 元信息再用「并发 单账号 1 / 全局 0」这种长文案会把英文标签挤到第二行。
 */
private fun concurrencySummary(failover: dev.aigw.core.failover.ModelFailoverSettings): String {
    val concurrency = failover.concurrency
    return when {
        concurrency.maxPerAccount == 0 && concurrency.maxGlobal == 0 -> "并发不限"
        else -> "单账号${concurrency.maxPerAccount}/全局${concurrency.maxGlobal}"
    }
}

/** 请求改写入口行的右侧摘要：一眼看出开了哪几项。 */
private fun rewriteSummary(settings: dev.aigw.core.gateway.MiddlewareSettings): String {
    val parts = buildList {
        if (settings.aliases.isNotEmpty()) add("别名 ${settings.aliases.size}")
        if (settings.systemPromptAppend.isNotBlank()) add("追加提示词")
        if (settings.stripThinkTags) add("清理思考标签")
    }
    return if (parts.isEmpty()) "未启用" else parts.joinToString("、")
}

/** 崩溃日志摘要：没记录时不提「0 份」，免得以为是功能坏了。 */
@Composable
private fun crashSummary(): String {
    val context = LocalContext.current
    val count = remember { CrashReporter.list(context).size }
    return if (count == 0) "无记录" else "$count 份"
}
