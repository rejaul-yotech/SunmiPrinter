package com.yotech.valtprinter.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yotech.valtprinter.core.util.AlarmHelper
import com.yotech.valtprinter.data.local.entity.PairedDeviceEntity
import com.yotech.valtprinter.data.local.entity.PrintJobEntity
import com.yotech.valtprinter.domain.model.ConnectionType
import com.yotech.valtprinter.domain.model.PrinterDevice
import com.yotech.valtprinter.domain.model.PrinterState
import com.yotech.valtprinter.ui.component.HardwareHubBottomSheet
import com.yotech.valtprinter.ui.component.HardwareHubCollapsedBanner
import com.yotech.valtprinter.ui.component.StatusPill
import com.yotech.valtprinter.ui.model.HardwareHubUiState
import com.yotech.valtprinter.ui.theme.CyanElectric
import com.yotech.valtprinter.ui.theme.NavySurface
import com.yotech.valtprinter.ui.theme.VioletElectric
import com.yotech.valtprinter.ui.viewmodel.PrinterViewModel
import kotlinx.coroutines.delay
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.os.Build

@Composable
fun PrinterScreen(
    viewModel: PrinterViewModel,
    onNavigateToPreview: () -> Unit,
    onOpenPairedDetails: (PairedDeviceEntity) -> Unit
) {
    val state by viewModel.printerState.collectAsStateWithLifecycle()
    val devices by viewModel.discoveredDevices.collectAsStateWithLifecycle()
    val pairedDevices by viewModel.pairedDevices.collectAsStateWithLifecycle()
    val usbPresent by viewModel.usbPresent.collectAsStateWithLifecycle()

    val isHardwareFault by viewModel.isHardwareFault.collectAsStateWithLifecycle()
    val hubUiState by viewModel.hardwareHubUiState.collectAsStateWithLifecycle()
    val recentJobs by viewModel.recentPrintJobs.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val latestViewModel = rememberUpdatedState(viewModel)
    val btPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        latestViewModel.value.onBluetoothConnectPermissionResult(isGranted)
    }

    LaunchedEffect(Unit) {
        viewModel.uiEvents.collect { event ->
            when (event) {
                is PrinterViewModel.PrinterUiEvent.ShowMessage -> {
                    snackbarHostState.showSnackbar(
                        message = event.message,
                        duration = SnackbarDuration.Short
                    )
                }

                PrinterViewModel.PrinterUiEvent.OpenBluetoothSettings -> {
                    val intent =
                        android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }
                }

                PrinterViewModel.PrinterUiEvent.RequestBluetoothConnectPermission -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        btPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    } else {
                        latestViewModel.value.onBluetoothConnectPermissionResult(true)
                    }
                }
            }
        }
    }

    LaunchedEffect(isHardwareFault, hubUiState) {
        val shouldAlarm = isHardwareFault && hubUiState == HardwareHubUiState.Expanded
        if (shouldAlarm) {
            AlarmHelper.startAlarmAndVibration(context)
        } else {
            AlarmHelper.stopAlarmAndVibration(context)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = "Valt Printer",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                val subtitle = when (state) {
                    is PrinterState.Scanning -> "Searching for nearby devices..."
                    is PrinterState.Connected -> "Hardware Online"
                    is PrinterState.Error -> "Connection Interrupted"
                    else -> "Cloud Print Server"
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            var showHandshakeSuccess by remember { mutableStateOf(false) }

            LaunchedEffect(state) {
                if (state is PrinterState.Connected) {
                    showHandshakeSuccess = true
                    delay(1200)
                    showHandshakeSuccess = false
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            AnimatedContent(targetState = state, label = "PrinterState") { currentState ->
                when (currentState) {
                    is PrinterState.Idle -> IdleStateView(
                        onScan = viewModel::startDiscovery
                    )

                    is PrinterState.AutoConnecting -> AutoConnectingView()

                    is PrinterState.Scanning -> ScanningStateView(
                        pairedDevices = pairedDevices,
                        devices = devices,
                        usbPresent = usbPresent,
                        onPairedConnect = viewModel::connectToPairedDevice,
                        onPairedDetails = onOpenPairedDetails,
                        onDeviceSelected = viewModel::connectToDevice,
                        onStopScan = viewModel::stopDiscovery,
                        onUsbConnect = viewModel::onUsbAttached
                    )

                    is PrinterState.Connecting -> ConnectingStateView(
                        deviceName = currentState.deviceName
                    )

                    else -> {
                        HardwareDashboard(
                            state = currentState,
                            recentJobs = recentJobs,
                            showHandshakeSuccess = showHandshakeSuccess,
                            onPreviewClick = onNavigateToPreview,
                            onDisconnect = viewModel::disconnect,
                            onRetry = viewModel::reconnect,
                            onScanOthers = viewModel::rescanForOthers
                        )
                    }
                }
            }
        }

        if (isHardwareFault && hubUiState == HardwareHubUiState.Collapsed) {
            HardwareHubCollapsedBanner(
                state = state,
                onExpand = viewModel::onHubExpand,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }

        if (isHardwareFault && hubUiState == HardwareHubUiState.Expanded) {
            val securedJobsLabel = "Safe state: ${recentJobs.size} job(s) secured"
            HardwareHubBottomSheet(
                state = state,
                securedJobsLabel = securedJobsLabel,
                onCollapse = viewModel::onHubCollapse,
                onStopRecovery = viewModel::disconnect,
                onChangePrinter = viewModel::rescanForOthers
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        )
    }
}

