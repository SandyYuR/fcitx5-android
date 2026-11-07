/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.stats

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.view.Gravity
import android.content.res.ColorStateList
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.*
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.stats.*
import org.fcitx.fcitx5.android.ui.common.OperationStatusView
import org.fcitx.fcitx5.android.ui.common.SettingsUiActivity
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorUi
import org.fcitx.fcitx5.android.utils.settingsColor
import java.io.File
import java.text.NumberFormat
import java.util.UUID

/** Independently implemented dashboard with shared Material colors and original input boundaries. */
class InputStatsActivity : SettingsUiActivity() {
    private val store by lazy { InputStats.get(this) }
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private var period = 0
    private var schema: String? = null
    private var settingsExpanded: Boolean? = null
    private var data: StatsSnapshot? = null
    private var today: StatsSnapshot? = null
    private var loading: Job? = null
    private var actionBusy = false
    private val periodTitles = intArrayOf(R.string.stats_today,R.string.stats_week,R.string.stats_month,R.string.stats_year,R.string.stats_all)
    private val moduleTitles = intArrayOf(R.string.stats_overview,R.string.stats_peak,R.string.stats_cloud_chars,R.string.stats_cloud_words,
        R.string.stats_cloud_mixed,R.string.stats_word_lengths,R.string.stats_code_lengths,R.string.stats_heatmap,R.string.stats_career)
    private val exportFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) action {
            val json = store.exportJson()
            withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri,"wt")!!.bufferedWriter().use { it.write(json) } }
        }
    }
    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) action {
            val json = withContext(Dispatchers.IO) {
                contentResolver.openInputStream(uri)!!.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(16384)
                    while (true) {
                        val read = input.read(buffer); if (read < 0) break
                        require(output.size()+read <= 32*1024*1024) { getString(R.string.stats_failed) }
                        output.write(buffer,0,read)
                    }
                    output.toString("UTF-8")
                }
            }
            confirm(R.string.stats_restore,getString(R.string.stats_import_confirm)) { store.importJson(json); refresh() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        period = savedInstanceState?.getInt("period",0)?.coerceIn(0,4) ?: 0
        schema = savedInstanceState?.getString("schema")
        if (savedInstanceState?.containsKey("settingsExpanded") == true) settingsExpanded=savedInstanceState.getBoolean("settingsExpanded")
        val toolbar = MaterialToolbar(this).apply { setTitle(R.string.stats_short_title); setNavigationIcon(R.drawable.ic_baseline_arrow_back_24) }
        content = column().apply { setPadding(dp(16),dp(12),dp(16),dp(24)) }
        scroll = ScrollView(this).apply { isFillViewport = true; addView(content) }
        content.addView(OperationStatusView(this).apply { show(getString(R.string.stats_loading),state=OperationStatusView.State.WORKING) })
        setContentView(column().apply {
            addView(toolbar,LinearLayout.LayoutParams(-1,-2))
            addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        })
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { store.settings.collect { if (it.loaded) refresh() } } }
    }
    override fun onResume() { super.onResume(); if (::content.isInitialized) refresh() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("period",period); outState.putString("schema",schema); super.onSaveInstanceState(outState)
        settingsExpanded?.let { outState.putBoolean("settingsExpanded",it) }
    }
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0,SHARE,0,R.string.stats_share).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        for ((id,title) in listOf(REFRESH to R.string.stats_refresh,EXPORT to R.string.stats_backup,IMPORT to R.string.stats_restore,
            CLEAR_PERIOD to R.string.stats_clear_period,CLEAR_PEAK to R.string.stats_clear_peak,CLEAR_ALL to R.string.stats_clear_all)) menu.add(0,id,id-SHARE,title)
        return true
    }
    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        for (id in SHARE..CLEAR_ALL) menu.findItem(id)?.isEnabled = !actionBusy && loading?.isActive != true && store.settings.value.loaded
        return super.onPrepareOptionsMenu(menu)
    }
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId != android.R.id.home && (actionBusy || loading?.isActive == true)) return true
        when (item.itemId) {
            android.R.id.home -> onBackPressedDispatcher.onBackPressed()
            SHARE -> chooseShare()
            REFRESH -> refresh()
            EXPORT -> exportFile.launch("input-statistics-"+StatsRange.day(System.currentTimeMillis())+".json")
            IMPORT -> MaterialAlertDialogBuilder(this).setTitle(R.string.stats_restore).setMessage(R.string.stats_restore_hint)
                .setNegativeButton(android.R.string.cancel,null).setPositiveButton(android.R.string.ok) { _,_ ->
                    importFile.launch(arrayOf("application/json","text/plain","application/octet-stream"))
                }.show()
            CLEAR_PERIOD -> {
                val selectedRange = range(); val selectedSchema = schema
                confirm(R.string.stats_clear_period,getString(R.string.stats_clear_confirm,schemaName(),getString(periodTitles[period]))) {
                    store.clear(selectedRange,selectedSchema); refresh()
                }
            }
            CLEAR_PEAK -> {
                val selectedRange = range(); val selectedSchema = schema
                confirm(R.string.stats_clear_peak,getString(R.string.stats_peak_confirm)) { store.clearPeaks(selectedRange,selectedSchema); refresh() }
            }
            CLEAR_ALL -> confirm(R.string.stats_clear_all,getString(R.string.stats_clear_all_confirm)) { store.clearAllData(); refresh() }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }
    private fun range() = when (period) { 0 -> StatsRange.today(); 1 -> StatsRange.week(); 2 -> StatsRange.month(); 3 -> StatsRange.year(); else -> StatsRange.all() }
    private fun refresh() {
        loading?.cancel()
        loading=lifecycleScope.launch {
            try {
                val expectedPeriod=period; var expectedSchema=schema
                val selectedRange=range()
                var next=store.snapshot(selectedRange,expectedSchema)
                if (period!=expectedPeriod || schema!=expectedSchema) return@launch
                if (expectedSchema!=null && next.schemas.none { it.id==expectedSchema }) {
                    schema=null; expectedSchema=null
                    next=store.snapshot(selectedRange,null)
                }
                val todayData=if (expectedPeriod==0) next else store.snapshot(StatsRange.today(),expectedSchema)
                if (period!=expectedPeriod || schema!=expectedSchema) return@launch
                data=next; today=todayData; render(next)
            } catch (error: CancellationException) { throw error } catch (error: Exception) { showError(error) }
            finally { invalidateOptionsMenu() }
        }
        invalidateOptionsMenu()
    }
    private fun render(s: StatsSnapshot) {
        val offset=scroll.scrollY
        content.removeAllViews()
        content.addView(hero(s),block())
        content.addView(StatsTypography.text(this,getString(R.string.stats_today_status,count(today?.totals?.characters?:0),count(today?.totals?.commits?:0)),StatsTypography.Role.LABEL).apply {
            setPadding(dp(4),dp(2),dp(4),dp(2))
        },block())
        val settings=store.settings.value
        val recordingControls=column().apply {
            addView(MaterialSwitch(this@InputStatsActivity).apply {
                setText(R.string.stats_record); minHeight=dp(48); isChecked=settings.enabled; isEnabled=!actionBusy && settings.loaded
                setOnCheckedChangeListener { _,checked -> action { store.setEnabled(checked); refresh() } }
            })
            addView(MaterialSwitch(this@InputStatsActivity).apply {
                setText(R.string.stats_words_record); minHeight=dp(48); isChecked=settings.wordFrequencyEnabled; isEnabled=!actionBusy && settings.loaded
                var binding=false
                setOnCheckedChangeListener { button,checked ->
                    if (!binding) {
                        if (checked) {
                            binding=true; button.isChecked=false; binding=false
                            confirm(R.string.stats_enable_words,getString(R.string.stats_enable_words_hint)) { store.setWordFrequencyEnabled(true); refresh() }
                        } else action { store.setWordFrequencyEnabled(false); refresh() }
                    }
                }
            })
            addView(text(R.string.stats_privacy),gap())
        }
        recordingControls.visibility=if (settingsExpanded ?: !settings.enabled) View.VISIBLE else View.GONE
        content.addView(EditorUi.card(this,box().apply {
            addView(EditorUi.button(this@InputStatsActivity,R.string.stats_recording_settings,tonal=true) {
                settingsExpanded=recordingControls.visibility!=View.VISIBLE
                recordingControls.visibility=if (settingsExpanded == true) View.VISIBLE else View.GONE
            },LinearLayout.LayoutParams(-1,-2))
            addView(recordingControls,gap())
        }),block())
        content.addView(EditorUi.card(this,box().apply {
            addView(ChipGroup(this@InputStatsActivity).apply {
                isSingleSelection=true; isSelectionRequired=true
                addView(chip(getString(R.string.stats_all_schemas),schema==null) { schema=null; refresh() })
                s.schemas.forEach { entry -> addView(chip(entry.name,entry.id==schema) { schema=entry.id; refresh() }) }
            })
            addView(ChipGroup(this@InputStatsActivity).apply {
                isSingleSelection=true; isSelectionRequired=true
                periodTitles.forEachIndexed { index,title -> addView(chip(getString(title),index==period) { period=index; refresh() }) }
            },gap())
        }),block())
        if (settings.error!=null || settings.rejectedEvents>0 || !settings.enabled || s.totals==StatsTotals()) {
            content.addView(OperationStatusView(this).apply {
                when {
                    settings.error!=null -> show(getString(R.string.stats_failed),settings.error,OperationStatusView.State.ERROR)
                    settings.rejectedEvents>0 -> show(getString(R.string.stats_failed),getString(R.string.stats_overflow,settings.rejectedEvents),OperationStatusView.State.ERROR)
                    !settings.enabled -> show(getString(R.string.stats_disabled))
                    else -> show(getString(R.string.stats_empty))
                }
            },block())
        }
        modules(s).take(8).forEach { content.addView(it,block()) }
        if (s.daily.size>1) content.addView(section(R.string.stats_trend,StatsChartView(this).apply {
            emptyText=getString(R.string.stats_empty); setDistribution(StatsDailySeries.build(s.daily,period))
        }),block())
        content.addView(text(R.string.stats_scope_hint),block())
        content.addView(text(R.string.stats_reference).apply {
            minimumHeight=dp(48)
            setTextColor(settingsColor(androidx.appcompat.R.attr.colorPrimary))
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://github.com/qxm888/trime-mie")))
            }
        },block())
        scroll.post { scroll.scrollTo(0,offset) }
        invalidateOptionsMenu()
    }
    private fun modules(s: StatsSnapshot, sharedImage: Boolean = false): List<View> = listOf(
        section(R.string.stats_overview,metrics(listOf(R.string.stats_commits to count(s.totals.commits),
            R.string.stats_characters to count(s.totals.characters),R.string.stats_keys to count(s.totals.keys),R.string.stats_corrections to percent(s.totals.deletionRatio)))),
        section(R.string.stats_speed_section,column().apply {
            addView(StatsMetricGrid(this@InputStatsActivity,2).apply {
                addView(speedMetric(R.string.stats_peak,s.totals.peakCpm,
                    getString(R.string.stats_kps,decimal(s.totals.peakKps))+" · "+getString(R.string.stats_code_rate,decimal(s.totals.peakCodeLength))))
                addView(speedMetric(R.string.stats_average,s.totals.averageCpm,getString(R.string.stats_code_rate,decimal(s.totals.averageCodeLength))))
            },LinearLayout.LayoutParams(-1,-2))
            addView(StatsTypography.text(this@InputStatsActivity,getString(R.string.stats_active,duration(s.totals.activeMillis)),StatsTypography.Role.LABEL),gap(16))
        }),
        cloud(R.string.stats_cloud_chars,s.words.filterKeys { it.codePointCount(0,it.length)==1 }),
        cloud(R.string.stats_cloud_words,s.words.filterKeys { it.codePointCount(0,it.length)>1 }),
        cloud(R.string.stats_cloud_mixed,s.words),
        section(R.string.stats_word_lengths,StatsChartView(this).apply {
            emptyText=getString(R.string.stats_empty); setDistribution(s.wordLengths.toSortedMap().mapKeys { it.key.toString() })
        }),
        section(R.string.stats_code_lengths,StatsChartView(this).apply {
            emptyText=getString(R.string.stats_empty); setDistribution(s.codeLengths.toSortedMap().mapKeys { it.key.toString() })
        }),
        heatmap(s,sharedImage),hero(s)
    )
    private fun hero(s: StatsSnapshot): View {
        val level=StatsPresentation.level(s.careerCharacters)
        val current=today?.totals?:s.totals
        return EditorUi.card(this,box().apply {
            setPadding(dp(20),dp(20),dp(20),dp(20))
            addView(LinearLayout(this@InputStatsActivity).apply {
                gravity=Gravity.CENTER_VERTICAL
                addView(StatsTypography.text(this@InputStatsActivity,resources.getStringArray(R.array.stats_realm_names)[StatsPresentation.realm(s.careerCharacters)],StatsTypography.Role.LABEL).apply {
                    setPadding(dp(10),dp(5),dp(10),dp(5))
                    background=MaterialShapeDrawable(ShapeAppearanceModel.builder().setAllCornerSizes(dp(16).toFloat()).build()).apply {
                        fillColor=ColorStateList.valueOf(settingsColor(com.google.android.material.R.attr.colorSurfaceBright))
                    }
                },LinearLayout.LayoutParams(-2,-2))
                addView(StatsTypography.text(this@InputStatsActivity,getString(R.string.stats_career),StatsTypography.Role.LABEL).apply {
                    gravity=Gravity.END
                },LinearLayout.LayoutParams(0,-2,1f).apply { marginStart=dp(12) })
            },LinearLayout.LayoutParams(-1,-2))
            addView(StatsTypography.text(this@InputStatsActivity,resources.getStringArray(R.array.stats_level_names)[level.number-1],StatsTypography.Role.HERO),gap(14))
            addView(StatsTypography.text(this@InputStatsActivity,getString(R.string.stats_level_number,level.number),StatsTypography.Role.LABEL),gap(6))
            addView(LinearProgressIndicator(this@InputStatsActivity).apply {
                max=1000
                isIndeterminate=false
                trackThickness=dp(4)
                trackCornerRadius=dp(2)
                setIndicatorColor(settingsColor(androidx.appcompat.R.attr.colorPrimary))
                trackColor=settingsColor(com.google.android.material.R.attr.colorSurfaceContainerHighest)
                setProgressCompat((level.progress*1000).toInt(),false)
            },gap(14))
            addView(StatsTypography.text(this@InputStatsActivity,getString(R.string.stats_next,count(level.remaining)),StatsTypography.Role.LABEL),gap(6))
            addView(metrics(listOf(R.string.stats_career_chars to count(s.careerCharacters),
                R.string.stats_speed_rating to if (current.activeMillis==0L) "—" else StatsPresentation.speedRating(current.averageCpm),
                R.string.stats_accuracy to if (current.keys==0L) "—" else StatsPresentation.accuracyRating(current.deletionRatio))),gap(20))
        }).apply {
            setCardBackgroundColor(settingsColor(com.google.android.material.R.attr.colorSecondaryContainer))
            fun applyForeground(view: View) {
                if (view is MaterialTextView) view.setTextColor(settingsColor(com.google.android.material.R.attr.colorOnSecondaryContainer))
                if (view is ViewGroup) repeat(view.childCount) { applyForeground(view.getChildAt(it)) }
            }
            applyForeground(this)
        }
    }
    private fun cloud(title: Int,words: Map<String,Long>) = section(title,column().apply {
        val description=when(title) {
            R.string.stats_cloud_chars -> R.string.stats_cloud_chars_description
            R.string.stats_cloud_words -> R.string.stats_cloud_words_description
            else -> R.string.stats_cloud_mixed_description
        }
        addView(StatsTypography.text(this@InputStatsActivity,getString(description),StatsTypography.Role.LABEL))
        addView(WordCloudView(this@InputStatsActivity).apply {
            emptyText=getString(R.string.stats_cloud_empty); accessibilityTitle=getString(title); setWords(words)
        },LinearLayout.LayoutParams(-1,dp(280)))
    })
    private fun heatmap(s: StatsSnapshot,sharedImage: Boolean): View {
        val chart=StatsChartView(this).apply {
            emptyText=getString(R.string.stats_heatmap_empty)
            val rows=s.layout?.geometry?.groupBy { it.y }?.toSortedMap()?.values?.map { row ->
                row.sortedBy { it.x }.map { key -> StatsChartView.HeatKey(key.label,key.label,key.x,key.width,key.height) }
            }.orEmpty()
            setKeyboardHeatmap(s.keyHeatmap,rows)
        }
        val hint=StatsTypography.text(this,getString(if (sharedImage) R.string.stats_heatmap_shared_bars
            else R.string.stats_heatmap_scroll),StatsTypography.Role.LABEL)
        return section(R.string.stats_heatmap,StatsHeatmapContent(this,chart,hint,sharedImage))
    }
    private fun metrics(values: List<Pair<Int,String>>): View = StatsMetricGrid(this,values.size.coerceAtMost(4)).apply {
        values.forEach { (label,value) -> addView(column().apply {
            gravity=Gravity.CENTER_HORIZONTAL
            addView(StatsTypography.value(this@InputStatsActivity,value).apply { gravity=Gravity.CENTER },LinearLayout.LayoutParams(-1,-2))
            addView(StatsTypography.text(this@InputStatsActivity,getString(label),StatsTypography.Role.LABEL).apply { gravity=Gravity.CENTER },gap(6))
        }) }
    }
    private fun speedMetric(label: Int,cpm: Double,detail: String) = column().apply {
        addView(StatsTypography.text(this@InputStatsActivity,getString(label),StatsTypography.Role.LABEL))
        addView(StatsTypography.value(this@InputStatsActivity,decimal(cpm),StatsTypography.Role.SPEED,
            settingsColor(androidx.appcompat.R.attr.colorPrimary)),gap(8))
        addView(StatsTypography.text(this@InputStatsActivity,getString(R.string.stats_cpm_unit),StatsTypography.Role.LABEL),gap(4))
        addView(StatsTypography.text(this@InputStatsActivity,detail,StatsTypography.Role.LABEL),gap(10))
    }
    private fun chooseShare() {
        val snapshot=data?:return
        val scopeLabel = getString(R.string.stats_share_scope,schemaName(),getString(periodTitles[period]))
        val textReport = report(snapshot)
        val prefs=getSharedPreferences("input-stats-share",MODE_PRIVATE)
        val chosen=prefs.getStringSet("modules",setOf("0","2","3","7"))!!.toMutableSet()
        MaterialAlertDialogBuilder(this).setTitle(R.string.stats_share)
            .setMultiChoiceItems(moduleTitles.map(::getString).toTypedArray(),moduleTitles.indices.map { it.toString() in chosen }.toBooleanArray()) { _,i,checked ->
                if (checked) chosen+=i.toString() else chosen-=i.toString()
            }.setNegativeButton(android.R.string.cancel,null)
            .setNeutralButton(R.string.stats_share_text) { _,_ -> action {
                checkShareSnapshot(snapshot)
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND)
                    .setType("text/plain").putExtra(Intent.EXTRA_TEXT,textReport),getString(R.string.stats_share)))
            } }
            .setPositiveButton(R.string.stats_share_image) { _,_ -> if (chosen.isNotEmpty()) {
                prefs.edit().putStringSet("modules",chosen).apply(); action { shareImage(snapshot,chosen,scopeLabel) }
            } }.show()
    }
    private fun checkShareSnapshot(s: StatsSnapshot) {
        if (s.recordingEpoch != store.recordingEpoch) throw CancellationException("Statistics changed before sharing")
    }
    private suspend fun shareImage(s: StatsSnapshot,chosen: Set<String>,scopeLabel: String) {
        checkShareSnapshot(s)
        val page=column().apply {
            setPadding(dp(16),dp(20),dp(16),dp(20)); setBackgroundColor(settingsColor(com.google.android.material.R.attr.colorSurfaceContainer))
            addView(text(R.string.stats_title,true),block())
            addView(text(scopeLabel),block())
            modules(s,sharedImage=true).forEachIndexed { index,view -> if (index.toString() in chosen) addView(view,block()) }
        }
        page.layoutDirection=View.LAYOUT_DIRECTION_LTR
        val width=measureStatsSharePage(page,scroll.width)
        require(page.measuredHeight in 1..StatsImageRenderer.MAX_HEIGHT) { getString(R.string.stats_failed) }
        page.layout(0,0,width,page.measuredHeight)
        val folder = File(cacheDir,"shared/input-statistics")
        withContext(Dispatchers.IO) { StatsImageRenderer.prune(folder) }
        val file = File(folder,UUID.randomUUID().toString()+".png")
        try {
            StatsImageRenderer.render(page,file) { checkShareSnapshot(s) }
            checkShareSnapshot(s)
            val uri=FileProvider.getUriForFile(this,"$packageName.share.fileprovider",file)
            checkShareSnapshot(s)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM,uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData=ClipData.newUri(contentResolver,getString(R.string.stats_title),uri) },
                getString(R.string.stats_share)))
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { file.delete() }
            throw error
        }
    }
    private fun report(s: StatsSnapshot)=buildString {
        append(getString(R.string.stats_title)).append('\n').append(schemaName()).append(" · ").append(getString(periodTitles[period])).append('\n')
        for ((label,value) in listOf(R.string.stats_characters to count(s.totals.characters),R.string.stats_commits to count(s.totals.commits),
            R.string.stats_keys to count(s.totals.keys),R.string.stats_corrections to percent(s.totals.deletionRatio),
            R.string.stats_peak to getString(R.string.stats_cpm,decimal(s.totals.peakCpm)),R.string.stats_average to getString(R.string.stats_cpm,decimal(s.totals.averageCpm)),
            R.string.stats_career_chars to count(s.careerCharacters))) append(getString(label)).append(": ").append(value).append('\n')
    }
    private fun confirm(title: Int,message: String,block: suspend () -> Unit) =
        MaterialAlertDialogBuilder(this).setTitle(title).setMessage(message).setNegativeButton(android.R.string.cancel,null)
            .setPositiveButton(android.R.string.ok) { _,_ -> action(block) }.show()
    private fun action(block: suspend () -> Unit) {
        if (actionBusy) return
        actionBusy=true; updateActionControls(); invalidateOptionsMenu()
        lifecycleScope.launch {
            try { block() } catch (error: CancellationException) { throw error } catch (error: Exception) { showError(error) }
            finally { actionBusy=false; updateActionControls(); invalidateOptionsMenu() }
        }
    }
    private fun updateActionControls() {
        fun update(view: View) {
            if (view is Chip || view is MaterialSwitch) view.isEnabled = !actionBusy && store.settings.value.loaded
            if (view is ViewGroup) repeat(view.childCount) { update(view.getChildAt(it)) }
        }
        update(content)
    }
    private fun showError(error: Exception) { MaterialAlertDialogBuilder(this).setTitle(R.string.stats_failed)
        .setMessage(error.message?:error.javaClass.simpleName).setPositiveButton(android.R.string.ok,null).show() }
    private fun schemaName()=data?.schemas?.firstOrNull { it.id==schema }?.name?:getString(R.string.stats_all_schemas)
    private fun chip(label: String,checked: Boolean,click: () -> Unit)=Chip(this).apply {
        id=View.generateViewId(); text=label; isCheckable=true; isChecked=checked; minHeight=dp(48)
        isEnabled=!actionBusy && store.settings.value.loaded
        setOnClickListener { if (isChecked && !actionBusy) click() }
    }
    private fun section(title: Int,view: View)=EditorUi.card(this,box().apply {
        addView(text(title,true),block(8)); addView(view,view.layoutParams?:LinearLayout.LayoutParams(-1,-2))
    })
    private fun column()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    private fun box()=column().apply { setPadding(dp(16),dp(16),dp(16),dp(16)) }
    private fun text(label: Int,title: Boolean=false)=text(getString(label),title)
    private fun text(value: String,title: Boolean=false)=MaterialTextView(this).apply {
        text=value
        setTextAppearance(if (title) com.google.android.material.R.style.TextAppearance_Material3_TitleMedium else com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        if (title) typeface=android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.BOLD)
        setTextColor(settingsColor(if (title) com.google.android.material.R.attr.colorOnSurface else com.google.android.material.R.attr.colorOnSurfaceVariant))
    }
    private fun block(bottom: Int=12)=LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(bottom) }
    private fun gap(top: Int=8)=LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(top) }
    private fun count(value: Long)=NumberFormat.getNumberInstance().format(value)
    private fun decimal(value: Double)=String.format(java.util.Locale.getDefault(),"%.1f",value)
    private fun percent(value: Double)=NumberFormat.getPercentInstance().apply { maximumFractionDigits=1 }.format(value.coerceIn(0.0,1.0))
    private fun duration(value: Long)=String.format(java.util.Locale.getDefault(),"%d:%02d",value/60000,value/1000%60)
    private fun dp(value: Int)=(value*resources.displayMetrics.density).toInt()
    private companion object { const val SHARE=0x535401; const val REFRESH=SHARE+1; const val EXPORT=SHARE+2; const val IMPORT=SHARE+3; const val CLEAR_PERIOD=SHARE+4; const val CLEAR_PEAK=SHARE+5; const val CLEAR_ALL=SHARE+6 }
}

/** Measure the same fixed-width tree that will be painted into the PNG. */
internal fun measureStatsSharePage(page: ViewGroup, preferredWidth: Int): Int {
    var minimumWidth = 0L
    fun visit(view: View) {
        if (view is StatsChartView && view.preferredMinimumWidth > 0) {
            var required = view.preferredMinimumWidth.toLong()
            var child: View = view
            while (child !== page) {
                (child.layoutParams as? ViewGroup.MarginLayoutParams)?.let { required += it.leftMargin + it.rightMargin }
                val parent = child.parent as? ViewGroup ?: break
                required += parent.paddingLeft + parent.paddingRight
                child = parent
            }
            minimumWidth = maxOf(minimumWidth, required)
        }
        if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
    }
    visit(page)
    val width = maxOf(preferredWidth.coerceAtLeast(240).toLong(), minimumWidth)
        .coerceAtMost(StatsImageRenderer.MAX_WIDTH.toLong()).toInt()
    page.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
    return width
}

internal object StatsPresentation {
    private val thresholds=longArrayOf(0,100,300,600,1000,2000,4000,7000,12000,20000,35000,55000,80000,120000,180000,280000,420000,620000,900000,1300000)
    data class Level(val number: Int,val progress: Float,val remaining: Long)
    fun realm(total: Long) = longArrayOf(0,10000,50000,200000,1000000,5000000,10000000).indexOfLast { total >= it }.coerceAtLeast(0)
    fun level(total: Long): Level {
        val index=thresholds.indexOfLast { total>=it }.coerceAtLeast(0)
        val next=thresholds.getOrNull(index+1)?:1_800_000L
        return Level(index+1,((total-thresholds[index]).toDouble()/(next-thresholds[index])).coerceIn(0.0,1.0).toFloat(),(next-total).coerceAtLeast(0))
    }
    fun speedRating(cpm: Double)=listOf(80.0 to "S+",60.0 to "S",45.0 to "A+",35.0 to "A",25.0 to "B+",18.0 to "B").firstOrNull { cpm>=it.first }?.second?:"C"
    fun accuracyRating(ratio: Double)=listOf(.01 to "S+",.03 to "S",.06 to "A+",.10 to "A",.15 to "B+",.22 to "B").firstOrNull { ratio.coerceAtLeast(0.0)<=it.first }?.second?:"C"
}
