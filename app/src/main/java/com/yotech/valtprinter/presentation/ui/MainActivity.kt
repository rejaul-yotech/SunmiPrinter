package com.yotech.valtprinter.presentation.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var permissionsGranted by mutableStateOf(false)
    private var isBluetoothEnabled by mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { p ->
            permissionsGranted = p.entries.all { it.value }
        }

    private val enableBtLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            checkBt()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionsGranted = hasPerms()
        checkBt()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    when {
                        !permissionsGranted -> PermissionUI { permissionLauncher.launch(getPermList().toTypedArray()) }
                        !isBluetoothEnabled -> BluetoothUI {
                            enableBtLauncher.launch(
                                Intent(
                                    BluetoothAdapter.ACTION_REQUEST_ENABLE
                                )
                            )
                        }

                        else -> PrinterScreen()
                    }
                }
            }
        }
    }

    private fun hasPerms() = getPermList().all {
        ContextCompat.checkSelfPermission(
            this,
            it
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkBt() {
        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        isBluetoothEnabled = bm.adapter?.isEnabled == true
    }

    private fun getPermList() = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    @Composable
    fun PermissionUI(onGrant: () -> Unit) {
        Column(Modifier.padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            Text("Permissions Required", style = MaterialTheme.typography.headlineSmall)
            Button(
                onGrant,
                Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
            ) { Text("Grant Permissions") }
        }
    }

    @Composable
    fun BluetoothUI(onEnable: () -> Unit) {
        Column(Modifier.padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            Text("Bluetooth is Off", style = MaterialTheme.typography.headlineSmall)
            Button(
                onEnable,
                Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
            ) { Text("Turn On Bluetooth") }
        }
    }
}