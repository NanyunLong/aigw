package io.aigw

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主界面（照 loomy 的 Compose 原生 UI）。
 *
 * 结构：顶部状态栏（网关状态+端口）→ 总额度卡片 → 账号列表（账号卡片，含额度/有效期/操作）
 * → 底部"添加账号"按钮（弹底部表单，选提供商 → 按登录优先级出对应输入）。
 *
 * 登录方式优先级（用户定死）：手机验证码 > 网页登录 > 微信扫码 > 配 key。
 */
class MainActivity : ComponentActivity() {

    private lateinit var pool: AccountPool
    private var server: GatewayServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pool = AccountPool(applicationContext)
        pool.load()
        pool.bootstrap()

        // 启动内嵌网关（默认 8787，照 loomy 的落地即面板）
        server = GatewayServer(8787, pool).also { it.startGateway() }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                GatewayScreen(pool, server)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { server?.stop() } catch (e: Exception) {}
    }
}

@Composable
fun GatewayScreen(pool: AccountPool, server: GatewayServer?) {
    var refreshKey by remember { mutableIntStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf("") }
    var accountsUpdated by remember { mutableStateOf(pool.snapshot()) }

    // 定时刷新账号状态
    LaunchedEffect(refreshKey) {
        while (true) {
            kotlinx.coroutines.delay(3000)
            accountsUpdated = pool.snapshot()
        }
    }

    Scaffold(
        containerColor = Color(0xFF0F1117),
        topBar = {
            Column(Modifier.fillMaxWidth().background(Color(0xFF0F1117)).padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Storage, contentDescription = null, tint = Color(0xFF4FC3F7))
                    Spacer(Modifier.width(8.dp))
                    Text("AIGW 网关", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(Modifier.weight(1f))
                    if (server != null) {
                        Box(Modifier.size(8.dp).background(Color(0xFF66BB6A), RoundedCornerShape(50)))
                        Spacer(Modifier.width(6.dp))
                        Text("运行中", color = Color(0xFF66BB6A), fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("127.0.0.1:8787 · 本地 OpenAI 兼容网关", fontSize = 12.sp, color = Color(0xFF90A4AE))
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                containerColor = Color(0xFF1E88E5),
                contentColor = Color.White,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("添加账号") }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            // 总额度卡
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1D26))
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("总可用额度", fontSize = 13.sp, color = Color(0xFF90A4AE))
                    Spacer(Modifier.height(6.dp))
                    val total = accountsUpdated.sumOf { it.available ?: 0L }
                    Text("$total", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4FC3F7))
                    Spacer(Modifier.height(6.dp))
                    Row {
                        Text("账号 ${accountsUpdated.size} 个", fontSize = 12.sp, color = Color(0xFFB0BEC5))
                        Spacer(Modifier.width(16.dp))
                        val usable = accountsUpdated.count { it.enabled && it.sessionValid() }
                        Text("可用 $usable 个", fontSize = 12.sp, color = Color(0xFFB0BEC5))
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // 账号列表
            if (accountsUpdated.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.AccountCircle, contentDescription = null, tint = Color(0xFF546E7A), modifier = Modifier.size(56.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("还没有账号", color = Color(0xFF90A4AE), fontSize = 15.sp)
                        Spacer(Modifier.height(4.dp))
                        Text("点右下角「添加账号」开始", color = Color(0xFF546E7A), fontSize = 13.sp)
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(accountsUpdated, key = { it.name }) { acc ->
                        AccountCard(acc, onRefresh = {
                            // 刷新额度
                            pool.refreshQuota(acc)
                            refreshKey++
                        }, onRemove = {
                            pool.remove(acc.name)
                            refreshKey++
                        })
                    }
                    item {
                        // 日志区
                        Card(
                            Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF141821))
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text("网关日志", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF90A4AE))
                                Spacer(Modifier.height(8.dp))
                                Text(logText.ifEmpty { "（空）" }, fontSize = 11.sp, color = Color(0xFF607D8B), lineHeight = 16.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    // 添加账号弹窗
    if (showAdd) {
        AddAccountDialog(
            onDismiss = { showAdd = false },
            onAdd = { provider, loginid, password, token, cookies ->
                val acc = LoomyAccount(
                    name = loginid.ifEmpty { "$provider-${System.currentTimeMillis() % 10000}" },
                    loginid = loginid,
                    password = password,
                    provider = provider.id,
                    token = token,
                    cookies = cookies,
                    expireAt = 0L,
                )
                pool.add(acc)
                if (provider == ProviderId.LOOMY && loginid.isNotEmpty() && password.isNotEmpty()) {
                    try {
                        LoomyClient().fill(acc)
                        pool.save(true)
                    } catch (e: Exception) {
                        acc.lastError = e.message ?: "登录失败"
                    }
                }
                showAdd = false
                refreshKey++
            }
        )
    }
}

@Composable
fun AccountCard(acc: LoomyAccount, onRefresh: () -> Unit, onRemove: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1D26))
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).background(Color(0xFF263238), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Text(acc.provider.first().uppercase(), color = Color(0xFF4FC3F7), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(acc.name, fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 15.sp)
                    Text(acc.provider, fontSize = 12.sp, color = Color(0xFF90A4AE))
                }
                if (acc.enabled && acc.sessionValid()) {
                    Tag(status = "有效")
                } else {
                    Tag(status = "失效", color = Color(0xFFEF5350))
                }
            }
            Spacer(Modifier.height(12.dp))
            Row {
                InfoCell("余额", acc.balance?.toString() ?: "-", Modifier.weight(1f))
                InfoCell("可用", acc.available?.toString() ?: "-", Modifier.weight(1f))
                InfoCell("剩余天数", acc.daysLeft()?.let { "%.1f".format(it) } ?: "-", Modifier.weight(1f))
                InfoCell("请求数", acc.requests.toString(), Modifier.weight(1f))
            }
            if (acc.lastError.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(acc.lastError, fontSize = 11.sp, color = Color(0xFFEF5350), maxLines = 2)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onRefresh) { Text("刷新额度", color = Color(0xFF4FC3F7), fontSize = 12.sp) }
                TextButton(onClick = onRemove) { Text("删除", color = Color(0xFFEF5350), fontSize = 12.sp) }
            }
        }
    }
}

@Composable
fun Tag(status: String, color: Color = Color(0xFF66BB6A)) {
    Box(Modifier.background(color.copy(alpha = 0.15f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(status, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun InfoCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, fontSize = 11.sp, color = Color(0xFF78909C))
        Spacer(Modifier.height(2.dp))
        Text(value, fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Medium)
    }
}

// ---------------------------------------------------------------- 添加账号

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAccountDialog(
    onDismiss: () -> Unit,
    onAdd: (ProviderId, String, String, String, String) -> Unit,
) {
    var selectedProvider by remember { mutableStateOf(ProviderId.LOOMY) }
    var loginid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var cookies by remember { mutableStateOf("") }

    val methods = ProviderLogin.methods(selectedProvider)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1D26),
        title = { Text("添加账号", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("选择平台", fontSize = 13.sp, color = Color(0xFF90A4AE))
                Spacer(Modifier.height(8.dp))
                // 平台选择（横向滚动 chips）
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    for (p in ProviderId.values()) {
                        val selected = p == selectedProvider
                        Box(
                            Modifier.padding(end = 8.dp)
                                .background(
                                    if (selected) Color(0xFF1E88E5) else Color(0xFF263238),
                                    RoundedCornerShape(20.dp)
                                )
                                .clickable { selectedProvider = p }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(p.label, color = if (selected) Color.White else Color(0xFFB0BEC5), fontSize = 12.sp)
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text("登录方式（按优先级）", fontSize = 13.sp, color = Color(0xFF90A4AE))
                Spacer(Modifier.height(6.dp))
                Text(
                    methods.joinToString(" > ") { methodLabel(it) },
                    fontSize = 12.sp, color = Color(0xFF4FC3F7)
                )

                Spacer(Modifier.height(14.dp))

                when {
                    methods.contains(LoginMethod.SMS) || methods.contains(LoginMethod.PASSWORD) -> {
                        OutlinedTextField(
                            value = loginid, onValueChange = { loginid = it },
                            label = { Text("手机号/账号") },
                            colors = fieldColors(), singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = password, onValueChange = { password = it },
                            label = { Text("密码（loomy 自动 RSA 加密登录）") },
                            colors = fieldColors(), singleLine = true,
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("登录后自动拉取模型和额度，无需手动配置", fontSize = 11.sp, color = Color(0xFF78909C))
                    }
                    methods.contains(LoginMethod.WEB) -> {
                        Text("打开网页登录：自动获取登录态", fontSize = 12.sp, color = Color(0xFFB0BEC5))
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = cookies, onValueChange = { cookies = it },
                            label = { Text("登录 Cookie（可选，自动登录后回填）") },
                            colors = fieldColors(), singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    methods.contains(LoginMethod.APIKEY) -> {
                        OutlinedTextField(
                            value = token, onValueChange = { token = it },
                            label = { Text("API Key（官方控制台获取）") },
                            colors = fieldColors(), singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(selectedProvider, loginid, password, token, cookies) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5))
            ) { Text("添加并登录") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = Color(0xFF90A4AE)) }
        }
    )
}

private fun methodLabel(m: LoginMethod): String = when (m) {
    LoginMethod.SMS -> "手机验证码"
    LoginMethod.PASSWORD -> "密码"
    LoginMethod.WEB -> "网页登录"
    LoginMethod.WECHAT -> "微信扫码"
    LoginMethod.APIKEY -> "配置 Key"
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Color(0xFF1E88E5),
    unfocusedBorderColor = Color(0xFF37474F),
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    cursorColor = Color(0xFF4FC3F7),
    focusedLabelColor = Color(0xFF4FC3F7),
    unfocusedLabelColor = Color(0xFF78909C)
)

// 扩展：Modifier.horizontalScroll（直接用 foundation 的）
// 占位说明：已在顶部 import androidx.compose.foundation.horizontalScroll