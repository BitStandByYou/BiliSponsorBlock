package io.github.bitstandbyyou.bilisb.settings

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.bitstandbyyou.bilisb.BuildConfig
import io.github.bitstandbyyou.bilisb.model.SponsorCategories
import io.github.bitstandbyyou.bilisb.sponsor.SkipStatsStore

/**
 * 设置页构建。
 *
 * 只有**单页**:模块入口 Activity 和宿主内弹窗共用本对象，避免双实现漂移。
 * 页面上只放用户能改的设置；服务器地址、缓存 TTL、最小片段时长、倒计时、标记颜色、
 * 提交相关配置都不再暴露（固定为默认值，见 [SettingsKeys] 的类注释）。
 */
object SettingsScreenBuilder {
    private const val REPO_URL = "https://github.com/BitStandByYou/BiliSponsorBlock"

    // ---- B 站视觉规范:品牌粉、白色圆角卡片、浅灰页面底 ----
    private val BILI_PINK = 0xFFFB7299.toInt()
    private val BILI_PINK_LIGHT = 0xFFFF9EB4.toInt()
    private val BILI_TEXT_PRIMARY = 0xFF18191C.toInt()
    private val BILI_TEXT_SECONDARY = 0xFF61666D.toInt()
    private val BILI_CARD_BG = 0xFFFFFFFF.toInt()
    private val BILI_PAGE_BG = 0xFFF1F2F3.toInt()
    private val BILI_DIVIDER = 0xFFE3E5E7.toInt()

    private fun biliCard(activity: Activity, color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(activity, radiusDp).toFloat()
        }

    /** 卡片内的行间分割线(卡片自带水平内边距,不再额外缩进)。 */
    private fun biliDividerInner(activity: Activity): View = View(activity).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 1),
        )
        setBackgroundColor(BILI_DIVIDER)
    }

    /** 小节:灰色小标题 + 白色圆角卡片(卡片自带水平内边距,行由 [block] 填充)。 */
    private fun biliSection(
        parent: LinearLayout,
        activity: Activity,
        title: String,
        block: LinearLayout.() -> Unit,
    ) {
        parent.addView(TextView(activity).apply {
            text = title
            textSize = 13f
            setTextColor(BILI_TEXT_SECONDARY)
            setPadding(dp(activity, 4), dp(activity, 2), 0, dp(activity, 6))
        })
        parent.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 14), dp(activity, 6), dp(activity, 14), dp(activity, 6))
            background = biliCard(activity, BILI_CARD_BG, 10)
            block()
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(activity, 10) })
    }

    /** 品牌头图(粉渐变)。 */
    private fun banner(activity: Activity): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            orientation = GradientDrawable.Orientation.TL_BR
            colors = intArrayOf(BILI_PINK, BILI_PINK_LIGHT)
            cornerRadius = dp(activity, 12).toFloat()
        }
        setPadding(dp(activity, 16), dp(activity, 14), dp(activity, 16), dp(activity, 14))
        addView(TextView(activity).apply {
            text = "哔哩哔哩空降助手"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        addView(TextView(activity).apply {
            text = "SponsorBlock · 跳过视频恰饭片段"
            textSize = 12f
            setTextColor(0xE6FFFFFF.toInt())
            setPadding(0, dp(activity, 2), 0, 0)
        })
    }

    /**
     * 构建整页设置(单页,包含总开关 / 跳过方式 / 跳过类别 / 界面显示 / 统计 / 关于)。
     */
    fun buildSettingsPage(activity: Activity, prefs: SharedPreferences): LinearLayout {
        fun card(block: LinearLayout.() -> Unit): LinearLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = biliCard(activity, BILI_CARD_BG, 10)
            setPadding(dp(activity, 14), dp(activity, 6), dp(activity, 14), dp(activity, 6))
            block()
        }

        fun cardLp(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(activity, 10) }

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = biliCard(activity, BILI_PAGE_BG, 12)
            setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 12), dp(activity, 4))

            addView(banner(activity), cardLp())

            // 总开关
            addView(card {
                addView(createCheckBox(activity, prefs, SettingsKeys.ENABLED, "启用 SponsorBlock", "关闭后模块不工作", true))
            }, cardLp())

            // 跳过方式(三选一)
            biliSection(this, activity, "跳过方式") {
                addView(createSkipModeGroup(activity, prefs))
            }

            // 跳过类别
            biliSection(this, activity, "跳过类别") {
                val categories = listOf(
                    Triple(SettingsKeys.CAT_SPONSOR, "赞助/恰饭", "付费推广、赞助商"),
                    Triple(SettingsKeys.CAT_SELFPROMO, "自我推广", "自己的商品、链接"),
                    Triple(SettingsKeys.CAT_INTERACTION, "互动提醒", "点赞、关注提示"),
                    Triple(SettingsKeys.CAT_INTRO, "开场动画", "片头动画"),
                    Triple(SettingsKeys.CAT_OUTRO, "结束画面", "片尾鸣谢"),
                    Triple(SettingsKeys.CAT_PREVIEW, "回顾/概要", "前情回顾"),
                    Triple(SettingsKeys.CAT_MUSIC_OFFTOPIC, "非音乐片段", "MV中非音乐部分"),
                    Triple(SettingsKeys.CAT_FILLER, "填充内容", "笑话、重复片段"),
                    Triple(SettingsKeys.CAT_POI_HIGHLIGHT, "精彩时刻", "视频精彩部分"),
                )
                categories.forEachIndexed { index, (key, title, summary) ->
                    if (index > 0) addView(biliDividerInner(activity))
                    addView(createCheckBox(activity, prefs, key, title, summary, true))
                }
            }

            // 界面显示
            biliSection(this, activity, "界面显示") {
                val rows = listOf(
                    createCheckBox(activity, prefs, SettingsKeys.SHOW_TOAST, "跳过提示", "跳过时显示 Toast", true),
                    createCheckBox(activity, prefs, SettingsKeys.SHOW_SEEKBAR_MARKER, "进度条标记", "标记片段位置", true),
                    createCheckBox(activity, prefs, SettingsKeys.SHOW_TIME_DEDUCTION, "时间扣减", "总时长减去跳过时长", true),
                    createCheckBox(activity, prefs, SettingsKeys.SHOW_SKIP_STATS, "跳过次数统计", "累计跳过次数与节省时长", true),
                    createCheckBox(
                        activity,
                        prefs,
                        SettingsKeys.HIDE_FULL_VIDEO_LABEL_CARDS,
                        "隐藏恰饭推广视频",
                        "在首页推荐、播放页更多视频和 UP 主投稿中隐藏带有整段推广标签的视频；在关注流/动态中移除对应视频动态",
                        true,
                    ),
                )
                rows.forEachIndexed { index, row ->
                    if (index > 0) addView(biliDividerInner(activity))
                    addView(row)
                }
            }

            // 统计
            biliSection(this, activity, "统计") {
                buildStats(activity, this)
            }

            // 关于(版本行可点击 → 跳转 GitHub 项目页;发版说明以 GitHub Releases 为单一来源)
            addView(TextView(activity).apply {
                text = "关于"
                textSize = 13f
                setTextColor(BILI_TEXT_SECONDARY)
                setPadding(dp(activity, 4), dp(activity, 2), 0, dp(activity, 6))
            })
            addView(card {
                addView(aboutItem(activity, "版本", "${BuildConfig.VERSION_NAME}(versionCode ${BuildConfig.VERSION_CODE})") {
                    openUrl(activity, REPO_URL)
                })
                addView(biliDividerInner(activity))
                addView(aboutItem(activity, "作者", "BitStandByYou"))
            }, cardLp())
        }
    }

    /**
     * 跳过方式三选一。
     *
     * 持久化仍是三个互斥布尔(见 [SkipMode]):选中即写入归一后的三元组,
     * 旧版本「自动跳过 + 片段静音」同开的配置在这里被收敛成单选。
     *
     * 每个选项用**单个 RadioButton** 承载「标题 + 说明」（说明用 span 缩小字号），
     * 因为 RadioGroup 只会给**直接子 RadioButton** 装互斥监听；
     * 把 RadioButton 包在 LinearLayout 里会导致互斥失效（多个同时可选中）。
     */
    private fun createSkipModeGroup(activity: Activity, prefs: SharedPreferences): RadioGroup {
        val group = RadioGroup(activity).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(0, dp(activity, 2), 0, dp(activity, 2))
        }
        val idToMode = linkedMapOf<Int, SkipMode>()
        val current = SkipMode.fromPreferences(prefs)
        val options = listOf(
            Triple(SkipMode.AUTO, "自动跳过", "检测到片段时自动跳过"),
            Triple(SkipMode.MANUAL, "手动跳过", "片段内显示「跳过」按钮，点按才跳"),
            Triple(SkipMode.MUTE, "片段静音", "只把静音类片段静音，不跳过任何片段"),
        )
        options.forEachIndexed { index, (mode, title, summary) ->
            if (index > 0) group.addView(biliDividerInner(activity))
            val label = SpannableStringBuilder().apply {
                append(title)
                setSpan(
                    ForegroundColorSpan(BILI_TEXT_PRIMARY), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                append("\n")
                val summaryStart = length
                append(summary)
                setSpan(
                    RelativeSizeSpan(0.8f), summaryStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                setSpan(
                    ForegroundColorSpan(BILI_TEXT_SECONDARY), summaryStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            val radio = RadioButton(activity).apply {
                id = View.generateViewId()
                text = label
                textSize = 15f
                setTextColor(BILI_TEXT_PRIMARY)
                setPadding(0, dp(activity, 8), 0, dp(activity, 8))
                runCatching {
                    buttonTintList = android.content.res.ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(BILI_PINK, 0xFFCCCCCC.toInt()),
                    )
                }
                isChecked = mode == current
            }
            idToMode[radio.id] = mode
            group.addView(radio)
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            val mode = idToMode[checkedId] ?: return@setOnCheckedChangeListener
            if (mode == SkipMode.fromPreferences(prefs)) return@setOnCheckedChangeListener
            SkipMode.writeTo(prefs, mode)
        }
        return group
    }

    private fun buildStats(activity: Activity, parent: LinearLayout) {
        // SkipStatsStore 的数据文件在宿主数据目录(HostTargets.HOST_DATA_DIRS),record 只发生在
        // 宿主进程 —— 模块 APK 进程读不到,这里会永远显示 0 且「重置」清的是空内存写不进宿主目录。
        // 模块进程下显示说明文案,不显示假数据。
        if (activity.packageName == SettingsSyncBridge.MODULE_PACKAGE) {
            parent.addView(TextView(activity).apply {
                text = "统计数据仅记录在哔哩哔哩宿主进程内。"
                textSize = 12f
                setTextColor(Color.GRAY)
                setPadding(0, 0, 0, dp(activity, 8))
            })
            return
        }
        val summary = TextView(activity).apply {
            textSize = 15f
            setTextColor(BILI_TEXT_PRIMARY)
            setPadding(0, dp(activity, 4), 0, dp(activity, 4))
        }
        val detail = TextView(activity).apply {
            textSize = 12f
            setTextColor(BILI_TEXT_SECONDARY)
            setPadding(0, 0, 0, dp(activity, 8))
        }

        fun refresh() {
            val s = SkipStatsStore.snapshot()
            summary.text = "已跳过 ${s.totalCount} 个片段 · 共节省 ${formatDuration(s.totalDurationMs)}"
            detail.text = if (s.perCategory.isEmpty()) {
                "暂无记录"
            } else {
                s.perCategory.entries.joinToString("\n") { (cat, st) ->
                    "  ${SponsorCategories.displayName(cat)}：${st.count} 个 · ${formatDuration(st.durationMs)}"
                }
            }
        }
        refresh()

        parent.addView(summary)
        parent.addView(detail)
        parent.addView(Button(activity).apply {
            text = "重置统计"
            setOnClickListener {
                SkipStatsStore.reset()
                refresh()
                Toast.makeText(activity, "统计已重置", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }

    private fun createCheckBox(
        activity: Activity,
        prefs: SharedPreferences,
        key: String,
        title: String,
        summary: String,
        defaultValue: Boolean,
    ): LinearLayout {
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(activity, 8), 0, dp(activity, 8))
        }
        val checkBox = CheckBox(activity).apply {
            text = title
            textSize = 15f
            setTextColor(BILI_TEXT_PRIMARY)
            // 勾选态用品牌粉,对齐 B 站 App 的开关/勾选视觉
            runCatching {
                buttonTintList = android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(BILI_PINK, 0xFFCCCCCC.toInt()),
                )
            }
            isChecked = prefs.getBoolean(key, defaultValue)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean(key, isChecked).apply()
            }
        }
        val summaryView = TextView(activity).apply {
            text = summary
            textSize = 12f
            setTextColor(BILI_TEXT_SECONDARY)
            setPadding(checkBox.paddingLeft + dp(activity, 32), 0, 0, 0)
        }
        layout.addView(checkBox)
        layout.addView(summaryView)
        return layout
    }

    private fun aboutItem(activity: Activity, title: String, value: String, onClick: (() -> Unit)? = null): View {
        val textCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                text = title
                textSize = 15f
                setTextColor(BILI_TEXT_PRIMARY)
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(activity).apply {
                text = value
                textSize = 13f
                setTextColor(BILI_TEXT_SECONDARY)
                setPadding(0, dp(activity, 2), 0, 0)
            })
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 6), dp(activity, 10), dp(activity, 6), dp(activity, 10))
            textCol.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(textCol)
            if (onClick != null) {
                isClickable = true
                background = selectableItemBackground(activity)
                addView(TextView(activity).apply {
                    text = "›"
                    textSize = 22f
                    setTextColor(BILI_PINK)
                })
                setOnClickListener { onClick() }
            }
        }
    }

    private fun openUrl(activity: Activity, url: String) {
        runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(activity, "无法打开链接", Toast.LENGTH_SHORT).show()
        }
    }

    private fun selectableItemBackground(activity: Activity): android.graphics.drawable.Drawable? {
        val tv = TypedValue()
        return if (activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
            activity.resources.getDrawable(tv.resourceId, activity.theme)
        } else {
            null
        }
    }

    fun wrapScroll(activity: Activity, child: View): ScrollView = ScrollView(activity).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        addView(child)
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
