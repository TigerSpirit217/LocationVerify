package com.tiger.locationverify.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.tencent.map.geolocation.TencentLocation
import com.tencent.map.geolocation.TencentLocationListener
import com.tencent.map.geolocation.TencentLocationManager
import com.tencent.map.geolocation.TencentLocationManagerOptions
import com.tencent.map.geolocation.TencentLocationRequest
import com.tiger.locationverify.R
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
        private const val SDK_TIMEOUT_MS = 10_000L
        private const val SDK_FAKE_SOURCE = "FAKE"
    }

    private var manager: TencentLocationManager? = null
    private var callback: ((CheckReport) -> Unit)? = null
    private var finished = true
    private val mainHandler = Handler(Looper.getMainLooper())

    private val listener = object : TencentLocationListener {
        override fun onLocationChanged(location: TencentLocation?, error: Int, reason: String?) {
            mainHandler.post {
                if (finished) return@post
                finished = true
                // 回调解析异常兜底：任何字段异常都不允许阻断结果上报
                val report = try {
                    if (error != TencentLocation.ERROR_OK) {
                        LogSaver.d(TAG, context.getString(R.string.log_tencent_error, error, reason))
                        buildFailure(error, reason ?: "")
                    } else if (location == null) {
                        LogSaver.d(TAG, context.getString(R.string.text_empty_location_callback_error_reason, error, reason))
                        CheckReport(context.getString(R.string.text_tencent_location_sdk), CheckReport.Status.FAILED,
                            listOf(context.getString(R.string.text_error_code) to "$error", context.getString(R.string.text_error_description) to (reason ?: context.getString(R.string.text_none))),
                            RiskLevel.UNKNOWN, context.getString(R.string.text_empty_location_callback))
                    } else {
                        LogSaver.d(
                            TAG, context.getString(
                                R.string.log_tencent_callback,
                                error, reason, location.provider, location.sourceProvider, location.isMockGps(), location.getFakeProbability()
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

        override fun onStatusUpdate(name: String, status: Int, desc: String) {
            mainHandler.post {
                if (finished) return@post
                statusUpdates[name] = "$status ($desc)"
                LogSaver.d(TAG, context.getString(R.string.text_status_update_name_status_desc, name, status, desc))
            }
        }
    }

    private val statusUpdates = linkedMapOf<String, String>()

    private val timeoutRunnable = Runnable {
        if (finished) return@Runnable
        finished = true
        LogSaver.d(TAG, context.getString(R.string.log_tencent_timeout, statusUpdates.toString()))
        deliver(CheckReport(context.getString(R.string.text_tencent_location_sdk), CheckReport.Status.FAILED,
            statusUpdates.map { (name, state) -> name to state }, RiskLevel.UNKNOWN,
            context.getString(R.string.text_location_timed_out_no_callback_within_15)))
    }

    fun start(onResult: (CheckReport) -> Unit) {
        release()
        finished = false
        callback = onResult
        statusUpdates.clear()
        if (!Prefs.getPrivacyAgreed(context)) {
            deliver(error(CheckReport.Status.NOT_AGREED, context.getString(R.string.text_privacy_consent_is_required_to_use_location)))
            return
        }
        val key = Prefs.getTencentKey(context)
        if (key.isBlank()) {
            // 允许只填部分 Key：未填写的 SDK 自动跳过，不初始化
            deliver(error(CheckReport.Status.NO_KEY, context.getString(R.string.text_tencent_key_is_missing_skipped_add_it)))
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
                // 检测只需要坐标与反作弊字段，不让首个结果等待地址解析。
                setRequestLevel(TencentLocationRequest.REQUEST_LEVEL_GEO)
                setFirstLocationNeedAddress(false)
                setLocMode(TencentLocationRequest.HIGH_ACCURACY_MODE)
                setGpsFirst(false)
                // SDK 默认首包超时也是 15 秒，会与应用 watchdog 竞争。
                // 提前接收 SDK 的错误码/说明，应用仍保留 15 秒兜底。
                setLocFirstTimeOut(SDK_TIMEOUT_MS)
                setAllowGPS(true)
                setAllowCache(false)
                setAllowDirection(false)
                // 关键：开启反作弊模块（默认关闭）→ getFakeProbability / getFakeReason
                setEnableAntiMock(true)
            }

            finished = false
            logEnvironment()
            LogSaver.d(TAG, context.getString(R.string.log_tencent_request,
                request.locMode, request.requestLevel, request.locFirstCallTimeOut,
                TIMEOUT_MS, request.isEnableAntiMock, request.isFirstLocationNeedAddress))
            mainHandler.postDelayed(timeoutRunnable, TIMEOUT_MS)
            val code = m.requestSingleFreshLocation(request, listener, Looper.getMainLooper())
            LogSaver.d(TAG, context.getString(R.string.text_starting_location_returncode_key, code, maskKey(key)))
            if (code != 0) {
                deliver(error(CheckReport.Status.FAILED, context.getString(R.string.text_tencent_location_request_was_rejected_return_code, code)))
            }
        } catch (t: Throwable) {
            LogSaver.d(TAG, context.getString(R.string.text_startup_exception, t))
            deliver(error(CheckReport.Status.FAILED, context.getString(R.string.text_tencent_sdk_startup_failed, t.message)))
        }
    }

    fun release() {
        finished = true
        callback = null
        mainHandler.removeCallbacksAndMessages(null)
        val m = manager ?: return
        manager = null
        try {
            m.removeUpdates(listener)
        } catch (t: Throwable) {
            LogSaver.d(TAG, context.getString(R.string.text_cleanup_exception, t))
        }
    }

    // ---------- 私有 ----------

    private fun logEnvironment() {
        try {
            val locationManager = context.getSystemService(LocationManager::class.java)
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val network = connectivity?.let { it.getNetworkCapabilities(it.activeNetwork) }
            LogSaver.d(TAG, context.getString(R.string.log_tencent_environment,
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED,
                locationManager?.let { LocationManagerCompat.isLocationEnabled(it) },
                network?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true))
        } catch (t: Exception) {
            LogSaver.d(TAG, context.getString(R.string.log_tencent_environment_failed, t.message))
        }
    }

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
        CheckReport(context.getString(R.string.text_tencent_location_sdk), status, emptyList(), RiskLevel.UNKNOWN, reason)

    private fun buildReport(loc: TencentLocation): CheckReport {
        // ---------- 定位成功 ----------
        val lines = mutableListOf<Pair<String, String>>()
        lines += context.getString(R.string.text_location_result) to context.getString(R.string.text_success)
        lines += context.getString(R.string.text_location_provider) to providerLabel(loc.provider)
        val sourceProvider = loc.sourceProvider ?: ""
        lines += context.getString(R.string.text_detailed_source_sourceprovider) to sourceProvider.ifBlank { context.getString(R.string.text_none) }

        // ---------- 虚拟位置重点字段 ----------
        val mockGps = loc.isMockGps()
        lines += context.getString(R.string.text_mock_gps_data_ismockgps) to when (mockGps) {
            1 -> context.getString(R.string.text_yes_gps_point_contains_mock_data)
            0 -> context.getString(R.string.text_no)
            -1 -> context.getString(R.string.text_unknown_only_valid_for_gps_sources)
            else -> context.getString(R.string.text_unknown_2, mockGps)
        }
        val fakeProb = loc.getFakeProbability()
        val fakeReason = loc.getFakeReason()
        lines += context.getString(R.string.text_fake_probability_fakeprobability_0_1) to if (fakeProb.isNaN() || fakeProb < 0f || fakeProb > 1f) context.getString(R.string.text_unknown) else "$fakeProb"
        lines += context.getString(R.string.text_fake_reason_fakereason) to
            (sourceProvider.contains(SDK_FAKE_SOURCE, ignoreCase = true).let {
                if (it) context.getString(R.string.text_detailed_source_is_fake, fakeReason) else context.getString(R.string.text_not_meaningful_unless_the_detailed_source_is, fakeReason)
            })

        lines += context.getString(R.string.text_latitude_longitude) to "${loc.latitude}, ${loc.longitude}"
        lines += context.getString(R.string.text_accuracy) to context.getString(R.string.text_m, loc.accuracy)
        lines += context.getString(R.string.text_coordinate_system) to coordinateLabel(loc.coordinateType)
        if (loc.altitude != 0.0) lines += context.getString(R.string.text_altitude) to context.getString(R.string.text_m, loc.altitude)
        if (loc.speed > 0f) lines += context.getString(R.string.text_speed) to context.getString(R.string.text_m_s, loc.speed)
        if (loc.bearing > 0f) lines += context.getString(R.string.text_bearing) to "${loc.bearing}°"
        if (loc.provider == TencentLocation.GPS_PROVIDER || loc.provider == TencentLocation.BEIDOU_PROVIDER) {
            lines += context.getString(R.string.text_gps_signal_strength) to rssiLabel(loc.gpsRssi)
        }
        lines += context.getString(R.string.text_address) to listOf(loc.nation, loc.province, loc.city, loc.district, loc.street, loc.town, loc.village)
            .filter { !it.isNullOrBlank() }
            .joinToString(" ")
            .ifBlank { context.getString(R.string.text_none) }
        loc.name?.takeIf { it.isNotBlank() }?.let { lines += context.getString(R.string.text_location_name) to it }
        loc.address?.takeIf { it.isNotBlank() }?.let { lines += context.getString(R.string.text_address_description) to it }
        // 室内楼宇信息可能为 null，必须判空
        val buildingId = loc.indoorBuildingId
        if (!buildingId.isNullOrBlank()) {
            lines += context.getString(R.string.text_indoor_building) to context.getString(R.string.text_floor, buildingId, loc.indoorBuildingFloor ?: context.getString(R.string.text_unknown))
        }
        val t = loc.time
        if (t > 0) lines += context.getString(R.string.text_location_time) to SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(t))

        // ---------- 风险判定 ----------
        val (risk, riskReason) = when {
            mockGps == 1 -> RiskLevel.HIGH to context.getString(R.string.text_tencent_sdk_identified_this_gps_point_as)
            sourceProvider.contains(SDK_FAKE_SOURCE, ignoreCase = true) ->
                RiskLevel.HIGH to context.getString(R.string.text_detailed_source_is_fake_sdk_detected_cheating, fakeReason)
            fakeProb >= 0.6f && fakeProb <= 1f -> RiskLevel.HIGH to context.getString(R.string.text_fake_probability_0_1_high_risk, fakeProb)
            fakeProb > 0f && fakeProb < 0.6f -> RiskLevel.MIDDLE to context.getString(R.string.text_fake_probability_0_1_suspected_mock_location, fakeProb)
            mockGps == 0 -> RiskLevel.LOW to context.getString(R.string.text_tencent_sdk_did_not_mark_this_gps)
            else -> RiskLevel.UNKNOWN to context.getString(R.string.text_this_source_cannot_assess_mock_gps_and)
        }
        return CheckReport(context.getString(R.string.text_tencent_location_sdk), CheckReport.Status.SUCCESS, lines, risk, riskReason)
    }

    private fun buildFailure(code: Int, reason: String): CheckReport {
        val errorLabel = "${errorName(code)} ($code)"
        return CheckReport(
            context.getString(R.string.text_tencent_location_sdk), CheckReport.Status.FAILED,
            listOf(
                context.getString(R.string.text_error_code) to errorLabel,
                context.getString(R.string.text_error_description) to reason.ifBlank { context.getString(R.string.text_none) }
            ),
            RiskLevel.UNKNOWN,
            context.getString(R.string.text_location_failed_mock_location_risk_cannot_be, errorLabel)
        )
    }

    private fun providerLabel(p: String?): String = when (p) {
        TencentLocation.GPS_PROVIDER -> context.getString(R.string.text_gps_satellite_location_2)
        TencentLocation.BEIDOU_PROVIDER -> context.getString(R.string.text_beidou_satellite_location)
        TencentLocation.NETWORK_PROVIDER -> context.getString(R.string.text_network_location)
        TencentLocation.CELL_PROVIDER -> context.getString(R.string.text_cell_tower_location)
        TencentLocation.COARSE_PROVIDER -> context.getString(R.string.text_coarse_location)
        else -> p?.ifBlank { context.getString(R.string.text_unknown) } ?: context.getString(R.string.text_unknown)
    }

    private fun coordinateLabel(t: Int): String = when (t) {
        TencentLocationManager.COORDINATE_TYPE_GCJ02 -> context.getString(R.string.text_gcj02_default)
        TencentLocationManager.COORDINATE_TYPE_WGS84 -> "WGS84"
        else -> context.getString(R.string.text_other, t)
    }

    private fun errorName(e: Int): String = when (e) {
        TencentLocation.ERROR_OK -> context.getString(R.string.text_success_0)
        TencentLocation.ERROR_NETWORK -> context.getString(R.string.text_network_error)
        TencentLocation.ERROR_BAD_JSON -> context.getString(R.string.text_gps_wi_fi_cell_error)
        TencentLocation.ERROR_WGS84 -> context.getString(R.string.text_coordinate_conversion_failed)
        TencentLocation.ERROR_INTERNAL -> context.getString(R.string.text_internal_sdk_error)
        TencentLocation.ERROR_USAGE_RESTRICTED -> context.getString(R.string.text_usage_restricted)
        else -> context.getString(R.string.text_unknown_2, e)
    }

    private fun rssiLabel(v: Int): String = when (v) {
        0 -> context.getString(R.string.text_no_signal)
        1 -> context.getString(R.string.text_weak)
        2 -> context.getString(R.string.text_medium)
        3 -> context.getString(R.string.text_strong)
        else -> context.getString(R.string.text_unknown_2, v)
    }
}
