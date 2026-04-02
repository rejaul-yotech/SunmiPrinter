package com.yotech.valtprinter.ui.receipt

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yotech.valtprinter.core.util.BitmapRenderer
import com.yotech.valtprinter.domain.model.PrintStatus
import com.yotech.valtprinter.domain.model.orderdata.BillingData
import com.yotech.valtprinter.domain.model.orderdata.OrderItem
import com.yotech.valtprinter.domain.model.orderdata.SubOrderItem
import com.yotech.valtprinter.ui.theme.CyanElectric
import com.yotech.valtprinter.ui.theme.NavySurface
import com.yotech.valtprinter.ui.viewmodel.PrinterViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptPreviewScreen(
    viewModel: PrinterViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Bind directly to the Gold Standard state flow
    val printStatus by viewModel.printStatus.collectAsStateWithLifecycle()

    // Reset status when entering the screen to ensure fresh state
    LaunchedEffect(Unit) {
        viewModel.resetPrintStatus()
    }

    LaunchedEffect(printStatus) {
        when (val status = printStatus) {
            is PrintStatus.Success -> {
                Toast.makeText(context, "Print Success!", Toast.LENGTH_SHORT).show()
                // Auto-reset after a short delay so user can print again if they want
                kotlinx.coroutines.delay(1000)
                viewModel.resetPrintStatus()
            }

            is PrintStatus.Failure -> {
                Toast.makeText(context, status.reason, Toast.LENGTH_LONG).show()
                // No auto-reset here so the error remains visible for a moment
            }

            else -> {} // No-op for Idle, Processing, Sending
        }
    }

    // High Fidelity Dummy Data... (Keep as is)
    val billingData = remember {
        BillingData(
            // Restaurant Identity (Dishoom Kensington)
            restaurantName = "Dishoom Kensington",
            restaurantPhone = "+44 20 7420 9325",
            logoResId = Icons.Default.Coffee, // R.drawable.ic_dishoom_logo

            // Address Information (Official UK Format)
            addressLine1 = "4 Derry Street",
            addressLine2 = null,               // No flat/suite needed for this building
            locality = "Kensington",           // The London Borough/Area
            city = "LONDON",                   // Post Town (Standardized to Uppercase)
            region = "Greater London",
            postalCode = "W8 5SE",
            countryCode = "GB",

            // Transaction Metadata
            staffName = "Md. Rejaul Karim",
            deviceName = "Sunmi V2 Pro",        // Common Android POS hardware
            orderDeviceName = "Tablet-KDS-01",
            timestamp = 1743602874000L,        // April 2, 2026
            orderId = "DSH-9921",
            orderTag = "Table 14",
            orderReference = "CHK-55201",
            orderType = "Dine In",

            // Financials
            currencyCode = "GBP",              // British Pound Sterling
            paymentStatus = "Paid",
            footerNote = "Optional 12.5% service charge added. Thank you!",

            subtotal = 44.0,
            serviceCharge = 5.50,              // 12.5% of 44.0
            vatPercentage = 20.0,              // Standard UK VAT rate
            isVatInclusive = true,             // Most UK restaurant menus are VAT inclusive
            additionalCharge = 0.0,
            bagFee = 0.0,
            grandTotal = 49.50,

            qrCodeContent = "https://www.dishoom.com/kensington/feedback",
            items = listOf(
                OrderItem(
                    id = "item_101",
                    name = "Chicken Ruby",
                    category = "Mains",
                    unitPrice = 14.50,
                    quantity = 2,
                    unitLabel = "portion",
                    subItems = listOf(
                        SubOrderItem(
                            id = "mod_1",
                            name = "Extra Spicy",
                            unitPrice = 0.0,
                            quantity = 1,
                            unitLabel = ""
                        )
                    )
                ),
                OrderItem(
                    id = "item_202",
                    name = "Garlic Naan",
                    category = "Sides",
                    unitPrice = 4.50,
                    quantity = 3,
                    unitLabel = "pcs"
                ),
                OrderItem(
                    id = "item_303",
                    name = "Masala Chai",
                    category = "Drinks",
                    unitPrice = 3.50,
                    quantity = 2,
                    unitLabel = "cups"
                )
            )
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Preview & Print", color = CyanElectric) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = NavySurface),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = CyanElectric
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.TopCenter
            ) {
                // The actual premium paper layout
                PosPrintingScreen(data = billingData)
            }

            Spacer(modifier = Modifier.height(16.dp))

            val view = LocalView.current

            // Determine UI state based on the Single Source of Truth
            val isLoading =
                printStatus is PrintStatus.Processing || printStatus is PrintStatus.Sending
            val isEnabled =
                printStatus is PrintStatus.Idle || printStatus is PrintStatus.Failure || printStatus is PrintStatus.Success

            Button(
                onClick = {
                    scope.launch {
                        // 1. Capture the layout (Processing state is handled in VM)
                        val bitmap: Bitmap = BitmapRenderer.renderComposableToBitmap(view) {
                            PosPrintingScreen(data = billingData, isScrollEnabled = false)
                        }
                        // 2. Clear out any previous success state before starting fresh
                        viewModel.resetPrintStatus()
                        // 3. Print
                        viewModel.printReceipt(bitmap)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .height(64.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CyanElectric),
                enabled = isEnabled && !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(color = NavySurface)
                } else {
                    Text(
                        "PRINT TO SUNMI",
                        style = MaterialTheme.typography.titleMedium,
                        color = NavySurface
                    )
                }
            }
        }
    }
}
