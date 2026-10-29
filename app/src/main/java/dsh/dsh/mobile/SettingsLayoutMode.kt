package dsh.dsh.mobile

import android.content.Context

/**
 * 设置页布局样式（v1.4.23）。
 *
 * 宝宝反馈「设置里很乱」——22 个条目平铺在一条长列表里，滚动到后半段容易迷路。
 * 提供三种排布，用户自己挑顺手的：
 *
 *  · [LIST]   —— 现状：分区标题 + 单列分组卡片（22 项一列到底）
 *  · [CARD]   —— 每个分区一张独立大卡片，分区之间留更大间距，卡内每项两行
 *                （标题 + 说明），视觉分块更强
 *  · [GRID]   —— 每个分区一个 2 列图标网格，只显示标题与图标；点格子进二级页/
 *                直接执行开关类操作。一屏能看完全部 22 项，适合熟手
 *
 * 三种都复用同一份条目定义（见 SettingsActivity 的 row builders），不复制逻辑。
 */
enum class SettingsLayoutMode(val id: String, val labelRes: Int, val cols: Int) {
    LIST("list", R.string.settings_layout_list, 1),
    CARD("card", R.string.settings_layout_card, 1),
    GRID("grid", R.string.settings_layout_grid, 2);

    companion object {
        private const val PREFS = "dsh_ui_prefs"
        private const val KEY = "settings_layout"

        fun fromId(id: String?): SettingsLayoutMode = entries.firstOrNull { it.id == id } ?: LIST

        fun current(ctx: Context): SettingsLayoutMode = fromId(
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        )

        fun set(ctx: Context, mode: SettingsLayoutMode) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, mode.id).apply()
        }
    }
}
