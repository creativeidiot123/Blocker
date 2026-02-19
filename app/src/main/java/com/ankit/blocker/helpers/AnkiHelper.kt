package com.ankit.blocker.helpers

import android.content.Context
import android.content.pm.PackageManager
import com.ankit.blocker.utils.Logger
import com.ichi2.anki.FlashCardsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONException

object AnkiHelper {
    private const val TAG = "AnkiHelper"
    const val ANKI_PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"

    // Supported AnkiDroid package names: stable, then alpha build
    private val ANKI_PACKAGES = listOf("com.ichi2.anki", "com.ichi2.anki.A")

    /**
     * Returns the package name of the installed AnkiDroid variant, or null if not found.
     * Checks stable release first, then alpha build.
     */
    fun getInstalledAnkiPackage(context: Context): String? {
        for (pkg in ANKI_PACKAGES) {
            try {
                context.packageManager.getPackageInfo(pkg, 0)
                Logger.d(TAG, "getInstalledAnkiPackage: Found AnkiDroid package=$pkg")
                return pkg
            } catch (_: PackageManager.NameNotFoundException) {
                // Not this variant – try next
            }
        }
        Logger.w(TAG, "getInstalledAnkiPackage: No AnkiDroid variant found")
        return null
    }

    fun isAnkiDroidInstalled(context: Context): Boolean = getInstalledAnkiPackage(context) != null

    fun hasAnkiPermission(context: Context): Boolean {
        return context.checkSelfPermission(ANKI_PERMISSION) == PackageManager.PERMISSION_GRANTED
    }

    suspend fun getDueCardsCount(context: Context): Int {
        return withContext(Dispatchers.IO) {
            try {
                withTimeout(5000L) {
                    val uri = FlashCardsContract.Deck.CONTENT_ALL_URI
                    val projection = arrayOf(FlashCardsContract.Deck.DECK_COUNTS)

                    val cursor = context.contentResolver.query(
                        uri,
                        projection,
                        null,
                        null,
                        null
                    )

                    if (cursor == null) {
                        Logger.e(TAG, "getDueCardsCount: contentResolver.query() returned NULL cursor")
                        return@withTimeout -1
                    }

                    cursor.use {
                        var totalDue = 0
                        val countsColumn = it.getColumnIndex(FlashCardsContract.Deck.DECK_COUNTS)

                        if (countsColumn == -1) {
                            Logger.e(TAG, "getDueCardsCount: DECK_COUNTS column not found")
                            return@withTimeout -1
                        }

                        while (it.moveToNext()) {
                            val countsJson = it.getString(countsColumn)
                            if (countsJson != null) {
                                try {
                                    // DECK_COUNTS is [learn, review, new]
                                    val counts = JSONArray(countsJson)
                                    val learn = counts.getInt(0)
                                    val review = counts.getInt(1)
                                    val new = counts.getInt(2)
                                    totalDue += (learn + review + new)
                                } catch (e: JSONException) {
                                    Logger.e(TAG, "getDueCardsCount: Error parsing deck counts JSON", e)
                                }
                            }
                        }
                        Logger.d(TAG, "getDueCardsCount: Total calculated due = $totalDue")
                        totalDue
                    }
                }
            } catch (e: TimeoutCancellationException) {
                // FIXED: log message now matches the actual timeout value
                Logger.e(TAG, "getDueCardsCount: Timed out after 5000ms")
                -1
            } catch (e: Exception) {
                Logger.e(TAG, "getDueCardsCount: Exception caught", e)
                -1
            }
        }
    }
}
