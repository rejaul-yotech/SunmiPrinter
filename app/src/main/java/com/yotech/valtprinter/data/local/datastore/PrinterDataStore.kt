package com.yotech.valtprinter.data.local.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "printer_settings")

/**
 * Manages the "Virtual Roll" height tracking and persistent printer states.
 */
@Singleton
class PrinterDataStore @Inject constructor(@ApplicationContext private val context: Context) {

    private object PreferencesKeys {
        val ACCUMULATED_PIXEL_HEIGHT = longPreferencesKey("accumulated_pixel_height")
        val MAX_ROLL_HEIGHT_PIXELS = longPreferencesKey("max_roll_height_pixels")
        val IS_PRINTER_PAUSED = booleanPreferencesKey("is_printer_paused")
        val LOG_TTL_DAYS = intPreferencesKey("log_ttl_days")
        val PREFERRED_PRINTER_ID = stringPreferencesKey("preferred_printer_id")
    }

    val accumulatedHeightFlow: Flow<Long> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.ACCUMULATED_PIXEL_HEIGHT] ?: 0L
    }

    val isPrinterPausedFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.IS_PRINTER_PAUSED] ?: false
    }

    val logTtlDaysFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.LOG_TTL_DAYS] ?: 30 // Default 30 days
    }

    val preferredPrinterIdFlow: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.PREFERRED_PRINTER_ID]
    }

    suspend fun isPrinterPaused(): Boolean {
        return context.dataStore.data.map { it[PreferencesKeys.IS_PRINTER_PAUSED] ?: false }.first()
    }

    suspend fun getLogTtlDays(): Int {
        return context.dataStore.data.map { it[PreferencesKeys.LOG_TTL_DAYS] ?: 30 }.first()
    }

    suspend fun getPreferredPrinterId(): String? {
        return context.dataStore.data.map { it[PreferencesKeys.PREFERRED_PRINTER_ID] }.first()
    }

    suspend fun updateAccumulatedHeight(height: Int) {
        context.dataStore.edit { preferences ->
            val current = preferences[PreferencesKeys.ACCUMULATED_PIXEL_HEIGHT] ?: 0L
            preferences[PreferencesKeys.ACCUMULATED_PIXEL_HEIGHT] = current + height
        }
    }

    suspend fun resetRoll() {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.ACCUMULATED_PIXEL_HEIGHT] = 0L
        }
    }

    suspend fun setPrinterPaused(paused: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.IS_PRINTER_PAUSED] = paused
        }
    }

    suspend fun setLogTtl(days: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.LOG_TTL_DAYS] = days
        }
    }

    suspend fun setPreferredPrinterId(printerId: String?) {
        context.dataStore.edit { preferences ->
            if (printerId.isNullOrBlank()) {
                preferences.remove(PreferencesKeys.PREFERRED_PRINTER_ID)
            } else {
                preferences[PreferencesKeys.PREFERRED_PRINTER_ID] = printerId
            }
        }
    }
}
