package com.example.mobilephotosensor.camera

import android.Manifest
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.*
import android.util.Log
import android.util.Size
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.example.mobilephotosensor.DeviceInfoCollector
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class RawCaptureManager(private val context: Context) {
    private val TAG = "RawCaptureManager"
    private lateinit var cameraManager: CameraManager
    private lateinit var cameraDevice: CameraDevice
    private lateinit var imageReader: ImageReader
    private val cameraOpenCloseLock = Semaphore(1)
    private lateinit var handler: Handler
    private lateinit var backgroundHandler: Handler
    private lateinit var backgroundThread: HandlerThread
    private lateinit var captureResult: TotalCaptureResult
    private lateinit var characteristics: CameraCharacteristics
    private var currentImage: Image? = null
    private var captureSession: CameraCaptureSession? = null
    private val deviceInfoCollector = DeviceInfoCollector(context)

    data class CaptureSettings(
        val iso: Int,
        val exposureTime: Long
    )

    data class CaptureResult(
        val imageFile: File,
        val metadataFile: File,
        val settings: CaptureSettings
    )

    init {
        startBackgroundThread()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").apply {
            start()
            backgroundHandler = Handler(looper)
        }
        handler = Handler(Looper.getMainLooper())
    }

    private fun stopBackgroundThread() {
        backgroundThread.quitSafely()
        try {
            backgroundThread.join()
        } catch (e: InterruptedException) {
            Log.e(TAG, "Background thread interrupted", e)
        }
    }

    fun isRawSupported(): Boolean {
        cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return cameraManager.cameraIdList.any { id ->
            val caps = cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            caps?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) ?: false
        }
    }

    fun captureBurst(
        settings: List<CaptureSettings>,
        callback: (List<CaptureResult>) -> Unit
    ) {
        if (!isRawSupported()) {
            callback(emptyList())
            return
        }

        backgroundHandler.post {
            try {
                initCamera { session ->
                    val results = mutableListOf<CaptureResult>()
                    settings.forEach { setting ->
                        try {
                            val result = captureSingleImage(session, setting)
                            result?.let { results.add(it) }
                            Thread.sleep(2000)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error capturing image", e)
                        }
                    }
                    handler.post { callback(results) }
                    close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Burst capture failed", e)
                handler.post { callback(emptyList()) }
            }
        }
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    private fun initCamera(onReady: (CameraCaptureSession) -> Unit) {
        cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            val caps = cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            caps?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) ?: false
        } ?: throw Exception("No RAW capable camera found")

        characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val rawSize = map?.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width * it.height }
            ?: Size(640, 480)

        imageReader = ImageReader.newInstance(
            rawSize.width, rawSize.height,
            ImageFormat.RAW_SENSOR, 4
        ).apply {
            setOnImageAvailableListener({ reader ->
                backgroundHandler.post {
                    currentImage?.close()
                    currentImage = reader.acquireLatestImage()
                    Log.d(TAG, "Image available for processing")
                }
            }, backgroundHandler)
        }

        if (!cameraOpenCloseLock.tryAcquire(30, TimeUnit.SECONDS)) {
            throw RuntimeException("Time out waiting to lock camera opening")
        }

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            @RequiresApi(Build.VERSION_CODES.P)
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                createCaptureSession(onReady)
            }

            override fun onDisconnected(camera: CameraDevice) {
                Log.e(TAG, "Camera disconnected")
                camera.close()
                cameraOpenCloseLock.release()
            }

            override fun onError(camera: CameraDevice, error: Int) {
                Log.e(TAG, "Camera error: $error")
                camera.close()
                cameraOpenCloseLock.release()
            }
        }, backgroundHandler)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun createCaptureSession(onReady: (CameraCaptureSession) -> Unit) {
        try {
            val outputConfig = OutputConfiguration(imageReader.surface)
            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(outputConfig),
                ContextCompat.getMainExecutor(context),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        onReady(session)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Session configuration failed")
                        cameraOpenCloseLock.release()
                    }
                }
            )
            cameraDevice.createCaptureSession(sessionConfig)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create capture session", e)
            cameraOpenCloseLock.release()
            throw e
        }
    }

    private fun captureSingleImage(
        session: CameraCaptureSession,
        settings: CaptureSettings
    ): CaptureResult? {
        val captureRequest = cameraDevice.createCaptureRequest(
            CameraDevice.TEMPLATE_STILL_CAPTURE
        ).apply {
            addTarget(imageReader.surface)
            set(CaptureRequest.SENSOR_SENSITIVITY, settings.iso)
            set(CaptureRequest.SENSOR_EXPOSURE_TIME, settings.exposureTime)
            set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
            set(CaptureRequest.BLACK_LEVEL_LOCK, true)
        }

        val latch = java.util.concurrent.CountDownLatch(1)
        var success = false

        session.capture(captureRequest.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                captureResult = result
                success = true
                latch.countDown()
            }

            override fun onCaptureFailed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                failure: CaptureFailure
            ) {
                Log.e(TAG, "Capture failed: ${failure.reason}")
                latch.countDown()
            }
        }, backgroundHandler)

        if (!latch.await(30, TimeUnit.SECONDS)) {
            Log.e(TAG, "Capture timed out (30s)")
            return null
        }

        if (!success) {
            Log.e(TAG, "Capture was not successful")
            return null
        }

        var retries = 3
        while (currentImage == null && retries > 0) {
            Thread.sleep(1000)
            retries--
        }

        currentImage?.let { image ->
            return try {
                val baseName = "raw_${System.currentTimeMillis()}"
                val imageFile = createOutputFile("$baseName.dng")
                val metadataFile = createOutputFile("$baseName.json")

                saveRawImage(image, imageFile)
                saveMetadata(settings, metadataFile)

                CaptureResult(imageFile, metadataFile, settings)
            } finally {
                image.close()
                currentImage = null
            }
        }

        Log.e(TAG, "No image available after capture")
        return null
    }

    private fun createOutputFile(filename: String): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, filename).apply {
            parentFile?.mkdirs()
        }
    }

    private fun saveRawImage(image: Image, outputFile: File) {
        try {
            FileOutputStream(outputFile).use { output ->
                DngCreator(characteristics, captureResult).use { dngCreator ->
                    dngCreator.writeImage(output, image)
                }
            }
            Log.d(TAG, "RAW saved (${outputFile.length()} bytes): ${outputFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving RAW", e)
            outputFile.delete()
            throw e
        }
    }

    private fun saveMetadata(settings: CaptureSettings, outputFile: File) {
        try {
            val metadata = JSONObject().apply {
                put("deviceInfo", deviceInfoCollector.collectDeviceInfo())
                put("captureSettings", JSONObject().apply {
                    put("iso", settings.iso)
                    put("exposureTime", settings.exposureTime)
                })
                put("cameraCharacteristics", JSONObject().apply {
                    characteristics.keys.forEach { key ->
                        try {
                            put(key.name, characteristics.get(key)?.toString())
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to serialize characteristic $key")
                        }
                    }
                })
            }

            FileOutputStream(outputFile).use { output ->
                output.write(metadata.toString(4).toByteArray())
            }
            Log.d(TAG, "Metadata saved: ${outputFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving metadata", e)
            outputFile.delete()
            throw e
        }
    }

    fun close() {
        try {
            backgroundHandler.post {
                currentImage?.close()
                currentImage = null
                captureSession?.close()
                captureSession = null
                if (::cameraDevice.isInitialized) {
                    cameraDevice.close()
                }
                if (::imageReader.isInitialized) {
                    imageReader.close()
                }
                stopBackgroundThread()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera resources", e)
        } finally {
            if (cameraOpenCloseLock.availablePermits() == 0) {
                cameraOpenCloseLock.release()
            }
        }
    }
}