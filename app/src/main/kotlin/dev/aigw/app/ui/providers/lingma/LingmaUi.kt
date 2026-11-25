package dev.aigw.app.ui.providers.lingma

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.aigw.app.ui.OutlineActionButton
import dev.aigw.app.ui.SectionCard
import dev.aigw.app.ui.SectionLabel
import dev.aigw.app.ui.providers.ProviderUi
import dev.aigw.app.ui.providers.ProviderUiActions
import dev.aigw.core.pool.AccountStatus
import dev.aigw.core.provider.lingma.LingmaProvider

object LingmaUi : ProviderUi {

    override val id: String = LingmaProvider.ID

    override fun description(): String = "通义灵码，阿里云账号浏览器登录反代"

    @Composable
    override fun Icon(modifier: Modifier) {
        Icon(
            Icons.Filled.Code,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier,
        )
    }

    @Composable
    override fun LoginEntry(accounts: List<AccountStatus>, actions: ProviderUiActions) {
        var expanded by remember { mutableStateOf(false) }
        var raw by remember { mutableStateOf("") }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionCard(enterIndex = 0) {
                SectionLabel("登录")
                Button(
                    onClick = { actions.onWebLogin(id, "") },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("在浏览器中打开通义灵码登录页")
                }
                OutlineActionButton(
                    text = if (expanded) "收起粘贴导入" else "粘贴回调链接导入",
                ) { expanded = !expanded }
                if (expanded) {
                    OutlinedTextField(
                        value = raw,
                        onValueChange = { raw = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 6,
                        label = { Text("回调链接（127.0.0.1:51123/auth/callback?auth=…&token=…）") },
                    )
                    Button(
                        onClick = {
                            actions.onImport(id, raw.trim())
                            raw = ""
                        },
                        enabled = raw.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("导入")
                    }
                }
            }
        }
    }
}
