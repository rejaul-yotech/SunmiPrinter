# ValtPrinter SDK — Integration Guide

> **SDK version:** 1.0.0  
> **Min SDK:** 24 (Android 7.0)  
> **Compile SDK:** 36  
> **Language:** Kotlin  

---

## What the SDK does

ValtPrinter SDK handles everything printer-related inside your host app:

| Capability | Detail |
|---|---|
| **Discovery** | Scans for Sunmi printers over Bluetooth, USB, and LAN |
| **Connection** | Connects, disconnects, and self-heals on unexpected drops |
| **Printing** | Renders receipt templates to bitmap and sends to the printer |
| **Queue** | Persistent job queue — no print is lost even if the app is killed |
| **Foreground Service** | Keeps the print server alive in the background automatically |

Your app only decides **which device to connect to**. Everything else is handled by the SDK.

---

## Step 1 — Copy the AAR files

You need **two** AAR files. Both are available in the ValtPrinter project:

| File | Source path in ValtPrinter project |
|---|---|
| `sdk-release.aar` | `sdk/build/outputs/aar/sdk-release.aar` |
| `externalprinterlibrary2-1.0.14-release.aar` | `app/libs/externalprinterlibrary2-1.0.14-release.aar` |

**To rebuild `sdk-release.aar` at any time:**
```bash
# From ValtPrinter project root — run once first if not done yet
./gradlew publishSunmiPublicationToMavenLocal

# Then build the AAR
./gradlew :sdk:bundleReleaseAar

# Output: sdk/build/outputs/aar/sdk-release.aar
```

Copy both files into your host project's `app/libs/` folder:

```
your-host-app/
└── app/
    └── libs/
        ├── sdk-release.aar
        └── externalprinterlibrary2-1.0.14-release.aar
```

---

## Step 2 — Configure `settings.gradle.kts`

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Required for resolving the local AARs
        flatDir { dirs("libs") }
    }
}
```

---

## Step 3 — Configure `app/build.gradle.kts`

### 3a — Enable required build features

```kotlin
android {
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true   // Required — SDK uses Compose for receipt rendering
    }
}

kotlin { jvmToolchain(17) }
```

### 3b — Add dependencies

The SDK AAR does **not** bundle its Maven dependencies. You must declare them explicitly.

```kotlin
dependencies {
    // ── ValtPrinter SDK ─────────────────────────────────────────────────────
    implementation(files("libs/sdk-release.aar"))
    implementation(files("libs/externalprinterlibrary2-1.0.14-release.aar"))

    // ── SDK required dependencies ────────────────────────────────────────────
    // Core
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.code.gson:gson:2.13.2")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")

    // Compose (BOM keeps all versions in sync)
    implementation(platform("androidx.compose:compose-bom:2026.03.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")

    // Room
    implementation("androidx.room:room-runtime:2.7.0")
    implementation("androidx.room:room-ktx:2.7.0")
    ksp("androidx.room:room-compiler:2.7.0")          // or annotationProcessor if not using KSP

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Debug only
    debugImplementation("androidx.compose.ui:ui-tooling")
}
```

> **KSP plugin required for Room.** In your `build.gradle.kts` plugins block:
> ```kotlin
> plugins {
>     id("com.google.devtools.ksp") version "2.3.6"   // match your Kotlin version
> }
> ```
> And in `settings.gradle.kts` `pluginManagement.repositories`:
> ```kotlin
> gradlePluginPortal()
> ```

---

## Step 4 — Initialise the SDK

Call `ValtPrinterSdk.init()` exactly once, in your `Application` class:

```kotlin
import android.app.Application
import com.yotech.valtprinter.sdk.ValtPrinterSdk

class EposApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ValtPrinterSdk.init(this)
    }
}
```

Register your `Application` class in `AndroidManifest.xml`:

```xml
<application
    android:name=".EposApp"
    ... >
```

> **Permissions, the foreground service, and the boot receiver are all declared in the
> SDK's own manifest and merged automatically.** You do not need to add them manually.

---

## Step 5 — Use the SDK

Obtain the singleton anywhere after init:

```kotlin
val sdk = ValtPrinterSdk.get()
```

### Scan for printers

```kotlin
// Start scan — results arrive via sdk.discoveredDevices
sdk.startScan()

// Observe discovered devices in your ViewModel / Composable
sdk.discoveredDevices.collect { devices ->
    // devices: List<PrinterDevice>
    // Each PrinterDevice has: id, name, address, connectionType (USB/LAN/BLUETOOTH)
}

// Stop scan when done
sdk.stopScan()
```

### Connect to a printer

The host app decides which device to connect to (e.g. user selects from scan results or you persist the preferred device):

```kotlin
lifecycleScope.launch {
    val device = sdk.discoveredDevices.value.first() // or your preferred device
    sdk.printerRepository.connect(device)
}
```

### Observe printer state

```kotlin
sdk.printerState.collect { state ->
    when (state) {
        is PrinterState.Idle         -> { /* no printer */ }
        is PrinterState.Scanning     -> { /* scanning */ }
        is PrinterState.Connecting   -> { /* handshaking with state.deviceName */ }
        is PrinterState.Connected    -> { /* ready — state.device has details */ }
        is PrinterState.Reconnecting -> { /* self-healing — state.deviceName, state.microState */ }
        is PrinterState.Error        -> { /* state.message, state.detail */ }
        is PrinterState.AutoConnecting -> { /* USB auto-connect in progress */ }
    }
}
```

### Disconnect

```kotlin
sdk.disconnect()
```

### Submit a print job

```kotlin
import com.yotech.valtprinter.data.local.entity.PrintJobEntity
import com.yotech.valtprinter.data.local.entity.PrintStatus

// The SDK accepts a job payload as JSON via the internal print DAO.
// Use the AIDL-free API: insert a job directly.

val dao = sdk.printDao    // internal, but exposed for direct job submission

lifecycleScope.launch(Dispatchers.IO) {
    val entity = PrintJobEntity(
        externalJobId = "ORDER-1234",
        payloadJson   = yourGsonPayloadJson,   // see Payload Format section below
        isPriority    = false,
        status        = PrintStatus.PENDING
    )
    dao.insertPrintJob(entity)
    // The SDK's QueueDispatcher picks it up automatically within ~2 seconds
}
```

### Receive job callbacks

```kotlin
import com.yotech.valtprinter.sdk.PrintJobCallback

class EposActivity : AppCompatActivity(), PrintJobCallback {

    override fun onResume() {
        super.onResume()
        ValtPrinterSdk.get().registerCallback(this)
    }

    override fun onPause() {
        super.onPause()
        ValtPrinterSdk.get().unregisterCallback(this)
    }

    override fun onJobSuccess(jobId: String) {
        runOnUiThread { showToast("Printed: $jobId") }
    }

    override fun onJobFailed(jobId: String, reason: String) {
        runOnUiThread { showToast("Failed $jobId: $reason") }
    }
}
```

---

## Payload Format

The SDK uses a JSON payload to identify which receipt template to render.

### POS / Billing receipt

```json
{
  "type": "BILLING",
  "data": {
    "orderId": "ORD-001",
    "restaurantName": "My Restaurant",
    "restaurantPhone": "+1 555 0100",
    "addressLine1": "123 Main St",
    "city": "New York",
    "postalCode": "10001",
    "countryCode": "US",
    "staffName": "John",
    "deviceName": "POS-1",
    "orderDeviceName": "Tablet-01",
    "timestamp": 1743602874000,
    "orderTag": "Table 5",
    "orderReference": "CHK-001",
    "orderType": "Dine In",
    "currencyCode": "USD",
    "paymentStatus": "Paid",
    "subtotal": 25.00,
    "serviceCharge": 0.0,
    "vatPercentage": 10.0,
    "isVatInclusive": false,
    "additionalCharge": 0.0,
    "bagFee": 0.0,
    "grandTotal": 27.50,
    "items": [
      {
        "id": "item_1",
        "name": "Burger",
        "category": "Mains",
        "unitPrice": 12.50,
        "quantity": 2,
        "unitLabel": "pcs",
        "subItems": []
      }
    ]
  }
}
```

### Raw text

```json
{
  "type": "RAW_TEXT",
  "text": "Hello from KDS!\nOrder #42\n3x Burger"
}
```

Build the JSON string with Gson:

```kotlin
val gson = Gson()
val payload = mapOf(
    "type" to "BILLING",
    "data" to billingDataObject
)
val payloadJson = gson.toJson(payload)
```

---

## Auto-connect on USB attach (optional)

If you want USB printers to connect automatically when plugged in, add to your `AndroidManifest.xml`:

```xml
<activity android:name=".YourMainActivity" ...>
    <intent-filter>
        <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
    </intent-filter>
    <meta-data
        android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
        android:resource="@xml/device_filter" />
</activity>
```

Create `res/xml/device_filter.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <usb-device vendor-id="0x0fe6" />  <!-- Sunmi USB VID -->
</resources>
```

Then in your Activity:

```kotlin
override fun onNewIntent(intent: Intent?) {
    super.onNewIntent(intent)
    if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
        lifecycleScope.launch {
            ValtPrinterSdk.get().printerRepository.autoConnectUsb()
        }
    }
}
```

---

## ProGuard / R8

No manual rules needed. The SDK ships a `consumer-rules.pro` file that is applied to your app automatically when you add the AAR.

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| `ValtPrinterSdk not initialised` | Ensure `ValtPrinterSdk.init(this)` is called in `Application.onCreate()` and the Application class is registered in the manifest |
| Build error: `Unresolved reference: ValtPrinterSdk` | Confirm both AARs are in `app/libs/` and `files("libs/sdk-release.aar")` is in dependencies |
| `ROOM_DOWNGRADE` crash | Never replace the AAR with an older version without providing a Room Migration |
| Printer connects but nothing prints | Check that `PrinterState.Connected` is reached before submitting jobs; inspect `sdk.printDao.getNextJob()` to see if jobs are queued |
| Bluetooth scan finds nothing | Ensure `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` permissions are granted at runtime (Android 12+) |
| USB not detected | Ensure `USB_DEVICE_ATTACHED` intent filter and device filter XML are configured (see Auto-connect section) |

---

## Rebuilding the AAR after SDK changes

```bash
# In ValtPrinter project — run once per machine, not per build
./gradlew publishSunmiPublicationToMavenLocal

# Every time SDK code changes
./gradlew :sdk:bundleReleaseAar

# Output
sdk/build/outputs/aar/sdk-release.aar
```

Replace `sdk-release.aar` in your host project's `app/libs/` and sync Gradle.
