package dev.aigw.app.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aigw.core.gateway.CustomProviderConfig
import dev.aigw.core.provider.custom.CustomProvider
import dev.aigw.core.provider.custom.ProviderPreset
import dev.aigw.core.provider.custom.ProviderPresets
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 自定义供应商的新建/编辑。
 *
 * 一屏填完三件事：连得上（地址 + Key）、看得见模型（拉取或手填）、存得下（名称与短名）。
 * 老流程把 Key 与模型留到详情页才可填，用户保存完拿到的是一个 0 账号、用不了的供应商。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomProviderScreen(
    existing: CustomProviderConfig?,
    existingKeys: List<String>,
    onSave: (CustomProviderConfig) -> Unit,
    onDelete: ((String) -> Unit)?,
    onFetchModels: (baseUrl: String, apiKey: String, onResult: (List<String>, String) -> Unit) -> Unit,
    onBack: () -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    // 短名默认随名称派生，用户手动改过之后就不再覆盖
    var key by remember { mutableStateOf(existing?.key.orEmpty()) }
    var keyEdited by remember { mutableStateOf(existing != null) }
    var baseUrl by remember { mutableStateOf(existing?.baseUrl.orEmpty()) }
    var apiKeysText by remember { mutableStateOf(existingKeys.joinToString("\n")) }
    var modelsText by remember { mutableStateOf(existing?.models.orEmpty().joinToString("\n")) }
    var advanced by remember { mutableStateOf(existing != null) }
    var fetched by remember { mutableStateOf<List<String>?>(null) }
    var fetchError by remember { mutableStateOf("") }
    var fetching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    // 「连接」卡片的定位器：点商家「选用」后平滑滚动到这里，把注意力带到已经填好的表单。
    val connectionRequester = remember { BringIntoViewRequester() }
    val scrollScope = rememberCoroutineScope()

    val baseUrlValue = baseUrl.trim()
    val normalizedBase = remember(baseUrlValue) { CustomProvider.normalizeBaseUrl(baseUrlValue) }
    val firstKey = apiKeysText.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
    val parsedModels = modelsText.lines().map { it.trim() }.filter { it.isNotEmpty() }

    PageScaffold(
        title = if (existing == null) "新建供应商" else "编辑供应商",
        subtitle = "OpenAI 兼容接口",
        leading = {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.clickable(onClick = onBack).padding(6.dp),
            )
        },
    ) {
        // 新建时先给一张「常见商家」清单：多数人只是要把某家的 Key 挂进来，
        // 让他自己回忆 baseUrl 是哪种写法（带不带 /v1、/v4）只会写错。
        if (existing == null) {
            PresetPicker(
                onPick = { preset ->
                    name = preset.name
                    key = CustomProviderConfig.deriveKey(preset.name)
                    keyEdited = false
                    baseUrl = preset.baseUrl
                    // 填好后把「连接」表单平滑滚进视野，省去再往下翻找；
                    // 稍等一帧等布局稳定（商家清单会同时收起）再触发。
                    scrollScope.launch {
                        delay(150)
                        connectionRequester.bringIntoView()
                    }
                },
            )
        }

        SectionCard(modifier = Modifier.bringIntoViewRequester(connectionRequester)) {
            SectionLabel("连接")
            OutlinedTextField(
                value = name,
                onValueChange = { value ->
                    name = value
                    if (!keyEdited) key = CustomProviderConfig.deriveKey(value)
                },
                label = { Text("名称") },
                placeholder = { Text("给自己看的名字") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("接口地址") },
                placeholder = { Text("https://api.example.com") },
                singleLine = true,
                supportingText = {
                    Text(
                        text = if (normalizedBase.isEmpty()) {
                            "域名或带 /v1 的地址都行，/v1 会自动补齐"
                        } else {
                            "实际请求 $normalizedBase/chat/completions"
                        },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = apiKeysText,
                onValueChange = { apiKeysText = it },
                label = { Text("API Key") },
                placeholder = { Text("一行一个，多个 Key 轮流使用") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SectionCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel("模型")
                Spacer(Modifier.weight(1f))
                Row(
                    modifier = Modifier.clickable(enabled = !fetching) {
                        when {
                            baseUrlValue.isEmpty() -> fetchError = "先填写接口地址"
                            firstKey.isEmpty() -> fetchError = "先填写 API Key"
                            else -> {
                                fetchError = ""
                                fetching = true
                                onFetchModels(baseUrlValue, firstKey) { list, message ->
                                    fetching = false
                                    if (message.isEmpty()) fetched = list else fetchError = message
                                }
                            }
                        }
                    },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Filled.CloudDownload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = when {
                            fetching -> "拉取中"
                            parsedModels.isEmpty() -> "获取模型"
                            else -> "重新获取"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (fetchError.isNotEmpty()) {
                Text(
                    text = fetchError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedTextField(
                value = modelsText,
                onValueChange = { modelsText = it },
                label = { Text("模型 id") },
                placeholder = { Text("一行一个；也可以点右上角获取") },
                minLines = 3,
                supportingText = { Text("已填 ${parsedModels.size} 个") },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SectionCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { advanced = !advanced },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "高级",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (advanced) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (advanced) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (advanced) {
                OutlinedTextField(
                    value = key,
                    onValueChange = {
                        key = it
                        keyEdited = true
                    },
                    label = { Text("模型前缀") },
                    singleLine = true,
                    enabled = existing == null,
                    supportingText = {
                        Text(
                            text = if (key.isEmpty()) {
                                "自动由名称生成；模型对外 id 为 custom:<前缀>/<模型名>"
                            } else {
                                "模型对外 id：custom:$key/<模型名>"
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        SectionCard {
            if (error.isNotEmpty()) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                onClick = {
                    val normalizedKey = CustomProviderConfig.normalizeKey(key)
                    error = when {
                        name.isBlank() -> "请填写名称"
                        baseUrlValue.isEmpty() -> "请填写接口地址"
                        firstKey.isEmpty() -> "请填写 API Key"
                        parsedModels.isEmpty() -> "至少填一个模型 id，或点「获取模型」"
                        normalizedKey.isEmpty() -> "名称里没有可用的英文字母，请在「高级」里填一个模型前缀"
                        else -> ""
                    }
                    if (error.isEmpty()) {
                        onSave(
                            CustomProviderConfig(
                                key = normalizedKey,
                                name = name.trim(),
                                baseUrl = baseUrlValue,
                                apiKeys = apiKeysText.lines(),
                                models = parsedModels,
                            ),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (existing == null) "保存并启用" else "保存")
            }
            if (existing != null && onDelete != null) {
                OutlineActionButton("删除该供应商") { onDelete(existing.key) }
            }
        }
    }

    fetched?.let { list ->
        MultiSelectSheet(
            title = "选择模型",
            subtitle = "共拉到 ${list.size} 个",
            items = list,
            keyOf = { it },
            labelOf = { it },
            emptyHint = "上游没有返回模型",
            confirmLabel = { count -> "保存 $count 个" },
            onDismiss = { fetched = null },
            onConfirm = { selected ->
                modelsText = selected.joinToString("\n")
                fetched = null
            },
            preselected = remember(list) { existing?.models.orEmpty().toSet() },
        )
    }
}

/**
 * 常见商家清单。
 *
 * 折叠状态只占一行，避免把「连接」表单压到屏幕外；展开后按「国内直连 / 海外直连 / 聚合中转」
 * 分组列出，点一家就把名称与地址填进上面的表单。
 *
 * 只填名称与地址，**不填模型**：模型列表由用户点「获取模型」实时拉取，
 * 这里写死任何清单都只会过期。
 *
 * 每家额外给一条「获取 Key」：点它打开该商家的控制台。多数人卡住的不是填地址，
 * 而是「去哪申请」。地址同时可长按复制——在浏览器里打不开或想换设备申请时用得上。
 */
@Composable
private fun PresetPicker(onPick: (ProviderPreset) -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var expanded by remember { mutableStateOf(false) }
    var group by remember { mutableStateOf(ProviderPreset.GROUP_CN) }
    val groups = remember { ProviderPresets.grouped() }
    val current = groups.firstOrNull { it.first == group }?.second.orEmpty()

    /** 打开商家控制台；没有 Custom Tab 或浏览器时退回复制，至少让用户拿得到地址。 */
    fun openConsole(url: String) {
        val opened = runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrDefault(false)
        if (!opened) {
            clipboard.setText(AnnotatedString(url))
            Toast.makeText(context, "没有可用浏览器，官网地址已复制", Toast.LENGTH_SHORT).show()
        }
    }

    SectionCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "从常见商家选择",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "共收录 ${ProviderPresets.all.size} 家，选中自动填好接口地址",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!expanded) return@SectionCard

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for ((name, _) in groups) {
                ChoiceChip(name, name == group) { group = name }
            }
        }

        Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            for (preset in current) {
                Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(preset.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = preset.note,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = "选用",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable {
                                    onPick(preset)
                                    expanded = false
                                }
                                .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                        )
                    }
                    if (preset.consoleUrl.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Text(
                                text = "获取 Key",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable { openConsole(preset.consoleUrl) }
                                    .padding(vertical = 3.dp),
                            )
                            Text(
                                text = "复制官网链接",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .clickable {
                                        clipboard.setText(AnnotatedString(preset.consoleUrl))
                                        Toast.makeText(context, "已复制 ${preset.name} 官网链接", Toast.LENGTH_SHORT)
                                            .show()
                                    }
                                    .padding(vertical = 3.dp),
                            )
                            Text(
                                text = preset.consoleUrl.removePrefix("https://").removePrefix("http://"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}