package com.yotech.valtprinter.sdk

import android.app.Application
import android.content.Context
import android.content.Intent
import android.view.View
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.gson.Gson
import com.yotech.valtprinter.core.util.FeedbackManager
import com.yotech.valtprinter.core.util.SdkLogger
import com.yotech.valtprinter.data.local.dao.PairedDeviceDao
import com.yotech.valtprinter.data.local.dao.PrintDao
import com.yotech.valtprinter.data.local.datastore.PrinterDataStore
import com.yotech.valtprinter.data.local.db.PrinterDatabase
import com.yotech.valtprinter.data.local.entity.PrintJobEntity
import com.yotech.valtprinter.data.queue.QueueDispatcher
import com.yotech.valtprinter.data.repository.PrinterRepositoryImpl
import com.yotech.valtprinter.data.service.PrinterForegroundService
import com.yotech.valtprinter.data.source.RawSocketPrintSource
import com.yotech.valtprinter.data.source.SdkPrintSource
import com.yotech.valtprinter.data.worker.CleanupWorker
import com.yotech.valtprinter.domain.model.PrintPayload
import com.yotech.valtprinter.domain.model.PrinterDevice
import com.yotech.valtprinter.domain.model.PrinterState
import com.yotech.valtprinter.domain.repository.PrinterRepository
import com.yotech.valtprinter.domain.util.PayloadParser
import com.yotech.valtprinter.domain.util.PrinterCallbackManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.TimeUnit

/**
 * Single entry point for the ValtPrinter SDK.
 *
 * **Host app setup** (call once in `Application.onCreate()`):
 * ```kotlin
 * ValtPrinterSdk.init(this)
 * ```
 * After init, access the singleton via `ValtPrinterSdk.get()`.
 */
class ValtPrinterSdk private constructor(app: Application) {

    // ── Internal dependency graph (manual DI) ────────────────────────────────

    private val db: PrinterDatabase = Room.databaseBuilder(
        app, PrinterDatabase::class.java, "valt_printer_db"
    )
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
        .build()

    internal val printDao: PrintDao = db.printDao()
    internal val pairedDeviceDao: PairedDeviceDao = db.pairedDeviceDao()
    internal val printerDataStore: PrinterDataStore = PrinterDataStore(app)

    private val feedbackManager: FeedbackManager = FeedbackManager(app)
    private val sdkPrintSource: SdkPrintSource = SdkPrintSource()
    private val rawSocketPrintSource: RawSocketPrintSource = RawSocketPrintSource()

    /**
     * Exposed as the composite [PrinterRepository] interface — not the concrete
     * impl. Manifest-declared components resolve this via [component] and MUST
     * NOT downcast. See [SdkComponent] for the reach-through contract.
     */
    internal val printerRepository: PrinterRepository get() = repository
    private val repository: PrinterRepositoryImpl = PrinterRepositoryImpl(
        context = app,
        sdkPrintSource = sdkPrintSource,
        rawSocketPrintSource = rawSocketPrintSource,
        feedbackManager = feedbackManager
    )

    private val callbackManager: PrinterCallbackManager = PrinterCallbackManager()
    private val payloadParser: PayloadParser = PayloadParser(Gson())

    /**
     * Supervisor scope for fire-and-forget work started from manifest-declared
     * components (broadcast receivers, service callbacks). Rooted at the SDK
     * singleton — lives for the lifetime of the process. Intentionally
     * separate from [QueueDispatcher]'s scope; see [SdkComponent].
     */
    private val asyncScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    internal val queueDispatcher: QueueDispatcher = QueueDispatcher(
        context = app,
        printDao = printDao,
        printerDataStore = printerDataStore,
        printerRepository = repository,
        renderRepository = repository,
        payloadParser = payloadParser,
        callbackManager = callbackManager
    )

    // ── Public API ───────────────────────────────────────────────────────────
    //
    // The SDK intentionally exposes a flat, narrow surface — host apps must NOT receive
    // a reference to the underlying [PrinterRepository] composite, because doing so leaks
    // internal sub-interfaces (RenderRepository, PrintJobRepository) and makes Law-of-Demeter
    // violations inevitable. Every public capability gets its own delegating method below.

    /** Live printer connection state. Observe in the host app to update UI. */
    val printerState: StateFlow<PrinterState> = repository.printerState

    /** Discovered printers during an active scan. */
    val discoveredDevices: StateFlow<List<PrinterDevice>> = repository.discoveredDevices

    /**
     * Per-job lifecycle event stream. Hosts correlate by [JobEvent.externalJobId]
     * to drive UI for a specific order (e.g. order-card progress/failure state)
     * independent of other jobs in flight.
     *
     * Hot, `replay = 0` — see [JobEvent] for the full state machine and
     * delivery semantics. [PrintJobCallback] is the simpler alternative for
     * hosts that only need terminal outcomes.
     */
    val jobEvents: SharedFlow<JobEvent> = callbackManager.jobEvents

    // ── Discovery & connection ───────────────────────────────────────────────

    /** Start scanning for nearby printers (Bluetooth, USB, LAN). */
    fun startScan() = repository.startScan()

    /** Stop any active scan. */
    fun stopScan() = repository.stopScan()

    /** Connect to a previously-discovered [device]. */
    suspend fun connect(device: PrinterDevice) = repository.connect(device)

    /**
     * Reconnect to a previously-paired [device] that may not currently be in the
     * discovered-devices list. Returns true when the connection attempt was launched.
     */
    suspend fun connectPairedDevice(device: PrinterDevice): Boolean =
        repository.connectPairedDevice(device)

    /** Best-effort one-shot USB auto-connect. Returns true when a USB printer was found. */
    suspend fun autoConnectUsb(): Boolean = repository.autoConnectUsb()

    /** Disconnect from the currently connected printer. */
    fun disconnect() = repository.disconnect()

    // ── Hardware & permission probes ─────────────────────────────────────────

    /** True if any USB device is currently attached. */
    fun isUsbPrinterPresent(): Boolean = repository.isUsbPrinterPresent()

    /** True if the Bluetooth device identified by [mac] is paired with this Android device. */
    fun isBtDeviceBonded(mac: String): Boolean = repository.isBtDeviceBonded(mac)

    /**
     * True if the app holds [android.Manifest.permission.BLUETOOTH_CONNECT].
     * Always true on API < 31.
     */
    fun hasBtConnectPermission(): Boolean = repository.hasBtConnectPermission()

    // ── Headless rendering plumbing ──────────────────────────────────────────

    /**
     * Register the off-screen [view] that the SDK will use to render receipts to bitmap.
     * The SDK holds [view] through a [java.lang.ref.WeakReference] — the host MUST still
     * call [clearCaptureView] when the owning component is destroyed to release rendering
     * pipeline state deterministically.
     */
    fun setCaptureView(view: View) = repository.setCaptureView(view)

    /** Drop the current capture view registration. Idempotent. */
    fun clearCaptureView() = repository.clearCaptureView()

    // ── Job submission ───────────────────────────────────────────────────────

    /**
     * Enqueue a print job. The SDK persists the payload, returns immediately, and the
     * background queue dispatcher prints it as soon as a connected printer is available.
     *
     * @param payload  the typed receipt payload to print.
     * @param externalJobId  caller-supplied idempotency key. Submitting twice with the same
     *                       id is a no-op — the second call returns [SubmitResult.Duplicate].
     * @param isPriority  true to float this job to the head of the queue.
     */
    suspend fun submitPrintJob(
        payload: PrintPayload,
        externalJobId: String,
        isPriority: Boolean = false
    ): SubmitResult {
        return try {
            val entity = PrintJobEntity(
                externalJobId = externalJobId,
                payloadJson = payloadParser.serialize(payload),
                isPriority = isPriority
            )
            val rowId = printDao.insertPrintJob(entity)
            if (rowId == -1L) {
                SubmitResult.Duplicate(externalJobId)
            } else {
                // Mirror the queue-row insertion into the JobEvent stream so
                // hosts observing [jobEvents] see the full lifecycle from the
                // moment of acceptance, without having to poll Room.
                callbackManager.emitEnqueued(externalJobId)
                SubmitResult.Enqueued(externalJobId)
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            SdkLogger.e("VALT_SUBMIT", "submitPrintJob failed for id=$externalJobId", t)
            SubmitResult.Failure(t.message ?: "Unknown persistence error")
        }
    }

    // ── Callbacks ────────────────────────────────────────────────────────────

    /**
     * Register a [PrintJobCallback] to receive job success/failure events.
     * Call [unregisterCallback] when the host component is destroyed.
     */
    fun registerCallback(callback: PrintJobCallback) =
        callbackManager.register(callback)

    /** Unregister a previously registered [PrintJobCallback]. */
    fun unregisterCallback(callback: PrintJobCallback) =
        callbackManager.unregister(callback)

    // ── Companion / Init ─────────────────────────────────────────────────────

    companion object {
        @Volatile private var instance: ValtPrinterSdk? = null

        /**
         * Initialise the SDK. Must be called once from `Application.onCreate()`.
         * Subsequent calls are no-ops.
         */
        fun init(app: Application): ValtPrinterSdk {
            return instance ?: synchronized(this) {
                instance ?: ValtPrinterSdk(app).also { sdk ->
                    instance = sdk
                    sdk.startForegroundService(app)
                    sdk.scheduleMaintenance(app)
                }
            }
        }

        /**
         * Returns the initialised SDK instance.
         * @throws IllegalStateException if [init] was not called first.
         */
        fun get(): ValtPrinterSdk =
            instance ?: error("ValtPrinterSdk not initialised. Call ValtPrinterSdk.init(app) in Application.onCreate().")

        /**
         * Typed dependency bag for manifest-instantiated Android components.
         * This is the **only** reach-through into the SDK allowed from
         * broadcast receivers and services. See [SdkComponent] for rationale.
         *
         * @throws IllegalStateException if [init] was not called first.
         */
        internal fun component(): SdkComponent {
            val sdk = get()
            return SdkComponent(
                printerRepository = sdk.printerRepository,
                queueDispatcher = sdk.queueDispatcher,
                printerDataStore = sdk.printerDataStore,
                asyncScope = sdk.asyncScope
            )
        }
    }

    private fun startForegroundService(context: Context) {
        val intent = Intent(context, PrinterForegroundService::class.java)
        context.startForegroundService(intent)
    }

    private fun scheduleMaintenance(context: Context) {
        val request = PeriodicWorkRequestBuilder<CleanupWorker>(24, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "ValtCleanup",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}

// ── Room Migrations (kept here alongside the SDK init, not spread across DI modules) ──

private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS paired_devices (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                address TEXT NOT NULL,
                connection_type TEXT NOT NULL,
                model TEXT,
                paired_at INTEGER NOT NULL,
                last_seen_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }
}

private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE paired_devices ADD COLUMN is_bonded INTEGER NOT NULL DEFAULT 0")
    }
}
