package com.pokedealscanner.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** Saves a finished scan and posts the "scan complete" notification. Shared by both scan methods. */
object ScanResults {
    const val PREFS_NAME = "poke_deal_scan"
    const val PREFS_TEXT = "last_scan_text"
    const val PREFS_TIME = "last_scan_time"
    const val PREFS_READER_LOG = "last_reader_log"
    const val PREFS_JSON = "last_scan_json"

    /** Why full-page reading did or didn't work on the last tap. Shown under Last Scan. */
    fun logReader(context: Context, message: String) {
        val stamp = java.text.DateFormat.getTimeInstance().format(java.util.Date())
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(PREFS_READER_LOG, "$stamp: $message")
            .apply()
    }

    private const val RESULT_CHANNEL = "poke_deal_results"
    private const val RESULT_NOTIFICATION_ID = 2002

    fun publish(context: Context, text: String, headline: String? = null, json: String? = null) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(PREFS_TEXT, text)
            .putString(PREFS_JSON, json)
            .putLong(PREFS_TIME, System.currentTimeMillis())
            .apply()

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(RESULT_CHANNEL, "Poke Deal scan results", NotificationManager.IMPORTANCE_DEFAULT)
        )

        val showResult = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_SHOW_RESULT
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 2202, showResult,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val preview = (headline ?: text.replace('\n', ' ')).take(110)
        val notification = Notification.Builder(context, RESULT_CHANNEL)
            .setContentTitle("Poke Deal scan complete")
            .setContentText(preview)
            .setStyle(Notification.BigTextStyle().bigText(text.take(2500)))
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        manager.notify(RESULT_NOTIFICATION_ID, notification)
    }
}
