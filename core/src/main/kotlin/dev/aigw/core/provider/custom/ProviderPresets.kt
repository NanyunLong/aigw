package dev.aigw.core.provider.custom

/**
 * 一个内置的「可反代商家」预设。
 *
 * 这里收录的都是对外提供 OpenAI 兼容端点的上游：填一个 API Key 就能挂进账号池，
 * 不需要像 Trae / Loomy 那样逆向私有登录与对话协议——那类工作量在上游每次改版时都要重做，
 * 而 OpenAI 兼容的这一类只要地址还在，就一直能用。
 *
 * 刻意**不预填模型名**：模型上下架很快，写死的清单一旦过期，用户看到「拉不到模型」
 * 会以为是功能坏了。界面上点「获取模型」是实时请求 `/models`，拿到的才是当下真实可用的。
 *
 * 地址以各商家官方文档为准：上游改域名时这张表可能滞后，遇到 404 先核对文档再改这里。
 */
data class ProviderPreset(
    /** 显示名。 */
    val name: String,
    /** OpenAI 兼容根地址（含版本段，如 `/v1`、`/api/v4`）。 */
    val baseUrl: String,
    /** 列表副标题：一句话说清这家长处。 */
    val note: String,
    val group: String = GROUP_CN,
    /** 控制台/申请 Key 的地址；空串表示不提供。 */
    val consoleUrl: String = "",
) {
    companion object {
        const val GROUP_CN = "国内直连"
        const val GROUP_GLOBAL = "海外直连"
        const val GROUP_AGGREGATOR = "聚合中转"
    }
}

/**
 * 内置商家预设表。新增一家 = 加一条。
 *
 * 挑选标准：必须确有 OpenAI 兼容的 `/chat/completions`，否则在这个 App 里选它只会得到 404。
 * 只提供官方直连地址，不收录需要邀请码或已停止注册的服务。
 */
object ProviderPresets {

    val all: List<ProviderPreset> = listOf(
        // ---------------------------------------------------------------- 国内直连
        ProviderPreset(
            name = "深度求索 DeepSeek",
            baseUrl = "https://api.deepseek.com/v1",
            note = "deepseek-chat / reasoner，性价比高",
            consoleUrl = "https://platform.deepseek.com",
        ),
        ProviderPreset(
            name = "月之暗面 Kimi",
            baseUrl = "https://api.moonshot.cn/v1",
            note = "长上下文见长，K 系列",
            consoleUrl = "https://platform.moonshot.cn",
        ),
        ProviderPreset(
            name = "智谱 GLM",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            note = "GLM 系列，网页版同源额度",
            consoleUrl = "https://bigmodel.cn",
        ),
        ProviderPreset(
            name = "硅基流动 SiliconFlow",
            baseUrl = "https://api.siliconflow.cn/v1",
            note = "开源模型聚合，注册送额度",
            consoleUrl = "https://cloud.siliconflow.cn",
        ),
        ProviderPreset(
            name = "阿里云百炼 DashScope",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            note = "通义千问全系，兼容模式直连",
            consoleUrl = "https://bailian.console.aliyun.com",
        ),
        ProviderPreset(
            name = "火山方舟 Ark",
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
            note = "豆包系列，需先开通推理接入点",
            consoleUrl = "https://console.volcengine.com/ark",
        ),
        ProviderPreset(
            name = "阶跃星辰 StepFun",
            baseUrl = "https://api.stepfun.com/v1",
            note = "Step 系列，多模态",
            consoleUrl = "https://platform.stepfun.com",
        ),
        ProviderPreset(
            name = "百度千帆",
            baseUrl = "https://qianfan.baidubce.com/v2",
            note = "文心系列，v2 已兼容 OpenAI",
            consoleUrl = "https://console.bce.baidu.com/qianfan",
        ),
        ProviderPreset(
            name = "腾讯混元",
            baseUrl = "https://api.hunyuan.cloud.tencent.com/v1",
            note = "混元系列，需在控制台开通",
            consoleUrl = "https://console.cloud.tencent.com/hunyuan",
        ),
        ProviderPreset(
            name = "讯飞星火",
            baseUrl = "https://spark-api-open.xf-yun.com/v1",
            note = "星火系列，HTTP 版兼容 OpenAI",
            consoleUrl = "https://console.xfyun.cn",
        ),
        ProviderPreset(
            name = "零一万物 01.AI",
            baseUrl = "https://api.lingyiwanwu.com/v1",
            note = "Yi 系列",
            consoleUrl = "https://platform.lingyiwanwu.com",
        ),
        ProviderPreset(
            name = "百川智能",
            baseUrl = "https://api.baichuan-ai.com/v1",
            note = "Baichuan 系列",
            consoleUrl = "https://platform.baichuan-ai.com",
        ),
        ProviderPreset(
            name = "MiniMax",
            baseUrl = "https://api.minimaxi.com/v1",
            note = "abab / M 系列，注意国内国际站域名不同",
            consoleUrl = "https://platform.minimaxi.com",
        ),
        ProviderPreset(
            name = "商汤日日新",
            baseUrl = "https://api.sensenova.cn/compatible-mode/v1",
            note = "SenseNova 系列，兼容模式",
            consoleUrl = "https://platform.sensenova.cn",
        ),

        // ---------------------------------------------------------------- 海外直连
        ProviderPreset(
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            note = "官方原厂，需要外网与外币卡",
            group = ProviderPreset.GROUP_GLOBAL,
            consoleUrl = "https://platform.openai.com",
        ),
        ProviderPreset(
            name = "Google Gemini",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
            note = "官方 OpenAI 兼容层，有免费额度",
            group = ProviderPreset.GROUP_GLOBAL,
            consoleUrl = "https://aistudio.google.com",
        ),
        ProviderPreset(
            name = "xAI Grok",
            baseUrl = "https://api.x.ai/v1",
            note = "Grok 系列",
            group = ProviderPreset.GROUP_GLOBAL,
            consoleUrl = "https://console.x.ai",
        ),
        ProviderPreset(
            name = "Mistral",
            baseUrl = "https://api.mistral.ai/v1",
            note = "欧洲厂商，有免费实验额度",
            group = ProviderPreset.GROUP_GLOBAL,
            consoleUrl = "https://console.mistral.ai",
        ),
        ProviderPreset(
            name = "Groq",
            baseUrl = "https://api.groq.com/openai/v1",
            note = "LPU 推理极快，开源模型",
            group = ProviderPreset.GROUP_GLOBAL,
            consoleUrl = "https://console.groq.com",
        ),
        ProviderPreset(
            name = "Together AI",
            baseUrl = "https://api.together.xyz/v1",
            note = "开源模型托管，品类全",
            group = ProviderPreset.GROUP_GLOBAL,
            consoleUrl = "https://api.together.ai",
        ),
        ProviderPreset(
            name = "Fireworks AI",
            baseUrl = "https://api.fireworks.ai/inference/v1",
            note = "开源模型高吞吐推理",
            group = ProviderPreset.GROUP_GLOBAL,
            consoleUrl = "https://fireworks.ai",
        ),
        ProviderPreset(
            name = "DeepInfra",
            baseUrl = "https://api.deepinfra.com/v1/openai",
            note = "开源模型按量计费，价格低",
            group = ProviderPreset.GROUP_GLOBAL,
            consoleUrl = "https://deepinfra.com",
        ),
        ProviderPreset(
            name = "Cerebras",
            baseUrl = "https://api.cerebras.ai/v1",
            note = "晶圆级芯片，推理速度极快",
            group = ProviderPreset.GROUP_GLOBAL,
            consoleUrl = "https://cloud.cerebras.ai",
        ),
        ProviderPreset(
            name = "Perplexity",
            baseUrl = "https://api.perplexity.ai",
            note = "联网检索增强，注意地址不带 /v1",
            group = ProviderPreset.GROUP_GLOBAL,
            consoleUrl = "https://www.perplexity.ai/settings/api",
        ),

        // ---------------------------------------------------------------- 聚合中转
        ProviderPreset(
            name = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            note = "一个 Key 调数百个模型，含免费档",
            group = ProviderPreset.GROUP_AGGREGATOR,
            consoleUrl = "https://openrouter.ai/keys",
        ),
        ProviderPreset(
            name = "302.AI",
            baseUrl = "https://api.302.ai/v1",
            note = "国内可直连的中转聚合",
            group = ProviderPreset.GROUP_AGGREGATOR,
            consoleUrl = "https://302.ai",
        ),
    )

    /** 按分组分块展示（保持 [all] 里的顺序）。 */
    fun grouped(): List<Pair<String, List<ProviderPreset>>> =
        all.groupBy { it.group }.map { (group, items) -> group to items }
}
