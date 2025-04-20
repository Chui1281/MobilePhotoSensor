package com.example.mobilephotosensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import org.json.JSONObject

class DeviceInfoCollector(private val context: Context) {
    fun collectDeviceInfo(): JSONObject {
        return JSONObject().apply {
            put("device", collectDeviceData())
            put("camera", collectCameraData())
            put("sensors", collectSensorData())
        }
    }

    private fun collectDeviceData(): JSONObject {
        return JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("androidVersion", Build.VERSION.RELEASE)
            put("hardware", Build.HARDWARE)
            put("cpuCores", Runtime.getRuntime().availableProcessors())
        }
    }

    private fun collectCameraData(): JSONObject {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return JSONObject().apply {
            cameraManager.cameraIdList.forEach { id ->
                put(id, JSONObject().apply {
                    val chars = cameraManager.getCameraCharacteristics(id)
                    put("sensorOrientation", chars.get(CameraCharacteristics.SENSOR_ORIENTATION))
                    put("availableFeatures", chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES))
                })
            }
        }
    }

    private fun collectSensorData(): JSONObject {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        return JSONObject().apply {
            sensorManager.getSensorList(Sensor.TYPE_ALL).forEach { sensor ->
                put(sensor.stringType, JSONObject().apply {
                    put("name", sensor.name)
                    put("vendor", sensor.vendor)
                    put("power", sensor.power)
                    put("resolution", sensor.resolution)
                })
            }
        }
    }
}