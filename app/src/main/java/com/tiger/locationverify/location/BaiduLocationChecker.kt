package com.tiger.locationverify.location

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.baidu.location.BDAbstractLocationListener
import com.baidu.location.BDLocation
import com.baidu.location.LocationClient
import com.baidu.location.LocationClientOption
import com.tiger.locationverify.data.Prefs
import com.tiger.locationverify.util.LogSaver

/**
 * 百度定位 SDK 检测器（单次定位）。
 *
 * 虚拟位置（模拟定位）检测要点：
 * 1. 必须在 [LocationClientOption.setEnableSimulateGnss] 中开启 `true` ——
 *    默认 false 时模拟 GNSS 结果会被 SDK 过滤，导致无法检出；
 * 2. 结果字段：
 *    - [BDLocation.getMockGnssProbability]：该定位点的作弊概率（高/中/低/未知/零）；
 *    - [BDLocation.getMockGnssStrategy]：命中的防作弊策略编号；
 *    - [BDLocation.getDisToRealLocation]：虚拟点与真实位置的距离（米）；
 *    - [BDLocation.getReallLocation]：SDK 推算的真实位置（虚拟时为非空）。
 */
class BaiduLocationChecker(private val context: Context) {

    companion object {
        private const val TAG = "Baidu"
        private const val TIMEOUT_MS = 15_000L
    }

    private var client: LocationClient? = null
    private var callback: ((CheckReport) -> Unit)? = null
    private var finished = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val listener = object : BDAbstractLocationListener() {
        override fun onReceiveLocation(location: BDLocation) {
            if (finished) return
            finished = true
            // 回调解析异常兜底：任何字段异常都不允许阻断结果上报
            val report = try {
                LogSaver.d(
                    TAG, "回调: locType=${location.locType}(${location.locTypeDescription}) " +
                        "mockProb=${location.getMockGnssProbability()} " +
                        "strategy=${location.getMockGnssStrategy()} " +
                        "disToReal=${location.getDisToRealLocation()}"
                )
                buildReport(location)
            } catch (t: Throwable) {
                LogSaver.d(TAG, "解析回调异常: $t")
                error(CheckReport.Status.FAILED, "解析定位结果异常：${t.message}")
            }
            deliver(report)
            release()
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
        val ak = Prefs.getBaiduAk(context)
        if (ak.isBlank()) {
            // 允许只填一个 Key：未填写的 SDK 自动跳过，不初始化
            deliver(error(CheckReport.Status.NO_KEY, "未填写百度 AK，已自动跳过（可在「配置密钥」中补充）"))
            return
        }
        try {
            // 运行时设置鉴权值（优先于 Manifest 中的 meta-data）
            LocationClient.setKey(ak)

            val c = LocationClient(context.applicationContext)
            client = c

            val option = LocationClientOption().apply {
                setLocationMode(LocationClientOption.LocationMode.Hight_Accuracy)
                setCoorType("bd09ll")                // 百度经纬度坐标系
                setOpenGnss(true)
                // 关键：允许模拟 GNSS 结果返回（默认 false 会被过滤），检测工具必须开启
                setEnableSimulateGnss(true)
                setIsNeedAddress(true)
                setIsNeedAltitude(true)
                setIsNeedLocationDescribe(true)
                setIsNeedLocationPoiList(false)
                setScanSpan(0)                       // requestLocation 为单次请求
                setWifiCacheTimeOut(5 * 60 * 1000)
            }
            c.setLocOption(option)
            c.registerLocationListener(listener)

            finished = false
            mainHandler.postDelayed(timeoutRunnable, TIMEOUT_MS)
            val code = c.requestLocation()           // 单次定位，异步回调
            LogSaver.d(TAG, "请求定位返回码=$code ak=${maskKey(ak)}")
        } catch (t: Throwable) {
            LogSaver.d(TAG, "启动异常: $t")
            deliver(error(CheckReport.Status.FAILED, "百度 SDK 启动异常：${t.message}"))
            release()
        }
    }

    fun release() {
        mainHandler.removeCallbacks(timeoutRunnable)
        val c = client ?: return
        client = null
        try {
            c.unRegisterLocationListener(listener)
            c.stop()
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
        CheckReport("百度地图定位 SDK", status, emptyList(), RiskLevel.UNKNOWN, reason)

    private fun buildReport(loc: BDLocation): CheckReport {
        val locType = loc.locType
        val lines = mutableListOf<Pair<String, String>>()

        lines += "定位类型" to "${locType}（${loc.locTypeDescription}）"
        lines += "经纬度" to "${loc.latitude}, ${loc.longitude}"
        lines += "定位精度(半径)" to "${loc.radius} 米"
        lines += "详细地址" to (loc.addrStr ?: "无")
        lines += "位置描述" to (loc.locationDescribe ?: "无")
        if (locType == BDLocation.TypeGpsLocation || locType == BDLocation.TypeGnssLocation) {
            lines += "卫星数" to "${loc.satelliteNumber}"
        }
        lines += "网络定位方式" to loc.networkLocationType.ifBlank { "无" }   // wf=wifi / cl=基站 / ll=GPS
        lines += "坐标系" to (loc.coorType ?: "未知")
        if (loc.altitude != Double.MIN_VALUE) {
            lines += "海拔" to "${loc.altitude} 米"
        }
        lines += "所处位置" to when (loc.getLocationWhere()) {
            BDLocation.LOCATION_WHERE_IN_CN -> "国内"
            BDLocation.LOCATION_WHERE_OUT_CN -> "国外"
            else -> "未知"
        }

        // ---------- 虚拟位置重点字段 ----------
        val mockProb = loc.getMockGnssProbability()
        val strategy = loc.getMockGnssStrategy()
        val disToReal = loc.getDisToRealLocation()
        val real = loc.getReallLocation()

        lines += "作弊概率(MockGNSS)" to "${mockProbLabel(mockProb)}（原始值 $mockProb）"
        lines += "防作弊策略" to if (strategy != 0) "命中策略 #$strategy" else "未命中"
        lines += "虚假位置与真实位置距离" to
            if (disToReal > 0) "$disToReal 米" else "无（未检出虚拟位置或无需计算）"
        if (real != null) {
            lines += "推算真实位置" to "${real.latitude}, ${real.longitude}"
        }

        // ---------- 风险判定 ----------
        val failedTypes = setOf(
            BDLocation.TypeNone,
            BDLocation.TypeCriteriaException,
            BDLocation.TypeNetWorkException,
            BDLocation.TypeOffLineLocationFail,
            BDLocation.TypeServerError
        )
        val (risk, reason) = when {
            mockProb == BDLocation.MOCK_GNSS_PROBABILITY_HIGH ->
                RiskLevel.HIGH to "百度 SDK 判定该定位点作弊概率高"
            disToReal > 0 ->
                RiskLevel.HIGH to "检出虚假位置，与真实位置相距 $disToReal 米"
            mockProb == BDLocation.MOCK_GNSS_PROBABILITY_MIDDLE ->
                RiskLevel.MIDDLE to "作弊概率中等，疑似虚拟定位"
            mockProb == BDLocation.MOCK_GNSS_PROBABILITY_LOW || mockProb == BDLocation.MOCK_GNSS_PROBABILITY_ZERO ->
                RiskLevel.LOW to "作弊概率低，未发现明显虚拟定位迹象"
            locType in failedTypes ->
                RiskLevel.UNKNOWN to "定位未成功（类型 $locType），无法评估虚拟位置风险"
            else ->
                RiskLevel.UNKNOWN to "作弊概率未知，建议结合定位来源人工判断"
        }

        val status = if (locType in failedTypes) CheckReport.Status.FAILED else CheckReport.Status.SUCCESS
        return CheckReport("百度地图定位 SDK", status, lines, risk, reason)
    }

    private fun mockProbLabel(v: Int): String = when (v) {
        BDLocation.MOCK_GNSS_PROBABILITY_HIGH -> "高"
        BDLocation.MOCK_GNSS_PROBABILITY_MIDDLE -> "中"
        BDLocation.MOCK_GNSS_PROBABILITY_LOW -> "低"
        BDLocation.MOCK_GNSS_PROBABILITY_ZERO -> "零（无作弊迹象）"
        else -> "未知"
    }
}