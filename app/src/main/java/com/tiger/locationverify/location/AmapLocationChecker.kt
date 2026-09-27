package com.tiger.locationverify.location

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationListener
import com.tiger.locationverify.data.Prefs
import com.tiger.locationverify.util.LogSaver

/**
 * 高德定位 SDK 检测器（单次定位）。
 *
 * 虚拟位置（模拟定位）检测要点：
 * 1. [AMapLocationClientOption.setMockEnable] 设为 `true`：
 *    允许模拟 GPS 结果返回并以 [AMapLocation.isMock] 标记；
 *    若设为 false（SDK 6.5.0+ 默认检测开启），模拟定位会直接失败并返回错误码 15。
 * 2. [AMapLocationClient.setApiKey] 支持运行时设置 Key（必须在实例化客户端之前调用）。
 * 3. 未填写 Key 时直接返回「已跳过」，不会初始化 SDK。
 */
class AmapLocationChecker(private val context: Context) {

    companion object {
        private const val TAG = "Amap"
        private const val TIMEOUT_MS = 15_000L
    }

    private var client: AMapLocationClient? = null
    private var callback: ((CheckReport) -> Unit)? = null
    private var finished = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val listener = AMapLocationListener { location ->
        if (finished) return@AMapLocationListener
        finished = true
        // 回调解析异常兜底：任何字段在特定环境下为 null 都不允许阻断结果上报，
        // 否则会出现「日志有回调、界面不刷新」的现象。
        val report = try {
            if (location == null) {
                LogSaver.d(TAG, "回调结果为空")
                error(CheckReport.Status.FAILED, "回调结果为空")
            } else {
                LogSaver.d(
                    TAG, "回调: errorCode=${location.errorCode} errorInfo=${location.errorInfo} " +
                        "isMock=${location.isMock} type=${location.locationType}"
                )
                buildReport(location)
            }
        } catch (t: Throwable) {
            LogSaver.d(TAG, "解析回调异常: $t")
            error(CheckReport.Status.FAILED, "解析定位结果异常：${t.message}")
        }
        deliver(report)
        release()
    }

    private val timeoutRunnable = Runnable {
        if (finished) return@Runnable
        finished = true
        LogSaver.d(TAG, "定位超时")
        deliver(error(CheckReport.Status.FAILED, "定位超时（15 秒未回调），请检查网络/定位开关后重试"))
        release()
    }

    fun start(onResult: (CheckReport) -> Unit) {
        callback = onResult
        if (!Prefs.getPrivacyAgreed(context)) {
            deliver(error(CheckReport.Status.NOT_AGREED, "尚未同意隐私政策，无法使用定位 SDK"))
            return
        }
        val key = Prefs.getAmapKey(context)
        if (key.isBlank()) {
            // 允许只填一个 Key：未填写的 SDK 自动跳过，不初始化
            deliver(error(CheckReport.Status.NO_KEY, "未填写高德 Key，已自动跳过（可在「配置密钥」中补充）"))
            return
        }
        try {
            // 运行时设置 API Key（优先于 Manifest meta-data；必须在实例化客户端之前调用）
            AMapLocationClient.setApiKey(key)

            val c = AMapLocationClient(context.applicationContext)
            client = c
            c.setLocationListener(listener)

            val option = AMapLocationClientOption().apply {
                setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy)
                setOnceLocation(true)
                // 6.4.9 起默认不再返回地址信息，需显式开启
                setNeedAddress(true)
                // 关键：允许模拟位置返回，结果以 isMock 标记（检测工具必须开启）
                setMockEnable(true)
                setLocationCacheEnable(false)
                setHttpTimeOut(10_000)
            }
            c.setLocationOption(option)

            finished = false
            mainHandler.postDelayed(timeoutRunnable, TIMEOUT_MS)
            c.startLocation()
            LogSaver.d(TAG, "开始定位 key=${maskKey(key)}")
        } catch (t: Throwable) {
            LogSaver.d(TAG, "启动异常: $t")
            deliver(error(CheckReport.Status.FAILED, "高德 SDK 启动异常：${t.message}"))
            release()
        }
    }

    fun release() {
        mainHandler.removeCallbacks(timeoutRunnable)
        val c = client ?: return
        client = null
        try {
            c.stopLocation()
            c.onDestroy()
        } catch (t: Throwable) {
            LogSaver.d(TAG, "释放异常: $t")
        }
    }

    // ---------- 私有 ----------

    private fun deliver(report: CheckReport) {
        val cb = callback ?: return
        val r = Runnable { cb(report) }
        if (Looper.myLooper() == Looper.getMainLooper()) r.run() else mainHandler.post(r)
    }

    private fun error(status: CheckReport.Status, reason: String) =
        CheckReport("高德地图定位 SDK", status, emptyList(), RiskLevel.UNKNOWN, reason)

    private fun buildReport(loc: AMapLocation): CheckReport {
        val code = loc.errorCode

        // 高德 6.5.0+：当不允许模拟（setMockEnable(false)）时，模拟定位直接失败并返回 15。
        // 这里 mockEnable=true 时一般不会走到 15，但仍兼容处理。
        if (code == 15) {
            return CheckReport(
                sdkName = "高德地图定位 SDK",
                status = CheckReport.Status.SUCCESS,
                lines = listOf(
                    "定位结果" to "失败：结果被识别为模拟位置",
                    "错误码" to "15（定位结果被模拟导致定位失败）",
                    "错误说明" to (loc.errorInfo ?: "无"),
                    "详情" to (loc.locationDetail ?: "无")
                ),
                risk = RiskLevel.HIGH,
                riskReason = "高德 SDK 判定本次定位结果为模拟位置（错误码 15）"
            )
        }

        if (code != 0) {
            val lines = mutableListOf<Pair<String, String>>()
            lines += "错误码" to "$code"
            lines += "错误说明" to (loc.errorInfo ?: "无")
            lines += "详情" to (loc.locationDetail ?: "无")
            errorHint(code)?.let { lines += "排查建议" to it }
            return CheckReport(
                "高德地图定位 SDK", CheckReport.Status.FAILED, lines,
                RiskLevel.UNKNOWN, "定位失败（错误码 $code），无法评估虚拟位置风险"
            )
        }

        // ---------- 定位成功 ----------
        val lines = mutableListOf<Pair<String, String>>()
        lines += "定位结果" to "成功"
        // 虚拟位置重点字段
        lines += "是否模拟位置(isMock)" to if (loc.isMock) "是（疑似虚拟定位）" else "否"
        lines += "定位来源(类型)" to "${loc.locationType}（${amapTypeLabel(loc.locationType)}）"
        lines += "定位信息描述" to (loc.locationDetail ?: "无")
        lines += "经纬度" to "${loc.latitude}, ${loc.longitude}"
        lines += "定位精度" to "${loc.accuracy} 米"
        lines += "可信度(trustedLevel)" to trustedLevelLabel(loc.trustedLevel)
        if (loc.satellites > 0) lines += "卫星数" to "${loc.satellites}"
        lines += "卫星信号强度" to gpsAccuracyLabel(loc.gpsAccuracyStatus)
        lines += "地址" to listOf(loc.province, loc.city, loc.district, loc.street, loc.address)
            .filter { !it.isNullOrBlank() }
            .joinToString(" ")
            .ifBlank { "无" }
        loc.poiName?.takeIf { it.isNotBlank() }?.let { lines += "POI" to it }
        loc.aoiName?.takeIf { it.isNotBlank() }?.let { lines += "AOI" to it }
        if (loc.speed > 0f) lines += "速度" to "${loc.speed} 米/秒"
        if (loc.bearing > 0f) lines += "方向" to "${loc.bearing}°"
        // 注意：buildingId/floor 仅在室内定位结果中返回，其余类型可能为 null，必须判空
        val buildingId = loc.buildingId
        if (!buildingId.isNullOrBlank()) {
            lines += "楼宇" to "$buildingId（楼层 ${loc.floor ?: "未知"}）"
        }

        val (risk, reason) = if (loc.isMock) {
            RiskLevel.HIGH to "高德 SDK 标记为模拟位置（isMock=true）"
        } else {
            RiskLevel.LOW to "高德 SDK 未标记为模拟位置"
        }
        return CheckReport("高德地图定位 SDK", CheckReport.Status.SUCCESS, lines, risk, reason)
    }

    /**
     * 高德官方「定位类型对照表」（2025-04-18）。
     * locationType 由 SDK 根据当前可用的定位依据自动选择，因此不同环境下会变化：
     * 室外 GPS 可用 → 1；请求间隔短/位移小 → 2；命中缓存 → 4；室内 Wi-Fi → 5；
     * 仅基站 → 6；离线库 → 8；最后位置 → 9；大致位置权限 → 11；高德网络定位失败兜底 → 12。
     */
    private fun amapTypeLabel(t: Int): String = when (t) {
        0 -> "定位失败"
        1 -> "GPS(卫星)定位结果"
        2 -> "前次定位结果"
        4 -> "缓存定位结果"
        5 -> "Wifi 定位结果（网络定位）"
        6 -> "基站定位结果（网络定位）"
        8 -> "离线定位结果"
        9 -> "最后位置缓存"
        11 -> "模糊定位结果（大致位置权限）"
        12 -> "系统网络定位（高德网络定位失败时的兜底）"
        else -> "其他类型"
    }

    /** trustedLevel 官方语义：BAD 级别与模拟定位结果相关 */
    private fun trustedLevelLabel(v: Int): String = when (v) {
        AMapLocation.TRUSTED_LEVEL_HIGH -> "高（周边信息 15 秒内，实时 GPS）"
        AMapLocation.TRUSTED_LEVEL_NORMAL -> "中（15 秒~2 分钟，缓存/离线/最后位置）"
        AMapLocation.TRUSTED_LEVEL_LOW -> "低（2~10 分钟）"
        AMapLocation.TRUSTED_LEVEL_BAD -> "非常低（>10 分钟；注意：模拟定位结果也为该等级）"
        else -> "未知（$v）"
    }

    private fun gpsAccuracyLabel(v: Int): String = when (v) {
        AMapLocation.GPS_ACCURACY_BAD -> "弱"
        AMapLocation.GPS_ACCURACY_GOOD -> "强"
        AMapLocation.GPS_ACCURACY_UNKNOWN -> "未知"
        else -> "未知（$v）"
    }

    private fun errorHint(code: Int): String? = when (code) {
        7 -> "Key 鉴权失败：请检查 Key 与「包名 + 签名 SHA1」是否绑定正确"
        10 -> "定位客户端启动失败：检查 AndroidManifest 是否声明 APSService 服务"
        12 -> "缺少定位权限：请在系统设置中授予定位权限（建议精确位置）"
        13 -> "未获取到 Wi-Fi 列表/基站信息且 GPS 不可用"
        14 -> "GPS 信号差：请移至开阔地带后重试"
        20 -> "应用仅获取到「大致位置」权限，请授予精确位置权限"
        else -> null
    }
}