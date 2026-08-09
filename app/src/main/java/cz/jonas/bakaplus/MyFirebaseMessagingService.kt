package cz.jonas.bakaplus

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val title = remoteMessage.notification?.title ?: remoteMessage.data["title"] ?: "BakaPlus"
        val body = remoteMessage.notification?.body ?: remoteMessage.data["body"] ?: ""

        val type = remoteMessage.data["type"]
        val targetTab = remoteMessage.data["targetTab"]
        val targetSubject = remoteMessage.data["targetSubject"]

        val channelId = when (type) {
            "homework" -> "baka_homeworks"
            "timetable" -> "baka_timetable"
            else -> "baka_grades"
        }

        sendNotification(title, body, channelId, targetTab, targetSubject)
    }

    private fun sendNotification(
        title: String,
        messageBody: String,
        channelId: String,
        targetTab: String?,
        targetSubject: String?
    ) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (targetTab != null) putExtra("targetTab", targetTab)
            if (targetSubject != null) putExtra("targetSubject", targetSubject)
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