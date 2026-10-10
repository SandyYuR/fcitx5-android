/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider
import androidx.annotation.ColorInt
import kotlin.math.min

/**
 * 键盘卡片（工具栏）上方圆角与编码区（预编辑）胶囊底部反向圆角共用的几何。
 *
 * 目标：编码区胶囊底部的**反向圆角**（向外扩的弧）与键盘卡片上方的**凸圆角**在卡片顶边
 * 上相切接续，看上去是一条连续弧线。做法是让胶囊主体左缘相对**卡片左缘**位移
 * `工具栏圆角 + 编码区圆角`：胶囊自己的左缘落在 `x = 工具栏圆角 + 编码区圆角`，
 * 其反向弧向左外扩 `编码区圆角`，正好收在 `x = 工具栏圆角` —— 卡片上圆角与顶边
 * 相切的切点，两条弧线在该点同切于卡片顶边。
 */
object ContinuousCornerGeometry {

    fun clampRadius(radius: Float, width: Float, height: Float): Float =
        radius.coerceAtLeast(0f).coerceAtMost(min(width, height).coerceAtLeast(0f) * 0.5f)

    /**
     * 编码区胶囊主体左缘相对**键盘卡片左缘**的位移。
     *
     * @param toolbarRadius 卡片上方（工具栏）圆角半径
     * @param inset 胶囊绘制区左缘相对卡片左缘的额外内缩（键盘侧边距、单手模式留白）。
     * 内缩把胶囊推得比卡片圆角还靠右时，反向弧退化为从胶囊自身左缘起画，弧线仍与
     * 卡片顶边（直线段）相切。
     * @param preeditRadius 编码区圆角半径，同时是反向弧的半径
     */
    fun preeditBodyOffset(toolbarRadius: Float, inset: Float, preeditRadius: Float): Float =
        (toolbarRadius - inset).coerceAtLeast(0f) + preeditRadius.coerceAtLeast(0f)

    /**
     * 反向圆角的**垂直**半径：等于编码区圆角，但最多占高度的一半，
     * 保证胶囊主体还剩得下一半高度（水平半径不受此限制，见 [addPreeditPath]）。
     */
    fun flareVerticalRadius(preeditRadius: Float, height: Float): Float =
        preeditRadius.coerceAtLeast(0f).coerceAtMost(height.coerceAtLeast(0f) * 0.5f)

    /**
     * 键盘卡片：上方两角半径 [topRadius]、下方两角半径 [bottomRadius]。
     * 该形状是凸的，可直接用于 [Outline.setConvexPath]（API < 30 的裁剪路径）。
     */
    fun addCardPath(path: Path, bounds: RectF, topRadius: Float, bottomRadius: Float) {
        path.reset()
        val width = bounds.width()
        val height = bounds.height()
        if (width <= 0f || height <= 0f) return
        val halfMin = min(width, height) * 0.5f
        val rt = topRadius.coerceIn(0f, halfMin)
        val rb = bottomRadius.coerceIn(0f, halfMin)
        val left = bounds.left
        val right = bounds.right
        val top = bounds.top
        val bottom = bounds.bottom

        path.moveTo(left + rt, top)
        path.lineTo(right - rt, top)
        if (rt > 0f) {
            path.arcTo(right - 2f * rt, top, right, top + 2f * rt, 270f, 90f, false)
        }
        path.lineTo(right, bottom - rb)
        if (rb > 0f) {
            path.arcTo(right - 2f * rb, bottom - 2f * rb, right, bottom, 0f, 90f, false)
        }
        path.lineTo(left + rb, bottom)
        if (rb > 0f) {
            path.arcTo(left, bottom - 2f * rb, left + 2f * rb, bottom, 90f, 90f, false)
        }
        path.lineTo(left, top + rt)
        if (rt > 0f) {
            path.arcTo(left, top, left + 2f * rt, top + 2f * rt, 180f, 90f, false)
        }
        path.close()
    }

    /**
     * 编码区胶囊：主体左缘位于 `bounds.left + bodyOffset`，右缘位于
     * `bounds.right - cornerRadius`（调用方给出的 bounds 必须已为右侧反向弧留出这部分宽度）。
     * 底部两角是**反向圆角**：水平半径固定为 [cornerRadius]（弧线水平落点仍是卡片圆角的
     * 切点），垂直半径按可用高度收敛到 [flareVerticalRadius]；底部弧线正好落在
     * `bounds.bottom`（键盘卡片顶边）。顶部两角是普通圆角（半径取 [cornerRadius] 与
     * 可用空间的较小值）。
     */
    fun addPreeditPath(path: Path, bounds: RectF, bodyOffset: Float, cornerRadius: Float) {
        path.reset()
        val height = bounds.height()
        if (bounds.width() <= 0f || height <= 0f) return
        val rx = cornerRadius.coerceAtLeast(0f)
        val ry = flareVerticalRadius(cornerRadius, height)
        val bodyLeft = bounds.left + bodyOffset.coerceAtLeast(0f)
        val bodyRight = (bounds.right - rx).coerceAtLeast(bodyLeft)
        val bodyTop = bounds.top
        val bodyBottom = bounds.bottom - ry
        if (bodyRight <= bodyLeft || bodyBottom <= bodyTop) {
            path.addRect(bounds, Path.Direction.CW)
            return
        }
        val r = min(min(rx, ry), min((bodyRight - bodyLeft) / 2f, (bodyBottom - bodyTop) / 2f))

        path.moveTo(bodyLeft + r, bodyTop)
        path.lineTo(bodyRight - r, bodyTop)
        if (r > 0f) {
            path.arcTo(bodyRight - 2f * r, bodyTop, bodyRight, bodyTop + 2f * r, 270f, 90f, false)
        }
        path.lineTo(bodyRight, bodyBottom)
        if (rx > 0f && ry > 0f) {
            // 右反向圆角：由主体的竖直右缘外扩到卡片顶边，圆心在主体右下方。
            path.arcTo(
                bodyRight, bodyBottom - ry,
                bodyRight + 2f * rx, bodyBottom + ry,
                180f, -90f, false
            )
        } else {
            path.lineTo(bodyRight, bounds.bottom)
        }
        path.lineTo(bodyLeft - rx, bounds.bottom)
        if (rx > 0f && ry > 0f) {
            // 左反向圆角：圆心在主体左下方，弧线收于 x = bodyLeft - rx，
            // 即卡片上圆角与顶边的切点处。
            path.arcTo(
                bodyLeft - 2f * rx, bodyBottom - ry,
                bodyLeft, bodyBottom + ry,
                90f, -90f, false
            )
        }
        path.lineTo(bodyLeft, bodyTop + r)
        if (r > 0f) {
            path.arcTo(bodyLeft, bodyTop, bodyLeft + 2f * r, bodyTop + 2f * r, 180f, 90f, false)
        }
        path.close()
    }
}

/**
 * 编码区胶囊背景。几何每次绘制时按当前参数重算：
 * [insetProvider] 给出胶囊绘制区左缘相对键盘卡片左缘的内缩（父布局 padding），
 * 运行期改变侧边距、单手留白都不需要重建 drawable。
 */
class PreeditShapeDrawable(
    @ColorInt private val color: Int,
    private val insetProvider: () -> Float,
    private val cardTopRadiusProvider: () -> Float,
    private val preeditRadiusProvider: () -> Float
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        paint.style = Paint.Style.FILL
        // 必须显式写 `this.color`：在 Paint 的作用域里裸写 `color` 会解析成
        // `Paint.color` 自己（`apply { this.color = color }` 实际编译为
        // `setColor(getColor())`），画笔会静默保留默认黑色。
        paint.color = this.color
    }

    private val path = Path()
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        val preeditRadius = preeditRadiusProvider()
        ContinuousCornerGeometry.addPreeditPath(
            path = path,
            bounds = rect,
            bodyOffset = ContinuousCornerGeometry.preeditBodyOffset(
                cardTopRadiusProvider(),
                insetProvider(),
                preeditRadius
            ),
            cornerRadius = preeditRadius
        )
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * 键盘卡片（含工具栏）形状：上方两角 [topRadiusProvider]，下方两角 [bottomRadiusProvider]。
 *
 * 必须作用在**卡片**（键盘窗口视图）而不是工具栏条本身：`keyBorder` 打开时工具栏条背景
 * 是透明的，在它上面做圆角根本看不见；而且工具栏条的背景色不透明时，单独给它做圆角会
 * 在角上露出下层卡片底色，形成缺口。
 */
class CardOutlineProvider(
    private val topRadiusProvider: () -> Float,
    private val bottomRadiusProvider: () -> Float = { 0f }
) : ViewOutlineProvider() {
    private val path = Path()
    private val bounds = RectF()

    override fun getOutline(view: View, outline: Outline) {
        if (view.width <= 0 || view.height <= 0) return
        bounds.set(0f, 0f, view.width.toFloat(), view.height.toFloat())
        ContinuousCornerGeometry.addCardPath(
            path, bounds, topRadiusProvider(), bottomRadiusProvider()
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("NewApi")
            outline.setPath(path)
        } else {
            @Suppress("DEPRECATION")
            outline.setConvexPath(path)
        }
    }
}
