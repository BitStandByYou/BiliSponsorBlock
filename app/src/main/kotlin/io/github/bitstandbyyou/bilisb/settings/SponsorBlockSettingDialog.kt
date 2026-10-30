package io.github.bitstandbyyou.bilisb.settings

import android.app.Activity
import android.app.AlertDialog

/**
 * 宿主内设置弹窗。
 *
 * 单页结构:弹窗内容就是完整设置页([SettingsScreenBuilder.buildSettingsPage]),
 * 不再有「控制中心 → SponsorBlock 详情」两级导航。
 *
 * 本类只负责弹窗生命周期：[SettingsWriter] 的创建/释放、连点去重、stale Activity 识别。
 */
object SponsorBlockSettingDialog {

    /**
     * 弹窗 R 角:窗口背景置透明,内容根容器(12dp 圆角)即弹窗轮廓。
     * AlertDialog 默认窗口底是主题的方角白底,会把圆角内容衬成方角。
     */
    private fun applyRoundedWindow(dialog: AlertDialog) {
        runCatching {
            dialog.window?.setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT),
            )
        }
    }

    @Volatile
    private var writer: SettingsWriter? = null

    /** [currentDialog] 挂靠的 Activity:宿主 Activity 被直接销毁(无 dismiss 回调)时用来识别 stale 弹窗。 */
    @Volatile
    private var currentActivity: Activity? = null

    /** 当前正在显示的弹窗:防止连点导致弹窗层层叠加。 */
    @Volatile
    private var currentDialog: AlertDialog? = null

    fun show(activity: Activity, onDismiss: (() -> Unit)? = null) {
        // Activity 已结束/正在结束时不能再 show()，否则会 WindowManager$BadTokenException
        if (activity.isFinishing || activity.isDestroyed) return
        // 上一个弹窗的挂靠 Activity 已销毁(没有 dismiss 回调):清掉静态引用并释放 writer,
        // 否则这里会永远命中「已在显示」而拒绝打开,还泄漏死 Activity。
        // writer 一并重建:复用 stale writer 会跳过 hydrate,期间模块端改过的设置会以旧值展示。
        if (currentActivity?.let { it.isFinishing || it.isDestroyed } == true) {
            currentDialog = null
            currentActivity = null
            writer?.close()
            writer = null
        }
        // 已经有一个弹窗在显示时不叠加（宿主「我的」页入口可能被连点）
        if (currentDialog?.isShowing == true) return
        val activeWriter = writer ?: SettingsWriter(activity).also { writer = it }

        val root = SettingsScreenBuilder.buildSettingsPage(activity, activeWriter.sharedPreferences)
        val dialog = AlertDialog.Builder(activity)
            .setView(SettingsScreenBuilder.wrapScroll(activity, root))
            .create()
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnDismissListener {
            if (dialog === currentDialog) {
                currentDialog = null
                currentActivity = null
            }
            // 释放 writer，避免静态引用长期持有旧 Activity 的 Context
            writer?.close()
            writer = null
            onDismiss?.invoke()
        }
        currentDialog = dialog
        currentActivity = activity
        applyRoundedWindow(dialog)
        dialog.show()
    }
}
