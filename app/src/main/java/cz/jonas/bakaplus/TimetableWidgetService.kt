package cz.jonas.bakaplus

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import org.json.JSONObject
import java.util.Calendar

class TimetableWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return TimetableRemoteViewsFactory(applicationContext)
    }
}

class TimetableRemoteViewsFactory(private val context: Context) :
    RemoteViewsService.RemoteViewsFactory {

    private data class Lesson(
        val hour: String,
        val beginTime: String,
        val endTime: String,
        val subject: String,
        val subjectName: String,
        val room: String,
        val teacher: String,
        val isCanceled: Boolean,
        val isChanged: Boolean,
        val changeDesc: String,
        val isCurrent: Boolean,
        val isRecorded: Boolean,
        val theme: String
    )

    private val lessons = ArrayList<Lesson>()

    override fun onCreate() {
        loadData()
    }

    override fun onDataSetChanged() {
        loadData()
    }

    private fun loadData() {
        lessons.clear()
        val prefs = context.getSharedPreferences(
            TimetableWidgetProvider.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        val rawData = prefs.getString(TimetableWidgetProvider.KEY_TIMETABLE_DATA, null) ?: return
        val totalDays = TimetableWidgetProvider.getTotalDaysCount(prefs)
        val dayIndex = prefs.getInt(
            TimetableWidgetProvider.KEY_DAY_INDEX,
            TimetableWidgetProvider.getDefaultDayIndex(prefs)
        ).coerceIn(0, (totalDays - 1).coerceAtLeast(0))

        try {
            val root = JSONObject(rawData)
            val days = root.optJSONArray("days") ?: return
            if (dayIndex >= days.length()) return

            val dayObj = days.getJSONObject(dayIndex)
            val isHoliday = dayObj.optBoolean("isHoliday", false)
            if (isHoliday) return

            val hoursArray = dayObj.optJSONArray("hours") ?: return

            val cal = Calendar.getInstance()
            val todayStr = String.format(
                java.util.Locale.US, "%04d-%02d-%02d",
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH)
            )
            val dayDateStr = dayObj.optString("date", "")
            val isToday = if (dayDateStr.isNotEmpty()) dayDateStr.startsWith(todayStr) else (dayIndex == 0)
            val nowMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

            for (i in 0 until hoursArray.length()) {
                val h = hoursArray.getJSONObject(i)
                val isCanceled = h.optBoolean("isCanceled", false)
                val subject = h.optString("subject", "").trim()
                val isEmpty = h.optBoolean("isEmpty", false)

                if ((isEmpty || subject.isEmpty()) && !isCanceled) {
                    continue
                }

                val rawHour = h.optString("hour", "").trim()
                val begin = h.optString("beginTime", "").trim()
                val hour = getHourNumber(rawHour, begin, i + 1)
                val end = h.optString("endTime", "").trim()
                val subjectName = h.optString("subjectName", "").trim()
                val room = h.optString("room", "").trim()
                val teacher = h.optString("teacher", "").trim()
                val isChanged = h.optBoolean("isChanged", false)
                val changeDesc = h.optString("changeDesc", "").trim()
                val isRecorded = h.optBoolean("isRecorded", false)
                val theme = h.optString("theme", "").trim()

                var isCurrent = false
                if (isToday && begin.isNotEmpty() && end.isNotEmpty() && !isCanceled) {
                    val startMin = parseTimeToMinutes(begin)
                    val endMin = parseTimeToMinutes(end)
                    if (startMin != -1 && endMin != -1 && nowMinutes in startMin..endMin) {
                        isCurrent = true
                    }
                }

                lessons.add(
                    Lesson(
                        hour = hour,
                        beginTime = begin,
                        endTime = end,
                        subject = subject,
                        subjectName = subjectName,
                        room = room,
                        teacher = teacher,
                        isCanceled = isCanceled,
                        isChanged = isChanged,
                        changeDesc = changeDesc,
                        isCurrent = isCurrent,
                        isRecorded = isRecorded,
                        theme = theme
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getHourNumber(rawHour: String, beginTime: String, fallbackIdx: Int): String {
        if (beginTime.isNotEmpty()) {
            val minutes = parseTimeToMinutes(beginTime)
            if (minutes != -1) {
                return when {
                    minutes < 460 -> "0"
                    minutes <= 505 -> "1"
                    minutes <= 560 -> "2"
                    minutes <= 620 -> "3"
                    minutes <= 675 -> "4"
                    minutes <= 730 -> "5"
                    minutes <= 785 -> "6"
                    minutes <= 840 -> "7"
                    minutes <= 895 -> "8"
                    minutes <= 950 -> "9"
                    minutes <= 1005 -> "10"
                    else -> if (rawHour.isNotEmpty()) rawHour else fallbackIdx.toString()
                }
            }
        }
        return if (rawHour.isNotEmpty()) rawHour else fallbackIdx.toString()
    }

    private fun parseTimeToMinutes(timeStr: String): Int {
        return try {
            val parts = timeStr.trim().split(":")
            if (parts.size >= 2) {
                parts[0].toInt() * 60 + parts[1].toInt()
            } else -1
        } catch (e: Exception) {
            -1
        }
    }

    override fun onDestroy() {
        lessons.clear()
    }

    override fun getCount(): Int = lessons.size

    override fun getViewAt(position: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_timetable_item)
        if (position >= lessons.size) return views

        val item = lessons[position]

        val hourLabel = when {
            item.hour.isEmpty() -> ""
            item.hour.endsWith(".") -> item.hour
            else -> "${item.hour}."
        }
        views.setTextViewText(R.id.text_item_hour, hourLabel)

        val timeLabel = if (item.beginTime.isNotEmpty() && item.endTime.isNotEmpty()) {
            "${item.beginTime} - ${item.endTime}"
        } else {
            ""
        }
        views.setTextViewText(R.id.text_item_time, timeLabel)
        views.setViewVisibility(
            R.id.text_item_time,
            if (timeLabel.isNotEmpty()) View.VISIBLE else View.GONE
        )

        val displayName = when {
            item.isCanceled -> "Odpadá"
            item.subjectName.isNotEmpty() && item.subjectName != "Neznámý předmět" && item.subjectName != "?" -> item.subjectName
            item.subject.isNotEmpty() -> item.subject
            else -> "Výuka"
        }
        views.setTextViewText(R.id.text_item_subject, displayName)

        val detailText = when {
            item.changeDesc.isNotEmpty() && item.theme.isNotEmpty() -> "${item.changeDesc} • ${item.theme}"
            item.changeDesc.isNotEmpty() -> item.changeDesc
            item.teacher.isNotEmpty() && item.theme.isNotEmpty() -> "${item.teacher} • ${item.theme}"
            item.theme.isNotEmpty() -> item.theme
            item.teacher.isNotEmpty() -> item.teacher
            item.subject.isNotEmpty() && item.subject != displayName -> item.subject
            else -> ""
        }
        views.setTextViewText(R.id.text_item_detail, detailText)
        views.setViewVisibility(
            R.id.text_item_detail,
            if (detailText.isNotEmpty()) View.VISIBLE else View.GONE
        )

        if (item.room.isNotEmpty() && !item.isCanceled) {
            views.setViewVisibility(R.id.text_item_room, View.VISIBLE)
            views.setTextViewText(R.id.text_item_room, item.room)
        } else {
            views.setViewVisibility(R.id.text_item_room, View.GONE)
        }

        when {
            item.isCanceled -> {
                views.setInt(R.id.item_root, "setBackgroundResource", R.drawable.widget_item_bg_canceled)
                views.setViewVisibility(R.id.text_item_status, View.VISIBLE)
                views.setTextViewText(R.id.text_item_status, "Odpadá")
                views.setTextColor(R.id.text_item_status, Color.parseColor("#F87171"))
                views.setTextColor(R.id.text_item_subject, Color.parseColor("#9CA3AF"))
            }
            item.isCurrent -> {
                views.setInt(R.id.item_root, "setBackgroundResource", R.drawable.widget_item_bg_current)
                views.setViewVisibility(R.id.text_item_status, View.VISIBLE)
                views.setTextViewText(R.id.text_item_status, "Právě probíhá")
                views.setTextColor(R.id.text_item_status, Color.parseColor("#818CF8"))
                views.setTextColor(R.id.text_item_subject, Color.parseColor("#FFFFFF"))
            }
            item.isChanged -> {
                views.setInt(R.id.item_root, "setBackgroundResource", R.drawable.widget_item_bg_changed)
                views.setViewVisibility(R.id.text_item_status, View.VISIBLE)
                views.setTextViewText(R.id.text_item_status, if (item.isRecorded) "Změna • Zapsáno" else "Změna")
                views.setTextColor(R.id.text_item_status, Color.parseColor("#FBBF24"))
                views.setTextColor(R.id.text_item_subject, Color.parseColor("#FFFFFF"))
            }
            item.isRecorded -> {
                views.setInt(R.id.item_root, "setBackgroundResource", R.drawable.widget_item_bg_recorded)
                views.setViewVisibility(R.id.text_item_status, View.VISIBLE)
                views.setTextViewText(R.id.text_item_status, "✓ Zapsáno")
                views.setTextColor(R.id.text_item_status, Color.parseColor("#34D399"))
                views.setTextColor(R.id.text_item_subject, Color.parseColor("#FFFFFF"))
            }
            else -> {
                views.setInt(R.id.item_root, "setBackgroundResource", R.drawable.widget_item_bg)
                views.setViewVisibility(R.id.text_item_status, View.GONE)
                views.setTextColor(R.id.text_item_subject, Color.parseColor("#FFFFFF"))
            }
        }

        val fillInIntent = Intent()
        views.setOnClickFillInIntent(R.id.item_root, fillInIntent)

        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = position.toLong()

    override fun hasStableIds(): Boolean = true
}
