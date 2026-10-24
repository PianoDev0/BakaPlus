package cz.jonas.bakaplus

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import org.json.JSONObject
import java.util.Calendar

class TimetableWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
        super.onUpdate(context, appWidgetManager, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        when (intent.action) {
            ACTION_PREV_DAY -> {
                val currentIdx = prefs.getInt(KEY_DAY_INDEX, getDefaultDayIndex(prefs))
                val newIdx = (currentIdx - 1).coerceAtLeast(0)
                prefs.edit().putInt(KEY_DAY_INDEX, newIdx).apply()
                updateAllWidgets(context)
            }
            ACTION_NEXT_DAY -> {
                val totalDays = getTotalDaysCount(prefs)
                val currentIdx = prefs.getInt(KEY_DAY_INDEX, getDefaultDayIndex(prefs))
                val newIdx = (currentIdx + 1).coerceAtMost((totalDays - 1).coerceAtLeast(0))
                prefs.edit().putInt(KEY_DAY_INDEX, newIdx).apply()
                updateAllWidgets(context)
            }
            ACTION_TODAY -> {
                prefs.edit().putInt(KEY_DAY_INDEX, getDefaultDayIndex(prefs)).apply()
                updateAllWidgets(context)
            }
            AppWidgetManager.ACTION_APPWIDGET_UPDATE -> {
                updateAllWidgets(context)
            }
        }
    }

    companion object {
        const val PREFS_NAME = "BakaPlusWidgetPrefs"
        const val KEY_TIMETABLE_DATA = "timetable_data"
        const val KEY_DAY_INDEX = "selected_day_index"

        const val ACTION_PREV_DAY = "cz.jonas.bakaplus.WIDGET_PREV_DAY"
        const val ACTION_NEXT_DAY = "cz.jonas.bakaplus.WIDGET_NEXT_DAY"
        const val ACTION_TODAY = "cz.jonas.bakaplus.WIDGET_TODAY"

        fun getTotalDaysCount(prefs: android.content.SharedPreferences): Int {
            val rawData = prefs.getString(KEY_TIMETABLE_DATA, null) ?: return 5
            return try {
                val root = JSONObject(rawData)
                val days = root.optJSONArray("days")
                days?.length() ?: 5
            } catch (e: Exception) {
                5
            }
        }

        fun getDefaultDayIndex(prefs: android.content.SharedPreferences? = null): Int {
            val rawData = prefs?.getString(KEY_TIMETABLE_DATA, null)
            val cal = Calendar.getInstance()
            val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
            val hourOfDay = cal.get(Calendar.HOUR_OF_DAY)

            val targetCal = Calendar.getInstance()
            if (hourOfDay >= 16) {
                targetCal.add(Calendar.DAY_OF_YEAR, 1)
            }
            while (targetCal.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY ||
                   targetCal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) {
                targetCal.add(Calendar.DAY_OF_YEAR, 1)
            }

            val targetDateStr = String.format(
                java.util.Locale.US, "%04d-%02d-%02d",
                targetCal.get(Calendar.YEAR),
                targetCal.get(Calendar.MONTH) + 1,
                targetCal.get(Calendar.DAY_OF_MONTH)
            )

            if (rawData != null) {
                try {
                    val root = JSONObject(rawData)
                    val days = root.optJSONArray("days")
                    if (days != null) {
                        for (i in 0 until days.length()) {
                            val dayObj = days.getJSONObject(i)
                            val dStr = dayObj.optString("date", "")
                            if (dStr.isNotEmpty() && dStr.startsWith(targetDateStr)) {
                                return i
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            return when (dayOfWeek) {
                Calendar.MONDAY -> if (hourOfDay >= 16) 1 else 0
                Calendar.TUESDAY -> if (hourOfDay >= 16) 2 else 1
                Calendar.WEDNESDAY -> if (hourOfDay >= 16) 3 else 2
                Calendar.THURSDAY -> if (hourOfDay >= 16) 4 else 3
                Calendar.FRIDAY -> if (hourOfDay >= 16) 0 else 4
                Calendar.SATURDAY, Calendar.SUNDAY -> 0
                else -> 0
            }
        }

        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, TimetableWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)

            for (appWidgetId in appWidgetIds) {
                updateWidget(context, appWidgetManager, appWidgetId)
            }
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.widget_list)
        }

        private fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_timetable)
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val rawData = prefs.getString(KEY_TIMETABLE_DATA, null)
            val totalDays = getTotalDaysCount(prefs)
            val dayIndex = prefs.getInt(KEY_DAY_INDEX, getDefaultDayIndex(prefs)).coerceIn(0, (totalDays - 1).coerceAtLeast(0))

            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val launchPendingIntent = PendingIntent.getActivity(
                context, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_title_group, launchPendingIntent)
            views.setOnClickPendingIntent(R.id.btn_widget_open, launchPendingIntent)
            views.setOnClickPendingIntent(R.id.widget_empty, launchPendingIntent)

            val prevIntent = Intent(context, TimetableWidgetProvider::class.java).apply {
                action = ACTION_PREV_DAY
            }
            views.setOnClickPendingIntent(
                R.id.btn_widget_prev,
                PendingIntent.getBroadcast(
                    context, 1, prevIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

            val nextIntent = Intent(context, TimetableWidgetProvider::class.java).apply {
                action = ACTION_NEXT_DAY
            }
            views.setOnClickPendingIntent(
                R.id.btn_widget_next,
                PendingIntent.getBroadcast(
                    context, 2, nextIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

            val todayIntent = Intent(context, TimetableWidgetProvider::class.java).apply {
                action = ACTION_TODAY
            }
            views.setOnClickPendingIntent(
                R.id.btn_widget_today,
                PendingIntent.getBroadcast(
                    context, 3, todayIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

            val dayNames = arrayOf("Pondělí", "Úterý", "Středa", "Čtvrtek", "Pátek")
            var currentDayTitle = dayNames[dayIndex % 5]
            var hasLessons = false
            var emptyReason = "Žádná výuka"
            var emptySubtitle = "Dnes máš volno nebo prázdniny."

            if (rawData != null) {
                try {
                    val root = JSONObject(rawData)
                    val days = root.optJSONArray("days")
                    if (days != null && dayIndex < days.length()) {
                        val dayObj = days.getJSONObject(dayIndex)
                        val name = dayObj.optString("dayName", dayNames[dayIndex % 5])
                        val dayNum = dayObj.optString("dayNumber", "")
                        currentDayTitle = if (dayNum.isNotEmpty() && dayNum != "null") "$name $dayNum." else name

                        val isHoliday = dayObj.optBoolean("isHoliday", false)
                        val desc = dayObj.optString("description", "")
                        val hours = dayObj.optJSONArray("hours")
                        hasLessons = hours != null && hours.length() > 0 && !isHoliday

                        if (isHoliday || !hasLessons) {
                            emptyReason = if (desc.isNotEmpty()) desc else "Bez výuky"
                            emptySubtitle = "Na tento den není naplánována žádná výuka."
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
                currentDayTitle = dayNames[dayIndex % 5]
                emptyReason = "Zatím žádná data"
                emptySubtitle = "Otevři aplikaci Student+ k načtení rozvrhu."
            }

            views.setTextViewText(R.id.text_widget_day, currentDayTitle)

            val serviceIntent = Intent(context, TimetableWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.widget_list, serviceIntent)

            val itemClickIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val itemClickPendingIntent = PendingIntent.getActivity(
                context, 10, itemClickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            views.setPendingIntentTemplate(R.id.widget_list, itemClickPendingIntent)

            views.setTextViewText(R.id.text_widget_empty_title, emptyReason)
            views.setTextViewText(R.id.text_widget_empty_subtitle, emptySubtitle)
            views.setEmptyView(R.id.widget_list, R.id.widget_empty)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
