package com.ankit.ankiblocker

import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.ichi2.anki.FlashCardsContract
import org.json.JSONArray
import org.json.JSONException

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AnkiBlocker"
        private const val ANKI_PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
        private const val PERMISSION_REQUEST_CODE = 1001
    }

    private lateinit var dueText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate: AnkiBlocker starting up")
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        dueText = findViewById(R.id.dueText)

        // Check if AnkiDroid is installed
        val ankiInstalled = isAnkiDroidInstalled()
        Log.d(TAG, "onCreate: AnkiDroid installed = $ankiInstalled")
        if (!ankiInstalled) {
            Log.w(TAG, "onCreate: AnkiDroid not found on device, aborting")
            dueText.text = "AnkiDroid not installed"
            return
        }

        // Check permission and query
        val hasPermission = checkSelfPermission(ANKI_PERMISSION) == PackageManager.PERMISSION_GRANTED
        Log.d(TAG, "onCreate: Permission '$ANKI_PERMISSION' granted = $hasPermission")
        if (hasPermission) {
            Log.d(TAG, "onCreate: Permission already granted, querying due count")
            showDueCount()
        } else {
            Log.d(TAG, "onCreate: Requesting permission '$ANKI_PERMISSION'")
            requestPermissions(arrayOf(ANKI_PERMISSION), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Log.d(TAG, "onRequestPermissionsResult: requestCode=$requestCode, permissions=${permissions.joinToString()}, grantResults=${grantResults.joinToString()}")
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "onRequestPermissionsResult: Permission GRANTED, querying due count")
                showDueCount()
            } else {
                Log.w(TAG, "onRequestPermissionsResult: Permission DENIED by user")
                dueText.text = "Permission denied"
            }
        }
    }

    private fun showDueCount() {
        Log.d(TAG, "showDueCount: Fetching due card count...")
        val count = getDueCardsCount()
        Log.d(TAG, "showDueCount: getDueCardsCount returned $count")
        dueText.text = if (count >= 0) {
            Log.d(TAG, "showDueCount: Success — displaying $count due cards")
            "Due cards: $count"
        } else {
            Log.e(TAG, "showDueCount: ERROR — getDueCardsCount returned $count, showing error to user")
            "Error reading AnkiDroid"
        }
    }

    private fun getDueCardsCount(): Int {
        val uri = FlashCardsContract.Deck.CONTENT_ALL_URI
        Log.d(TAG, "getDueCardsCount: Querying URI = $uri")
        // We need DECK_COUNTS to calculate due cards
        val projection = arrayOf(FlashCardsContract.Deck.DECK_COUNTS)
        
        return try {
            Log.d(TAG, "getDueCardsCount: Calling contentResolver.query()...")
            val cursor = contentResolver.query(
                uri,
                projection,
                null,
                null,
                null
            )
            if (cursor == null) {
                Log.e(TAG, "getDueCardsCount: contentResolver.query() returned NULL cursor — provider may not exist or URI is wrong")
                return -1
            }
            cursor.use {
                var totalDue = 0
                val countsColumn = it.getColumnIndex(FlashCardsContract.Deck.DECK_COUNTS)
                
                Log.d(TAG, "getDueCardsCount: Cursor returned ${it.count} rows")
                
                while (it.moveToNext()) {
                    val countsJson = it.getString(countsColumn)
                    try {
                        // DECK_COUNTS is [learn, review, new]
                        val counts = JSONArray(countsJson)
                        val learn = counts.getInt(0)
                        val review = counts.getInt(1)
                        val new = counts.getInt(2)
                        totalDue += (learn + review + new)
                    } catch (e: JSONException) {
                        Log.e(TAG, "getDueCardsCount: Error parsing deck counts JSON: $countsJson", e)
                    }
                }
                Log.d(TAG, "getDueCardsCount: Total calculated due = $totalDue")
                totalDue
            }
        } catch (e: Exception) {
            Log.e(TAG, "getDueCardsCount: EXCEPTION caught!")
            Log.e(TAG, "getDueCardsCount: Exception class = ${e.javaClass.name}")
            Log.e(TAG, "getDueCardsCount: Exception message = ${e.message}")
            Log.e(TAG, "getDueCardsCount: Full stack trace:", e)
            -1
        }
    }

    private fun isAnkiDroidInstalled(): Boolean {
        return try {
            val pkgInfo = packageManager.getPackageInfo("com.ichi2.anki", 0)
            Log.d(TAG, "isAnkiDroidInstalled: Found AnkiDroid — package=${pkgInfo.packageName}, versionName=${pkgInfo.versionName}, versionCode=${pkgInfo.longVersionCode}")
            true
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "isAnkiDroidInstalled: AnkiDroid package 'com.ichi2.anki' NOT found", e)
            false
        }
    }
}