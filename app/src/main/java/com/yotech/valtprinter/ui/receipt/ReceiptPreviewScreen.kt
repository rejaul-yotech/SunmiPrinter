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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yotech.valtprinter.core.util.BitmapRenderer
import com.yotech.valtprinter.domain.model.PrintResult
import com.yotech.valtprinter.domain.model.orderdata.BillingData
import com.yotech.valtprinter.domain.model.orderdata.OrderItem
import com.yotech.valtprinter.domain.model.orderdata.SubOrderItem
import com.yotech.valtprinter.ui.viewmodel.PrinterViewModel
import com.yotech.valtprinter.ui.theme.CyanElectric
import com.yotech.valtprinter.ui.theme.NavySurface
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptPreviewScreen(
    viewModel: PrinterViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isPrinting by remember { mutableStateOf(false) }

    val printResult by viewModel.printResult.collectAsStateWithLifecycle(initialValue = null)

    LaunchedEffect(printResult) {
        printResult?.let { result ->
            isPrinting = false
            when (result) {
                is PrintResult.Success -> {
                    Toast.makeText(context, "Print Success!", Toast.LENGTH_SHORT).show()
                }

                is PrintResult.Failure -> {
                    Toast.makeText(context, result.reason, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // High Fidelity Dummy Data based on our new Premium Layout
    val billingData = remember {
        BillingData(
            restaurantName = "Dishoom Kensington",
            restaurantPhone = "+44 20 7420 9325",
            logoResId = Icons.Default.Coffee,
            addressLine1 = "4 Derry Street",
            city = "LONDON",
            postalCode = "W8 5SE",
            countryCode = "GB",
            staffName = "Md. Rejaul Karim",
            deviceName = "Sunmi V2 Pro",
            orderDeviceName = "Tablet-KDS-01",
            timestamp = System.currentTimeMillis(),
            orderId = "DSH-9921",
            orderTag = "Table 14",
            orderReference = "CHK-55201",
            orderType = "Dine In",
            currencyCode = "GBP",
            paymentStatus = "Paid",
            footerNote = "Optional 12.5% service charge added. Thank you!",
            subtotal = 44.0,
            serviceCharge = 5.50,
            vatPercentage = 20.0,
            isVatInclusive = true,
            grandTotal = 49.50,
            items = listOf(
                OrderItem(
                    id = "item_101",
                    name = "Chicken Ruby",
                    category = "Mains",
                    unitPrice = 14.50,
                    quantity = 2,
                    unitLabel = "portion",
                    subItems = listOf(
                        SubOrderItem("mod_1", "Extra Spicy", 0.0, 1, "")
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
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = CyanElectric)
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
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                contentAlignment = Alignment.TopCenter
            ) {
                // The actual premium paper layout
                PosPrintingScreen(data = billingData)
            }

            Spacer(modifier = Modifier.height(16.dp))

            val view = LocalView.current
            Button(
                onClick = {
                    isPrinting = true
                    scope.launch {
                        // Capture the PosPrintingScreen layout exactly as it will appear on paper
                        val bitmap: Bitmap = BitmapRenderer.renderComposableToBitmap(view) {
                            PosPrintingScreen(data = billingData)
                        }
                        viewModel.printReceipt(bitmap)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .height(64.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CyanElectric),
                enabled = !isPrinting
            ) {
                if (isPrinting) {
                    CircularProgressIndicator(color = NavySurface)
                } else {
                    Text("PRINT TO SUNMI", style = MaterialTheme.typography.titleMedium, color = NavySurface)
                }
            }
        }
    }
}
