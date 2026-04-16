package com.yotech.valtprinter.data.local.db

import androidx.room.*
import com.yotech.valtprinter.data.local.dao.PairedDeviceDao
import com.yotech.valtprinter.data.local.dao.PrintDao
import com.yotech.valtprinter.data.local.entity.PairedDeviceEntity
import com.yotech.valtprinter.data.local.entity.PrintJobEntity
import com.yotech.valtprinter.data.local.entity.PrintStatus

@Database(entities = [PrintJobEntity::class, PairedDeviceEntity::class], version = 3, exportSchema = false)
@TypeConverters(Converters::class)
abstract class PrinterDatabase : RoomDatabase() {
    abstract fun printDao(): PrintDao
    abstract fun pairedDeviceDao(): PairedDeviceDao
}

class Converters {
    @TypeConverter
    fun fromStatus(status: PrintStatus): String = status.name

    @TypeConverter
    fun toStatus(value: String): PrintStatus = enumValueOf<PrintStatus>(value)
}
