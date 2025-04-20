package com.example.mobilephotosensor.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
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
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var captureResult: TotalCaptureResult
    private lateinit var characteristics: CameraCharacteristics

    data class CaptureSettings(
        val iso: Int,
        val exposureTime: Long
    )

    fun isRawSupported(): Boolean {
        return try {
            cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            if (cameraManager.cameraIdList.isEmpty()) {
                return false
            }
            cameraManager.cameraIdList.any { id ->
                val caps = cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                caps?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW) ?: false
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception checking RAW support", e)
            false
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Camera access error", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking RAW support", e)
            false
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE])
    fun captureBurst(
        settings: List<CaptureSettings>,
        callback: (List<File>) -> Unit
    ) {
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("Required permissions not granted")
            }

            initCamera { session ->
                val results = mutableListOf<File>()
                for (setting in settings) {
                    try {
                        val file = captureSingleImage(session, setting)
                        file?.let { results.add(it) }
                        TimeUnit.MILLISECONDS.sleep(300)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error capturing image", e)
                    }
                }
                callback(results)
                close()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception in burst capture", e)
            callback(emptyList())
        } catch (e: Exception) {
            Log.e(TAG, "Burst capture failed", e)
            callback(emptyList())
        }
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    private fun initCamera(onReady: (CameraCaptureSession) -> Unit) {
        try {
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val caps = cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                caps?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW) ?: false
            } ?: throw Exception("No RAW capable camera found")

            characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val rawSize = characteristics
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.RAW_SENSOR)
                ?.maxByOrNull { it.width * it.height }
                ?: throw Exception("No RAW_SENSOR output available")

            imageReader = ImageReader.newInstance(
                rawSize.width, rawSize.height,
                ImageFormat.RAW_SENSOR, 3
            )

            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening")
            }

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCaptureSession(onReady)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    camera.close()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    throw RuntimeException("Camera device error: $error")
                }
            }, handler)
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception in camera init", e)
            throw e
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Camera access exception", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Camera initialization failed", e)
            throw e
        }
    }

    private fun createCaptureSession(onReady: (CameraCaptureSession) -> Unit) {
        try {
            cameraDevice.createCaptureSession(
                listOf(imageReader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        cameraOpenCloseLock.release()
                        onReady(session)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        cameraOpenCloseLock.release()
                        throw RuntimeException("Camera session configuration failed")
                    }
                },
                handler
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Camera access exception", e)
            cameraOpenCloseLock.release()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Session creation failed", e)
            cameraOpenCloseLock.release()
            throw e
        }
    }

    private fun captureSingleImage(
        session: CameraCaptureSession,
        settings: CaptureSettings
    ): File? {
        return try {
            val captureRequest = cameraDevice.createCaptureRequest(
                CameraDevice.TEMPLATE_STILL_CAPTURE
            ).apply {
                addTarget(imageReader.surface)
                set(CaptureRequest.SENSOR_SENSITIVITY, settings.iso)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, settings.exposureTime)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
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
            }, handler)

            if (!latch.await(5, TimeUnit.SECONDS)) {
                Log.e(TAG, "Capture timed out")
                return null
            }

            if (!success) {
                Log.e(TAG, "Capture failed")
                return null
            }

            val image = imageReader.acquireLatestImage() ?: run {
                Log.e(TAG, "No image available")
                return null
            }

            try {
                val file = createOutputFile()
                saveRawImage(image, file)
                return file
            } finally {
                image.close()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception in capture", e)
            null
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Camera access error", e)
            null
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Camera in illegal state", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error", e)
            null
        }
    }

    private fun createOutputFile(): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, "raw_${System.currentTimeMillis()}.dng").apply {
            parentFile?.mkdirs()
        }
    }

    private fun saveRawImage(image: Image, outputFile: File) {
        try {
            FileOutputStream(outputFile).use { output ->
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                output.write(bytes)
            }
            Log.d(TAG, "Saved RAW image: ${outputFile.absolutePath}")
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception saving image", e)
            outputFile.delete()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save RAW image", e)
            outputFile.delete()
            throw e
        }
    }

    fun close() {
        try {
            cameraDevice.close()
            imageReader.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing resources", e)
        } finally {
            if (cameraOpenCloseLock.availablePermits() == 0) {
                cameraOpenCloseLock.release()
            }
        }
    }
}