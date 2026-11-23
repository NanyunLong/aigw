package io.aigw

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 多账号池（移植 pool.py）。
 *
 *  - 多账号轮换：balance（额度最多）/ round_robin / lru
 *  - 失败冷却：401/403/429 或额度耗尽 → cooldown 跳过，换下一个账号
 *  - 自动续期：session 过期前自动重新登录（密码路径）
 *  - 持久化：账号存 app 私有目录 accounts.json
 */
class AccountPool(private val context: Context) {
    private val lock = Any()
    private val accounts = mutableListOf<LoomyAccount>()
    private val rrIndex = AtomicInteger(0)
    private val running = AtomicBoolean(false)
    private var lastSave = 0L
    private val client = LoomyClient()

    private fun file(): File = File(context.filesDir, "accounts.json")

    // -------------------------------------------------------------- storage

    @Synchronized
    fun load() {
        val f = file()
        val list = mutableListOf<LoomyAccount>()
        if (f.exists()) {
            try {
                val root = JSONObject(f.readText())
                val arr = root.optJSONArray("accounts") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    list.add(LoomyAccount.fromJson(item, i))
                }
            } catch (e: Exception) {
                // 解析失败：保留旧文件，但不要崩溃
            }
        }
        synchronized(lock) {
            val previous = accounts.associateBy { it.name }
            for (acc in list) {
                val old = previous[acc.name]
                if (old != null) {
                    acc.requests = old.requests
                    acc.pointsUsed = old.pointsUsed
                    acc.failures = old.failures
                    acc.lastUsed = old.lastUsed
                    acc.lastError = old.lastError
                    if (acc.expireAt == 0L && old.expireAt != 0L) {
                        acc.session = old.session
                        acc.expireAt = old.expireAt
                        acc.userid = old.userid
                    }
                }
            }
            accounts.clear()
            accounts.addAll(list)
        }
    }

    @Synchronized
    fun save(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSave < 5000) return
        lastSave = now
        val arr = JSONArray()
        synchronized(lock) {
            for (acc in accounts) arr.put(LoomyAccount.toJson(acc))
        }
        val root = JSONObject()
        root.put("accounts", arr)
        try {
            file().writeText(root.toString())
        } catch (e: Exception) {
            // 写失败忽略
        }
    }

    // ---------------------------------------------------------------- access

    @Synchronized
    fun snapshot(): List<LoomyAccount> = synchronized(lock) { accounts.toList() }

    @Synchronized
    fun add(acc: LoomyAccount): Boolean = synchronized(lock) {
        if (accounts.any { it.name == acc.name }) return false
        accounts.add(acc)
        save(true)
        true
    }

    @Synchronized
    fun remove(name: String): Boolean = synchronized(lock) {
        val before = accounts.size
        accounts.removeAll { it.name == name }
        val changed = accounts.size != before
        if (changed) save(true)
        changed
    }

    @Synchronized
    fun update(name: String, fn: (LoomyAccount) -> Unit): Boolean = synchronized(lock) {
        val acc = accounts.firstOrNull { it.name == name } ?: return false
        fn(acc)
        save(true)
        true
    }

    fun get(name: String): LoomyAccount? = synchronized(lock) { accounts.firstOrNull { it.name == name } }

    // -------------------------------------------------------------- rotation

    fun usable(): List<LoomyAccount> = synchronized(lock) {
        accounts.filter { it.enabled && it.sessionValid() && !it.inCooldown() }
    }

    /**
     * 取一个可用账号（策略 balance 默认）。
     * @param exclude 已试过的账号名，跳过
     */
    fun acquire(strategy: String = "balance", exclude: List<String> = emptyList()): LoomyAccount {
        val candidates = synchronized(lock) {
            accounts.filter {
                it.enabled && it.sessionValid() && !it.inCooldown() && it.name !in exclude
            }
        }
        if (candidates.isEmpty()) {
            // 有账号但都在冷却 / 无 session：尝试自动续期
            val anyEnabled = synchronized(lock) { accounts.any { it.enabled } }
            throw PoolError(if (anyEnabled) "没有可用账号（可能都在冷却或 session 过期）" else "账号池为空，请先添加账号")
        }
        val picked = when (strategy) {
            "round_robin" -> candidates[Math.floorMod(rrIndex.getAndIncrement(), candidates.size)]
            "lru" -> candidates.minByOrNull { it.lastUsed } ?: candidates[0]
            else -> candidates.maxByOrNull { it.available ?: 0L } ?: candidates[0]
        }
        picked.lastUsed = System.currentTimeMillis() / 1000.0
        return picked
    }

    /** 上报成功：记录用量。 */
    fun reportSuccess(acc: LoomyAccount, usage: Map<String, Any?> = emptyMap()) {
        acc.requests++
        val total = (usage["total_tokens"] as? Number)?.toLong()
        if (total != null) acc.pointsUsed += total
        acc.lastError = ""
        save()
    }

    /** 上报失败：401/403/429 等冷却该账号。 */
    fun reportFailure(acc: LoomyAccount, status: Int, body: String = "") {
        acc.failures++
        acc.lastError = "HTTP $status ${body.take(120)}"
        when (status) {
            401, 403 -> {
                acc.session = ""   // 会话失效
                acc.cooldown(300.0, "HTTP $status")
            }
            429 -> acc.cooldown(30.0, "HTTP 429 rate limit")
            else -> acc.cooldown(60.0, "HTTP $status")
        }
        save()
    }

    /** 刷新额度（调用 /points 或 /userinfo 更新 available/balance）。 */
    fun refreshQuota(acc: LoomyAccount) {
        try {
            val payload = client.queryQuota(acc.session, acc.identity)
            val data = payload.optJSONObject("data") ?: payload
            acc.available = data.optLong("available", acc.available ?: 0L)
            acc.balance = data.optLong("balance", acc.balance ?: 0L)
            acc.dailyBalance = data.optLong("dailyBalance", acc.dailyBalance ?: 0L)
            acc.quotaUpdatedAt = System.currentTimeMillis() / 1000
        } catch (e: Exception) {
            // 刷新失败不阻塞
        }
    }

    /** 尝试自动续期：session 过期或即将过期的账号重新登录。 */
    fun bootstrap() {
        synchronized(lock) {
            for (acc in accounts) {
                if (!acc.enabled) continue
                if (acc.provider != "loomy") continue
                val needLogin = acc.session.isEmpty() ||
                        (acc.expireAt != 0L && acc.expireAt - System.currentTimeMillis() / 1000 < 3600)
                if (needLogin && acc.loginid.isNotEmpty() && acc.password.isNotEmpty()) {
                    try {
                        client.fill(acc)
                    } catch (e: Exception) {
                        acc.lastError = e.message ?: "login failed"
                    }
                }
            }
        }
        save(true)
    }
}

class PoolError(message: String) : RuntimeException(message)