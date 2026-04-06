package com.yotech.valtprinter.ui.screen

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.yotech.valtprinter.ui.receipt.ReceiptPreviewScreen
import com.yotech.valtprinter.ui.viewmodel.PrinterViewModel
import com.yotech.valtprinter.ui.theme.ValtPrinterTheme
import dagger.hilt.android.AndroidEntryPoint

enum class AppScreen { PRINTER, PREVIEW }

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @javax.inject.Inject
    lateinit var repository: com.yotech.valtprinter.domain.repository.PrinterRepository

    private val viewModel: PrinterViewModel by viewModels()
    private var permissionsGranted by androidx.compose.runtime.mutableStateOf(false)
    private var currentScreen by mutableStateOf(AppScreen.PRINTER)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = results.values.all { it }
        if (permissionsGranted) {
            viewModel.onUsbAttached() // Re-check USB after permissions granted
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionsGranted = checkAllPermissions()
        
        // Elite Resilience: Set parent view for headless rendering
        repository.setCaptureView(window.decorView)

        if (permissionsGranted) {
            startPrinterService()
        }

        enableEdgeToEdge()
        setContent {
            ValtPrinterTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (!permissionsGranted) {
                        PermissionUI { permissionLauncher.launch(getRequiredPermissions()) }
                    } else {
                        when (currentScreen) {
                            AppScreen.PRINTER -> PrinterScreen(
                                viewModel = viewModel,
                                onNavigateToPreview = { currentScreen = AppScreen.PREVIEW }
                            )
                            AppScreen.PREVIEW -> ReceiptPreviewScreen(
                                viewModel = viewModel,
                                onBack = { currentScreen = AppScreen.PRINTER }
                            )
                        }
                    }
                }
            }
        }
        
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            if (permissionsGranted) {
                viewModel.onUsbAttached()
            }
        }
    }

    private fun checkAllPermissions() = getRequiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun getRequiredPermissions(): Array<String> {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            perms.add(Manifest.permission.BLUETOOTH)
            perms.add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        return perms.toTypedArray()
    }

    private fun startPrinterService() {
        val intent = Intent(this, com.yotech.valtprinter.data.service.PrinterForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    @Composable
    fun PermissionUI(onGrant: () -> Unit) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(32.dp),
            Arrangement.Center,
            Alignment.CenterHorizontally
        ) {
            Text(
                "Printer Access Required",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center
            )
            Text(
                "We need Bluetooth and Location permissions to discover and connect to SUNMI printer devices.",
                Modifier.padding(vertical = 16.dp),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground
            )
            Button(onClick = onGrant, Modifier.fillMaxWidth()) {
                Text("Grant Permissions")
            }
        }
    }

    @Deprecated("Deprecated in Java", ReplaceWith("super.onBackPressed()"))
    override fun onBackPressed() {
        if (currentScreen == AppScreen.PREVIEW) {
            currentScreen = AppScreen.PRINTER
        } else {
            super.onBackPressed()
        }
    }
}