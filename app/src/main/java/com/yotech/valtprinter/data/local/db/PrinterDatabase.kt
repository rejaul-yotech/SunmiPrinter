package com.yotech.valtprinter.data.local.db

import androidx.room.*
import com.yotech.valtprinter.data.local.dao.PrintDao
import com.yotech.valtprinter.data.local.entity.PrintJobEntity
import com.yotech.valtprinter.data.local.entity.PrintStatus

@Database(entities = [PrintJobEntity::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class PrinterDatabase : RoomDatabase() {
    abstract fun printDao(): PrintDao
}

class Converters {
    @TypeConverter
    fun fromStatus(status: PrintStatus): String = status.name

    @TypeConverter
    fun toStatus(value: String): PrintStatus = enumValueOf<PrintStatus>(value)
}
