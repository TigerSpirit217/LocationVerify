package com.tiger.locationverify.location

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.tencent.map.geolocation.TencentLocation
import com.tencent.map.geolocation.TencentLocationListener
import com.tencent.map.geolocation.TencentLocationManager
import com.tencent.map.geolocation.TencentLocationManagerOptions
import com.tencent.map.geolocation.TencentLocationRequest
import com.tiger.locationverify.data.Prefs
import com.tiger.locationverify.util.LogSaver
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 腾讯定位 SDK 检测器（单次定位）。
 *
 * 虚拟位置（模拟定位）检测要点：
 * 1. [TencentLocationManager.setMockEnable] 设为 `true`：默认会过滤 mockGPS 数据，
 *    打开后才能收到 mock 结果并通过 [TencentLocation.isMockGps] 判断（1=是 / 0=否 / -1=无法判断，仅 GPS 来源有效）；
 * 2. [TencentLocationRequest.setEnableAntiMock] 设为 `true`：开启反作弊模块（默认关闭），
 *    结果通过 [TencentLocation.getFakeProbability]（作弊可能性，0~1）与 [TencentLocation.getFakeReason]（作弊原因）获取；
 * 3. [TencentLocationManagerOptions.setKey] 支持运行时设置 Key。
 */
class TencentLocationChecker(private val context: Context) {

    companion object {
        private const val TAG = "Tencent"
        private const val TIMEOUT_MS = 15_000L
        private const val SDK_FAKE_SOURCE = "FAKE"
    }

    private var manager: TencentLocationManager? = null
    private var callback: ((CheckReport) -> Unit)? = null
    private var finished = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val listener = object : TencentLocationListener {
        override fun onLocationChanged(location: TencentLocation, error: Int, reason: String) {
            if (finished) return
            finished = true
            // 回调解析异常兜底：任何字段异常都不允许阻断结果上报
            val report = try {
                LogSaver.d(
                    TAG, "回调: error=$error reason=$reason provider=${location.provider} " +
                        "sourceProvider=${location.sourceProvider} " +
                        "mockGps=${location.isMockGps()} fakeProb=${location.getFakeProbability()}"
                )
                buildReport(location, error, reason)
            } catch (t: Throwable) {
                LogSaver.d(TAG, "解析回调异常: $t")
                error(CheckReport.Status.FAILED, "解析定位结果异常：${t.message}")
            }
            deliver(report)
            release()
        }

        override fun onStatusUpdate(name: String, status: Int, desc: String) {
            LogSaver.d(TAG, "状态更新: name=$name status=$status desc=$desc")
        }
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
        val key = Prefs.getTencentKey(context)
        if (key.isBlank()) {
            // 允许只填部分 Key：未填写的 SDK 自动跳过，不初始化
            deliver(error(CheckReport.Status.NO_KEY, "未填写腾讯 Key，已自动跳过（可在「配置密钥」中补充）"))
            return
        }
        try {
            // 运行时设置 Key（优先于 Manifest meta-data；必须在获取管理器之前调用）
            TencentLocationManagerOptions.setKey(key)

            val m = TencentLocationManager.getInstance(context.applicationContext)
            manager = m
            // 关键：允许 mockGPS 数据返回（默认会被过滤），检测工具必须开启
            m.setMockEnable(true)
            m.setCoordinateType(TencentLocationManager.COORDINATE_TYPE_GCJ02)

            val request = TencentLocationRequest.create().apply {
                setInterval(0L)                                       // 0 = 单次定位
                setRequestLevel(TencentLocationRequest.REQUEST_LEVEL_ADMIN_AREA) // 经纬度+行政区划+地址+名称
                setAllowGPS(true)
                setAllowCache(false)
                setAllowDirection(false)
                // 关键：开启反作弊模块（默认关闭）→ getFakeProbability / getFakeReason
                setEnableAntiMock(true)
            }

            finished = false
            mainHandler.postDelayed(timeoutRunnable, TIMEOUT_MS)
            val code = m.requestLocationUpdates(request, listener, Looper.getMainLooper())
            LogSaver.d(TAG, "开始定位 返回码=$code key=${maskKey(key)}")
        } catch (t: Throwable) {
            LogSaver.d(TAG, "启动异常: $t")
            deliver(error(CheckReport.Status.FAILED, "腾讯 SDK 启动异常：${t.message}"))
            release()
        }
    }

    fun release() {
        mainHandler.removeCallbacks(timeoutRunnable)
        val m = manager ?: return
        manager = null
        try {
            m.removeUpdates(listener)
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
        CheckReport("腾讯地图定位 SDK", status, emptyList(), RiskLevel.UNKNOWN, reason)

    private fun buildReport(loc: TencentLocation, error: Int, reason: String): CheckReport {
        if (error != TencentLocation.ERROR_OK) {
            val lines = mutableListOf<Pair<String, String>>()
            lines += "错误码" to errorName(error)
            lines += "错误说明" to reason.ifBlank { "无" }
            return CheckReport(
                "腾讯地图定位 SDK", CheckReport.Status.FAILED, lines,
                RiskLevel.UNKNOWN, "定位失败（${errorName(error)}），无法评估虚拟位置风险"
            )
        }

        // ---------- 定位成功 ----------
        val lines = mutableListOf<Pair<String, String>>()
        lines += "定位结果" to "成功"
        lines += "定位来源(provider)" to providerLabel(loc.provider)
        val sourceProvider = loc.sourceProvider ?: ""
        lines += "细分来源(sourceProvider)" to sourceProvider.ifBlank { "无" }

        // ---------- 虚拟位置重点字段 ----------
        val mockGps = loc.isMockGps()
        lines += "是否Mock数据(isMockGps)" to when (mockGps) {
            1 -> "是（GPS 点为 Mock 数据）"
            0 -> "否"
            -1 -> "无法判断（仅 GPS 来源时有效）"
            else -> "未知（$mockGps）"
        }
        val fakeProb = loc.getFakeProbability()
        val fakeReason = loc.getFakeReason()
        lines += "作弊概率(fakeProbability, 0~1)" to if (fakeProb.isNaN()) "0" else "$fakeProb"
        lines += "作弊原因(fakeReason)" to
            (sourceProvider.contains(SDK_FAKE_SOURCE, ignoreCase = true).let {
                if (it) "$fakeReason（细分来源为 FAKE）" else "$fakeReason（细分来源非 FAKE 时无意义）"
            })

        lines += "经纬度" to "${loc.latitude}, ${loc.longitude}"
        lines += "定位精度" to "${loc.accuracy} 米"
        lines += "坐标系" to coordinateLabel(loc.coordinateType)
        if (loc.altitude != 0.0) lines += "海拔" to "${loc.altitude} 米"
        if (loc.speed > 0f) lines += "速度" to "${loc.speed} 米/秒"
        if (loc.bearing > 0f) lines += "方向" to "${loc.bearing}°"
        if (loc.provider == TencentLocation.GPS_PROVIDER || loc.provider == TencentLocation.BEIDOU_PROVIDER) {
            lines += "GPS信号强度" to rssiLabel(loc.gpsRssi)
        }
        lines += "地址" to listOf(loc.nation, loc.province, loc.city, loc.district, loc.street, loc.town, loc.village)
            .filter { !it.isNullOrBlank() }
            .joinToString(" ")
            .ifBlank { "无" }
        loc.name?.takeIf { it.isNotBlank() }?.let { lines += "位置名称" to it }
        loc.address?.takeIf { it.isNotBlank() }?.let { lines += "地址描述" to it }
        // 室内楼宇信息可能为 null，必须判空
        val buildingId = loc.indoorBuildingId
        if (!buildingId.isNullOrBlank()) {
            lines += "室内楼宇" to "$buildingId（楼层 ${loc.indoorBuildingFloor ?: "未知"}）"
        }
        val t = loc.time
        if (t > 0) lines += "定位时间" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(t))

        // ---------- 风险判定 ----------
        val (risk, riskReason) = when {
            mockGps == 1 -> RiskLevel.HIGH to "腾讯 SDK 判定该 GPS 点为 Mock 数据（isMockGps=1）"
            sourceProvider.contains(SDK_FAKE_SOURCE, ignoreCase = true) ->
                RiskLevel.HIGH to "细分来源为 FAKE，SDK 判定为作弊（原因码 $fakeReason）"
            fakeProb >= 0.6f -> RiskLevel.HIGH to "作弊概率 $fakeProb（0~1），判定为高风险"
            fakeProb > 0f -> RiskLevel.MIDDLE to "作弊概率 $fakeProb（0~1），疑似虚拟定位"
            else -> RiskLevel.LOW to "未检出模拟定位（isMockGps=0 / 无 FAKE / 作弊概率 0）"
        }
        return CheckReport("腾讯地图定位 SDK", CheckReport.Status.SUCCESS, lines, risk, riskReason)
    }

    private fun providerLabel(p: String?): String = when (p) {
        TencentLocation.GPS_PROVIDER -> "GPS 卫星定位"
        TencentLocation.BEIDOU_PROVIDER -> "北斗卫星定位"
        TencentLocation.NETWORK_PROVIDER -> "网络定位"
        TencentLocation.CELL_PROVIDER -> "基站定位"
        TencentLocation.COARSE_PROVIDER -> "模糊定位"
        else -> p?.ifBlank { "未知" } ?: "未知"
    }

    private fun coordinateLabel(t: Int): String = when (t) {
        TencentLocationManager.COORDINATE_TYPE_GCJ02 -> "GCJ02（默认）"
        TencentLocationManager.COORDINATE_TYPE_WGS84 -> "WGS84"
        else -> "其他（$t）"
    }

    private fun errorName(e: Int): String = when (e) {
        TencentLocation.ERROR_OK -> "成功(0)"
        TencentLocation.ERROR_NETWORK -> "网络问题"
        TencentLocation.ERROR_BAD_JSON -> "GPS/Wi-Fi/基站错误"
        TencentLocation.ERROR_WGS84 -> "坐标转换失败"
        TencentLocation.ERROR_INTERNAL -> "SDK 内部错误"
        TencentLocation.ERROR_USAGE_RESTRICTED -> "用量受限"
        else -> "未知（$e）"
    }

    private fun rssiLabel(v: Int): String = when (v) {
        0 -> "无信号"
        1 -> "弱"
        2 -> "中"
        3 -> "强"
        else -> "未知（$v）"
    }
}