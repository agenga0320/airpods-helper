package io.github.agenga0320.podhelper.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import io.github.agenga0320.podhelper.MainActivity
import io.github.agenga0320.podhelper.PodRepository
import io.github.agenga0320.podhelper.PodUiState
import io.github.agenga0320.podhelper.R

/** 桌面 Widget（3×1）。資料由 PodService 推過來，不自己定時更新。 */
class BatteryWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        manager.updateAppWidget(ids, views(context, PodRepository.state.value))
    }

    companion object {
        fun updateAll(context: Context, state: PodUiState) {
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(ComponentName(context, BatteryWidget::class.java))
                .takeIf { it.isNotEmpty() }?.let { manager.updateAppWidget(it, views(context, state)) }
            manager.getAppWidgetIds(ComponentName(context, SmallBatteryWidget::class.java))
                .takeIf { it.isNotEmpty() }?.let { manager.updateAppWidget(it, SmallBatteryWidget.views(context, state)) }
        }

        fun fmt(v: Int?, charging: Boolean) = (v?.let { "$it%" } ?: "--") + if (charging) "⚡" else ""

        fun openApp(context: Context): PendingIntent =
            PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

        private fun views(context: Context, s: PodUiState) = RemoteViews(context.packageName, R.layout.widget).apply {
            val p = s.pods
            setTextViewText(R.id.title, p?.modelName ?: "AirPods")
            setTextViewText(R.id.left, fmt(p?.leftBattery, p?.leftCharging == true))
            setTextViewText(R.id.right, fmt(p?.rightBattery, p?.rightCharging == true))
            setTextViewText(R.id.case_battery, fmt(s.caseBattery, p?.caseCharging == true))
            setOnClickPendingIntent(R.id.root, openApp(context))
        }
    }
}

/** 1×1 小圖示：上面是兩耳中較低的電量，下面是充電盒。 */
class SmallBatteryWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        manager.updateAppWidget(ids, views(context, PodRepository.state.value))
    }

    companion object {
        fun views(context: Context, s: PodUiState) = RemoteViews(context.packageName, R.layout.widget_small).apply {
            val p = s.pods
            // 兩耳取較低的那顆，和 iPhone 小工具一樣；只剩一顆有回報時就顯示那一顆
            val pods = listOfNotNull(p?.leftBattery, p?.rightBattery).minOrNull()
            val podsCharging = p?.leftCharging == true || p?.rightCharging == true
            setTextViewText(R.id.pods, BatteryWidget.fmt(pods, podsCharging))
            setTextViewText(R.id.case_battery, BatteryWidget.fmt(s.caseBattery, p?.caseCharging == true))
            setOnClickPendingIntent(R.id.root, BatteryWidget.openApp(context))
        }
    }
}
