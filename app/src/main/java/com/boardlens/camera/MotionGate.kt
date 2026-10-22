package com.boardlens.camera

import kotlin.math.sqrt

/**
 * Decides from gyroscope samples whether the phone is being moved (panned, turned).
 * Frames taken while moving are blurred and point at the wrong place, so they are not
 * worth reading. Pure Kotlin; unit-tested. Fed by [MotionMonitor].
 *
 * A normal handheld tremor is roughly 0.05-0.15 rad/s; deliberately pointing the phone
 * somewhere else is well above [thresholdRadPerSec].
 */
class MotionGate(
    private val thresholdRadPerSec: Float = 0.35f,
    /** Stay "moving" this long after the last fast sample, so the settling frame is skipped too. */
    private val holdNanos: Long = 300_000_000L,
) {
    @Volatile
    private var lastFastNanos: Long = NEVER

    /** [timestampNanos] on the elapsedRealtimeNanos clock (SensorEvent.timestamp). */
    fun onSample(timestampNanos: Long, wx: Float, wy: Float, wz: Float) {
        if (sqrt(wx * wx + wy * wy + wz * wz) > thresholdRadPerSec) lastFastNanos = timestampNanos
    }

    fun isMoving(nowNanos: Long): Boolean {
        val last = lastFastNanos
        return last != NEVER && nowNanos - last < holdNanos
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE
    }
}
