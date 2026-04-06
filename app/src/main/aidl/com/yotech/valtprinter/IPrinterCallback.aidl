package com.yotech.valtprinter;

/**
 * Elite Double-Approval Callback Interface.
 * Link Apps must implement this in their AIDL interface to receive
 * real-time deterministic updates on print jobs bypassing standard Broadcast reliability issues.
 */
interface IPrinterCallback {
    /**
     * Called when the print job is fully and successfully printed on physical paper.
     */
    void onJobSuccess(String externalJobId);

    /**
     * Called when a print job has critically failed.
     */
    void onJobFailed(String externalJobId, String reason);
}
