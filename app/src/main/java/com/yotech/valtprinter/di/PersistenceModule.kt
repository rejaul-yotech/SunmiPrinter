package com.yotech.valtprinter.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yotech.valtprinter.data.local.dao.PairedDeviceDao
import com.yotech.valtprinter.data.local.dao.PrintDao
import com.yotech.valtprinter.data.local.db.PrinterDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PersistenceModule {
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

    @Provides
    @Singleton
    fun providePrinterDatabase(@ApplicationContext context: Context): PrinterDatabase {
        return Room.databaseBuilder(
                context,
                PrinterDatabase::class.java,
                "valt_printer_db"
            ).addMigrations(MIGRATION_1_2).build()
    }

    @Provides
    @Singleton
    fun providePrintDao(database: PrinterDatabase): PrintDao {
        return database.printDao()
    }

    @Provides
    @Singleton
    fun providePairedDeviceDao(database: PrinterDatabase): PairedDeviceDao {
        return database.pairedDeviceDao()
    }
}
