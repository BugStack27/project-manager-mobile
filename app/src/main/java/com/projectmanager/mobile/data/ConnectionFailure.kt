package com.projectmanager.mobile.data

/**
 * 连接失败的类别。
 *
 * 传输层其实一直知道「为什么连不上」（网关返回码、异常类型都拿到了），但界面统一显示成「重连中」，
 * 用户分不清是桌面端没开、网络不通，还是要重新扫码。这里把原因收敛成有限几种，
 * 界面照文案展示，并按 [needsRescan] 决定要不要给「重新扫码」的入口。
 */
enum class LinkFailureKind(
    /** 列表状态行的短标签，尽量不超过 5 个字 */
    val chip: String,
    /** 详情页标题：一句话说清是什么错 */
    val title: String,
    /** 下一步该做什么 */
    val hint: String,
    /** 只有重新扫码（换频道 / 换密码）能解决 */
    val needsRescan: Boolean = false
) {
    NETWORK(
        chip = "网络不通",
        title = "连不上 PushGo 网关",
        hint = "检查手机网络，或确认这台网关地址还能访问；网络恢复后会自动重连。"
    ),
    PASSWORD(
        chip = "密码错误",
        title = "频道密码不对",
        hint = "桌面端重新生成过配对信息，扫码拿新的频道与密码。",
        needsRescan = true
    ),
    CHANNEL(
        chip = "频道失效",
        title = "频道不存在或已被替换",
        hint = "桌面端换过频道，扫码重新绑定这一台。",
        needsRescan = true
    ),
    GATEWAY(
        chip = "网关报错",
        title = "PushGo 网关返回错误",
        hint = "网关侧的问题，稍后会自动重试；一直连不上就在桌面端重新配对。"
    ),
    CONFIG(
        chip = "配对缺失",
        title = "本地连接信息不完整",
        hint = "这一条连接缺频道或密码，重新扫码补齐。",
        needsRescan = true
    ),
    UNKNOWN(
        chip = "连接异常",
        title = "连接失败",
        hint = "稍后会自动重试；一直连不上就重新扫码。"
    )
}

/** 一次连接失败：类别 + 传输层原文 + 发生时间（原文在详情页折叠展示，便于对日志） */
data class LinkFailure(
    val kind: LinkFailureKind,
    val detail: String,
    val at: Long
)
