/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.update

import android.app.DownloadManager
import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.ActivityNotFoundException
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor.AutoCloseInputStream
import android.text.TextUtils
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import android.widget.Toast
import android.provider.OpenableColumns
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.utils.Const
import splitties.dimensions.dp
import splitties.resources.styledColor
import splitties.views.backgroundColor
import timber.log.Timber
import java.io.FileInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.regex.PatternSyntaxException
import kotlin.coroutines.coroutineContext

class UpdateCheckActivity : AppCompatActivity() {
    private lateinit var toolbar: Toolbar
    private lateinit var mirrorsGroup: ChipGroup
    private lateinit var latestVersionText: TextView
    private lateinit var assetsView: RecyclerView
    private lateinit var adapter: UpdateAssetAdapter
    private lateinit var downloadManager: DownloadManager

    private val downloadStates = linkedMapOf<String, AssetUiState>()
    private val manualDownloadJobs = mutableMapOf<String, Job>()
    private var selectedMirrorId: String? = null
    private var mirrors: List<MirrorRule> = emptyList()

    // Per-download speed sampling for the DownloadManager polling path:
    // actual elapsed time + EMA smoothing, so the displayed rate doesn't
    // jitter between 0 and huge bursts as DownloadManager batches its
    // progress writes.
    private data class SpeedSample(val timeMs: Long, val bytes: Long, val smoothed: Long)
    private val speedSamples = mutableMapOf<String, SpeedSample>()

    private val pollHandler = Handler(Looper.getMainLooper())
    private var polling = false

    private val mirrorEditorLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            reloadMirrors()
        }

    private val sharedReadPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                refreshLatestRelease()
            }
        }

    private var pendingLegacyDownloadState: AssetUiState? = null

    private val legacyStoragePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val pending = pendingLegacyDownloadState
            pendingLegacyDownloadState = null
            if (granted && pending != null) {
                beginDownload(pending)
            } else if (pending != null) {
                Toast.makeText(
                    this,
                    getString(R.string.update_storage_permission_denied),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    private val pollRunnable = object : Runnable {
        override fun run() {
            pollDownloadProgress()
            if (polling) {
                pollHandler.postDelayed(this, 800L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_update_check)
        toolbar = findViewById(R.id.toolbar)
        toolbar.backgroundColor = styledColor(android.R.attr.colorPrimary)
        toolbar.elevation = dp(4f)
        mirrorsGroup = findViewById(R.id.mirrorChipGroup)
        latestVersionText = findViewById(R.id.latestVersionText)
        assetsView = findViewById(R.id.assetsRecycler)
        downloadManager = getSystemService(DownloadManager::class.java)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.update_page_title)
        val toolbarBaseTopPadding = toolbar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, insets ->
            val statusTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.updatePadding(top = toolbarBaseTopPadding + statusTop)
            insets
        }
        ViewCompat.requestApplyInsets(toolbar)

        adapter = UpdateAssetAdapter(
            onItemClick = { onAssetClicked(it) },
            onActionClick = { onAssetActionClicked(it) }
        )
        assetsView.layoutManager = LinearLayoutManager(this)
        assetsView.adapter = adapter

        if (requiresSharedReadPermission() && !hasSharedReadPermission()) {
            sharedReadPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        reloadMirrors()
        refreshLatestRelease()
    }

    override fun onResume() {
        super.onResume()
        validateFinishedDownloads()
        pollDownloadProgress()
    }

    override fun onDestroy() {
        stopPolling()
        manualDownloadJobs.values.toList().forEach { it.cancel() }
        manualDownloadJobs.clear()
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_update_check, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_update_hosts -> {
                startActivity(Intent(this, UpdateHostsSettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun refreshLatestRelease() {
        latestVersionText.text = getString(R.string.update_checking)
        lifecycleScope.launch {
            try {
                val release = UpdateRepository.fetchLatestRelease(this@UpdateCheckActivity)
                val versionFallback = release.assets.firstOrNull()?.name ?: release.releaseName
                val newBadge = if (UpdateRepository.isNewerVersion(
                        release.tagName,
                        Const.versionName,
                        release.publishedAt,
                        BuildConfig.BUILD_TIME,
                        versionFallback
                    )) {
                    getString(R.string.update_new_version_badge)
                } else {
                    ""
                }
                // Extract version string from asset name (e.g. "0.1.2-352-g6f8634b9" or "latest-352-g6f8634b9")
                val versionFromAsset = versionFallback.let { name ->
                    Regex("""([\w.]+-\d+-g[0-9a-fA-F]+)""").find(name)?.value
                }
                val displayVersion = versionFromAsset
                    ?: release.tagName.ifBlank { release.releaseName }
                latestVersionText.text = getString(
                    R.string.update_latest_version_line,
                    displayVersion,
                    newBadge
                ).trim()
                val notes = if (release.releaseBody.isBlank()) {
                    getString(R.string.update_release_notes_empty)
                } else {
                    renderReleaseNotes(release.releaseBody.trim())
                }
                val list = withContext(Dispatchers.IO) {
                    release.assets.map { asset ->
                        detectExistingCompletedState(asset)
                            ?: downloadStates[asset.browserDownloadUrl]
                            ?: AssetUiState(asset = asset)
                    }
                }
                downloadStates.clear()
                list.forEach { downloadStates[it.asset.browserDownloadUrl] = it }
                adapter.submitList(list)
                adapter.setReleaseNotes(notes)
            } catch (t: Throwable) {
                Timber.w(t, "Failed to fetch release")
                latestVersionText.text = getString(R.string.update_failed_to_fetch)
                adapter.setReleaseNotes("")
                Toast.makeText(
                    this@UpdateCheckActivity,
                    getString(R.string.update_failed_to_fetch),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun reloadMirrors() {
        mirrors = UpdatePrefs.loadMirrorRules(this)
        selectedMirrorId = UpdatePrefs.loadSelectedMirrorId(this)
        if (selectedMirrorId != null && mirrors.none { it.id == selectedMirrorId }) {
            selectedMirrorId = null
            UpdatePrefs.saveSelectedMirrorId(this, null)
        }
        renderMirrorChips()
    }

    private fun renderMirrorChips() {
        mirrorsGroup.removeAllViews()
        val originalChip = Chip(this).apply {
            text = getString(R.string.update_mirror_original)
            isCheckable = true
            isChecked = selectedMirrorId == null
            setOnClickListener {
                selectedMirrorId = null
                UpdatePrefs.saveSelectedMirrorId(this@UpdateCheckActivity, null)
                renderMirrorChips()
            }
        }
        mirrorsGroup.addView(originalChip)
        mirrors.forEach { mirror ->
            val chip = Chip(this).apply {
                text = mirror.name
                isCheckable = true
                isChecked = selectedMirrorId == mirror.id
                setOnClickListener {
                    selectedMirrorId = if (selectedMirrorId == mirror.id) null else mirror.id
                    UpdatePrefs.saveSelectedMirrorId(this@UpdateCheckActivity, selectedMirrorId)
                    renderMirrorChips()
                }
                setOnLongClickListener {
                    openMirrorEditor(mirror.id)
                    true
                }
            }
            mirrorsGroup.addView(chip)
        }
        mirrorsGroup.addView(Chip(this).apply {
            text = "+"
            isCheckable = false
            setOnClickListener { openMirrorEditor(null) }
        })
    }

    private fun openMirrorEditor(mirrorId: String?) {
        mirrorEditorLauncher.launch(
            Intent(this, UpdateMirrorEditActivity::class.java).apply {
                mirrorId?.let { putExtra(UpdateMirrorEditActivity.EXTRA_MIRROR_ID, it) }
            }
        )
    }

    private fun selectedMirror(): MirrorRule? {
        return mirrors.firstOrNull { it.id == selectedMirrorId }
    }

    private fun detectExistingCompletedState(asset: ReleaseAsset): AssetUiState? {
        val hit = findLatestSuccessfulDownload(asset)
            ?: findLatestMediaStoreDownload(asset)
            ?: findLatestLegacyDownloadFile(asset)
            ?: return null
        return AssetUiState(
            asset = asset,
            status = AssetStatus.FINISHED,
            progressPercent = 100,
            downloadedBytes = hit.sizeBytes,
            totalBytes = hit.sizeBytes,
            speedBytesPerSec = 0L,
            downloadId = hit.id,
            installUri = hit.uri
        )
    }

    private data class DownloadHit(
        val id: Long?,
        val uri: Uri,
        val localUri: Uri,
        val sizeBytes: Long,
        val lastModified: Long
    )

    private fun findLatestSuccessfulDownload(asset: ReleaseAsset): DownloadHit? {
        val query = DownloadManager.Query().setFilterByStatus(DownloadManager.STATUS_SUCCESSFUL)
        val cursor = downloadManager.query(query)
        cursor.use { c ->
            val idIdx = c.getColumnIndex(DownloadManager.COLUMN_ID)
            val titleIdx = c.getColumnIndex(DownloadManager.COLUMN_TITLE)
            val localUriIdx = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
            val sizeIdx = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val modifiedIdx = c.getColumnIndex(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)
            var best: DownloadHit? = null
            var bestTs = Long.MIN_VALUE
            while (c.moveToNext()) {
                if (titleIdx < 0 || idIdx < 0 || localUriIdx < 0) continue
                val title = c.getString(titleIdx).orEmpty()
                val localUriRaw = c.getString(localUriIdx).orEmpty()
                if (localUriRaw.isBlank()) continue
                val id = c.getLong(idIdx)
                val installUri = downloadManager.getUriForDownloadedFile(id) ?: continue
                val localUri = runCatching { Uri.parse(localUriRaw) }.getOrNull() ?: continue
                val displayName = extractDisplayName(localUri, title)
                if (!nameMatchesAsset(displayName, asset.name)) continue
                val size = detectUriSize(installUri, sizeIdx.takeIf { it >= 0 }?.let { c.getLong(it) } ?: 0L)
                if (asset.sizeBytes > 0 && size != asset.sizeBytes) continue
                if (!hashMatchesAsset(id, installUri, asset)) continue
                val ts = if (modifiedIdx >= 0) c.getLong(modifiedIdx) else 0L
                if (best == null || ts >= bestTs) {
                    best = DownloadHit(
                        id = id,
                        uri = installUri,
                        localUri = localUri,
                        sizeBytes = if (size > 0) size else asset.sizeBytes,
                        lastModified = ts
                    )
                    bestTs = ts
                }
            }
            return best
        }
    }

    private fun findLatestLegacyDownloadFile(asset: ReleaseAsset): DownloadHit? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return null
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val candidates = downloadsDir.listFiles()?.asSequence().orEmpty()
            .filter { it.isFile && nameMatchesAsset(it.name, asset.name) }
            .sortedByDescending { it.lastModified() }
        for (file in candidates) {
            val uri = Uri.fromFile(file)
            val size = file.length()
            if (asset.sizeBytes > 0 && size != asset.sizeBytes) continue
            if (!hashMatchesAsset(null, uri, asset)) continue
            return DownloadHit(
                id = null,
                uri = uri,
                localUri = uri,
                sizeBytes = size,
                lastModified = file.lastModified()
            )
        }
        return null
    }

    private fun findLatestMediaStoreDownload(asset: ReleaseAsset): DownloadHit? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val base = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.SIZE,
            MediaStore.Downloads.DATE_MODIFIED
        )
        contentResolver.query(base, projection, null, null, null)?.use { c ->
            val idIdx = c.getColumnIndex(MediaStore.Downloads._ID)
            val nameIdx = c.getColumnIndex(MediaStore.Downloads.DISPLAY_NAME)
            val sizeIdx = c.getColumnIndex(MediaStore.Downloads.SIZE)
            val modifiedIdx = c.getColumnIndex(MediaStore.Downloads.DATE_MODIFIED)
            var best: DownloadHit? = null
            var bestTs = Long.MIN_VALUE
            while (c.moveToNext()) {
                if (idIdx < 0 || nameIdx < 0) continue
                val id = c.getLong(idIdx)
                val uri = ContentUris.withAppendedId(base, id)
                val name = c.getString(nameIdx).orEmpty()
                if (!nameMatchesAsset(name, asset.name)) continue
                val size = if (sizeIdx >= 0) c.getLong(sizeIdx) else 0L
                if (asset.sizeBytes > 0 && size > 0 && size != asset.sizeBytes) continue
                if (!hashMatchesAsset(null, uri, asset)) continue
                val ts = if (modifiedIdx >= 0) c.getLong(modifiedIdx) else 0L
                if (best == null || ts >= bestTs) {
                    best = DownloadHit(
                        id = null,
                        uri = uri,
                        localUri = uri,
                        sizeBytes = if (size > 0) size else asset.sizeBytes,
                        lastModified = ts
                    )
                    bestTs = ts
                }
            }
            return best
        }
        return null
    }

    private fun onAssetClicked(state: AssetUiState) {
        when (state.status) {
            AssetStatus.IDLE, AssetStatus.FAILED, AssetStatus.CANCELED -> startDownload(state)
            AssetStatus.FINISHED -> launchInstall(state)
            AssetStatus.DOWNLOADING -> Unit
        }
    }

    private fun onAssetActionClicked(state: AssetUiState) {
        when (state.status) {
            AssetStatus.DOWNLOADING -> cancelDownload(state)
            AssetStatus.FINISHED -> launchInstall(state)
            else -> Unit
        }
    }

    private fun startDownload(state: AssetUiState) {
        if (requiresLegacyWritePermission() && !hasLegacyWritePermission()) {
            pendingLegacyDownloadState = state
            legacyStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        beginDownload(state)
    }

    private fun showUrlDialog(title: String, url: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(url)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun beginDownload(state: AssetUiState) {
        val mirror = selectedMirror()
        val downloadUrl = try {
            UpdateRepository.applyMirror(state.asset.browserDownloadUrl, mirror)
        } catch (_: PatternSyntaxException) {
            Toast.makeText(this, getString(R.string.update_mirror_rule_invalid), Toast.LENGTH_SHORT).show()
            return
        }
        if (shouldUseHostsMappedDirectDownload(mirror)) {
            startHostsMappedDirectDownload(state, downloadUrl)
            return
        }
        try {
            val request = DownloadManager.Request(Uri.parse(downloadUrl))
                .setTitle(state.asset.name)
                .setDescription(getString(R.string.update_downloading))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, state.asset.name)
            val id = downloadManager.enqueue(request)
            speedSamples.remove(state.asset.browserDownloadUrl)
            val updated = state.copy(
                status = AssetStatus.DOWNLOADING,
                progressPercent = 0,
                downloadedBytes = 0L,
                speedBytesPerSec = 0L,
                downloadId = id,
                installUri = null,
                error = null
            )
            downloadStates[state.asset.browserDownloadUrl] = updated
            adapter.submitList(downloadStates.values.toList())
            startPolling()
        } catch (e: IllegalArgumentException) {
            showUrlDialog(getString(R.string.update_invalid_download_url), downloadUrl)
        } catch (e: Exception) {
            showUrlDialog(getString(R.string.update_failed_to_fetch), downloadUrl)
        }
    }

    private fun requiresLegacyWritePermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
    }

    private fun hasLegacyWritePermission(): Boolean {
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun requiresSharedReadPermission(): Boolean {
        return Build.VERSION.SDK_INT in Build.VERSION_CODES.Q..32
    }

    private fun hasSharedReadPermission(): Boolean {
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun shouldUseHostsMappedDirectDownload(mirror: MirrorRule?): Boolean {
        return mirror == null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            UpdateRepository.isHostsEnabled(this)
    }

    private fun startHostsMappedDirectDownload(state: AssetUiState, downloadUrl: String) {
        val key = state.asset.browserDownloadUrl
        manualDownloadJobs[key]?.cancel()
        downloadStates[key] = state.copy(
            status = AssetStatus.DOWNLOADING,
            progressPercent = 0,
            downloadedBytes = 0L,
            speedBytesPerSec = 0L,
            downloadId = null,
            installUri = null,
            error = null
        )
        adapter.submitList(downloadStates.values.toList())
        startPolling()
        val job = lifecycleScope.launch(Dispatchers.IO) {
            var targetUri: Uri? = null
            try {
                val outUri = createPendingDownloadUri(state.asset.name)
                    ?: throw IllegalStateException("Failed to create download target")
                targetUri = outUri
                val request = Request.Builder()
                    .url(downloadUrl)
                    .addHeader("User-Agent", "fcitx5-android-update")
                    .build()
                val client = UpdateRepository.httpClientWithConfiguredHosts(this@UpdateCheckActivity)
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("Download request failed: ${response.code}")
                    }
                    val body = response.body ?: throw IllegalStateException("Download body is empty")
                    val total = body.contentLength().takeIf { it > 0 } ?: state.asset.sizeBytes
                    contentResolver.openOutputStream(outUri, "w")?.use { out ->
                        body.byteStream().use { input ->
                            val buf = ByteArray(8 * 1024)
                            var downloaded = 0L
                            var lastBytes = 0L
                            var lastAt = System.currentTimeMillis()
                            var smoothedSpeed = 0L
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                downloaded += n
                                val now = System.currentTimeMillis()
                                if (now - lastAt >= 750L) {
                                    val dt = (now - lastAt).coerceAtLeast(1L)
                                    val instant = ((downloaded - lastBytes).coerceAtLeast(0L) * 1000L) / dt
                                    // EMA with alpha = 0.4; seed with the first
                                    // instant value so the bar isn't stuck at 0.
                                    smoothedSpeed = if (smoothedSpeed == 0L) instant
                                        else (smoothedSpeed * 6 + instant * 4) / 10
                                    val progress = if (total > 0L) {
                                        ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                                    } else 0
                                    val displayed = smoothedSpeed
                                    withContext(Dispatchers.Main) {
                                        val current = downloadStates[key] ?: return@withContext
                                        downloadStates[key] = current.copy(
                                            status = AssetStatus.DOWNLOADING,
                                            progressPercent = progress,
                                            downloadedBytes = downloaded,
                                            totalBytes = if (total > 0L) total else current.totalBytes,
                                            speedBytesPerSec = displayed,
                                            downloadId = null
                                        )
                                        adapter.submitList(downloadStates.values.toList())
                                    }
                                    lastAt = now
                                    lastBytes = downloaded
                                }
                            }
                        }
                    } ?: throw IllegalStateException("Failed to open target output stream")
                    markPendingDownloadComplete(outUri)
                    val size = detectUriSize(outUri, total).coerceAtLeast(0L)
                    val downloadedTotal = if (size > 0L) size else total.coerceAtLeast(0L)
                    withContext(Dispatchers.Main) {
                        val current = downloadStates[key] ?: return@withContext
                        downloadStates[key] = current.copy(
                            status = AssetStatus.FINISHED,
                            progressPercent = 100,
                            downloadedBytes = downloadedTotal,
                            totalBytes = downloadedTotal,
                            speedBytesPerSec = 0L,
                            downloadId = null,
                            installUri = outUri,
                            error = null
                        )
                        adapter.submitList(downloadStates.values.toList())
                    }
                }
            } catch (_: CancellationException) {
                targetUri?.let { deletePendingDownload(it) }
                withContext(Dispatchers.Main) {
                    val current = downloadStates[key] ?: return@withContext
                    downloadStates[key] = current.copy(
                        status = AssetStatus.CANCELED,
                        speedBytesPerSec = 0L,
                        downloadId = null
                    )
                    adapter.submitList(downloadStates.values.toList())
                }
            } catch (t: Throwable) {
                Timber.w(t, "Hosts-mapped direct download failed")
                targetUri?.let { deletePendingDownload(it) }
                withContext(Dispatchers.Main) {
                    val current = downloadStates[key] ?: return@withContext
                    downloadStates[key] = current.copy(
                        status = AssetStatus.FAILED,
                        speedBytesPerSec = 0L,
                        downloadId = null,
                        error = getString(R.string.update_asset_failed)
                    )
                    adapter.submitList(downloadStates.values.toList())
                }
            } finally {
                withContext(Dispatchers.Main) {
                    manualDownloadJobs.remove(key)
                }
            }
        }
        manualDownloadJobs[key] = job
    }

    private fun cancelDownload(state: AssetUiState) {
        val key = state.asset.browserDownloadUrl
        val id = state.downloadId
        if (id != null) {
            downloadManager.remove(id)
        } else {
            manualDownloadJobs.remove(key)?.cancel()
        }
        speedSamples.remove(key)
        downloadStates[key] = state.copy(
            status = AssetStatus.CANCELED,
            speedBytesPerSec = 0L,
            downloadId = null
        )
        adapter.submitList(downloadStates.values.toList())
    }

    private fun launchInstall(state: AssetUiState) {
        val uri = state.installUri ?: state.downloadId?.let { downloadManager.getUriForDownloadedFile(it) }
        if (uri == null) {
            Toast.makeText(this, getString(R.string.update_install_not_found), Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.update_install_not_found), Toast.LENGTH_SHORT).show()
        }
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        pollHandler.post(pollRunnable)
    }

    private fun stopPolling() {
        polling = false
        pollHandler.removeCallbacksAndMessages(null)
    }

    private fun pollDownloadProgress() {
        validateFinishedDownloads()
        val downloading = downloadStates.values.filter { it.status == AssetStatus.DOWNLOADING }
        if (downloading.isEmpty()) {
            stopPolling()
            return
        }
        downloading.forEach { state ->
            val id = state.downloadId ?: return@forEach
            val query = DownloadManager.Query().setFilterById(id)
            val cursor = downloadManager.query(query)
            cursor.useSafely { c ->
                if (!c.moveToFirst()) {
                    downloadStates[state.asset.browserDownloadUrl] = state.copy(
                        status = AssetStatus.FAILED,
                        error = getString(R.string.update_asset_failed)
                    )
                    return@useSafely
                }
                val status = c.getIntOrZero(DownloadManager.COLUMN_STATUS)
                val total = c.getLongOrZero(DownloadManager.COLUMN_TOTAL_SIZE_BYTES).coerceAtLeast(0L)
                val soFar = c.getLongOrZero(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR).coerceAtLeast(0L)
                val progress = if (total > 0) ((soFar * 100) / total).toInt().coerceIn(0, 100) else 0
                val url = state.asset.browserDownloadUrl
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        speedSamples.remove(url)
                        val installUri = downloadManager.getUriForDownloadedFile(id)
                        downloadStates[url] = state.copy(
                            status = AssetStatus.FINISHED,
                            progressPercent = 100,
                            downloadedBytes = total,
                            totalBytes = total,
                            speedBytesPerSec = 0L,
                            installUri = installUri
                        )
                    }
                    DownloadManager.STATUS_FAILED -> {
                        speedSamples.remove(url)
                        downloadStates[url] = state.copy(
                            status = AssetStatus.FAILED,
                            speedBytesPerSec = 0L,
                            error = getString(R.string.update_asset_failed)
                        )
                    }
                    DownloadManager.STATUS_PAUSED,
                    DownloadManager.STATUS_PENDING,
                    DownloadManager.STATUS_RUNNING -> {
                        val now = android.os.SystemClock.elapsedRealtime()
                        val prev = speedSamples[url]
                        val displayed: Long = if (prev == null) {
                            speedSamples[url] = SpeedSample(now, soFar, 0L)
                            0L
                        } else {
                            val dt = now - prev.timeMs
                            // Only refresh once at least ~750ms of wall time has
                            // passed; otherwise keep the previous smoothed value
                            // so DownloadManager's bursty writes don't show up.
                            if (dt >= 750L) {
                                val db = (soFar - prev.bytes).coerceAtLeast(0L)
                                val instant = db * 1000L / dt
                                // EMA with alpha = 0.4
                                val smoothed = (prev.smoothed * 6 + instant * 4) / 10
                                speedSamples[url] = SpeedSample(now, soFar, smoothed)
                                smoothed
                            } else {
                                prev.smoothed
                            }
                        }
                        downloadStates[url] = state.copy(
                            status = AssetStatus.DOWNLOADING,
                            progressPercent = progress,
                            downloadedBytes = soFar,
                            totalBytes = total,
                            speedBytesPerSec = displayed
                        )
                    }
                }
            }
        }
        adapter.submitList(downloadStates.values.toList())
    }

    private fun validateFinishedDownloads() {
        var changed = false
        val list = downloadStates.values.toList()
        list.forEach { state ->
            if (state.status != AssetStatus.FINISHED) return@forEach
            if (state.downloadId == null) {
                val uri = state.installUri
                val size = uri?.let { detectUriSize(it, state.totalBytes) } ?: -1L
                if (uri == null ||
                    !uriUsable(uri) ||
                    (state.asset.sizeBytes > 0 && size > 0 && size != state.asset.sizeBytes)
                    || !hashMatchesAsset(null, uri, state.asset)
                ) {
                    downloadStates[state.asset.browserDownloadUrl] = state.copy(
                        status = AssetStatus.IDLE,
                        progressPercent = 0,
                        downloadedBytes = 0L,
                        speedBytesPerSec = 0L,
                        downloadId = null,
                        installUri = null
                    )
                    changed = true
                }
                return@forEach
            }
            val hit = findLatestSuccessfulDownload(state.asset)
            if (hit == null) {
                downloadStates[state.asset.browserDownloadUrl] = state.copy(
                    status = AssetStatus.IDLE,
                    progressPercent = 0,
                    downloadedBytes = 0L,
                    speedBytesPerSec = 0L,
                    downloadId = null,
                    installUri = null
                )
                changed = true
                return@forEach
            }
            if (state.downloadId != hit.id || state.installUri != hit.uri || state.totalBytes != hit.sizeBytes) {
                downloadStates[state.asset.browserDownloadUrl] = state.copy(
                    status = AssetStatus.FINISHED,
                    progressPercent = 100,
                    downloadedBytes = hit.sizeBytes,
                    totalBytes = hit.sizeBytes,
                    speedBytesPerSec = 0L,
                    downloadId = hit.id,
                    installUri = hit.uri
                )
                changed = true
                return@forEach
            }
            if (!uriUsable(hit.uri)) {
                downloadStates[state.asset.browserDownloadUrl] = state.copy(
                    status = AssetStatus.IDLE,
                    progressPercent = 0,
                    downloadedBytes = 0L,
                    speedBytesPerSec = 0L,
                    downloadId = null,
                    installUri = null
                )
                changed = true
            }
        }
        if (changed) {
            adapter.submitList(downloadStates.values.toList())
        }
    }

    private fun createPendingDownloadUri(displayName: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        return contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    }

    private fun markPendingDownloadComplete(uri: Uri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val values = ContentValues().apply {
            put(MediaStore.Downloads.IS_PENDING, 0)
        }
        contentResolver.update(uri, values, null, null)
    }

    private fun deletePendingDownload(uri: Uri) {
        runCatching { contentResolver.delete(uri, null, null) }
    }

    private fun hashMatchesAsset(downloadId: Long?, localUri: Uri, asset: ReleaseAsset): Boolean {
        val expected = asset.sha256?.lowercase()?.trim().orEmpty()
        if (expected.isBlank()) return true
        val actual = sha256Of(downloadId, localUri) ?: return false
        return actual.equals(expected, ignoreCase = true)
    }

    private fun sha256Of(downloadId: Long?, uri: Uri): String? {
        val md = MessageDigest.getInstance("SHA-256")
        val input = openLocalInputStream(downloadId, uri) ?: return null
        input.use { stream ->
            val buf = ByteArray(8 * 1024)
            while (true) {
                val n = stream.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun openLocalInputStream(downloadId: Long?, uri: Uri): InputStream? {
        if (downloadId != null) {
            return runCatching { AutoCloseInputStream(downloadManager.openDownloadedFile(downloadId)) }
                .getOrNull()
        }
        return when (uri.scheme) {
            "file" -> {
                val file = File(uri.path.orEmpty())
                if (!file.exists()) {
                    null
                } else {
                    runCatching { file.inputStream() }.getOrNull()
                }
            }
            "content" -> runCatching { contentResolver.openInputStream(uri) }.getOrNull()
            else -> null
        }
    }

    private fun renderReleaseNotes(markdown: String): CharSequence {
        val lines = markdown
            .replace("\r\n", "\n")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("```") }
            .map {
                var line = it.replace(Regex("^#{1,6}\\s*"), "")
                if (line.startsWith("- ") || line.startsWith("* ")) {
                    line = "• " + line.drop(2).trim()
                }
                line
            }
            .take(16)
            .toList()
        if (lines.isEmpty()) return getString(R.string.update_release_notes_empty)
        val builder = SpannableStringBuilder()
        lines.forEachIndexed { index, line ->
            appendMarkdownLine(builder, line)
            if (index != lines.lastIndex) builder.append('\n')
        }
        return builder
    }

    private fun appendMarkdownLine(builder: SpannableStringBuilder, line: String) {
        val linkRegex = Regex("""\[(.+?)]\((https?://[^)]+)\)""")
        var cursor = 0
        linkRegex.findAll(line).forEach { match ->
            if (match.range.first > cursor) {
                appendBoldText(builder, line.substring(cursor, match.range.first))
            }
            val text = match.groupValues[1]
            val url = match.groupValues[2]
            val start = builder.length
            appendBoldText(builder, text)
            val end = builder.length
            if (end > start) builder.setSpan(URLSpan(url), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            cursor = match.range.last + 1
        }
        if (cursor < line.length) {
            appendBoldText(builder, line.substring(cursor))
        }
        val urlRegex = Regex("""https?://\S+""")
        urlRegex.findAll(builder.toString()).forEach { m ->
            builder.setSpan(URLSpan(m.value), m.range.first, m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun appendBoldText(builder: SpannableStringBuilder, text: String) {
        val boldRegex = Regex("""\*\*(.+?)\*\*""")
        var cursor = 0
        boldRegex.findAll(text).forEach { match ->
            if (match.range.first > cursor) {
                builder.append(text.substring(cursor, match.range.first))
            }
            val start = builder.length
            builder.append(match.groupValues[1])
            builder.setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            cursor = match.range.last + 1
        }
        if (cursor < text.length) {
            builder.append(text.substring(cursor))
        }
    }

    private fun nameMatchesAsset(displayName: String, assetName: String): Boolean {
        if (displayName == assetName) return true
        val dot = assetName.lastIndexOf('.')
        if (dot <= 0) return false
        val base = assetName.substring(0, dot)
        val ext = assetName.substring(dot)
        // Android download manager may auto-rename duplicates to "name(1).ext"
        val duplicatePattern = Regex("""^${Regex.escape(base)}\(\d+\)${Regex.escape(ext)}$""")
        return duplicatePattern.matches(displayName)
    }

    private fun extractDisplayName(localUri: Uri, title: String): String {
        if (localUri.scheme == "file") {
            return File(localUri.path.orEmpty()).name.ifBlank { title }
        }
        if (localUri.scheme == "content") {
            runCatching {
                contentResolver.query(localUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            }.getOrNull()?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val name = c.getString(idx)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
        }
        return title
    }

    private fun detectUriSize(localUri: Uri, fallback: Long): Long {
        if (localUri.scheme == "file") {
            val f = File(localUri.path.orEmpty())
            if (f.exists()) return f.length()
            return -1L
        }
        if (localUri.scheme == "content") {
            runCatching {
                contentResolver.query(localUri, arrayOf(OpenableColumns.SIZE), null, null, null)
            }.getOrNull()?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0) {
                        val size = c.getLong(idx)
                        if (size > 0) return size
                    }
                }
            }
            return fallback
        }
        return fallback
    }

    private fun uriUsable(uri: Uri): Boolean {
        return try {
            contentResolver.openInputStream(uri)?.use { true } ?: false
        } catch (_: Throwable) {
            false
        }
    }

    private fun Cursor.getIntOrZero(columnName: String): Int {
        val idx = getColumnIndex(columnName)
        if (idx < 0) return 0
        return getInt(idx)
    }

    private fun Cursor.getLongOrZero(columnName: String): Long {
        val idx = getColumnIndex(columnName)
        if (idx < 0) return 0L
        return getLong(idx)
    }

    private inline fun Cursor.useSafely(block: (Cursor) -> Unit) {
        use { block(this) }
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        val units = arrayOf("B/s", "KB/s", "MB/s", "GB/s")
        var value = bytesPerSec.toDouble()
        var i = 0
        while (value >= 1024 && i < units.lastIndex) {
            value /= 1024.0
            i++
        }
        return String.format(Locale.US, "%.1f %s", value, units[i])
    }
}
