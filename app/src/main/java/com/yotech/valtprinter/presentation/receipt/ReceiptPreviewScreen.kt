package com.yotech.valtprinter.presentation.receipt

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yotech.valtprinter.core.util.BitmapRenderer
import com.yotech.valtprinter.domain.model.PrintResult
import com.yotech.valtprinter.presentation.viewmodel.PrinterViewModel
import com.yotech.valtprinter.ui.theme.CyanElectric
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

    val receiptData = remember {
        ReceiptData("ORDER #404", "Md. Rejaul Karim", listOf("1x Burger", "1x Soda"))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Preview & Print") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
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
                contentAlignment = Alignment.Center
            ) {
                // The actual preview composable
                ReceiptTemplate(data = receiptData)
            }

            Spacer(modifier = Modifier.height(16.dp))

            val view = androidx.compose.ui.platform.LocalView.current
            Button(
                onClick = {
                    isPrinting = true
                    scope.launch {
                        val bitmap: Bitmap = BitmapRenderer.renderComposableToBitmap(view) {
                            ReceiptTemplate(data = receiptData)
                        }
                        viewModel.printReceipt(bitmap)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .height(56.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CyanElectric),
                enabled = !isPrinting
            ) {
                if (isPrinting) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text("PRINT RECEIPT", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}
