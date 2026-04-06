package com.yotech.valtprinter.di

import android.content.Context
import androidx.room.Room
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

    @Provides
    @Singleton
    fun providePrinterDatabase(@ApplicationContext context: Context): PrinterDatabase {
        return Room.databaseBuilder(
            context,
            PrinterDatabase::class.java,
            "valt_printer_db"
        ).fallbackToDestructiveMigration().build()
    }

    @Provides
    @Singleton
    fun providePrintDao(database: PrinterDatabase): PrintDao {
        return database.printDao()
    }
}
