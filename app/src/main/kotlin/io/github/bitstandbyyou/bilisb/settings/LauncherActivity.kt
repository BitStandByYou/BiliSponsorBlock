package io.github.bitstandbyyou.bilisb.settings

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.ViewGroup

/**
 * 模块入口设置页。
 *
 * 和宿主内弹窗共用 [SettingsScreenBuilder] 的单页布局，避免双实现漂移。
 */
class LauncherActivity : Activity() {
    private lateinit var settingsWriter: SettingsWriter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsWriter = SettingsWriter(this)
        val content = SettingsScreenBuilder.buildSettingsPage(this, settingsWriter.sharedPreferences)
        setContentView(SettingsScreenBuilder.wrapScroll(this, content))
    }

    override fun onPause() {
        super.onPause()
        // 返回键/切后台不会触发 EditText 失焦(历史上数值输入行只在失焦时落盘):
        // 这里在离开前主动清掉焦点,保持与旧版本一致的落盘时机。
        clearFocusRecursively(window?.decorView)
    }

    override fun onDestroy() {
        // 每个 Activity 实例一个 writer;不 close 的话旋转/深色切换每次泄漏
        // 一条守护 IO 线程 + 一个 prefs 监听器,还会让每次改动重复做镜像写与 provider 推送。
        if (::settingsWriter.isInitialized) {
            settingsWriter.close()
        }
        super.onDestroy()
    }

    private fun clearFocusRecursively(view: View?) {
        view ?: return
        if (view.hasFocus()) {
            view.clearFocus()
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                clearFocusRecursively(view.getChildAt(i))
            }
        }
    }
}
