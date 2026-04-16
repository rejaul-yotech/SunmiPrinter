package com.yotech.valtprinter.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "paired_devices")
data class PairedDeviceEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val address: String,
    @ColumnInfo(name = "connection_type")
    val connectionType: String,
    val model: String? = null,
    @ColumnInfo(name = "paired_at")
    val pairedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "last_seen_at")
    val lastSeenAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "is_bonded")
    val isBonded: Boolean = false
)

