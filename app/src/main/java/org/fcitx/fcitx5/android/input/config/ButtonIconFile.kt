/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.config

import android.content.res.Resources
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.VectorDrawable
import android.os.Build
import android.util.Log
import android.util.Xml
import android.util.AttributeSet
import androidx.vectordrawable.graphics.drawable.VectorDrawableCompat
import androidx.core.graphics.PathParser
import org.fcitx.fcitx5.android.utils.appContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.util.TypedValue
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException

/**
 * Helpers for custom button icons stored under `<externalFilesDir>/button_icons/`.
 *
 * Historically the icon field stored an absolute path that embedded the app's
 * package-specific external files dir, e.g.
 * `file:/storage/emulated/0/Android/data/org.fcitx.fcitx5.android.fx/files/button_icons/foo.png`.
 * Such paths break when the config is imported into a build with a different
 * applicationId (release vs debug, fx vs mainline), because the external files
 * dir path changes even though the image file is copied over.
 *
 * The canonical form is now a relative path `file:button_icons/foo.png`, resolved
 * against the current external files dir at load time. Both forms are accepted for
 * backward compatibility.
 */
object ButtonIconFile {

    const val PREFIX = "file:"
    const val DIR = "button_icons"
    private const val TAG = "ButtonIconFile"

    fun isFileIcon(icon: String?): Boolean = icon != null && icon.startsWith(PREFIX)

    /**
     * Extract the `button_icons`-relative name from a raw path (prefix stripped).
     * Falls back to the plain file name when the marker is absent.
     */
    private fun extractName(rawPath: String): String {
        val marker = "$DIR/"
        val idx = rawPath.lastIndexOf(marker)
        return if (idx >= 0) rawPath.substring(idx + marker.length) else File(rawPath).name
    }

    /**
     * Convert any file icon value to the canonical relative form
     * `file:button_icons/<name>`. Non-file icons are returned unchanged.
     */
    fun toRelative(icon: String): String {
        if (!isFileIcon(icon)) return icon
        val raw = icon.removePrefix(PREFIX)
        if (raw.isEmpty()) return icon
        return "$PREFIX$DIR/${extractName(raw)}"
    }

    /**
     * Resolve a file icon value to an absolute path on the current device, trying:
     * 1. the stored path as-is (same-package, non-exported data);
     * 2. relative to the current external files dir;
     * 3. `<externalFilesDir>/button_icons/<name>` (cross-variant imported data).
     * Returns null when nothing resolves to an existing file.
     */
    fun resolvePath(icon: String): String? {
        if (!isFileIcon(icon)) return null
        val raw = icon.removePrefix(PREFIX)
        if (raw.isEmpty()) return null
        val direct = File(raw)
        if (direct.isAbsolute && direct.exists()) return direct.absolutePath
        val ext = appContext.getExternalFilesDir(null) ?: return null
        if (!direct.isAbsolute) {
            val rel = File(ext, raw)
            if (rel.exists()) return rel.absolutePath
        }
        val byName = File(File(ext, DIR), extractName(raw))
        return if (byName.exists()) byName.absolutePath else null
    }

    /**
     * Load the drawable for a file icon value, or null when it cannot be resolved.
     */
    fun loadDrawable(icon: String): Drawable? {
        val path = resolvePath(icon) ?: run {
            Log.w(TAG, "Failed to resolve custom icon path: $icon")
            return null
        }
        Log.i(TAG, "Loading custom icon: $icon -> $path")
        return if (path.endsWith(".xml", ignoreCase = true)) {
            loadXmlDrawable(path).also {
                if (it == null) {
                    Log.w(TAG, "XML custom icon fallback to default: $path")
                } else {
                    Log.i(TAG, "Loaded XML custom icon: $path")
                }
            }
        } else if (path.endsWith(".svg", ignoreCase = true)) {
            loadSvgDrawable(path).also {
                if (it == null) {
                    Log.w(TAG, "SVG custom icon fallback to default: $path")
                } else {
                    Log.i(TAG, "Loaded SVG custom icon: $path")
                }
            }
        } else {
            try {
                Drawable.createFromPath(path).also {
                    if (it == null) {
                        Log.w(TAG, "Raster custom icon fallback to default: $path")
                    } else {
                        Log.i(TAG, "Loaded raster custom icon: $path")
                    }
                }
            } catch (e: RuntimeException) {
                Log.w(TAG, "Failed to load custom icon drawable from path: $path", e)
                null
            }
        }
    }

    private fun loadXmlDrawable(path: String): Drawable? {
        val bytes = try {
            File(path).readBytes()
        } catch (e: IOException) {
            Log.w(TAG, "Failed to read custom xml icon file: $path", e)
            return null
        }

        val framework = tryLoadFrameworkXmlDrawable(path, bytes)
        if (framework != null) return framework

        val platformVector = tryLoadPlatformVectorDrawable(path, bytes)
        if (platformVector != null) return platformVector

        val compatVector = tryLoadCompatVectorDrawable(path, bytes)
        if (compatVector != null) return compatVector

        val simpleVector = tryLoadSimpleVectorDrawable(path, bytes)
        if (simpleVector != null) return simpleVector

        Log.w(TAG, "Failed to inflate custom xml icon drawable: $path")
        return null
    }

    @Throws(XmlPullParserException::class, IOException::class)
    private fun newXmlParser(input: InputStream): XmlPullParser {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(input, null)
        var event = parser.eventType
        while (event != XmlPullParser.START_TAG && event != XmlPullParser.END_DOCUMENT) {
            event = parser.next()
        }
        if (event != XmlPullParser.START_TAG) {
            throw XmlPullParserException("No start tag found")
        }
        return parser
    }

    private fun tryLoadFrameworkXmlDrawable(path: String, content: ByteArray): Drawable? {
        val resources = appContext.resources
        return try {
            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                setInput(ByteArrayInputStream(content), null)
            }
            // Use null theme for file-based parser to avoid Theme.obtainStyledAttributes()
            // requiring XmlBlock$Parser (resource XML only).
            Drawable.createFromXml(resources, parser, null)
        } catch (e: IOException) {
            Log.w(TAG, "Failed to parse custom xml icon file (framework): $path (${e.message})")
            null
        } catch (e: XmlPullParserException) {
            Log.w(TAG, "Failed to parse custom xml icon file (framework): $path (${e.message})")
            null
        } catch (e: Resources.NotFoundException) {
            Log.w(TAG, "Drawable resource not found while inflating xml icon (framework): $path (${e.message})")
            null
            null
        } catch (e: RuntimeException) {
            Log.w(TAG, "Failed to inflate custom xml icon drawable (framework): $path (${e.message})")
            null
        }
    }

    private fun tryLoadPlatformVectorDrawable(path: String, content: ByteArray): Drawable? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null
        return try {
            val resources = appContext.resources
            val parser = newXmlParser(ByteArrayInputStream(content))
            if (parser.name != "vector") {
                Log.w(TAG, "Unsupported custom xml icon root <${parser.name}>: $path")
                return null
            }
            val attrs: AttributeSet = Xml.asAttributeSet(parser)
            VectorDrawable.createFromXmlInner(resources, parser, attrs, null)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to inflate custom xml icon drawable (platform vector): $path (${e.message})")
            null
        }
    }

    private fun tryLoadCompatVectorDrawable(path: String, content: ByteArray): Drawable? {
        return try {
            val resources = appContext.resources
            val parser = newXmlParser(ByteArrayInputStream(content))
            if (parser.name != "vector") return null
            val attrs: AttributeSet = Xml.asAttributeSet(parser)
            VectorDrawableCompat.createFromXmlInner(resources, parser, attrs, null)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to inflate custom xml icon drawable (compat vector): $path (${e.message})")
            null
        }
    }

    private fun tryLoadSimpleVectorDrawable(path: String, content: ByteArray): Drawable? {
        return try {
            val parser = newXmlParser(ByteArrayInputStream(content))
            if (parser.name != "vector") return null

            val width = parseDimension(getAttr(parser, "width")) ?: return null
            val height = parseDimension(getAttr(parser, "height")) ?: return null
            val viewportWidth = getAttr(parser, "viewportWidth")?.toFloatOrNull() ?: return null
            val viewportHeight = getAttr(parser, "viewportHeight")?.toFloatOrNull() ?: return null

            val paths = mutableListOf<SimplePathItem>()
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "path") {
                    val pathData = getAttr(parser, "pathData")
                    if (!pathData.isNullOrBlank()) {
                        val pathObj = PathParser.createPathFromPathData(pathData)
                        val color = parseColor(getAttr(parser, "fillColor")) ?: Color.WHITE
                        val alpha = getAttr(parser, "fillAlpha")?.toFloatOrNull() ?: 1f
                        pathObj.fillType = if (getAttr(parser, "fillType") == "evenOdd") {
                            Path.FillType.EVEN_ODD
                        } else {
                            Path.FillType.WINDING
                        }
                        paths += SimplePathItem(pathObj, color, alpha.coerceIn(0f, 1f))
                    }
                }
                event = parser.next()
            }
            if (paths.isEmpty()) {
                Log.w(TAG, "No drawable paths found in custom vector xml: $path")
                null
            } else {
                Log.i(TAG, "Loaded XML custom icon via simple vector parser: $path")
                SimpleVectorDrawable(width, height, viewportWidth, viewportHeight, paths)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse custom xml icon with simple vector parser: $path (${e.message})")
            null
        }
    }

    private fun getAttr(parser: XmlPullParser, name: String): String? {
        for (i in 0 until parser.attributeCount) {
            if (parser.getAttributeName(i) == name) return parser.getAttributeValue(i)
        }
        return null
    }

    private fun parseDimension(value: String?): Float? {
        if (value.isNullOrBlank()) return null
        val s = value.trim()
        return when {
            s.endsWith("dp") ->
                s.removeSuffix("dp").toFloatOrNull()?.let {
                    TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        it,
                        appContext.resources.displayMetrics
                    )
                }
            s.endsWith("px") -> s.removeSuffix("px").toFloatOrNull()
            else -> s.toFloatOrNull()
        }
    }

    private fun parseColor(value: String?): Int? {
        if (value.isNullOrBlank()) return null
        return runCatching { Color.parseColor(value.trim()) }.getOrNull()
    }

    private data class SimplePathItem(
        val path: Path,
        val fillColor: Int,
        val fillAlpha: Float
    )

    /**
     * Check whether a file icon path should be tinted with theme colors.
     * XML vector icons and monochrome SVGs (black/white/gray only) are tinted.
     */
    fun shouldTintIcon(icon: String?): Boolean {
        if (icon == null) return false
        if (icon.endsWith(".xml", ignoreCase = true)) return true
        if (icon.endsWith(".svg", ignoreCase = true)) {
            val path = resolvePath(icon) ?: return false
            return isSvgMonochrome(path)
        }
        return false
    }

    private fun isSvgMonochrome(path: String): Boolean {
        val bytes = try {
            File(path).readBytes()
        } catch (_: IOException) {
            return false
        }
        try {
            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                setInput(ByteArrayInputStream(bytes), null)
            }
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    val fill = parseSvgColorForMonochrome(getAttr(parser, "fill"))
                    if (fill != null && !isGray(fill)) return false
                    val stroke = parseSvgColorForMonochrome(getAttr(parser, "stroke"))
                    if (stroke != null && !isGray(stroke)) return false
                }
                event = parser.next()
            }
            return true
        } catch (_: Exception) {
            return false
        }
    }

    private fun parseSvgColorForMonochrome(value: String?): Int? {
        if (value.isNullOrBlank() || value.equals("none", ignoreCase = true)) return null
        return runCatching { Color.parseColor(value.trim()) }.getOrNull()
    }

    private fun isGray(color: Int): Boolean {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        return r == g && g == b
    }

    private fun loadSvgDrawable(path: String): Drawable? {
        try {
            val svg = com.caverock.androidsvg.SVG.getFromInputStream(File(path).inputStream())
            val picture = svg.renderToPicture()
            val w = picture.width.coerceAtLeast(1)
            val h = picture.height.coerceAtLeast(1)
            // Cap at a reasonable max dimension
            val maxDim = 256f
            val scale = minOf(maxDim / w, maxDim / h, 1f)
            val bitW = (w * scale).toInt().coerceAtLeast(1)
            val bitH = (h * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(bitW, bitH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.scale(bitW.toFloat() / w, bitH.toFloat() / h)
            canvas.drawPicture(picture)
            return BitmapDrawable(appContext.resources, bitmap)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load SVG: $path", e)
            return null
        }
    }

    private class SimpleVectorDrawable(
        private val widthDp: Float,
        private val heightDp: Float,
        private val viewportWidth: Float,
        private val viewportHeight: Float,
        private val paths: List<SimplePathItem>
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private var alphaValue: Int = 255
        private var colorFilterValue: ColorFilter? = null
        private var tintColor: Int? = null

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty || viewportWidth <= 0f || viewportHeight <= 0f) return
            canvas.save()
            canvas.translate(b.left.toFloat(), b.top.toFloat())
            canvas.scale(b.width().toFloat() / viewportWidth, b.height().toFloat() / viewportHeight)
            for (item in paths) {
                paint.color = tintColor ?: item.fillColor
                paint.alpha = (alphaValue * item.fillAlpha).toInt().coerceIn(0, 255)
                paint.colorFilter = colorFilterValue
                canvas.drawPath(item.path, paint)
            }
            canvas.restore()
        }

        override fun setAlpha(alpha: Int) {
            alphaValue = alpha.coerceIn(0, 255)
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            colorFilterValue = colorFilter
            invalidateSelf()
        }

        override fun setTint(tintColor: Int) {
            this.tintColor = tintColor
            invalidateSelf()
        }

        override fun setTintList(tint: ColorStateList?) {
            tintColor = tint?.defaultColor
            invalidateSelf()
        }

        override fun setTintMode(tintMode: PorterDuff.Mode?) {
            // Simple vector fallback uses solid fill tinting only.
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getIntrinsicWidth(): Int = widthDp.toInt().coerceAtLeast(1)

        override fun getIntrinsicHeight(): Int = heightDp.toInt().coerceAtLeast(1)
    }
}
