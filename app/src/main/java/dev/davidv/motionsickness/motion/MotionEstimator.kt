// SPDX-FileCopyrightText: 2026 David Ventura
// SPDX-License-Identifier: GPL-3.0-only

package dev.davidv.motionsickness.motion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Device-frame acceleration in m/s^2, plus angular rates, feeding the renderer.
 *
 * [x], [y] are the screen-plane components of device-frame acceleration with gravity removed
 * (+x right, +y up). [outOfPlane] is the signed component along the device's +Z axis (out of
 * the screen, toward the user). [rollRadians] is unused (kept at 0) — dots no longer
 * compensate for how the phone is tilted, matching Apple's raw device-relative behavior.
 *
 * [yawRateRps] / [pitchRateRps] are raw device-frame angular velocities (rad/s). They are
 * computed and bias-corrected but not currently consumed by the renderer — dots react only
 * to acceleration, not to tilting/rotating the phone in place.
 */
data class MotionVector(
    val x: Float,
    val y: Float,
    val outOfPlane: Float,
    val rollRadians: Float,
    val yawRateRps: Float,
    val pitchRateRps: Float,
) {
    companion object { val ZERO = MotionVector(0f, 0f, 0f, 0f, 0f, 0f) }
}

/**
 * Reads raw accelerometer + gyroscope data (no fused rotation-vector dependency, since that
 * fused sensor is unreliable on the emulator and unnecessary now that cues don't world-level)
 * into the inputs the renderer needs.
 */
class MotionEstimator(context: Context) {

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val _motion = MutableStateFlow(MotionVector.ZERO)
    val motion: StateFlow<MotionVector> = _motion.asStateFlow()

    // Slow-tracked gravity estimate, subtracted from raw accelerometer readings to approximate
    // linear acceleration ourselves instead of relying on the platform's fused sensor.
    private var gravX = 0f
    private var gravY = 0f
    private var gravZ = 0f

    private var filteredX = 0f
    private var filteredY = 0f
    private var filteredZ = 0f
    private var filteredYawRate = 0f
    private var filteredPitchRate = 0f
    private var lastAccelTsNs = 0L
    private var lastGyroTsNs = 0L

    // Gyro-bias state. Even "calibrated" gyros carry a small DC offset on each axis; with our
    // direct angular-rate-to-offset integration that bias becomes a perpetual scroll when the
    // phone is stationary.
    private var gxBias = 0f
    private var gyBias = 0f
    private var gzBias = 0f
    private var stillAccumSec = 0f
    private var lastAccelMagSq = 0f

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER ->
                    updateAccel(event.values[0], event.values[1], event.values[2], event.timestamp)
                Sensor.TYPE_GYROSCOPE ->
                    updateGyro(event.values[0], event.values[1], event.values[2], event.timestamp)
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun updateAccel(ax: Float, ay: Float, az: Float, tsNs: Long) {
        val dt = if (lastAccelTsNs == 0L) 0.02f else ((tsNs - lastAccelTsNs) / 1e9f).coerceIn(0.001f, 0.2f)
        lastAccelTsNs = tsNs

        // Raw accelerometer includes gravity; track it with a slow low-pass filter and
        // subtract it out to approximate linear acceleration ourselves.
        val gAlpha = dt / (GRAVITY_TIME_CONSTANT_SEC + dt)
        gravX += gAlpha * (ax - gravX)
        gravY += gAlpha * (ay - gravY)
        gravZ += gAlpha * (az - gravZ)

        val hx = ax - gravX
        val hy = ay - gravY
        val hz = az - gravZ

        val alpha = dt / (ACCEL_TIME_CONSTANT_SEC + dt)
        filteredX += alpha * (hx - filteredX)
        filteredY += alpha * (hy - filteredY)
        filteredZ += alpha * (hz - filteredZ)

        // Cache the current linear-accel magnitude squared for stillness detection in the
        // gyro handler (it typically ticks faster than accel).
        lastAccelMagSq = hx * hx + hy * hy + hz * hz

        publish()
    }

    private fun updateGyro(gx: Float, gy: Float, gz: Float, tsNs: Long) {
        val dt = if (lastGyroTsNs == 0L) 0.01f else ((tsNs - lastGyroTsNs) / 1e9f).coerceIn(0.001f, 0.2f)
        lastGyroTsNs = tsNs

        val rawGyroMagSq = gx * gx + gy * gy + gz * gz
        val isStill = lastAccelMagSq < STILL_ACCEL_MAG_SQ && rawGyroMagSq < STILL_GYRO_MAG_SQ
        if (isStill) {
            stillAccumSec += dt
            if (stillAccumSec > STILL_SETTLE_SEC) {
                val biasAlpha = dt / (BIAS_TRACK_SEC + dt)
                gxBias += biasAlpha * (gx - gxBias)
                gyBias += biasAlpha * (gy - gyBias)
                gzBias += biasAlpha * (gz - gzBias)
            }
        } else {
            stillAccumSec = 0f
        }

        // Raw device-frame gyro axes, no world-leveling.
        val gxd = gx - gxBias
        val gzd = gz - gzBias

//        val yawRate = gzd
//        val pitchRate = gxd

        val alpha = dt / (GYRO_TIME_CONSTANT_SEC + dt)
        filteredYawRate += alpha * (gzd - filteredYawRate)
        filteredPitchRate += alpha * (gxd - filteredPitchRate)

        publish()
    }

    private fun deadband(v: Float, threshold: Float): Float =
        if (v > threshold) v - threshold else if (v < -threshold) v + threshold else 0f

    private fun publish() {
        _motion.value = MotionVector(
            x = filteredX,
            y = filteredY,
            outOfPlane = filteredZ,
            rollRadians = 0f,
            yawRateRps = deadband(filteredYawRate, GYRO_DEADBAND_RPS),
            pitchRateRps = deadband(filteredPitchRate, GYRO_DEADBAND_RPS),
        )
    }

    fun start() {
        // Delay sensor registration to ensure foreground state is fully granted,
        // bypassing Android 12+ background sensor restrictions.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            sensorManager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_GAME)
            sensorManager.registerListener(listener, gyroscope, SensorManager.SENSOR_DELAY_GAME)

            // TEMP: synthetic oscillating drive, bypassing real sensors — remove once done
            // debugging. Confirms the dot-movement math independent of hardware/emulator input.
            if (DEBUG_SYNTHETIC_MOTION) {
                android.os.Handler(android.os.Looper.getMainLooper()).also { h ->
                    val runnable = object : Runnable {
                        var t = 0f
                        override fun run() {
                            t += 0.05f
                            filteredX = 6f * kotlin.math.sin(t)
                            publish()
                            h.postDelayed(this, 50L)
                        }
                    }
                    h.post(runnable)
                }
            }
        }, 500L)
    }

    fun stop() {
        sensorManager.unregisterListener(listener)
        lastAccelTsNs = 0L
        lastGyroTsNs = 0L
        filteredX = 0f
        filteredY = 0f
        filteredZ = 0f
        filteredYawRate = 0f
        filteredPitchRate = 0f
        // Don't clear gx/gy/gzBias or gravity estimate — both are hardware/orientation
        // properties that should persist across stop/start.
        stillAccumSec = 0f
        lastAccelMagSq = 0f
        _motion.value = MotionVector.ZERO
    }

    companion object {
        private const val ACCEL_TIME_CONSTANT_SEC = 0.08f
        private const val GRAVITY_TIME_CONSTANT_SEC = 1.0f
        private const val GYRO_TIME_CONSTANT_SEC = 0.03f

        // Stillness thresholds. Tight enough that actual motion doesn't count as still; loose
        // enough that hand-held-still does. ~0.3 m/s² accel magnitude, ~0.05 rad/s (3 deg/s)
        // rotation magnitude.
        private const val STILL_ACCEL_MAG_SQ = 0.09f
        private const val STILL_GYRO_MAG_SQ = 0.0025f
        // Require this much continuous stillness before starting to track bias.
        private const val STILL_SETTLE_SEC = 0.8f
        // How quickly the bias estimate converges while still — longer = steadier, slower.
        private const val BIAS_TRACK_SEC = 1.5f
        // Residual rate below this is rounded to zero to kill micro-drift.
        private const val GYRO_DEADBAND_RPS = 0.01f // ~0.57 deg/s

        private const val DEBUG_SYNTHETIC_MOTION = false // set false before shipping
    }
}