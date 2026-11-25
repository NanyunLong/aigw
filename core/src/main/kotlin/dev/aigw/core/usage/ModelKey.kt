package dev.aigw.core.usage

/** 模型 key 归一化：合并同名模型（去掉 provider 前缀）。 */
object ModelKey {
    /** 合并模式：去掉 provider/ 前缀，保留模型名。 */
    fun merged(raw: String): String = raw.substringAfterLast('/').trim()

    /** 精确模式：保留完整 provider/model。 */
    fun of(raw: String, exact: Boolean): String = if (exact) raw.trim() else merged(raw)
}
