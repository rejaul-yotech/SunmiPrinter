# ValtPrinter SDK — Integration Guide

> **SDK version:** 1.1.0 (Phase 0 hardening)
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
| **Idempotency** | Submitting the same `externalJobId` twice is a deterministic no-op |
| **Observability** | Built-in `SdkLogger` redacts MAC, IP, device IDs, and device names |

Your app only decides **which device to connect to** and **what payload to print**. Everything else is handled by the SDK.

---

## What changed in 1.1.0 (read this if you are upgrading)

The public API was tightened to remove Law-of-Demeter leaks and Sunmi types from the surface. If you are upgrading from 1.0.0:

| 1.0.0 (deprecated) | 1.1.0 (current) |
|---|---|
| `sdk.printerRepository.connect(device)` | `sdk.connect(device)` |
| `sdk.printerRepository.autoConnectUsb()` | `sdk.autoConnectUsb()` |
| `sdk.printerRepository.disconnect()` | `sdk.disconnect()` |
| `sdk.printDao.insertPrintJob(entity)` | `sdk.submitPrintJob(payload, externalJobId, isPriority)` |
| Strong-ref capture View | `WeakReference` — host **must** call `clearCaptureView()` in `onDestroy` |
| `getActiveCloudPrinter()` (returned Sunmi `CloudPrinter`) | Removed — abstract `isPrinterReady()` is internal |

`PrinterRepository` is no longer accessible from host apps. Every capability is now a single delegating method on `ValtPrinterSdk`.

---

## Step 1 — Add the SDK to your project

You have two distribution paths. **Maven Local** is the recommended primary path because it preserves transitive dependency metadata and version resolution. **flatDir** is supported as a fallback for environments where you cannot run a publish step.

### Option A — Maven Local (recommended)

In the **ValtPrinter project**, publish the AARs to your machine's local Maven cache:

```bash
# One-time: publish the Sunmi vendor AAR
./gradlew publishSunmiPublicationToMavenLocal

# Every time SDK code changes: publish the SDK
./gradlew :sdk:publishToMavenLocal
```

In your **host app's** `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        mavenLocal()  // resolves sdk-release.aar + Sunmi vendor AAR
    }
}
```

In your host app's `app/build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.yotech.valtprinter:sdk:1.1.0")
    // The Sunmi vendor AAR is pulled transitively — no separate declaration needed.
}
```

### Option B — flatDir (fallback)

Copy both AARs into `your-host-app/app/libs/`:

| File | Source path in ValtPrinter project |
|---|---|
| `sdk-release.aar` | `sdk/build/outputs/aar/sdk-release.aar` |
| `externalprinterlibrary2-1.0.14-release.aar` | `app/libs/externalprinterlibrary2-1.0.14-release.aar` |

Rebuild `sdk-release.aar`:

```bash
./gradlew publishSunmiPublicationToMavenLocal   # one-time
./gradlew :sdk:bundleReleaseAar                 # every change
# Output: sdk/build/outputs/aar/sdk-release.aar
```

In `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        flatDir { dirs("libs") }
    }
}
```

In `app/build.gradle.kts`:

```kotlin
dependencies {
    implementation(files("libs/sdk-release.aar"))
    implementation(files("libs/externalprinterlibrary2-1.0.14-release.aar"))
    // … plus all transitive deps below, because flatDir does not propagate POM metadata.
}
```

---

## Step 2 — Configure `app/build.gradle.kts`

### 2a — Enable required build features

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

### 2b — Add transitive dependencies (flatDir only)

If you used **Option A (Maven Local)** these are pulled transitively. If you used **Option B (flatDir)**, declare them explicitly:

```kotlin
dependencies {
    // Core
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.code.gson:gson:2.13.2")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")

    // Compose (BOM keeps versions in sync)
    implementation(platform("androidx.compose:compose-bom:2026.03.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")

    // Room
    implementation("androidx.room:room-runtime:2.7.0")
    implementation("androidx.room:room-ktx:2.7.0")
    ksp("androidx.room:room-compiler:2.7.0")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Debug only
    debugImplementation("androidx.compose.ui:ui-tooling")
}
```

> **KSP plugin required for Room.** In your top-level/`app` `build.gradle.kts` plugins block:
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

## Step 3 — Initialise the SDK

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

> **Permissions, the foreground service, the boot receiver, the Room database,
> the WorkManager cleanup job, and Sunmi vendor manifest entries are all
> declared in the SDK's own manifest and merged automatically.** You do not need
> to declare them yourself.

After `init`, access the singleton anywhere:

```kotlin
val sdk = ValtPrinterSdk.get()
```

`get()` throws `IllegalStateException` if `init` was never called — fail loud during development, never silently.

---

## Step 4 — Headless rendering setup (mandatory)

The SDK renders receipts to bitmap by composing into an off-screen `View` that **the host owns**. You must register that View once it is attached, and clear it when its owner is destroyed. The SDK holds your View through a `WeakReference`, but `clearCaptureView()` is required to deterministically release rendering pipeline state.

### Recommended pattern — `LifecycleObserver`

```kotlin
class CaptureViewBinder(
    private val view: View,
    private val sdk: ValtPrinterSdk = ValtPrinterSdk.get()
) : DefaultLifecycleObserver {

    override fun onCreate(owner: LifecycleOwner) {
        sdk.setCaptureView(view)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        sdk.clearCaptureView()
    }
}

// In your Activity / Fragment:
lifecycle.addObserver(CaptureViewBinder(myOffscreenComposeView))
```

### Minimal pattern — direct calls

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val captureView = ComposeView(this).apply {
        // visibility = View.INVISIBLE   // off-screen but laid out
    }
    setContentView(captureView)
    ValtPrinterSdk.get().setCaptureView(captureView)
}

override fun onDestroy() {
    ValtPrinterSdk.get().clearCaptureView()
    super.onDestroy()
}
```

> **Why this matters:** without a registered View, the queue dispatcher cannot
> render new chunks and the active job will fail with
> `"Capture View is null. UI not ready for headless rendering."`. The
> `WeakReference` prevents Activity leaks, but does not absolve you of
> calling `clearCaptureView()` — that is the contract.

---

## Step 5 — Use the SDK

### Scan for printers

```kotlin
sdk.startScan()

// Observe discovered devices in your ViewModel / Composable
sdk.discoveredDevices.collect { devices ->
    // devices: List<PrinterDevice>
    // Each PrinterDevice has: id, name, address, connectionType (USB/LAN/BLUETOOTH)
}

sdk.stopScan()
```

### Connect to a printer

```kotlin
lifecycleScope.launch {
    val device = sdk.discoveredDevices.value.first()  // or your preferred device
    sdk.connect(device)
}
```

### Reconnect to a previously paired printer

When the device is no longer in the discovered list (e.g. on app restart):

```kotlin
lifecycleScope.launch {
    val launched: Boolean = sdk.connectPairedDevice(savedDevice)
    if (!launched) {
        // Couldn't even start the attempt — surface UX (e.g. "device not bonded").
    }
}
```

### Best-effort USB auto-connect

```kotlin
lifecycleScope.launch {
    val didConnect: Boolean = sdk.autoConnectUsb()
}
```

### Observe printer state

```kotlin
sdk.printerState.collect { state ->
    when (state) {
        is PrinterState.Idle           -> { /* no printer */ }
        is PrinterState.Scanning       -> { /* scanning */ }
        is PrinterState.Connecting     -> { /* handshaking with state.deviceName */ }
        is PrinterState.Connected      -> { /* ready — state.device has details */ }
        is PrinterState.Reconnecting   -> { /* self-healing — state.deviceName, state.microState */ }
        is PrinterState.Error          -> { /* state.message, state.detail */ }
        is PrinterState.AutoConnecting -> { /* USB auto-connect in progress */ }
    }
}
```

### Hardware & permission probes

```kotlin
val usbAttached: Boolean   = sdk.isUsbPrinterPresent()
val isBonded: Boolean      = sdk.isBtDeviceBonded(macAddress)
val canTalkToBt: Boolean   = sdk.hasBtConnectPermission()  // always true on API < 31
```

### Disconnect

```kotlin
sdk.disconnect()
```

### Submit a print job

The host app builds a typed `PrintPayload` and submits it via a single suspend
call. The SDK serialises it, persists it idempotently, and returns immediately.
The background queue dispatcher prints it as soon as a connected printer is
available.

```kotlin
import com.yotech.valtprinter.domain.model.PrintPayload
import com.yotech.valtprinter.sdk.SubmitResult

lifecycleScope.launch {
    val payload = PrintPayload.Billing(data = billingData)   // or PrintPayload.RawText("…")

    when (val result = sdk.submitPrintJob(
        payload        = payload,
        externalJobId  = "ORDER-1234",   // your idempotency key
        isPriority     = false
    )) {
        is SubmitResult.Enqueued  -> { /* queued — wait for callback */ }
        is SubmitResult.Duplicate -> { /* same id submitted before — no-op */ }
        is SubmitResult.Failure   -> { /* persistence error — show result.reason */ }
    }
}
```

> **Idempotency contract:** submitting the same `externalJobId` twice is a
> deterministic no-op. The second call returns `SubmitResult.Duplicate` and
> does not re-print. Use this to safely retry network/AIDL callers without
> producing double receipts.

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

`jobId` here is the same `externalJobId` you supplied to `submitPrintJob`.

---

## Payload format

Construct typed payloads — never raw JSON. The SDK uses Gson internally, but
that is an implementation detail you are no longer coupled to.

### POS / Billing receipt

```kotlin
val payload = PrintPayload.Billing(
    data = BillingData(
        orderId         = "ORD-001",
        restaurantName  = "My Restaurant",
        restaurantPhone = "+1 555 0100",
        addressLine1    = "123 Main St",
        city            = "New York",
        postalCode      = "10001",
        countryCode     = "US",
        staffName       = "John",
        deviceName      = "POS-1",
        orderDeviceName = "Tablet-01",
        timestamp       = System.currentTimeMillis(),
        orderTag        = "Table 5",
        orderReference  = "CHK-001",
        orderType       = "Dine In",
        currencyCode    = "USD",
        paymentStatus   = "Paid",
        subtotal        = 25.00,
        serviceCharge   = 0.0,
        vatPercentage   = 10.0,
        isVatInclusive  = false,
        additionalCharge = 0.0,
        bagFee          = 0.0,
        grandTotal      = 27.50,
        items           = listOf(
            BillingItem(
                id        = "item_1",
                name      = "Burger",
                category  = "Mains",
                unitPrice = 12.50,
                quantity  = 2,
                unitLabel = "pcs",
                subItems  = emptyList()
            )
        )
    )
)
```

### Raw text

```kotlin
val payload = PrintPayload.RawText("Hello from KDS!\nOrder #42\n3x Burger")
```

### Unknown / passthrough

`PrintPayload.Unknown` exists for the queue dispatcher to print arbitrary
diagnostic JSON when an upstream system supplies a payload type the SDK does
not yet understand. Host apps should not normally produce this variant.

---

## Auto-connect on USB attach (optional)

Add to `AndroidManifest.xml`:

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

`res/xml/device_filter.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <usb-device vendor-id="0x0fe6" />  <!-- Sunmi USB VID -->
</resources>
```

In your Activity:

```kotlin
override fun onNewIntent(intent: Intent?) {
    super.onNewIntent(intent)
    if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
        lifecycleScope.launch {
            ValtPrinterSdk.get().autoConnectUsb()
        }
    }
}
```

---

## Error-handling patterns

The SDK only surfaces errors at three places. Handle each in the layer that owns it:

| Source | What you get | Recommended handling |
|---|---|---|
| `submitPrintJob` | `SubmitResult.Failure(reason)` | Show inline error to operator. Safe to retry with the same `externalJobId`. |
| `printerState` | `PrinterState.Error(message, detail)` | Update connection UI. The SDK auto-recovers transient drops via `Reconnecting`. |
| `PrintJobCallback.onJobFailed` | `(jobId, reason)` | Show toast/notification. Do **not** auto-resubmit blindly — the queue already retries connectivity-class failures. |

> **Connectivity-class failures** (Bluetooth power-off, USB unplug, LAN drop)
> are detected by the queue dispatcher and the job is held in `INTERRUPTED`
> state until the printer reconnects. You receive no callback in that window —
> watch `printerState` instead.

> **Paper-out** is treated as a full reprint trigger: progress is reset to chunk 0
> and the queue is paused until the operator resolves it (loads paper, then
> the next state transition resumes the queue).

---

## Observability & PII redaction

The SDK uses an internal `SdkLogger` for all log output. It redacts identifiers
before they reach `Log.*`:

| Field | Redaction |
|---|---|
| MAC address | `AA:BB:CC:11:22:33` → `AA:BB:CC:**:**:**` (OUI preserved for vendor diagnosis) |
| IPv4 address | `192.168.1.42` → `192.168.*.*` (/16 preserved) |
| Device id | `BT-AA:BB:CC:11:22:33` → `BT-AA:BB:CC:**:**:**` |
| Device name | `Cashier-Front-Desk-Reaj` → `Cashier-***` |

You should follow the same policy in your host app. **Do not log raw MACs, IPs,
or PII** when reacting to `printerState` or `PrintJobCallback` events.

---

## ProGuard / R8

No manual rules needed. The SDK ships a `consumer-rules.pro` file that is
applied to your app automatically when you add the AAR.

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| `ValtPrinterSdk not initialised` | Ensure `ValtPrinterSdk.init(this)` runs in `Application.onCreate()` and the Application class is registered in the manifest |
| Build error: `Unresolved reference: ValtPrinterSdk` | Confirm Maven Local was published, or both AARs are in `app/libs/` and listed in `dependencies` |
| Build error: `Unresolved reference: printerRepository` | You are on the old API. Replace `sdk.printerRepository.X(...)` with `sdk.X(...)` — see the upgrade table at the top |
| `ROOM_DOWNGRADE` crash | Never replace the AAR with an older version without providing a Room Migration |
| Jobs queued but nothing prints | Check `sdk.printerState` reaches `Connected`; verify a capture View is registered (`setCaptureView`) |
| `onJobFailed` fires with `"Capture View is null"` | You forgot to call `setCaptureView` after the host View was attached, or you cleared it too early |
| Bluetooth scan finds nothing | Ensure `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` are granted at runtime (Android 12+); confirm `sdk.hasBtConnectPermission()` returns true |
| USB not detected | Confirm `USB_DEVICE_ATTACHED` intent filter and device filter XML are configured (see Auto-connect section); call `sdk.isUsbPrinterPresent()` to probe |
| Same receipt printed twice | You are submitting different `externalJobId` values for the same logical order — supply a stable id and rely on `SubmitResult.Duplicate` |

---

## Rebuilding the AAR after SDK changes

```bash
# In ValtPrinter project — run once per machine, not per build
./gradlew publishSunmiPublicationToMavenLocal

# Maven Local path (recommended)
./gradlew :sdk:publishToMavenLocal

# flatDir path (fallback)
./gradlew :sdk:bundleReleaseAar
# Output: sdk/build/outputs/aar/sdk-release.aar
```

For the flatDir path, copy `sdk-release.aar` into your host project's
`app/libs/` and sync Gradle. For the Maven Local path, just sync Gradle in the
host project — the new version is picked up automatically.
