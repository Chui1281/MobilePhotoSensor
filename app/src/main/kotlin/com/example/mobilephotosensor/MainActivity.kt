package com.example.mobilephotosensor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var btnCapture: Button
    private val PERMISSION_REQUEST_CODE = 100
    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )
    private val TAG = "PermissionDebug"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnCapture = findViewById(R.id.capture_button)
        btnCapture.setOnClickListener {
            debugPermissionStates()
            handleCameraAccess()
        }
    }

    private fun debugPermissionStates() {
        REQUIRED_PERMISSIONS.forEach { permission ->
            val status = when {
                ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED -> "GRANTED"
                ActivityCompat.shouldShowRequestPermissionRationale(this, permission) -> "DENIED (Can ask again)"
                else -> "DENIED (Don't ask again)"
            }
            Log.d(TAG, "Permission $permission: $status")
        }
    }

    private fun handleCameraAccess() {
        when {
            hasAllPermissions() -> {
                Log.d(TAG, "All permissions granted - starting camera")
                startCamera()
            }
            shouldShowRationale() -> {
                Log.d(TAG, "Showing rationale for permissions")
                showRationaleDialog()
            }
            else -> {
                Log.d(TAG, "Requesting permissions directly")
                requestPermissions()
            }
        }
    }

    private fun hasAllPermissions(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun shouldShowRationale(): Boolean {
        return REQUIRED_PERMISSIONS.any {
            ActivityCompat.shouldShowRequestPermissionRationale(this, it)
        }
    }

    private fun showRationaleDialog() {
        AlertDialog.Builder(this)
            .setTitle("Требуются разрешения")
            .setMessage("Для работы приложения необходимы:\n\n1. Доступ к камере\n2. Доступ к хранилищу")
            .setPositiveButton("OK") { _, _ ->
                requestPermissions()
            }
            .setNegativeButton("Отмена") { _, _ ->
                Toast.makeText(this, "Функционал камеры недоступен", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            REQUIRED_PERMISSIONS,
            PERMISSION_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode != PERMISSION_REQUEST_CODE) return

        logPermissionResults(permissions, grantResults)

        when {
            hasAllPermissions() -> {
                Log.d(TAG, "User granted all permissions")
                startCamera()
            }
            hasPermanentDenial() -> {
                Log.d(TAG, "User permanently denied some permissions")
                showSettingsRedirectDialog()
            }
            else -> {
                Log.d(TAG, "User temporarily denied some permissions")
                Toast.makeText(this, "Не все разрешения предоставлены", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun logPermissionResults(permissions: Array<String>, grantResults: IntArray) {
        permissions.zip(grantResults.toList()).forEach { (permission, result) ->
            val status = when (result) {
                PackageManager.PERMISSION_GRANTED -> "GRANTED"
                else -> "DENIED (${if (ActivityCompat.shouldShowRequestPermissionRationale(this, permission)) "Can ask again" else "Don't ask again"})"
            }
            Log.d(TAG, "Permission result: $permission - $status")
        }
    }

    private fun hasPermanentDenial(): Boolean {
        return REQUIRED_PERMISSIONS.any { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(this, permission)
        }
    }

    private fun showSettingsRedirectDialog() {
        AlertDialog.Builder(this)
            .setTitle("Доступ запрещен")
            .setMessage("Вы запретили разрешения навсегда. Хотите открыть настройки, чтобы изменить это?")
            .setPositiveButton("Настройки") { _, _ ->
                openAppSettings()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    private fun startCamera() {
        Toast.makeText(this, "Доступ к камере разрешен!", Toast.LENGTH_SHORT).show()
        // Здесь реализация работы с камерой
    }
}