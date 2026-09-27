package com.tiger.locationverify.location

/**
 * 单个 SDK 的检测报告（纯展示数据，由 UI 渲染为文本）。
 */
data class CheckReport(
    val sdkName: String,
    val status: Status,
    /** 字段名 -> 值，按顺序展示 */
    val lines: List<Pair<String, String>>,
    val risk: RiskLevel,
    val riskReason: String
) {
    enum class Status { IDLE, RUNNING, SUCCESS, FAILED, NO_KEY, NOT_AGREED }
}

enum class RiskLevel(val label: String) {
    /** 明确检出模拟/虚拟定位 */
    HIGH("高风险"),
    /** 可疑，需人工复核 */
    MIDDLE("中风险"),
    /** 未发现明显异常 */
    LOW("低风险"),
    /** 信息不足，无法评估 */
    UNKNOWN("无法判断")
}

/** 将报告格式化为可展示的文本 */
fun buildText(report: CheckReport): String = buildString {
    val statusText = when (report.status) {
        CheckReport.Status.IDLE -> "待检测"
        CheckReport.Status.RUNNING -> "检测中…"
        CheckReport.Status.SUCCESS -> "完成"
        CheckReport.Status.FAILED -> "失败"
        CheckReport.Status.NO_KEY -> "已跳过（未配置密钥）"
        CheckReport.Status.NOT_AGREED -> "未同意隐私政策"
    }
    appendLine("状态: $statusText")
    append("虚拟定位风险: ${report.risk.label}")
    if (report.riskReason.isNotBlank()) append("（${report.riskReason}）")
    appendLine()
    if (report.lines.isNotEmpty()) {
        appendLine()
        report.lines.forEach { (k, v) -> appendLine("$k: $v") }
    }
}

/** 隐藏密钥中间部分，避免日志泄露 */
fun maskKey(key: String): String =
    if (key.length <= 8) "***" else key.take(4) + "…" + key.takeLast(4)