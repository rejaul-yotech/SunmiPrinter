package com.yotech.valtprinter.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yotech.valtprinter.data.local.entity.PairedDeviceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PairedDeviceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(device: PairedDeviceEntity)

    @Query("SELECT * FROM paired_devices ORDER BY last_seen_at DESC")
    fun getAllFlow(): Flow<List<PairedDeviceEntity>>

    @Query("SELECT * FROM paired_devices WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): PairedDeviceEntity?

    @Query("DELETE FROM paired_devices WHERE id = :id")
    suspend fun deleteById(id: String)
}

