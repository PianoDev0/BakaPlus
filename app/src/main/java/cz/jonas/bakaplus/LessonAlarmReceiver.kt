package cz.jonas.bakaplus

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import java.util.Calendar

class LessonAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            LessonAlarmScheduler.scheduleFromPrefs(context)
            return
        }

        if (action == LessonAlarmScheduler.ACTION_LESSON_ALARM) {
            val prefs = context.getSharedPreferences("BakaPlusPrefs", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("notifNextLesson", false)) return

            if (prefs.getBoolean("notifQuietHours", false)) {
                val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                if (currentHour >= 22 || currentHour < 6) return
            }

            val title = intent.getStringExtra("title") ?: "Další vyučovací hodina"
            val body = intent.getStringExtra("body") ?: ""
            val notificationId = intent.getIntExtra("notificationId", System.currentTimeMillis().toInt())

            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("targetTab", "dashboard")
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                notificationId,
                launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val notification = NotificationCompat.Builder(context, "baka_timetable")
                .setSmallIcon(R.drawable.ic_stat_name)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(notificationId, notification)
        }
    }
}
