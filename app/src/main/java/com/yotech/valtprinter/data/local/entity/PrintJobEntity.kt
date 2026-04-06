package com.yotech.valtprinter.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents the state of a single print request in the persistent queue.
 * Designed for the "Gold Standard" resilience (Power cuts, Paper-out).
 */
@Entity(tableName = "print_queue")
data class PrintJobEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "job_id_external")
    val externalJobId: String, // From the Link App for idempotency

    @ColumnInfo(name = "payload_json")
    val payloadJson: String, // Raw JSON payload to be rendered

    @ColumnInfo(name = "is_priority")
    val isPriority: Boolean = false,

    @ColumnInfo(name = "status")
    val status: PrintStatus = PrintStatus.PENDING,

    @ColumnInfo(name = "current_chunk_index")
    val currentChunkIndex: Int = 0,

    @ColumnInfo(name = "total_chunks")
    val totalChunks: Int = 0,

    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,

    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int = 0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)

enum class PrintStatus {
    PENDING,    // Waiting to be picked up
    PROCESSING, // Currently being rendered or buffered to printer
    COMPLETED,  // Physical print and cut confirmed
    FAILED,     // Irrecoverable error
    INTERRUPTED // Stopped mid-print (e.g. Paper-out or Power loss)
}
