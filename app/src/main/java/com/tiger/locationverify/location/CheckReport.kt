package com.tiger.locationverify.location

import android.content.Context
import com.tiger.locationverify.R

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

enum class RiskLevel(private val labelRes: Int) {
    HIGH(R.string.risk_high),
    MIDDLE(R.string.risk_middle),
    LOW(R.string.risk_low),
    UNKNOWN(R.string.risk_unknown);

    fun label(context: Context): String = context.getString(labelRes)
}

/** 将报告格式化为可展示的文本 */
fun buildText(context: Context, report: CheckReport): String = buildString {
    val statusText = when (report.status) {
        CheckReport.Status.IDLE -> context.getString(R.string.text_ready)
        CheckReport.Status.RUNNING -> context.getString(R.string.text_checking)
        CheckReport.Status.SUCCESS -> context.getString(R.string.text_completed)
        CheckReport.Status.FAILED -> context.getString(R.string.text_failed)
        CheckReport.Status.NO_KEY -> context.getString(R.string.text_skipped_no_key_configured)
        CheckReport.Status.NOT_AGREED -> context.getString(R.string.text_privacy_consent_missing)
    }
    appendLine(context.getString(R.string.text_status, statusText))
    append(context.getString(R.string.text_mock_location_risk, report.risk.label(context)))
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