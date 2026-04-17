package com.yotech.valtprinter.sdk

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.gson.Gson
import com.yotech.valtprinter.data.local.dao.PairedDeviceDao
import com.yotech.valtprinter.data.local.dao.PrintDao
import com.yotech.valtprinter.data.local.datastore.PrinterDataStore
import com.yotech.valtprinter.data.local.db.PrinterDatabase
import com.yotech.valtprinter.data.queue.QueueDispatcher
import com.yotech.valtprinter.data.repository.PrinterRepositoryImpl
import com.yotech.valtprinter.data.service.PrinterForegroundService
import com.yotech.valtprinter.data.source.RawSocketPrintSource
import com.yotech.valtprinter.data.source.SdkPrintSource
import com.yotech.valtprinter.data.worker.CleanupWorker
import com.yotech.valtprinter.domain.model.PrinterDevice
import com.yotech.valtprinter.domain.model.PrinterState
import com.yotech.valtprinter.domain.repository.PrinterRepository
import com.yotech.valtprinter.domain.repository.RenderRepository
import com.yotech.valtprinter.domain.util.PayloadParser
import com.yotech.valtprinter.domain.util.PrinterCallbackManager
import com.yotech.valtprinter.core.util.FeedbackManager
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

    private val repository: PrinterRepositoryImpl = PrinterRepositoryImpl(
        context = app,
        sdkPrintSource = sdkPrintSource,
        rawSocketPrintSource = rawSocketPrintSource,
        feedbackManager = feedbackManager
    )

    private val callbackManager: PrinterCallbackManager = PrinterCallbackManager()
    private val payloadParser: PayloadParser = PayloadParser(Gson())

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

    /** Live printer connection state. Observe in the host app to update UI. */
    val printerState: StateFlow<PrinterState> = repository.printerState

    /** Discovered printers during an active scan. */
    val discoveredDevices: StateFlow<List<PrinterDevice>> = repository.discoveredDevices

    /** The underlying [PrinterRepository] for advanced use cases. */
    val printerRepository: PrinterRepository = repository

    /** Start scanning for nearby printers (Bluetooth, USB, LAN). */
    fun startScan() = repository.startScan()

    /** Stop any active scan. */
    fun stopScan() = repository.stopScan()

    /** Disconnect from the currently connected printer. */
    fun disconnect() = repository.disconnect()

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
