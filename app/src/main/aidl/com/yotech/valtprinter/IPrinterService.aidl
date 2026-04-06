package com.yotech.valtprinter;

/**
 * The core IPC interface for the ValtPrinter "Gold Standard" Server.
 * Allows external Link Apps to submit print jobs and query status.
 */
interface IPrinterService {
    /**
     * Submit a new print job to the persistent Room queue.
     * @param jobId A unique identifier from the Link App for idempotency.
     * @param payloadJson The raw JSON data to be rendered (e.g. BillingData).
     * @param isPriority If true, the job floats to the top of the queue.
     * @return A transaction token if successfully persisted, or null on failure.
     */
    String submitPrintJob(String jobId, String payloadJson, boolean isPriority);

    /**
     * Get the number of jobs currently in the persistent queue (Pending/Interrupted).
     */
    int getQueueCount();

    /**
     * Clear all completed print logs from the database.
     */
    void clearCompletedLogs();
}
