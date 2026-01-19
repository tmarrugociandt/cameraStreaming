package com.ciandt.camerastreaming.ota.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ciandt.camerastreaming.R
import com.ciandt.camerastreaming.ota.utils.OTALogger
import kotlinx.coroutines.launch

/**
 * Main Activity for managing Local OTA updates (APK from local storage)
 *
 * Remote OTA (downloading from URL) has been completely removed.
 * This activity now ONLY handles LOCAL OTA updates.
 */
class OTAActivity : AppCompatActivity() {

    private val viewModel: OTAViewModel by viewModels()

    private lateinit var statusText: TextView
    private lateinit var versionText: TextView
    private lateinit var checkButton: Button
    private lateinit var prepareButton: Button
    private lateinit var installButton: Button
    private lateinit var cancelButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var actionContainer: LinearLayout

    private var currentApkPath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ota)

        requestRequiredPermissions()
        initializeViews()
        setupObservers()
    }

    private fun requestRequiredPermissions() {
        val permissionsNeeded = mutableListOf<String>()

        // Check for READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        // For Android 14+, add READ_MEDIA_VISUAL_USER_SELECTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsNeeded.add("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
            }
        }

        if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsNeeded.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun initializeViews() {
        statusText = findViewById(R.id.status_text)
        versionText = findViewById(R.id.version_text)
        checkButton = findViewById(R.id.check_button)
        prepareButton = findViewById(R.id.download_button)  // Reutilizamos como "Prepare"
        installButton = findViewById(R.id.install_button)
        cancelButton = findViewById(R.id.cancel_button)
        progressBar = findViewById(R.id.progress_bar)
        progressText = findViewById(R.id.progress_text)
        actionContainer = findViewById(R.id.action_container)

        // Actualizar etiqueta del botón
        prepareButton.text = "Prepare & Validate"

        // Check for updates from local storage
        checkButton.setOnClickListener {
            OTALogger.i("Check for updates clicked")
            viewModel.checkForUpdates()
        }

        // Prepare and validate APK
        prepareButton.setOnClickListener {
            OTALogger.i("Prepare clicked")
            (viewModel.uiState.value as? UIState.UpdateAvailable)?.let { state ->
                currentApkPath = state.updateInfo.apkUrl
                viewModel.prepareAndValidateAPK(state.updateInfo.apkUrl)
            }
        }

        // Install APK
        installButton.setOnClickListener {
            OTALogger.i("Install clicked")
            showInstallConfirmation()
        }

        // Cancel operation
        cancelButton.setOnClickListener {
            OTALogger.i("Cancel clicked")
            viewModel.cancel()
            currentApkPath = null
        }
    }

    private fun setupObservers() {
        // Observar versión instalada
        lifecycleScope.launch {
            viewModel.installedVersion.collect { version ->
                updateVersionText(version)
            }
        }

        // Observar estado de la UI
        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                updateUIForState(state)
            }
        }
    }

    private fun updateVersionText(version: String) {
        versionText.text = "Installed version: $version"
    }

    private fun updateUIForState(state: UIState) {
        when (state) {
            is UIState.Idle -> {
                statusText.text = "Ready to check for updates from local storage"
                progressBar.visibility = android.view.View.GONE
                progressText.visibility = android.view.View.GONE
                checkButton.isEnabled = true
                prepareButton.isEnabled = false
                installButton.isEnabled = false
                cancelButton.isEnabled = false
            }

            is UIState.Checking -> {
                statusText.text = "Checking for updates in app storage..."
                progressBar.visibility = android.view.View.VISIBLE
                progressBar.isIndeterminate = true
                progressText.visibility = android.view.View.GONE
                checkButton.isEnabled = false
                prepareButton.isEnabled = false
                installButton.isEnabled = false
                cancelButton.isEnabled = true
            }

            is UIState.UpdateAvailable -> {
                val update = state.updateInfo
                statusText.text = "New update available\nv${update.versionName}\n${update.description}"
                progressBar.visibility = android.view.View.GONE
                progressText.visibility = android.view.View.GONE
                checkButton.isEnabled = true
                prepareButton.isEnabled = true
                installButton.isEnabled = false
                cancelButton.isEnabled = true
            }

            is UIState.NoUpdates -> {
                statusText.text = "No updates available in local storage"
                progressBar.visibility = android.view.View.GONE
                progressText.visibility = android.view.View.GONE
                checkButton.isEnabled = true
                prepareButton.isEnabled = false
                installButton.isEnabled = false
                cancelButton.isEnabled = false
            }

            is UIState.Verifying -> {
                statusText.text = "Verifying APK..."
                progressBar.visibility = android.view.View.VISIBLE
                progressBar.isIndeterminate = true
                progressText.visibility = android.view.View.GONE
                checkButton.isEnabled = false
                prepareButton.isEnabled = false
                installButton.isEnabled = false
                cancelButton.isEnabled = true
            }

            is UIState.ReadyToInstall -> {
                statusText.text = "APK verified and ready to install"
                progressBar.visibility = android.view.View.GONE
                progressText.visibility = android.view.View.GONE
                checkButton.isEnabled = false
                prepareButton.isEnabled = false
                installButton.isEnabled = true
                cancelButton.isEnabled = true
            }

            is UIState.Installing -> {
                statusText.text = "Installing update..."
                progressBar.visibility = android.view.View.VISIBLE
                progressBar.isIndeterminate = true
                progressText.visibility = android.view.View.GONE
                checkButton.isEnabled = false
                prepareButton.isEnabled = false
                installButton.isEnabled = false
                cancelButton.isEnabled = false
            }

            is UIState.Error -> {
                statusText.text = "Error: ${state.message}"
                progressBar.visibility = android.view.View.GONE
                progressText.visibility = android.view.View.GONE
                checkButton.isEnabled = true
                prepareButton.isEnabled = false
                installButton.isEnabled = false
                cancelButton.isEnabled = true
            }
        }
    }

    private fun showInstallConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Confirm installation")
            .setMessage("Do you want to install the update now? The app will be restarted.")
            .setPositiveButton("Install") { _, _ ->
                currentApkPath?.let { apkPath ->
                    viewModel.installUpdate(apkPath)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            var allPermissionsGranted = true
            for (grantResult in grantResults) {
                if (grantResult != PackageManager.PERMISSION_GRANTED) {
                    allPermissionsGranted = false
                    break
                }
            }

            if (allPermissionsGranted) {
                OTALogger.i("All permissions granted")
            } else {
                OTALogger.w("Some permissions were denied")
                AlertDialog.Builder(this)
                    .setTitle("Permissions needed")
                    .setMessage("This app needs permission to read files from your device storage to check for updates.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
    }
}

