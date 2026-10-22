package com.boardlens.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/** Feeds gyroscope readings into [gate]. Start while the camera screen is visible, stop when it is not. */
class MotionMonitor(context: Context, val gate: MotionGate = MotionGate()) : SensorEventListener {

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val gyroscope: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    /** False on phones without a gyroscope; motion gating is then simply off. */
    val available: Boolean get() = gyroscope != null

    fun start() {
        val sensor = gyroscope ?: return
        sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.values.size >= 3) gate.onSample(event.timestamp, event.values[0], event.values[1], event.values[2])
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
