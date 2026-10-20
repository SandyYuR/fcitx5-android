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
import androidx.annotation.ChecksSdkIntAtLeast
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

    /**
     * 把半径夹进 `[0, min(width, height) / 2]`。
     *
     * 非有限值（NaN/Inf）一律当 0：`Float.coerceAtLeast` 对 NaN 的判定为 false，会原样返回
     * NaN，而 NaN 半径喂给 [Outline.setRoundRect] 会让裁剪形状非法。半径来自用户偏好，
     * 读到脏数据时宁可直角也不能把异常值传进系统调用。
     */
    fun clampRadius(radius: Float, width: Float, height: Float): Float {
        if (!radius.isFinite()) return 0f
        return radius.coerceAtLeast(0f).coerceAtMost(min(width, height).coerceAtLeast(0f) * 0.5f)
    }

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
     *
     * 形状在几何上是凸的，但 **不能**据此直接用 [Outline.setConvexPath]：R 之前的
     * `Path.isConvex()` 是按坐标符号翻转次数统计的启发式，这种带圆角的高瘦矩形会被它
     * 误判成凹的并抛 `IllegalArgumentException: path must be convex`。裁剪形状请一律走
     * [applyCardOutline]，不要在这里或调用点自己判断 API 级别。
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
     * API < 30（R 之前）退化后的统一圆角半径：上下半径不等时取较大者，四角同半径。
     *
     * 这是一次近似——旧系统只能用 [Outline.setRoundRect] 表达四角同半径的形状。
     * 取较大者而不是较小者，是为了让「工具栏上方圆角」这类用户显式设置的角保持原样。
     */
    fun legacyUniformRadius(topRadius: Float, bottomRadius: Float): Float =
        maxOf(topRadius, bottomRadius).coerceAtLeast(0f)

    /**
     * 把 [path]（[addCardPath] 的产物）作为 [outline] 的裁剪形状，兼容各 API 级别：
     *
     * - `Outline.setPath` 是 **API 30 (R)** 才加入的方法：守卫写成 `>= Q` 会让 Android 10
     *   走到一个不存在的方法上，抛 `NoSuchMethodError: android.graphics.Outline.setPath`。
     * - API 30 之前只有 [Outline.setConvexPath]，它在 R 之前会真的校验凸性，而
     *   `Path.isConvex()` 在老 Skia 上对 [addCardPath] 生成的圆角矩形误判为凹（见 [addCardPath]），
     *   于是抛 `IllegalArgumentException: path must be convex`。
     *
     * 所以 API < 30 一律退化成圆角矩形：半径相等时形状完全一致，不等时四角同半径
     * （见 [legacyUniformRadius]）。任何一档都不会抛异常。
     *
     * @param width 卡片宽度（px），必须 > 0
     * @param height 卡片高度（px），必须 > 0
     */
    fun applyCardOutline(
        outline: Outline,
        width: Int,
        height: Int,
        topRadius: Float,
        bottomRadius: Float,
        path: Path
    ) {
        if (usesPathOutline(Build.VERSION.SDK_INT)) {
            outline.setPath(path)
        } else {
            val radius = clampRadius(
                legacyUniformRadius(topRadius, bottomRadius), width.toFloat(), height.toFloat()
            )
            outline.setRoundRect(0, 0, width, height, radius)
        }
    }

    /**
     * 该 API 级别能否用 [Outline.setPath] 表达任意（上下不同圆角）的裁剪形状。
     *
     * 门槛是 **API 30 (R)**：Outline.setPath 从 R 才有，写成 `>= Q` 会让 Android 10 抛
     * `NoSuchMethodError`。R 之前的 Outline.setConvexPath 也不能当兜底——它在 Q 之前会
     * 校验凸性，把 [addCardPath] 生成的圆角矩形误判成凹的（见 [addCardPath] 的说明）。
     *
     * 抽成纯函数是为了让 ContinuousCornerGeometryTest 能在 JVM 上钉住 28/29/30 三档，
     * `Build.VERSION.SDK_INT` 本身在单测里不可注入。
     */
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
    fun usesPathOutline(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.R

    /**
     * 编码区胶囊：主体左缘位于 `bounds.left + bodyOffset`，右缘位于
     * `bounds.right - rightBodyOffset`。左右位移都包含反向弧所需宽度；右侧还有卡片圆角
     * 对应的透明留白，长编码铺满可用宽度时，两侧主体和反向弧仍对称地收在卡片内。
     * 底部两角是**反向圆角**：水平半径固定为 [cornerRadius]（弧线水平落点仍是卡片圆角的
     * 切点），垂直半径按可用高度收敛到 [flareVerticalRadius]；底部弧线正好落在
     * `bounds.bottom`（键盘卡片顶边）。顶部两角是普通圆角（半径取 [cornerRadius] 与
     * 可用空间的较小值）。
     */
    fun addPreeditPath(
        path: Path,
        bounds: RectF,
        bodyOffset: Float,
        cornerRadius: Float,
        rightBodyOffset: Float = cornerRadius
    ) {
        path.reset()
        val height = bounds.height()
        if (bounds.width() <= 0f || height <= 0f) return
        val rx = cornerRadius.coerceAtLeast(0f)
        val ry = flareVerticalRadius(cornerRadius, height)
        val bodyLeft = bounds.left + bodyOffset.coerceAtLeast(0f)
        val bodyRight = (bounds.right - rightBodyOffset.coerceAtLeast(0f)).coerceAtLeast(bodyLeft)
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
 * [insetProvider] / [rightInsetProvider] 给出两侧相对键盘卡片的内缩（父布局 padding），
 * 运行期改变侧边距、单手留白都不需要重建 drawable。
 */
class PreeditShapeDrawable(
    @ColorInt private val color: Int,
    private val insetProvider: () -> Float,
    private val rightInsetProvider: () -> Float,
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
            cornerRadius = preeditRadius,
            rightBodyOffset = ContinuousCornerGeometry.preeditBodyOffset(
                cardTopRadiusProvider(),
                rightInsetProvider(),
                preeditRadius
            )
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
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) {
            // 尺寸归零时 Outline 必须被显式重置：getOutline 拿到的是复用的 Outline，
            // 什么都不设会让 clipToOutline 的宿主沿用上一帧的形状。
            outline.setEmpty()
            return
        }
        val topRadius = topRadiusProvider()
        val bottomRadius = bottomRadiusProvider()
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        ContinuousCornerGeometry.addCardPath(path, bounds, topRadius, bottomRadius)
        ContinuousCornerGeometry.applyCardOutline(
            outline, width, height, topRadius, bottomRadius, path
        )
    }
}
