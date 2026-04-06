package com.yotech.valtprinter.data.local.dao

import androidx.room.*
import com.yotech.valtprinter.data.local.entity.PrintJobEntity
import com.yotech.valtprinter.data.local.entity.PrintStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface PrintDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPrintJob(job: PrintJobEntity): Long

    @Update
    suspend fun updatePrintJob(job: PrintJobEntity)

    /**
     * The Heart of the Zero-Loss Queue:
     * Atomic query to fetch the next candidate for printing.
     * Criteria:
     * 1. Priority jobs float to top.
     * 2. Interrupted jobs (from power cut) are resumed next.
     * 3. Within same priority, order by creation time.
     */
    @Query("""
        SELECT * FROM print_queue 
        WHERE status = 'PENDING' OR status = 'INTERRUPTED' 
        ORDER BY is_priority DESC, created_at ASC 
        LIMIT 1
    """)
    suspend fun getNextJob(): PrintJobEntity?

    @Query("SELECT * FROM print_queue WHERE id = :id")
    suspend fun getJobById(id: Long): PrintJobEntity?

    @Query("SELECT * FROM print_queue WHERE job_id_external = :externalId LIMIT 1")
    suspend fun getJobByExternalId(externalId: String): PrintJobEntity?

    @Query("UPDATE print_queue SET status = :status, updated_at = :timestamp WHERE id = :id")
    suspend fun updateStatus(id: Long, status: PrintStatus, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE print_queue SET current_chunk_index = :chunkIndex, updated_at = :timestamp WHERE id = :id")
    suspend fun updateChunkProgress(id: Long, chunkIndex: Int, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM print_queue WHERE status = 'PENDING' OR status = 'PROCESSING' OR status = 'INTERRUPTED'")
    fun getQueueCountFlow(): Flow<Int>

    @Query("DELETE FROM print_queue WHERE status = 'COMPLETED' AND updated_at < :timestamp")
    suspend fun deleteOldLogs(timestamp: Long)

    @Query("SELECT * FROM print_queue ORDER BY created_at DESC LIMIT 50")
    fun getRecentJobsFlow(): Flow<List<PrintJobEntity>>
}
