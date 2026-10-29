package com.ybdsj.deeppocket.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

/**
 * 三种尺寸小组件的公共实现。
 *
 * 广播回调里不按收到的 appWidgetIds 逐个刷，而是交给 [WidgetUpdater.refreshAll] 统一重刷：
 * 余额和计费时段是全局状态，任何一次刷新都应让三个尺寸保持一致，
 * 否则用户桌面上不同尺寸的小组件会显示不同步的数字。
 */
abstract class BalanceWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        WidgetUpdater.refreshAll(context)
    }

    override fun onEnabled(context: Context) {
        // 第一次添加该类小组件时立刻渲染一次，避免出现空白占位。
        WidgetUpdater.refreshAll(context)
    }

    // onDeleted 不做特殊处理：实例删除后 getAppWidgetIds 不再返回它，刷新自然跳过。
}
