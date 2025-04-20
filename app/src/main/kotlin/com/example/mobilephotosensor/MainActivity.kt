package com.example.mobilephotosensor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.mobilephotosensor.camera.RawCaptureManager

class MainActivity : AppCompatActivity() {
    private lateinit var rawCapture: RawCaptureManager
    private val PERMISSION_REQUEST_CODE = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rawCapture = RawCaptureManager(this)

        findViewById<Button>(R.id.capture_button).setOnClickListener {
            checkCameraPermission()
        }
    }

    private fun checkCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                startCaptureSession()
            }
            ActivityCompat.shouldShowRequestPermissionRationale(
                this,
                Manifest.permission.CAMERA
            ) -> {
                Toast.makeText(
                    this,
                    "Для съемки в RAW формате требуется разрешение камеры",
                    Toast.LENGTH_LONG
                ).show()
                requestCameraPermission()
            }
            else -> {
                requestCameraPermission()
            }
        }
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            PERMISSION_REQUEST_CODE
        )
    }

    @RequiresPermission(allOf = [Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE])
    private fun startCaptureSession() {
        if (!rawCapture.isRawSupported()) {
            Toast.makeText(
                this,
                "Ваше устройство не поддерживает RAW съемку",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        Toast.makeText(this, "Начало съемки...", Toast.LENGTH_SHORT).show()

        // Настройки для серийной съемки
        val settings = listOf(
            RawCaptureManager.CaptureSettings(iso = 100, exposureTime = 1_000_000L),  // Низкий ISO, длинная выдержка
            RawCaptureManager.CaptureSettings(iso = 400, exposureTime = 500_000L),   // Средний ISO
            RawCaptureManager.CaptureSettings(iso = 800, exposureTime = 250_000L)    // Высокий ISO, короткая выдержка
        )

        rawCapture.captureBurst(settings) { files ->
            runOnUiThread {
                if (files.isNotEmpty()) {
                    Toast.makeText(
                        this,
                        "Успешно сохранено ${files.size} RAW изображений\nПуть: ${files.first().parent}",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        this,
                        "Не удалось сделать снимки",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startCaptureSession()
                } else {
                    Toast.makeText(
                        this,
                        "Разрешение не получено. Функции камеры недоступны",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        rawCapture.close()
    }
}