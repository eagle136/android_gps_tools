package com.gpstesttools.gps

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * 指南针传感器引擎：TYPE_ROTATION_VECTOR（优先）或 ACCELEROMETER+MAGNETIC_FIELD（降级），
 * 输出归一化到 [0,360) 的方位角 StateFlow。若已定位，叠加磁偏角修正为地理北。
 */
object CompassEngine {

    /** 当前朝向（地理北为 0°，顺时针，0..360） */
    val heading: StateFlow<Float> get() = _heading
    private val _heading = MutableStateFlow(0f)

    /** 磁偏角（东偏为正），无定位时为 null */
    val declination: StateFlow<Float?> get() = _declination
    private val _declination = MutableStateFlow<Float?>(null)

    private var sensorManager: SensorManager? = null
    private var rotSensor: Sensor? = null
    private var accelSensor: Sensor? = null
    private var magSensor: Sensor? = null
    private var started = false

    private val accelValues = FloatArray(3)
    private val magValues = FloatArray(3)

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR -> {
                    val rot = FloatArray(9)
                    SensorManager.getRotationMatrixFromVector(rot, event.values)
                    val orient = FloatArray(3)
                    SensorManager.getOrientation(rot, orient)
                    updateHeading(Math.toDegrees(orient[0].toDouble()).toFloat())
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    System.arraycopy(event.values, 0, accelValues, 0, 3)
                    computeFromAccelMag()
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    System.arraycopy(event.values, 0, magValues, 0, 3)
                    computeFromAccelMag()
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun computeFromAccelMag() {
        val rot = FloatArray(9)
        if (SensorManager.getRotationMatrix(rot, null, accelValues, magValues)) {
            val orient = FloatArray(3)
            SensorManager.getOrientation(rot, orient)
            updateHeading(Math.toDegrees(orient[0].toDouble()).toFloat())
        }
    }

    private fun updateHeading(raw: Float) {
        val decl = _declination.value ?: 0f
        var h = (raw + decl) % 360f
        if (h < 0) h += 360f
        _heading.value = h
    }

    /** 启动传感器监听（幂等） */
    fun start(context: Context) {
        if (started) return
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager = sm
        rotSensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        accelSensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magSensor = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        if (rotSensor != null) {
            sm.registerListener(listener, rotSensor, SensorManager.SENSOR_DELAY_UI)
        } else {
            accelSensor?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
            magSensor?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        }
        started = true
    }

    /**
     * 用当前定位更新磁偏角（东偏为正）。
     * GeomagneticField 在旧 SDK 位于 android.location，SDK 37 移至 android.hardware，
     * 用反射兼容两种布局；失败则保持 null（仅失去磁偏角修正，不影响指南针）。
     */
    fun updateDeclination(lat: Double, lon: Double, altitudeM: Double, epochMillis: Long) {
        runCatching {
            val cls = try {
                Class.forName("android.location.GeomagneticField")
            } catch (e: ClassNotFoundException) {
                Class.forName("android.hardware.GeomagneticField")
            }
            val ctor = cls.getConstructor(
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
            )
            val gm = ctor.newInstance(lat.toFloat(), lon.toFloat(), altitudeM.toFloat(), epochMillis)
            val getter = cls.getMethod("getDeclination")
            _declination.value = getter.invoke(gm) as Float
        }
    }

    /** 停止传感器监听 */
    fun stop(context: Context) {
        if (!started) return
        val sm = sensorManager
        if (sm != null) {
            runCatching { sm.unregisterListener(listener) }
        }
        sensorManager = null
        started = false
    }

    /** 朝向取整（用于显示） */
    fun roundedHeading() = _heading.value.roundToInt() % 360
}
