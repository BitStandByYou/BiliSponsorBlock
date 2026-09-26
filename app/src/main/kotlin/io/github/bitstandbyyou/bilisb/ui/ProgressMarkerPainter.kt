package io.github.bitstandbyyou.bilisb.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import io.github.bitstandbyyou.bilisb.model.SponsorCategories
import io.github.bitstandbyyou.bilisb.model.SponsorSegment

/**
 * 进度条片段标记绘制。
 *
 * 绘制赞助片段矩形标记与 POI 高亮圆点：
 *   - 画在**轨道 drawable 自己的 bounds 上**（薄轨道高度 top→bottom），而不是父 View 的整高，
 *     这样标记和进度条等高、嵌在轨道里，而不是一根高 bar 浮在上面。
 *   - 用**实色**分类画笔（不带 alpha），对应 patch 里的 `anVar.o`。
 *   - 普通片段：`drawRect(xStart, bounds.top, xEnd, bounds.bottom)`。
 *   - POI 高亮：`drawCircle(xStart, centerY, height/2)`，对应 patch `anVar.d()` 分支。
 *
 * 几何计算全部在 [MarkerGeometry] 里（纯 Kotlin，不依赖 android.graphics），
 * 统一处理片段超出视频时长和边界像素的情况。
 *
 * 调用方将进度条区域传入 [drawInBounds]，由本对象在对应边界内绘制片段标记。
 */
object ProgressMarkerPainter {
    // 分类配色兜底(实色,无 alpha)。当快照里没有该分类颜色时回退到这里。
    // 与 SettingsKeys.CATEGORY_COLOR_DEFAULTS 的默认值保持一致。
    private val defaultCategoryColors = mapOf(
        "sponsor" to Color.rgb(0, 210, 0),           // 绿色
        "selfpromo" to Color.rgb(255, 255, 0),       // 黄色
        "interaction" to Color.rgb(170, 0, 255),     // 紫色
        "intro" to Color.rgb(0, 255, 255),           // 青色
        "outro" to Color.rgb(0, 100, 255),           // 蓝色
        "preview" to Color.rgb(255, 128, 0),         // 橙色
        "music_offtopic" to Color.rgb(255, 0, 180),  // 粉色
        "filler" to Color.rgb(127, 0, 255),          // 深紫
        SponsorCategories.POI_HIGHLIGHT to Color.rgb(255, 30, 30),   // 红色
    )

    private val defaultColor = Color.rgb(255, 196, 0)

    // 复用单个 Paint，避免每帧 new。FILL + 抗锯齿（圆点更平滑）。
    private val paint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    /**
     * 在轨道 drawable 上绘制标记。对应 patch 的 `b(...)`。
     *
     * @param drawable 轨道 drawable，用它的 bounds 决定标记的位置和高度
     * @param colorOverrides 用户在设置里自定义的分类颜色(category→ARGB)。缺该分类则回退内置配色。
     */
    fun draw(
        drawable: Drawable,
        canvas: Canvas,
        durationMs: Long,
        segments: List<SponsorSegment>,
        colorOverrides: Map<String, Int> = emptyMap(),
    ) {
        drawInBounds(canvas, drawable.bounds, durationMs, segments, colorOverrides)
    }

    /**
     * 在给定 bounds 上绘制标记。
     *
     * 使用给定矩形区域绘制，适用于 drawable 轨道和 SeekBar 等进度条视图。
     */
    fun drawInBounds(
        canvas: Canvas,
        bounds: android.graphics.Rect,
        durationMs: Long,
        segments: List<SponsorSegment>,
        colorOverrides: Map<String, Int> = emptyMap(),
    ) {
        if (durationMs <= 0 || segments.isEmpty()) {
            return
        }
        if (bounds.isEmpty) {
            return
        }

        // 几何在纯函数里算；非法片段（起点超时长、end<start 等）会被跳过而不是抛异常。
        val ranges = MarkerGeometry.markerRanges(
            boundsLeft = bounds.left.toFloat(),
            boundsRight = bounds.right.toFloat(),
            durationMs = durationMs,
            segments = segments,
        )
        if (ranges.isEmpty()) {
            return
        }

        // 裁剪到轨道区域：即使调用方给的 bounds 偏大（例如 SeekBar 本体），
        // 标记也不会溢出到进度条之外盖住其它控件。
        val checkpoint = canvas.save()
        canvas.clipRect(bounds)
        try {
            drawRanges(canvas, bounds, ranges, colorOverrides)
        } finally {
            canvas.restoreToCount(checkpoint)
        }
    }

    private fun drawRanges(
        canvas: Canvas,
        bounds: android.graphics.Rect,
        ranges: List<MarkerGeometry.MarkerRange>,
        colorOverrides: Map<String, Int>,
    ) {
        val top = bounds.top.toFloat()
        val bottom = bounds.bottom.toFloat()
        val centerY = bounds.exactCenterY()
        val radius = bounds.height() / 2.0f

        ranges.forEach { range ->
            paint.color = colorOverrides[range.category]
                ?: defaultCategoryColors[range.category]
                ?: defaultColor

            if (range.isPoi) {
                // POI 高亮：画圆点（对齐 patch anVar.d() 分支），半径不超过半高，避免超出轨道
                canvas.drawCircle(range.xStart, centerY, radius, paint)
            } else {
                canvas.drawRect(range.xStart, top, range.xEnd, bottom, paint)
            }
        }
    }
}
