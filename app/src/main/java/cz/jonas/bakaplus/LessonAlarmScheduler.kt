package cz.jonas.bakaplus

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object LessonAlarmScheduler {
    const val ACTION_LESSON_ALARM = "cz.jonas.bakaplus.ACTION_LESSON_ALARM"

    fun scheduleFromPrefs(context: Context) {
        val widgetPrefs = context.getSharedPreferences(TimetableWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE)
        val timetableData = widgetPrefs.getString(TimetableWidgetProvider.KEY_TIMETABLE_DATA, null)
        scheduleAlarms(context, timetableData)
    }

    fun scheduleAlarms(context: Context, timetableJsonStr: String?) {
        val prefs = context.getSharedPreferences("BakaPlusPrefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("notifNextLesson", false)
        if (!enabled || timetableJsonStr.isNullOrEmpty()) {
            cancelAllAlarms(context)
            return
        }

        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val root = JSONObject(timetableJsonStr)
            val days = root.optJSONArray("days") ?: return

            val now = System.currentTimeMillis()
            val cal = Calendar.getInstance()
            val dateParser = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

            for (d in 0 until days.length()) {
                val dayObj = days.optJSONObject(d) ?: continue
                val dateStr = dayObj.optString("date")
                if (dateStr.isEmpty()) continue

                val hours = dayObj.optJSONArray("hours") ?: continue
                for (h in 0 until hours.length()) {
                    val hourObj = hours.optJSONObject(h) ?: continue
                    if (hourObj.optBoolean("isCanceled", false)) continue

                    val beginTime = hourObj.optString("beginTime")
                    val endTime = hourObj.optString("endTime")
                    val subject = hourObj.optString("subject")
                    if (beginTime.isEmpty() || subject.isEmpty()) continue

                    val timeParts = beginTime.split(":")
                    if (timeParts.size != 2) continue
                    val bh = timeParts[0].toIntOrNull() ?: continue
                    val bm = timeParts[1].toIntOrNull() ?: continue

                    val parsedDate = dateParser.parse(dateStr) ?: continue
                    cal.time = parsedDate
                    cal.set(Calendar.HOUR_OF_DAY, bh)
                    cal.set(Calendar.MINUTE, bm)
                    cal.set(Calendar.SECOND, 0)
                    cal.set(Calendar.MILLISECOND, 0)

                    val lessonStartMillis = cal.timeInMillis
                    val triggerMillis = lessonStartMillis - (10 * 60 * 1000)

                    if (triggerMillis > now && triggerMillis - now < 7 * 24 * 60 * 60 * 1000) {
                        val room = hourObj.optString("room")
                        val teacher = hourObj.optString("teacher")
                        val changeDesc = hourObj.optString("changeDesc")

                        val roomInfo = if (room.isNotEmpty()) " (učebna $room)" else ""
                        val teacherInfo = if (teacher.isNotEmpty()) " • $teacher" else ""
                        val changeInfo = if (changeDesc.isNotEmpty()) " • $changeDesc" else ""
                        val timeRange = "$beginTime – $endTime"

                        val title = "Další hodina: $subject$roomInfo"
                        val body = "$timeRange$teacherInfo$changeInfo"

                        val requestCode = (dateStr + "_" + beginTime + "_" + subject).hashCode()

                        val intent = Intent(context, LessonAlarmReceiver::class.java).apply {
                            action = ACTION_LESSON_ALARM
                            putExtra("title", title)
                            putExtra("body", body)
                            putExtra("notificationId", requestCode)
                        }

                        val pendingIntent = PendingIntent.getBroadcast(
                            context,
                            requestCode,
                            intent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
                            } else {
                                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
                            }
                        } else {
                            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun cancelAllAlarms(context: Context) {
        try {
            val widgetPrefs = context.getSharedPreferences(TimetableWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE)
            val timetableData = widgetPrefs.getString(TimetableWidgetProvider.KEY_TIMETABLE_DATA, null) ?: return
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val root = JSONObject(timetableData)
            val days = root.optJSONArray("days") ?: return

            for (d in 0 until days.length()) {
                val dayObj = days.optJSONObject(d) ?: continue
                val dateStr = dayObj.optString("date")
                val hours = dayObj.optJSONArray("hours") ?: continue
                for (h in 0 until hours.length()) {
                    val hourObj = hours.optJSONObject(h) ?: continue
                    val beginTime = hourObj.optString("beginTime")
                    val subject = hourObj.optString("subject")
                    val requestCode = (dateStr + "_" + beginTime + "_" + subject).hashCode()

                    val intent = Intent(context, LessonAlarmReceiver::class.java).apply {
                        action = ACTION_LESSON_ALARM
                    }
                    val pendingIntent = PendingIntent.getBroadcast(
                        context,
                        requestCode,
                        intent,
                        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                    )
                    if (pendingIntent != null) {
                        alarmManager.cancel(pendingIntent)
                        pendingIntent.cancel()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
