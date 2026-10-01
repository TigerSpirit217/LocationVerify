package com.tiger.locationverify.location

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.baidu.location.BDAbstractLocationListener
import com.baidu.location.BDLocation
import com.baidu.location.LocationClient
import com.baidu.location.LocationClientOption
import com.tiger.locationverify.R
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
    private var finished = true
    private val mainHandler = Handler(Looper.getMainLooper())

    private val listener = object : BDAbstractLocationListener() {
        override fun onReceiveLocation(location: BDLocation?) {
            mainHandler.post {
                if (finished) return@post
                finished = true
                // 回调解析异常兜底：任何字段异常都不允许阻断结果上报
                val report = try {
                    if (location == null) {
                        error(CheckReport.Status.FAILED, context.getString(R.string.text_empty_location_callback))
                    } else {
                        LogSaver.d(
                            TAG, context.getString(
                                R.string.log_baidu_callback,
                                location.locType, location.locTypeDescription, location.getMockGnssProbability(), location.getMockGnssStrategy(), location.getDisToRealLocation()
                            )
                        )
                        buildReport(location)
                    }
                } catch (t: Throwable) {
                    LogSaver.d(TAG, context.getString(R.string.text_failed_to_parse_callback, t))
                    error(CheckReport.Status.FAILED, context.getString(R.string.text_failed_to_parse_location_result, t.message))
                }
                deliver(report)
            }
        }
    }

    private val timeoutRunnable = Runnable {
        if (finished) return@Runnable
        finished = true
        LogSaver.d(TAG, context.getString(R.string.text_location_timed_out))
        deliver(error(CheckReport.Status.FAILED, context.getString(R.string.text_location_timed_out_no_callback_within_15)))
    }

    fun start(onResult: (CheckReport) -> Unit) {
        release()
        finished = false
        callback = onResult
        if (!Prefs.getPrivacyAgreed(context)) {
            deliver(error(CheckReport.Status.NOT_AGREED, context.getString(R.string.text_privacy_consent_is_required_to_use_location)))
            return
        }
        val ak = Prefs.getBaiduAk(context)
        if (ak.isBlank()) {
            // 允许只填一个 Key：未填写的 SDK 自动跳过，不初始化
            deliver(error(CheckReport.Status.NO_KEY, context.getString(R.string.text_baidu_ak_is_missing_skipped_add_it)))
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
                setScanSpan(0)                       // 不开启周期定位，接收首次结果后停止
                setWifiCacheTimeOut(5 * 60 * 1000)
            }
            c.setLocOption(option)
            c.registerLocationListener(listener)

            finished = false
            mainHandler.postDelayed(timeoutRunnable, TIMEOUT_MS)
            // start() 异步启动服务并自动发起首次定位；未启动时 requestLocation() 返回 1。
            c.start()
            LogSaver.d(TAG, context.getString(R.string.text_starting_location_ak, maskKey(ak)))
        } catch (t: Throwable) {
            LogSaver.d(TAG, context.getString(R.string.text_startup_exception, t))
            deliver(error(CheckReport.Status.FAILED, context.getString(R.string.text_baidu_sdk_startup_failed, t.message)))
        }
    }

    fun release() {
        finished = true
        callback = null
        mainHandler.removeCallbacksAndMessages(null)
        val c = client ?: return
        client = null
        try {
            c.unRegisterLocationListener(listener)
            c.stop()
        } catch (t: Throwable) {
            LogSaver.d(TAG, context.getString(R.string.text_cleanup_exception, t))
        }
    }

    // ---------- 私有 ----------

    private fun deliver(report: CheckReport) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { deliver(report) }
            return
        }
        val cb = callback ?: return
        release()
        cb(report)
    }

    private fun error(status: CheckReport.Status, reason: String) =
        CheckReport(context.getString(R.string.text_baidu_location_sdk), status, emptyList(), RiskLevel.UNKNOWN, reason)

    private fun buildReport(loc: BDLocation): CheckReport {
        val locType = loc.locType
        val lines = mutableListOf<Pair<String, String>>()

        lines += context.getString(R.string.text_location_type) to "${locType}（${loc.locTypeDescription}）"
        lines += context.getString(R.string.text_latitude_longitude) to "${loc.latitude}, ${loc.longitude}"
        lines += context.getString(R.string.text_accuracy_radius) to context.getString(R.string.text_m, loc.radius)
        lines += context.getString(R.string.text_full_address) to (loc.addrStr ?: context.getString(R.string.text_none))
        lines += context.getString(R.string.text_location_description) to (loc.locationDescribe ?: context.getString(R.string.text_none))
        if (locType == BDLocation.TypeGpsLocation || locType == BDLocation.TypeGnssLocation) {
            lines += context.getString(R.string.text_satellite_count) to "${loc.satelliteNumber}"
        }
        lines += context.getString(R.string.text_network_location_method) to (loc.networkLocationType?.ifBlank { context.getString(R.string.text_none) } ?: context.getString(R.string.text_none))
        lines += context.getString(R.string.text_coordinate_system) to (loc.coorType ?: context.getString(R.string.text_unknown))
        if (loc.altitude != Double.MIN_VALUE) {
            lines += context.getString(R.string.text_altitude) to context.getString(R.string.text_m, loc.altitude)
        }
        lines += context.getString(R.string.text_region) to when (loc.getLocationWhere()) {
            BDLocation.LOCATION_WHERE_IN_CN -> context.getString(R.string.text_inside_china)
            BDLocation.LOCATION_WHERE_OUT_CN -> context.getString(R.string.text_outside_china)
            else -> context.getString(R.string.text_unknown)
        }

        // ---------- 虚拟位置重点字段 ----------
        val mockProb = loc.getMockGnssProbability()
        val strategy = loc.getMockGnssStrategy()
        val disToReal = loc.getDisToRealLocation()
        val real = loc.getReallLocation()

        lines += context.getString(R.string.text_mock_probability_mockgnss) to context.getString(R.string.text_raw_value, mockProbLabel(mockProb), mockProb)
        lines += context.getString(R.string.text_anti_mock_strategy) to if (strategy != 0) context.getString(R.string.text_strategy_matched, strategy) else context.getString(R.string.text_no_match)
        lines += context.getString(R.string.text_distance_between_fake_and_real_locations) to
            if (disToReal > 0) context.getString(R.string.text_m, disToReal) else context.getString(R.string.text_none_no_fake_location_detected_or_calculation)
        if (real != null) {
            lines += context.getString(R.string.text_estimated_real_location) to "${real.latitude}, ${real.longitude}"
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
            locType in failedTypes ->
                RiskLevel.UNKNOWN to context.getString(R.string.text_location_failed_type_mock_location_risk_cannot, locType)
            mockProb == BDLocation.MOCK_GNSS_PROBABILITY_HIGH ->
                RiskLevel.HIGH to context.getString(R.string.text_baidu_sdk_reported_a_high_mock_probability)
            disToReal > 0 ->
                RiskLevel.HIGH to context.getString(R.string.text_fake_location_detected_m_from_the_real, disToReal)
            mockProb == BDLocation.MOCK_GNSS_PROBABILITY_MIDDLE ->
                RiskLevel.MIDDLE to context.getString(R.string.text_moderate_mock_probability_suspected_mock_location)
            mockProb == BDLocation.MOCK_GNSS_PROBABILITY_LOW || mockProb == BDLocation.MOCK_GNSS_PROBABILITY_ZERO ->
                RiskLevel.LOW to context.getString(R.string.text_low_mock_probability_no_clear_signs_of)
            else ->
                RiskLevel.UNKNOWN to context.getString(R.string.text_mock_probability_is_unknown_review_the_location)
        }

        val status = if (locType in failedTypes) CheckReport.Status.FAILED else CheckReport.Status.SUCCESS
        return CheckReport(context.getString(R.string.text_baidu_location_sdk), status, lines, risk, reason)
    }

    private fun mockProbLabel(v: Int): String = when (v) {
        BDLocation.MOCK_GNSS_PROBABILITY_HIGH -> context.getString(R.string.text_high)
        BDLocation.MOCK_GNSS_PROBABILITY_MIDDLE -> context.getString(R.string.text_medium)
        BDLocation.MOCK_GNSS_PROBABILITY_LOW -> context.getString(R.string.text_low)
        BDLocation.MOCK_GNSS_PROBABILITY_ZERO -> context.getString(R.string.text_zero_no_signs_of_cheating)
        else -> context.getString(R.string.text_unknown)
    }
}
