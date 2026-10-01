package com.tiger.locationverify.location

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationListener
import com.tiger.locationverify.R
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
    private var finished = true
    private val mainHandler = Handler(Looper.getMainLooper())

    private val listener = AMapLocationListener { location ->
        mainHandler.post {
            if (finished) return@post
            finished = true
            // 回调解析异常兜底：任何字段在特定环境下为 null 都不允许阻断结果上报，
            // 否则会出现「日志有回调、界面不刷新」的现象。
            val report = try {
                if (location == null) {
                    LogSaver.d(TAG, context.getString(R.string.text_empty_location_callback))
                    error(CheckReport.Status.FAILED, context.getString(R.string.text_empty_location_callback))
                } else {
                    LogSaver.d(
                        TAG, context.getString(
                            R.string.log_amap_callback,
                            location.errorCode, location.errorInfo, location.isMock, location.locationType
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
        val key = Prefs.getAmapKey(context)
        if (key.isBlank()) {
            // 允许只填一个 Key：未填写的 SDK 自动跳过，不初始化
            deliver(error(CheckReport.Status.NO_KEY, context.getString(R.string.text_amap_key_is_missing_skipped_add_it)))
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
            LogSaver.d(TAG, context.getString(R.string.text_starting_location_key, maskKey(key)))
        } catch (t: Throwable) {
            LogSaver.d(TAG, context.getString(R.string.text_startup_exception, t))
            deliver(error(CheckReport.Status.FAILED, context.getString(R.string.text_amap_sdk_startup_failed, t.message)))
        }
    }

    fun release() {
        finished = true
        callback = null
        mainHandler.removeCallbacksAndMessages(null)
        val c = client ?: return
        client = null
        try {
            c.stopLocation()
            c.onDestroy()
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
        CheckReport(context.getString(R.string.text_amap_location_sdk), status, emptyList(), RiskLevel.UNKNOWN, reason)

    private fun buildReport(loc: AMapLocation): CheckReport {
        val code = loc.errorCode

        // 高德 6.5.0+：当不允许模拟（setMockEnable(false)）时，模拟定位直接失败并返回 15。
        // 这里 mockEnable=true 时一般不会走到 15，但仍兼容处理。
        if (code == 15) {
            return CheckReport(
                sdkName = context.getString(R.string.text_amap_location_sdk),
                status = CheckReport.Status.SUCCESS,
                lines = listOf(
                    context.getString(R.string.text_location_result) to context.getString(R.string.text_failed_result_identified_as_a_mock_location),
                    context.getString(R.string.text_error_code) to context.getString(R.string.text_15_location_failed_because_the_result_was),
                    context.getString(R.string.text_error_description) to (loc.errorInfo ?: context.getString(R.string.text_none)),
                    context.getString(R.string.text_details) to (loc.locationDetail ?: context.getString(R.string.text_none))
                ),
                risk = RiskLevel.HIGH,
                riskReason = context.getString(R.string.text_amap_sdk_identified_this_result_as_a)
            )
        }

        if (code != 0) {
            val lines = mutableListOf<Pair<String, String>>()
            lines += context.getString(R.string.text_error_code) to "$code"
            lines += context.getString(R.string.text_error_description) to (loc.errorInfo ?: context.getString(R.string.text_none))
            lines += context.getString(R.string.text_details) to (loc.locationDetail ?: context.getString(R.string.text_none))
            errorHint(code)?.let { lines += context.getString(R.string.text_troubleshooting) to it }
            return CheckReport(
                context.getString(R.string.text_amap_location_sdk), CheckReport.Status.FAILED, lines,
                RiskLevel.UNKNOWN, context.getString(R.string.text_location_failed_error_code_mock_location_risk, code)
            )
        }

        // ---------- 定位成功 ----------
        val lines = mutableListOf<Pair<String, String>>()
        lines += context.getString(R.string.text_location_result) to context.getString(R.string.text_success)
        // 虚拟位置重点字段
        lines += context.getString(R.string.text_mock_location_ismock) to if (loc.isMock) context.getString(R.string.text_yes_suspected_mock_location) else context.getString(R.string.text_no)
        lines += context.getString(R.string.text_location_source_type) to "${loc.locationType}（${amapTypeLabel(loc.locationType)}）"
        lines += context.getString(R.string.text_location_details) to (loc.locationDetail ?: context.getString(R.string.text_none))
        lines += context.getString(R.string.text_latitude_longitude) to "${loc.latitude}, ${loc.longitude}"
        lines += context.getString(R.string.text_accuracy) to context.getString(R.string.text_m, loc.accuracy)
        lines += context.getString(R.string.text_trust_level_trustedlevel) to trustedLevelLabel(loc.trustedLevel)
        if (loc.satellites > 0) lines += context.getString(R.string.text_satellite_count) to "${loc.satellites}"
        lines += context.getString(R.string.text_satellite_signal_strength) to gpsAccuracyLabel(loc.gpsAccuracyStatus)
        lines += context.getString(R.string.text_address) to listOf(loc.province, loc.city, loc.district, loc.street, loc.address)
            .filter { !it.isNullOrBlank() }
            .joinToString(" ")
            .ifBlank { context.getString(R.string.text_none) }
        loc.poiName?.takeIf { it.isNotBlank() }?.let { lines += "POI" to it }
        loc.aoiName?.takeIf { it.isNotBlank() }?.let { lines += "AOI" to it }
        if (loc.speed > 0f) lines += context.getString(R.string.text_speed) to context.getString(R.string.text_m_s, loc.speed)
        if (loc.bearing > 0f) lines += context.getString(R.string.text_bearing) to "${loc.bearing}°"
        // 注意：buildingId/floor 仅在室内定位结果中返回，其余类型可能为 null，必须判空
        val buildingId = loc.buildingId
        if (!buildingId.isNullOrBlank()) {
            lines += context.getString(R.string.text_building) to context.getString(R.string.text_floor, buildingId, loc.floor ?: context.getString(R.string.text_unknown))
        }

        val (risk, reason) = if (loc.isMock) {
            RiskLevel.HIGH to context.getString(R.string.text_amap_sdk_marked_this_result_as_a)
        } else {
            RiskLevel.LOW to context.getString(R.string.text_amap_sdk_did_not_mark_this_result)
        }
        return CheckReport(context.getString(R.string.text_amap_location_sdk), CheckReport.Status.SUCCESS, lines, risk, reason)
    }

    /**
     * 高德官方「定位类型对照表」（2025-04-18）。
     * locationType 由 SDK 根据当前可用的定位依据自动选择，因此不同环境下会变化：
     * 室外 GPS 可用 → 1；请求间隔短/位移小 → 2；命中缓存 → 4；室内 Wi-Fi → 5；
     * 仅基站 → 6；离线库 → 8；最后位置 → 9；大致位置权限 → 11；高德网络定位失败兜底 → 12。
     */
    private fun amapTypeLabel(t: Int): String = when (t) {
        0 -> context.getString(R.string.text_location_failed)
        1 -> context.getString(R.string.text_gps_satellite_location)
        2 -> context.getString(R.string.text_previous_location_result)
        4 -> context.getString(R.string.text_cached_location_result)
        5 -> context.getString(R.string.text_wi_fi_location_network)
        6 -> context.getString(R.string.text_cell_tower_location_network)
        8 -> context.getString(R.string.text_offline_location_result)
        9 -> context.getString(R.string.text_last_known_location)
        11 -> context.getString(R.string.text_coarse_location_approximate_permission)
        12 -> context.getString(R.string.text_system_network_location_amap_network_fallback)
        else -> context.getString(R.string.text_other_type)
    }

    /** trustedLevel 官方语义：BAD 级别与模拟定位结果相关 */
    private fun trustedLevelLabel(v: Int): String = when (v) {
        AMapLocation.TRUSTED_LEVEL_HIGH -> context.getString(R.string.text_high_within_15_seconds_live_gps)
        AMapLocation.TRUSTED_LEVEL_NORMAL -> context.getString(R.string.text_normal_15_seconds_2_minutes_cached_offline)
        AMapLocation.TRUSTED_LEVEL_LOW -> context.getString(R.string.text_low_2_10_minutes)
        AMapLocation.TRUSTED_LEVEL_BAD -> context.getString(R.string.text_very_low_over_10_minutes_also_used)
        else -> context.getString(R.string.text_unknown_2, v)
    }

    private fun gpsAccuracyLabel(v: Int): String = when (v) {
        AMapLocation.GPS_ACCURACY_BAD -> context.getString(R.string.text_weak)
        AMapLocation.GPS_ACCURACY_GOOD -> context.getString(R.string.text_strong)
        AMapLocation.GPS_ACCURACY_UNKNOWN -> context.getString(R.string.text_unknown)
        else -> context.getString(R.string.text_unknown_2, v)
    }

    private fun errorHint(code: Int): String? = when (code) {
        7 -> context.getString(R.string.text_key_authentication_failed_check_package_name_and)
        10 -> context.getString(R.string.text_location_client_startup_failed_check_the_apsservice)
        12 -> context.getString(R.string.text_location_permission_is_missing_grant_location_access)
        13 -> context.getString(R.string.text_wi_fi_cell_data_is_unavailable_and)
        14 -> context.getString(R.string.text_poor_gps_signal_move_to_an_open)
        20 -> context.getString(R.string.text_only_approximate_location_access_was_granted_grant)
        else -> null
    }
}
