package com.example.utils

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.metrics.performance.JankStats
import androidx.metrics.performance.PerformanceMetricsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class ScreenJankMetric(
    val screenName: String,
    val totalFrames: Long = 0L,
    val jankFrames: Long = 0L,
    val maxDurationMs: Double = 0.0,
    val recentJankTimestamp: Long = 0L
) {
    val jankPercentage: Float
        get() = if (totalFrames > 0) (jankFrames * 100f) / totalFrames else 0f
}

data class OverallJankSnapshot(
    val totalFrames: Long = 0L,
    val jankFrames: Long = 0L,
    val jankPercentage: Float = 0f,
    val maxFrameDurationMs: Double = 0.0,
    val currentScreen: String = "App",
    val screenMetrics: Map<String, ScreenJankMetric> = emptyMap(),
    val displayRefreshRateHz: Float = 60f
)

/**
 * Singleton JankStatsManager using Google's androidx.metrics:metrics-performance.
 * Logs frame-duration and jank percentage per screen in real-time.
 * Accessible completely on-device without requiring adb or a computer!
 */
object JankStatsManager {
    private const val TAG = "JankStatsManager"
    private const val PREFS_NAME = "jank_stats_prefs"
    private const val KEY_OVERLAY_ENABLED = "overlay_enabled"

    private var jankStats: JankStats? = null
    private var stateHolder: PerformanceMetricsState.Holder? = null

    private val _totalFrames = AtomicLong(0)
    private val _jankFrames = AtomicLong(0)
    private var _maxFrameDurationNanos = 0L

    @Volatile
    private var _currentScreen = "App"

    private val screenStatsMap = ConcurrentHashMap<String, ScreenStatsAccumulator>()

    private class ScreenStatsAccumulator(val screenName: String) {
        val total = AtomicLong(0)
        val jank = AtomicLong(0)
        @Volatile var maxDurationNanos: Long = 0L
        @Volatile var lastJankTime: Long = 0L

        fun toMetric(): ScreenJankMetric = ScreenJankMetric(
            screenName = screenName,
            totalFrames = total.get(),
            jankFrames = jank.get(),
            maxDurationMs = maxDurationNanos / 1_000_000.0,
            recentJankTimestamp = lastJankTime
        )
    }

    private val _metricsFlow = MutableStateFlow(OverallJankSnapshot())
    val metricsFlow: StateFlow<OverallJankSnapshot> = _metricsFlow.asStateFlow()

    private val _isOverlayEnabled = MutableStateFlow(false)
    val isOverlayEnabled: StateFlow<Boolean> = _isOverlayEnabled.asStateFlow()

    fun initialize(activity: Activity) {
        try {
            val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            _isOverlayEnabled.value = prefs.getBoolean(KEY_OVERLAY_ENABLED, false)

            stateHolder = PerformanceMetricsState.getHolderForHierarchy(activity.window.decorView)
            stateHolder?.state?.putState("Screen", _currentScreen)

            val displayRefreshRate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                activity.display?.mode?.refreshRate ?: 60f
            } else {
                @Suppress("DEPRECATION")
                activity.windowManager.defaultDisplay?.refreshRate ?: 60f
            }

            jankStats = JankStats.createAndTrack(activity.window) { frameData ->
                val total = _totalFrames.incrementAndGet()
                val isJank = frameData.isJank
                val durationNanos = frameData.frameDurationUiNanos

                if (durationNanos > _maxFrameDurationNanos) {
                    _maxFrameDurationNanos = durationNanos
                }

                val currentJank = if (isJank) {
                    _jankFrames.incrementAndGet()
                } else {
                    _jankFrames.get()
                }

                val screen = _currentScreen
                val acc = screenStatsMap.getOrPut(screen) { ScreenStatsAccumulator(screen) }
                acc.total.incrementAndGet()
                if (isJank) {
                    acc.jank.incrementAndGet()
                    acc.lastJankTime = System.currentTimeMillis()
                }
                if (durationNanos > acc.maxDurationNanos) {
                    acc.maxDurationNanos = durationNanos
                }

                // Update published StateFlow every 10 frames or on jank frame to keep UI overhead near zero
                if (total % 10L == 0L || isJank) {
                    val map = screenStatsMap.mapValues { it.value.toMetric() }
                    val jankPct = if (total > 0) (currentJank * 100f) / total else 0f
                    _metricsFlow.value = OverallJankSnapshot(
                        totalFrames = total,
                        jankFrames = currentJank,
                        jankPercentage = jankPct,
                        maxFrameDurationMs = _maxFrameDurationNanos / 1_000_000.0,
                        currentScreen = screen,
                        screenMetrics = map,
                        displayRefreshRateHz = displayRefreshRate
                    )
                }

                if (isJank) {
                    Log.w(
                        TAG,
                        "JANK on $screen: duration=${durationNanos / 1_000_000.0}ms"
                    )
                }
            }
            jankStats?.isTrackingEnabled = true
            Log.i(TAG, "JankStats initialized tracking successfully. Refresh rate: $displayRefreshRate Hz")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize JankStats: ${e.message}", e)
        }
    }

    fun setOverlayEnabled(context: Context, enabled: Boolean) {
        _isOverlayEnabled.value = enabled
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_OVERLAY_ENABLED, enabled).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save overlay preference: ${e.message}")
        }
    }

    fun trackScreen(screenName: String) {
        _currentScreen = screenName
        try {
            stateHolder?.state?.putState("Screen", screenName)
        } catch (e: Exception) {
            Log.w(TAG, "Could not put screen state: ${e.message}")
        }
    }

    fun clearStats() {
        _totalFrames.set(0)
        _jankFrames.set(0)
        _maxFrameDurationNanos = 0L
        screenStatsMap.clear()
        _metricsFlow.value = OverallJankSnapshot(
            totalFrames = 0,
            jankFrames = 0,
            jankPercentage = 0f,
            maxFrameDurationMs = 0.0,
            currentScreen = _currentScreen,
            screenMetrics = emptyMap()
        )
    }

    fun getExportSummary(): String {
        val snapshot = _metricsFlow.value
        val sb = StringBuilder()
        sb.appendLine("=== AMAR DUKAN JANK & PERFORMANCE REPORT ===")
        sb.appendLine("Total Frames: ${snapshot.totalFrames}")
        sb.appendLine("Jank Frames: ${snapshot.jankFrames}")
        sb.appendLine("Jank Percentage: %.2f%%".format(snapshot.jankPercentage))
        sb.appendLine("Max Frame Duration: %.1f ms".format(snapshot.maxFrameDurationMs))
        sb.appendLine("Target Display Refresh: %.0f Hz".format(snapshot.displayRefreshRateHz))
        sb.appendLine("\n--- Screen-by-Screen Breakdown ---")
        if (snapshot.screenMetrics.isEmpty()) {
            sb.appendLine("No screens recorded yet.")
        } else {
            snapshot.screenMetrics.values.sortedByDescending { it.jankPercentage }.forEach { s ->
                sb.appendLine(
                    "• ${s.screenName.padEnd(20)} | Frames: ${s.totalFrames.toString().padStart(5)} | Jank: ${s.jankFrames.toString().padStart(4)} (${"%.1f%%".format(s.jankPercentage).padStart(6)}) | Max: %.1f ms".format(
                        s.maxDurationMs
                    )
                )
            }
        }
        sb.appendLine("============================================")
        return sb.toString()
    }
}
