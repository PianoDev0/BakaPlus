package cz.jonas.bakaplus

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.util.Calendar

class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val prefs = getSharedPreferences("BakaPlusPrefs", Context.MODE_PRIVATE)
        val type = remoteMessage.data["type"]

        if (type == "grade" && !prefs.getBoolean("notifMarks", true)) return
        if (type == "homework" && !prefs.getBoolean("notifTasks", true)) return
        if (type == "message" && !prefs.getBoolean("notifMessages", true)) return

        if (prefs.getBoolean("notifQuietHours", false)) {
            val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            if (currentHour >= 22 || currentHour < 6) return
        }

        if (type == "grade") {
            val threshold = prefs.getFloat("notifWeightThreshold", 1f)
            val weight = remoteMessage.data["weight"]?.toFloatOrNull() ?: 1f
            if (weight < threshold) return
        }

        val title = remoteMessage.notification?.title ?: remoteMessage.data["title"] ?: "BakaPlus"
        val body = remoteMessage.notification?.body ?: remoteMessage.data["body"] ?: ""
        val targetTab = remoteMessage.data["targetTab"]
        val targetSubject = remoteMessage.data["targetSubject"]
        val accountId = remoteMessage.data["accountId"]

        val channelId = when (type) {
            "homework" -> "baka_homeworks"
            "timetable" -> "baka_timetable"
            "message" -> "baka_messages"
            else -> "baka_grades"
        }

        sendNotification(title, body, channelId, targetTab, targetSubject, accountId)
    }

    private fun sendNotification(
        title: String,
        messageBody: String,
        channelId: String,
        targetTab: String?,
        targetSubject: String?,
        accountId: String?
    ) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (targetTab != null) putExtra("targetTab", targetTab)
            if (targetSubject != null) putExtra("targetSubject", targetSubject)
            if (accountId != null) putExtra("accountId", accountId)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(title)
            .setContentText(messageBody)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        notificationManager.notify(System.currentTimeMillis().toInt(), notificationBuilder.build())
    }
}