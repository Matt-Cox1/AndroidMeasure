package dev.precisionmeasure.app.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

data class LevelReading(
    val pitchDeg: Double = 0.0,
    val rollDeg: Double = 0.0,
    val totalTiltDeg: Double = 0.0,
    val isFlatOrientation: Boolean = true,
    val isLevel: Boolean = false,
    val hasTareOffset: Boolean = false,
)

/**
 * Low-pass filtered IMU spirit level supporting both flat tabletop leveling (bull's-eye)
 * and upright wall / picture-frame leveling, plus one-tap zero tare.
 */
class LevelSensorManager(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gravitySensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val _reading = MutableStateFlow(LevelReading())
    val reading: StateFlow<LevelReading> = _reading.asStateFlow()

    private var gx = 0.0
    private var gy = 0.0
    private var gz = 9.81
    private var initialized = false

    private var tarePitch = 0.0
    private var tareRoll = 0.0
    private var hasTare = false

    fun start() {
        val sensor = gravitySensor ?: return
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    fun tareCurrent() {
        val raw = computeRawPitchRoll()
        tarePitch = raw.first
        tareRoll = raw.second
        hasTare = true
        emitCurrent()
    }

    fun clearTare() {
        tarePitch = 0.0
        tareRoll = 0.0
        hasTare = false
        emitCurrent()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0].toDouble()
        val y = event.values[1].toDouble()
        val z = event.values[2].toDouble()
        if (!initialized) {
            gx = x
            gy = y
            gz = z
            initialized = true
        } else {
            val alpha = 0.22
            gx += alpha * (x - gx)
            gy += alpha * (y - gy)
            gz += alpha * (z - gz)
        }
        emitCurrent()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun computeRawPitchRoll(): Pair<Double, Double> {
        val norm = sqrt(gx * gx + gy * gy + gz * gz).coerceAtLeast(1e-6)
        val nx = gx / norm
        val ny = gy / norm
        val nz = gz / norm
        val isFlat = abs(nz) >= 0.55
        return if (isFlat) {
            // Flat on table: pitch along Y, roll along X.
            val pitch = atan2(ny, abs(nz)) * (180.0 / PI)
            val roll = atan2(-nx, abs(nz)) * (180.0 / PI)
            pitch to roll
        } else {
            // Upright vertical wall level: roll is lateral tilt across portrait width.
            val roll = atan2(nx, ny) * (180.0 / PI)
            val pitch = atan2(nz, hypot(nx, ny)) * (180.0 / PI)
            pitch to roll
        }
    }

    private fun emitCurrent() {
        val norm = sqrt(gx * gx + gy * gy + gz * gz).coerceAtLeast(1e-6)
        val isFlat = abs(gz / norm) >= 0.55
        val (rawPitch, rawRoll) = computeRawPitchRoll()
        val pitch = rawPitch - tarePitch
        val roll = rawRoll - tareRoll
        val total = if (isFlat) hypot(pitch, roll) else abs(roll)
        _reading.value = LevelReading(
            pitchDeg = pitch,
            rollDeg = roll,
            totalTiltDeg = total,
            isFlatOrientation = isFlat,
            isLevel = total <= LEVEL_TOLERANCE_DEG,
            hasTareOffset = hasTare,
        )
    }

    companion object {
        private const val LEVEL_TOLERANCE_DEG = 0.6
    }
}
