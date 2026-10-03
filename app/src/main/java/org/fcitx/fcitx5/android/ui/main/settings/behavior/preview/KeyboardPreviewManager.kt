/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.AuxBarAction
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.keyboard.AuxBarConfig
import org.fcitx.fcitx5.android.input.keyboard.LayoutVariant
import org.fcitx.fcitx5.android.input.editing.FoxyTextEditingUi
import org.fcitx.fcitx5.android.input.editing.TextEditingLayoutLoader
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.ui.main.settings.preview.PreviewInputMethodEntry
import org.fcitx.fcitx5.android.ui.main.settings.behavior.utils.LayoutJsonUtils
import splitties.dimensions.dp

/**
 * Keyboard preview manager, responsible for previewing keyboard layouts.
 *
 * Main functions:
 * - [updatePreview] - Update keyboard preview
 * - [clear] - Clear preview keyboard
 *
 * How it works:
 * 1. Build in-memory JSON to store current layout
 * 2. Render inside [TextKeyboard.withPreviewLayout], which makes that JSON the layout source for
 *    this thread's lookups only — the process-wide ConfigProvider is not touched (see E9)
 * 3. Load TextKeyboard for preview (reads from in-memory JSON, no disk I/O)
 * 4. Preview cache entries are dropped on the way out; the real keyboard's stay
 *
 * Usage example:
 * ```kotlin
 * val previewManager = KeyboardPreviewManager(context, container, entries)
 * previewManager.updatePreview(layoutName, subModeLabel, fcitxConnection)
 * ```
 */
class KeyboardPreviewManager(
    private val context: Context,
    private val previewContainer: ViewGroup,
    private val entries: Map<String, List<List<Map<String, Any?>>>>,
    private val layoutHeightPercentOverrideProvider: (String) -> Int? = { null },
    private val layoutAuxBarConfigProvider: (String) -> AuxBarConfig? = { null },
    private val layoutAuxBarKeysProvider: (String) -> List<Map<String, Any?>> = { emptyList() },
    private val subModeNameToIdProvider: () -> Map<String, String> = { emptyMap() },
    /**
     * 编辑器当前正在编辑的排列；null 表示"普通排列"。
     *
     * 放进预览的意义是：预览键盘的宽度是它所在容器的宽度，永远够不到分体阈值，用户正在
     * 编辑分体布局时预览却按普通排列渲染——"改了没生效"的既视感。这里把排列强制过去，
     * 预览就渲染用户正在编辑的那份行集合。
     */
    private val forcedVariantProvider: () -> LayoutVariant? = { null }
) {
    private var previewKeyboard: TextKeyboard? = null
    private var previewTextEditingView: View? = null
    private val previewBlurMask by lazy { PreviewKeyBlurMaskView(context) }

    /**
     * Everything the rendered preview depends on; see the short-circuit in [updatePreview].
     */
    private data class PreviewSignature(
        val layoutName: String,
        val subModeLabel: String?,
        val effectiveSubModeKey: String?,
        val themeName: String,
        val keyBorder: Boolean,
        val layoutJson: String,
        /** 强制预览的排列形态；null 表示按键盘宽度自动判定。 */
        val forcedVariant: String?
    )
    private var lastPreviewSignature: PreviewSignature? = null

    /**
     * Update keyboard preview.
     *
     * @param layoutName Layout name
     * @param previewSubModeLabel Submode label, null for default
     * @param fcitxConnection Fcitx connection for getting current input method
     */
    fun updatePreview(
        layoutName: String,
        previewSubModeLabel: String?,
        fcitxConnection: FcitxConnection
    ) {
        // Try to load submode-specific layout first
        val effectiveSubModeKey = resolveEffectiveSubModeKey(layoutName, previewSubModeLabel)
        val forcedVariant = forcedVariantProvider()
        // 正在编辑分体布局时，预览的就是那一份。它挂在**当前子布局**下面
        // （`rime:倉頡五代:__variant__:split`），与方案专用布局是并列的两条分支，必须单独解析。
        val variantEntryKey = splitEntryKey(
            layoutName = layoutName,
            subModeLabel = previewSubModeLabel,
            editingSplit = forcedVariant?.isSplit == true
        )
        val effectiveLayoutKey = variantEntryKey ?: effectiveSubModeKey ?: layoutName
        val rows = entries[effectiveLayoutKey]
            ?: if (layoutName == TEXT_EDITOR_LAYOUT_NAME) emptyList() else return

        val theme = ThemeManager.activeTheme
        val keyBorder = ThemeManager.prefs.keyBorder.getValue()

        // Build submode map with all available submodes for this layout
        val subModeMap = buildSubModeMap(
            layoutName = layoutName,
            subModeKey = effectiveSubModeKey,
            currentRows = rows,
            previewSubModeLabel = previewSubModeLabel,
            forcedVariant = forcedVariant,
            variantEntryKey = variantEntryKey
        )
        val tempJson = JsonObject(mapOf(layoutName to JsonObject(subModeMap)))

        // Nothing that affects the rendering changed, so keep the existing preview. The editor
        // calls this on every drag step, and each call otherwise rebuilt and re-measured a whole
        // TextKeyboard (see E9).
        val signature = PreviewSignature(
            layoutName = layoutName,
            subModeLabel = previewSubModeLabel,
            effectiveSubModeKey = effectiveLayoutKey,
            themeName = theme.name,
            keyBorder = keyBorder,
            layoutJson = tempJson.toString(),
            forcedVariant = forcedVariant?.name
        )
        if ((previewKeyboard != null || previewTextEditingView != null) && signature == lastPreviewSignature) return
        lastPreviewSignature = signature

        previewContainer.removeAllViews()
        previewContainer.background = theme.backgroundDrawable(keyBorder)
        previewBlurMask.bindKeyboard(null)

        // Remove old keyboard view
        previewKeyboard?.let {
            previewContainer.removeView(it)
            previewKeyboard = null
        }

        previewKeyboard = null
        previewTextEditingView = null

        try {
            if (layoutName == TEXT_EDITOR_LAYOUT_NAME) {
                createTextEditorPreview(rows, theme)
            } else {
                TextKeyboard.withPreviewLayout(tempJson) {
                    createKeyboardPreview(
                        layoutName,
                        previewSubModeLabel,
                        effectiveLayoutKey,
                        fcitxConnection,
                        forcedVariant
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("KeyboardPreview", "Failed to create keyboard preview for layout: $layoutName, submode: $previewSubModeLabel", e)
            showError(e.message ?: "Unknown error")
        }
    }

    private fun createTextEditorPreview(
        rows: List<List<Map<String, Any?>>>,
        theme: org.fcitx.fcitx5.android.data.theme.Theme
    ) {
        val layout = TextEditingLayoutLoader.fromRows(rows)
        val prefs = AppPrefs.getInstance()
        val textEditing = prefs.textEditing
        val keyboard = prefs.keyboard
        val isLandscape = context.resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val globalPercent = if (isLandscape) {
            keyboard.keyboardHeightPercentLandscape.getValue()
        } else {
            keyboard.keyboardHeightPercent.getValue()
        }
        val displayMetrics = context.resources.displayMetrics
        val rowScale = computeRowHeightScale(rows)
        val keyboardHeight = (
            displayMetrics.heightPixels * globalPercent * rowScale / 100f
        ).toInt().coerceAtLeast(context.dp(120))
        val ui = FoxyTextEditingUi(
            context,
            theme,
            ThemeManager.prefs.keyRippleEffect.getValue(),
            ThemeManager.prefs.keyBorder.getValue(),
            context.dp(ThemeManager.prefs.textEditingButtonRadius.getValue().toFloat()),
            textEditing.cursorStepDp.getValue(),
            textEditing.cursorLongPressDelay.getValue().toLong(),
            textEditing.cursorPromptMove.getValue(),
            textEditing.cursorPromptLongPress.getValue(),
            textEditing.cursorPromptSelecting.getValue(),
            textEditing.cursorPromptReleaseSelection.getValue(),
            layout,
            null
        )
        previewTextEditingView = ui.root
        previewContainer.addView(
            ui.root,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, keyboardHeight)
        )
        ui.root.requestLayout()
        ui.root.invalidate()
    }

    private fun buildSubModeMap(
        layoutName: String,
        subModeKey: String?,
        currentRows: List<List<Map<String, Any?>>>,
        previewSubModeLabel: String?,
        forcedVariant: LayoutVariant?,
        variantEntryKey: String?
    ): MutableMap<String, JsonElement> {
        val subModeMap = mutableMapOf<String, JsonElement>()

        val currentRowsArray = rowsArrayOf(currentRows)

        // 高度/辅助栏按"当前实际编辑的那份条目"取：正在编辑分体布局时，高度覆盖也记在
        // 分体条目的键上（编辑器的高度对话框写的就是这个键），用布局名去查会查不到。
        val effectiveKey = variantEntryKey ?: subModeKey ?: layoutName
        val auxBarConfig = if (layoutAuxBarConfigProvider(effectiveKey)?.position == org.fcitx.fcitx5.android.input.keyboard.AuxBarPosition.AbovePreedit) null
            else layoutAuxBarConfigProvider(effectiveKey)
        val auxBarKeys = layoutAuxBarKeysProvider(effectiveKey)
        val meta = if (auxBarConfig != null) {
            val posStr = when (auxBarConfig.position) {
                org.fcitx.fcitx5.android.input.keyboard.AuxBarPosition.Top -> "top"
                org.fcitx.fcitx5.android.input.keyboard.AuxBarPosition.Bottom -> "bottom"
                org.fcitx.fcitx5.android.input.keyboard.AuxBarPosition.Left -> "left"
                org.fcitx.fcitx5.android.input.keyboard.AuxBarPosition.Right -> "right"
                org.fcitx.fcitx5.android.input.keyboard.AuxBarPosition.AbovePreedit -> "top"
            }
            val auxBarObj = JsonObject(
                mutableMapOf<String, JsonElement>(
                    "position" to JsonPrimitive(posStr),
                    "size_percent" to JsonPrimitive(auxBarConfig.sizePercent)
                ).apply {
                    if (auxBarKeys.isNotEmpty()) {
                        this["keys"] = JsonArray(auxBarKeys.map { keyMap ->
                            JsonObject(keyMap.entries.associate { (k, v) ->
                                k to LayoutJsonUtils.convertToJsonProperty(v)
                            })
                        })
                    }
                }
            )
            JsonObject(mapOf("aux_bar" to auxBarObj))
        } else if (auxBarKeys.isNotEmpty()) {
            JsonObject(mapOf(
                "aux_bar" to JsonObject(mapOf(
                    "keys" to JsonArray(auxBarKeys.map { keyMap ->
                        JsonObject(keyMap.entries.associate { (k, v) ->
                            k to LayoutJsonUtils.convertToJsonProperty(v)
                        })
                    })
                ))
            ))
        } else null

        if (variantEntryKey != null) {
            // 正在编辑分体布局：把它作为 `__variant__:split` 条目写进临时 JSON 里**该子布局
            // 所在的这一层**，并同时放入该子布局的普通排列作为回退。两份内容可能完全相同
            // （用户还没改），但都写才能让预览走的是真实键盘那条解析路径——否则"预览对了、
            // 真机不对"这种偏差无从暴露。
            val splitElement: JsonElement = if (meta != null) {
                JsonObject(mapOf("__meta__" to meta, "default" to currentRowsArray))
            } else {
                currentRowsArray
            }
            val splitLabel = LayoutJsonUtils.toVariantSubModeLabel(LayoutVariant.Split.jsonKey!!)
            if (subModeKey != null && entries.containsKey(subModeKey)) {
                // 方案自己的分体布局：嵌在该方案条目内部，普通排列取该方案的。
                val dockedRows = rowsArrayOf(entries[subModeKey])
                subModeMap[previewSubModeLabel ?: "default"] = JsonObject(
                    mapOf("default" to dockedRows, splitLabel to splitElement)
                )
                entries[layoutName]?.let { subModeMap["default"] = rowsArrayOf(it) }
            } else {
                // 布局本体的分体布局：与 `default` 平级。
                val dockedRows = entries[layoutName]?.let { rowsArrayOf(it) } ?: currentRowsArray
                subModeMap["default"] = dockedRows
                subModeMap[splitLabel] = splitElement
            }
            return subModeMap
        }

        if (subModeKey != null && entries.containsKey(subModeKey)) {
            // Editing a submode layout - add it with its label
            val subModeEntry: JsonElement = if (meta != null) {
                JsonObject(mapOf("__meta__" to meta, "default" to currentRowsArray))
            } else {
                currentRowsArray
            }
            subModeMap[previewSubModeLabel ?: "default"] = subModeEntry
            // Also add default layout if it exists (for fallback)
            val defaultRows = entries[layoutName]
            if (defaultRows != null) {
                subModeMap["default"] = rowsArrayOf(defaultRows)
            }
        } else {
            // Editing default layout
            subModeMap["default"] = if (meta != null) {
                JsonObject(mapOf("__meta__" to meta, "default" to currentRowsArray))
            } else {
                currentRowsArray
            }
        }

        return subModeMap
    }

    /**
     * Create keyboard preview view.
     */
    private fun createKeyboardPreview(
        layoutName: String,
        previewSubModeLabel: String?,
        effectiveSubModeKey: String?,
        fcitxConnection: FcitxConnection,
        forcedVariant: LayoutVariant?
    ) {
        val theme = ThemeManager.activeTheme

        // Create the preview IME from the currently cached in-process IME state, and hand it to
        // the keyboard at construction time so its very first layout pass already resolves the
        // previewed layout. Avoid runImmediately() here because updatePreview is called very
        // frequently during editing and can ANR when host IME and editor contend for the same
        // IPC path.
        val previewIme = PreviewInputMethodEntry.create(
            layoutName = layoutName,
            subModeLabel = previewSubModeLabel,
            base = TextKeyboard.ime
        )

        previewKeyboard = TextKeyboard(context, theme, previewIme, isPreview = true).apply {
            // 形态覆盖要在 [onAttach]/[refreshStyle] **之前**设好：预览容器宽度永远够不到
            // 分体阈值，不覆盖的话第一趟布局就会退回竖屏停靠，多一次重建不说，那一帧的
            // 按键位置也是错的。
            if (forcedVariant != null) setLayoutVariantOverride(forcedVariant)
            val displayMetrics = context.resources.displayMetrics
            val screenHeight = displayMetrics.heightPixels

            val effectiveLayoutKey = effectiveSubModeKey ?: layoutName
            val rows = entries[effectiveLayoutKey].orEmpty()

            // Get keyboard height percentage from layout override or preferences
            val keyboardPrefs = AppPrefs.getInstance().keyboard
            val isLandscape = context.resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
            val globalPercent = if (isLandscape) {
                keyboardPrefs.keyboardHeightPercentLandscape.getValue()
            } else {
                keyboardPrefs.keyboardHeightPercent.getValue()
            }
            val basePercent = layoutHeightPercentOverrideProvider(effectiveLayoutKey)
                ?: globalPercent
            val rowScale = computeRowHeightScale(rows)
            val effectivePercent = (basePercent * rowScale).coerceIn(10f, 90f)
            val keyboardHeight = (screenHeight * effectivePercent / 100f).toInt()

            // Get keyboard side and bottom padding from preferences
            val sidePadding = keyboardPrefs.keyboardSidePadding.getValue()
            val bottomPadding = keyboardPrefs.keyboardBottomPadding.getValue()
            val sidePaddingPx = (sidePadding * displayMetrics.density).toInt()
            val bottomPaddingPx = (bottomPadding * displayMetrics.density).toInt()

            val layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                keyboardHeight
            )
            previewContainer.addView(
                previewBlurMask,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    keyboardHeight
                )
            )
            previewContainer.addView(this, layoutParams)

            onAttach()

            onInputMethodUpdate(previewIme)
            setTextScale(1.0f)
            refreshStyle()
            updateAuxBarActions(emptyList<AuxBarAction>())
            previewBlurMask.applyTheme(theme, ThemeManager.prefs.keyBorder.getValue())
            previewBlurMask.bindKeyboard(this)
            post { previewBlurMask.refreshMask(hierarchyChanged = true) }
            requestLayout()
            invalidate()
        }
    }

    private fun computeRowHeightScale(rows: List<List<Map<String, Any?>>>): Float {
        if (rows.isEmpty()) return 1f

        val parsedPercents = rows.map { row ->
            row.mapNotNull { key ->
                (key["rowHeightPercent"] as? Number)?.toFloat()
                    ?: (key["rowHeightPercent"] as? String)?.trim()?.toFloatOrNull()
            }.maxOrNull()?.takeIf { it in 1f..100f }
        }

        val definedSum = parsedPercents.filterNotNull().sum()
        val undefinedCount = parsedPercents.count { it == null }

        val distributed = if (undefinedCount == 0) {
            parsedPercents.map { it ?: 0f }
        } else {
            val remaining = (100f - definedSum).coerceAtLeast(0f)
            val avg = remaining / undefinedCount
            parsedPercents.map { it ?: avg }
        }

        val sum = distributed.sum()
        if (sum <= 0f) return 1f
        val normalized = distributed.map { it * 100f / sum }
        return (normalized.sum() / 100f).coerceAtLeast(0.1f)
    }

    private fun resolveEffectiveSubModeKey(
        layoutName: String,
        previewSubModeLabel: String?
    ): String? {
        val label = previewSubModeLabel?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val keyByLabel = "$layoutName:$label"
        if (entries.containsKey(keyByLabel)) return keyByLabel
        val keyById = subModeNameToIdProvider()[label]?.let { "$layoutName:$it" }
        if (keyById != null && entries.containsKey(keyById)) return keyById
        return null
    }

    /**
     * 正在编辑的那个**子布局的分体布局**条目键；不在编辑分体布局、或它不存在时返回 null。
     *
     * 分体布局挂在子布局上：`仓颉 + 分体` → `rime:倉頡五代:__variant__:split`，
     * `默认 + 分体` → `rime:__variant__:split`。方案既可能以标签为键、也可能以 id 为键
     * （[subModeNameToIdProvider]），两条都要试。
     */
    private fun splitEntryKey(
        layoutName: String,
        subModeLabel: String?,
        editingSplit: Boolean
    ): String? {
        if (!editingSplit) return null
        val label = subModeLabel?.trim()?.takeIf { it.isNotEmpty() }
        val candidates = listOfNotNull(label, label?.let { subModeNameToIdProvider()[it] }).distinct()
        candidates.forEach { candidate ->
            val key = LayoutJsonUtils.entryKeyOf(layoutName, candidate, LayoutVariant.Split)
            if (entries.containsKey(key)) return key
        }
        if (label != null) return null
        return LayoutJsonUtils.entryKeyOf(layoutName, null, LayoutVariant.Split)
            .takeIf { entries.containsKey(it) }
    }

    /** 编辑器内部的行集合转成 JSON 数组（预览用的临时布局）。 */
    private fun rowsArrayOf(rows: List<List<Map<String, Any?>>>?): JsonArray =
        JsonArray((rows ?: emptyList()).map { row ->
            JsonArray(row.map { key ->
                JsonObject(key.entries.associate { (k, v) ->
                    k to LayoutJsonUtils.convertToJsonProperty(v)
                })
            })
        })

    /**
     * Show error message in preview container.
     */
    private fun showError(message: String) {
        previewContainer.removeAllViews()
        val errorText = TextView(context).apply {
            text = context.getString(R.string.text_keyboard_layout_preview_error, message)
            textSize = 12f
            setTextColor(Color.RED)
            setPadding(context.dp(16), context.dp(8), context.dp(16), context.dp(8))
        }
        previewContainer.addView(errorText)
    }

    private companion object {
        private const val TEXT_EDITOR_LAYOUT_NAME = "text_editor"
    }

    fun clear() {
        previewBlurMask.bindKeyboard(null)
        previewKeyboard?.let {
            previewContainer.removeView(it)
            previewKeyboard = null
        }
        previewTextEditingView?.let {
            previewContainer.removeView(it)
            previewTextEditingView = null
        }
        // The next updatePreview must rebuild, not short-circuit against a preview we removed.
        lastPreviewSignature = null
    }

    /**
     * Get preview keyboard as bitmap.
     * @return Bitmap of the preview keyboard, or null if no preview is available
     */
    fun getPreviewBitmap(): Bitmap? {
        val keyboard = previewKeyboard
        val targetView: View = if (previewContainer.width > 0 && previewContainer.height > 0) {
            previewContainer
        } else if (keyboard != null) {
            keyboard
        } else if (previewContainer.childCount > 0) {
            previewContainer
        } else {
            return null
        }
        val width = if (targetView.width > 0) targetView.width else targetView.measuredWidth
        val height = if (targetView.height > 0) targetView.height else targetView.measuredHeight
        if (width <= 0 || height <= 0) return null

        // Directly render the current view tree into bitmap
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        targetView.draw(canvas)

        return bitmap
    }

}

/**
 * Extension function to convert dp to pixels.
 */
private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
