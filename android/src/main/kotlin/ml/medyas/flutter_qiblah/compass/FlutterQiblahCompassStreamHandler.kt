package ml.medyas.flutter_qiblah.compass

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display
import android.view.Surface
import ml.medyas.flutter_qiblah.compass.model.DisplayRotation
import ml.medyas.flutter_qiblah.compass.model.RotationVector
import ml.medyas.flutter_qiblah.compass.util.CompassHelper
import ml.medyas.flutter_qiblah.compass.util.CompassHelper.calculateHeading
import ml.medyas.flutter_qiblah.compass.util.CompassHelper.convertRadtoDeg
import ml.medyas.flutter_qiblah.compass.util.CompassHelper.map180to360
import ml.medyas.flutter_qiblah.compass.util.MathUtils
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.EventChannel.EventSink


class FlutterQiblahCompassStreamHandler(private val context: Context) : EventChannel.StreamHandler {
    private var compassSensorEventListener: SensorEventListener? = null
    private var display: Display? = null
    private var sensorManager: SensorManager? = null
    private var rotationSensor: Sensor? = null
    private var accelerometerSensor: Sensor? = null
    private var magneticFieldSensor: Sensor? = null
    private val truncatedRotationVectorValue = FloatArray(4)
    private val rotationMatrix = FloatArray(9)
    private var lastHeading = 0f
    private var lastAccuracySensorStatus = 0
    private var compassUpdateNextTimestamp: Long = 0
    private var accelerometerReading = FloatArray(3)
    private var magneticReading = FloatArray(3)

    private val isCompassSensorAvailable: Boolean
        get() = rotationSensor != null

    init {
        getSensors(context)
    }

    override fun onListen(arguments: Any?, events: EventSink?) {
        events?.let {
            compassSensorEventListener = CompassSensorEventListener(events)
            registerListener()
        }
    }

    override fun onCancel(arguments: Any?) {
        unregisterListener()
        compassSensorEventListener = null
    }

    private fun getSensors(context: Context) {
        display = (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                .getDisplay(Display.DEFAULT_DISPLAY)
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager!!.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotationSensor == null) {
            Log.d(TAG, "Rotation vector sensor not supported on device, "
                    + "falling back to accelerometer and magnetic field.")
            accelerometerSensor = sensorManager!!.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        }
        magneticFieldSensor = sensorManager!!.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    }

    private fun registerListener() {
        if (sensorManager == null) return
        // Does nothing if the sensors already registered.
        sensorManager!!.registerListener(compassSensorEventListener, magneticFieldSensor, SENSOR_DELAY_MICROS)
        if (isCompassSensorAvailable) {
            sensorManager!!.registerListener(compassSensorEventListener, rotationSensor, SENSOR_DELAY_MICROS)
        } else {
            sensorManager!!.registerListener(compassSensorEventListener, accelerometerSensor, SENSOR_DELAY_MICROS)
        }
    }

    private fun unregisterListener() {
        if (sensorManager == null) return
        sensorManager!!.unregisterListener(compassSensorEventListener, magneticFieldSensor)
        if (isCompassSensorAvailable) {
            sensorManager!!.unregisterListener(compassSensorEventListener, rotationSensor)
        } else {
            sensorManager!!.unregisterListener(compassSensorEventListener, accelerometerSensor)
        }
    }

    private inner class CompassSensorEventListener(val eventSink: EventSink) : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (lastAccuracySensorStatus == SensorManager.SENSOR_STATUS_UNRELIABLE) {
                Log.d(TAG, "Compass sensor is unreliable, device calibration is needed.")
            }
            when {
                event.sensor.type == Sensor.TYPE_ROTATION_VECTOR -> {
                    val rotationVectorReading = getRotationVectorFromSensorEvent(event)
                    updateRotationCompass(rotationVectorReading)
                    return
                }

                event.sensor.type == Sensor.TYPE_ACCELEROMETER && !isCompassSensorAvailable -> {
                    CompassHelper.lowPassFilter(event.values.clone(), accelerometerReading)
                    updateHeading()
                }

                event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD && !isCompassSensorAvailable -> {
                    CompassHelper.lowPassFilter(event.values.clone(), magneticReading)
                    updateHeading()
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
            when (sensor.type) {
                Sensor.TYPE_MAGNETIC_FIELD -> if (lastAccuracySensorStatus != accuracy) {
                    lastAccuracySensorStatus = accuracy
                }

                Sensor.TYPE_ROTATION_VECTOR -> Log.v(TAG, "Received rotation vector sensor accuracy $accuracy")
                else -> Log.w(TAG, "Unexpected accuracy changed event of type ${sensor.type}")
            }

        }

        private fun updateHeading() {
            var heading = calculateHeading(accelerometerReading, magneticReading)
            heading = convertRadtoDeg(heading)
            heading = map180to360(heading)

            notifyCompassChangeListeners(heading.toDouble())
        }


        private fun updateRotationCompass(rotationVectorValue: FloatArray) {
            val rotationVector = RotationVector(rotationVectorValue[0], rotationVectorValue[1], rotationVectorValue[2])
            val displayRotation = getDisplayRotation()
            val magneticAzimuth = MathUtils.calculateAzimuth(rotationVector, displayRotation)

            notifyCompassChangeListeners(magneticAzimuth.degrees.toDouble())
        }

        private fun getDisplayRotation(): DisplayRotation {
            return when (display!!.rotation) {
                Surface.ROTATION_90 -> DisplayRotation.ROTATION_90
                Surface.ROTATION_180 -> DisplayRotation.ROTATION_180
                Surface.ROTATION_270 -> DisplayRotation.ROTATION_270
                else -> DisplayRotation.ROTATION_0
            }
        }

        private fun notifyCompassChangeListeners(degrees: Double) {
            if(degrees.isNaN()) {
                return
            }

            val data = DoubleArray(3)
            data[0] = degrees
            data[1] = 0.0
            data[2] = accuracy.toDouble()

            eventSink.success(data)
            lastHeading = degrees.toFloat()
        }

        private val accuracy: Int
            get() = when (lastAccuracySensorStatus) {
                SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> {
                    15
                }

                SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> {
                    30
                }

                SensorManager.SENSOR_STATUS_ACCURACY_LOW -> {
                    45
                }

                else -> {
                    -1
                }
            }

        private fun getRotationVectorFromSensorEvent(event: SensorEvent): FloatArray {
            return if (event.values.size > 4) {
                System.arraycopy(event.values, 0, truncatedRotationVectorValue, 0, 4)
                truncatedRotationVectorValue
            } else {
                event.values
            }
        }
    }

    companion object {
        private const val TAG = "FlutterQiblahCompass"
        private const val SENSOR_DELAY_MICROS = 30 * 1000
    }
}
