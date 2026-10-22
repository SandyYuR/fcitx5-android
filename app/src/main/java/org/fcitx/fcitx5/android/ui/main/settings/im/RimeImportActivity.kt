/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.im

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar as Toolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.ui.main.ShareReceiveDirectoryPicker
import org.fcitx.fcitx5.android.ui.common.SettingsUiActivity
import org.fcitx.fcitx5.android.utils.ExternalRimeStorage
import java.io.File
import java.util.zip.ZipFile

/** Imports a profile ZIP into a new profile directory chosen by the user. */
class RimeImportActivity : SettingsUiActivity() {
    private val connection by lazy { FcitxDaemon.connect(javaClass.name) }
    private lateinit var status: TextView
    private lateinit var choose: MaterialButton
    private var pendingArchivePath: String? = null
    private var pendingDestinationPath: String? = null
    private var pendingFallbackMessage: String? = null
    private var pendingSelectionStep: String? = null
    private val appDirectoryPicker by lazy { ShareReceiveDirectoryPicker(this) }

    private val zipPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) copyArchiveAndChooseDestination(uri)
    }

    private val destinationPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val archive = pendingArchiveFile()
        if (archive == null) {
            pendingArchivePath = null
            pendingDestinationPath = null
            pendingSelectionStep = null
            choose.isEnabled = true
            status.setText(R.string.direct_rime_archive_missing)
            return@registerForActivityResult
        }
        if (uri == null) {
            cancelPendingImport()
            return@registerForActivityResult
        }
        val destination = resolveDestination(uri)
        if (destination == null) {
            failPendingImport(getString(R.string.addon_dir_profile_external_dir_invalid))
            return@registerForActivityResult
        }
        pendingSelectionStep = null
        requestStorageAccess(destination)
    }

    private val allFilesAccessLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (hasExternalStorageAccess()) {
            pendingDestinationPath?.let { importPendingArchive(it) }
                ?: failPendingImport(getString(R.string.addon_dir_profile_external_dir_invalid))
        } else {
            offerAppDirectoryFallback(getString(R.string.addon_dir_profile_all_files_permission_required))
        }
    }

    private val legacyStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingDestinationPath?.let { importPendingArchive(it) }
                ?: failPendingImport(getString(R.string.addon_dir_profile_external_dir_invalid))
        }
        else offerAppDirectoryFallback(getString(R.string.addon_dir_profile_storage_permission_required))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingArchivePath = savedInstanceState?.getString(STATE_PENDING_ARCHIVE)
            ?.takeIf(::isPendingArchive)
        pendingDestinationPath = if (pendingArchivePath != null) {
            savedInstanceState?.getString(STATE_PENDING_DESTINATION)
        } else null
        pendingSelectionStep = if (pendingArchivePath != null) {
            savedInstanceState?.getString(STATE_PENDING_SELECTION_STEP)
        } else null
        pendingFallbackMessage = if (pendingArchivePath != null) {
            savedInstanceState?.getString(STATE_PENDING_FALLBACK)
        } else null
        connection
        val padding = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = Toolbar(this).apply {
            id = R.id.toolbar
            setTitle(R.string.direct_rime_import)
            setNavigationIcon(R.drawable.ic_baseline_arrow_back_24)
            setNavigationContentDescription(androidx.appcompat.R.string.abc_action_bar_up_description)
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        content.addView(TextView(this).apply {
            setText(R.string.direct_rime_import_description)
            textSize = 16f
        })
        choose = MaterialButton(this).apply {
            setText(R.string.direct_rime_choose_zip)
            isEnabled = pendingArchivePath == null
            setOnClickListener {
                zipPicker.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed"))
            }
        }
        content.addView(choose)
        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, padding, 0, padding)
        }
        content.addView(status)
        content.addView(TextInputLayout(this).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            hint = getString(R.string.direct_rime_test_input)
            addView(TextInputEditText(context).apply {
                id = R.id.direct_rime_test_editor
                minLines = 3
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            }, LinearLayout.LayoutParams(-1, -2))
        })
        root.addView(ScrollView(this).apply { addView(content) })
        setContentView(root)
        setSupportActionBar(toolbar)
        when {
            pendingFallbackMessage != null -> offerAppDirectoryFallback(pendingFallbackMessage!!)
            pendingSelectionStep == STEP_DESTINATION_CHOICE -> showDestinationChoice()
            pendingSelectionStep == STEP_APP_DIRECTORY -> chooseAppDirectory()
        }
    }

    private fun copyArchiveAndChooseDestination(uri: Uri) {
        choose.isEnabled = false
        status.setText(R.string.direct_rime_reading_archive)
        lifecycleScope.launch {
            var archive: File? = null
            try {
                val folder = File(cacheDir, "rime-import").apply {
                    check(isDirectory || mkdirs()) { "无法创建临时目录" }
                }
                val copiedArchive = File.createTempFile("profile-", ".zip", folder)
                archive = copiedArchive
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { input ->
                        copiedArchive.outputStream().use { output ->
                            val buffer = ByteArray(32 * 1024)
                            var bytes = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                bytes += count
                                require(bytes <= MAX_ARCHIVE_BYTES) {
                                    getString(R.string.direct_rime_archive_too_large)
                                }
                                output.write(buffer, 0, count)
                            }
                        }
                    } ?: error("无法读取所选 ZIP")
                }
                status.setText(R.string.direct_rime_validating_archive)
                withContext(Dispatchers.IO) { validateRimeArchive(copiedArchive) }
                pendingArchivePath = copiedArchive.absolutePath
                showDestinationChoice()
            } catch (e: kotlinx.coroutines.CancellationException) {
                archive?.delete()
                throw e
            } catch (e: Exception) {
                archive?.delete()
                pendingArchivePath = null
                pendingDestinationPath = null
                pendingSelectionStep = null
                pendingFallbackMessage = null
                choose.isEnabled = true
                status.text = getString(R.string.direct_rime_import_failed, e.message.orEmpty())
            }
        }
    }

    private fun validateRimeArchive(archive: File) {
        val hasRootSchema = runCatching {
            ZipFile(archive).use { zip ->
                val entries = zip.entries()
                var found = false
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val segments = entry.name.split('/').filter { it.isNotEmpty() && it != "." }
                    val safeRootPath = !entry.name.startsWith('/') && '\\' !in entry.name &&
                        '\u0000' !in entry.name && segments.none { it == ".." || ':' in it }
                    if (!entry.isDirectory && safeRootPath && segments.size == 1 &&
                        (segments.single() == "default.yaml" || segments.single() == "default.custom.yaml")) {
                        found = true
                        break
                    }
                }
                found
            }
        }.getOrDefault(false)
        require(hasRootSchema) { getString(R.string.direct_rime_archive_not_profile) }
    }

    private fun showDestinationChoice() {
        pendingSelectionStep = STEP_DESTINATION_CHOICE
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.direct_rime_choose_destination)
            .setItems(arrayOf(
                getString(R.string.direct_rime_app_directory),
                getString(R.string.direct_rime_external_directory)
            )) { _, which ->
                when (which) {
                    0 -> {
                        pendingSelectionStep = STEP_APP_DIRECTORY
                        chooseAppDirectory()
                    }
                    1 -> {
                        pendingSelectionStep = STEP_EXTERNAL_PICKER
                        destinationPicker.launch(null)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> cancelPendingImport() }
            .setOnCancelListener { cancelPendingImport() }
            .show()
    }

    private fun chooseAppDirectory() {
        pendingSelectionStep = STEP_APP_DIRECTORY
        lifecycleScope.launch {
            try {
                val relativePath = appDirectoryPicker.chooseTargetDirectory(
                    appDirectoryPicker.lastDirectoryOr("shared")
                )
                if (relativePath == null) {
                    cancelPendingImport()
                    return@launch
                }
                val directory = appDirectoryPicker.resolveDirectory(relativePath)
                pendingSelectionStep = null
                importPendingArchive(directory.absolutePath, relativePath)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failPendingImport(getString(R.string.direct_rime_import_failed, e.message.orEmpty()))
            }
        }
    }

    private fun requestStorageAccess(destination: String) {
        pendingDestinationPath = destination
        pendingSelectionStep = STEP_EXTERNAL_PERMISSION
        when (ExternalRimeStorage.accessForSdk(Build.VERSION.SDK_INT)) {
            ExternalRimeStorage.Access.LegacyPermission -> {
                if (hasExternalStorageAccess()) importPendingArchive(destination)
                else legacyStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            ExternalRimeStorage.Access.Unsupported ->
                offerAppDirectoryFallback(getString(R.string.addon_dir_profile_android10_unsupported))
            ExternalRimeStorage.Access.AllFilesPermission -> {
                if (hasExternalStorageAccess()) importPendingArchive(destination)
                else requestAllFilesAccess()
            }
        }
    }

    private fun offerAppDirectoryFallback(message: String) {
        pendingFallbackMessage = message
        pendingSelectionStep = STEP_APP_FALLBACK
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.direct_rime_choose_destination)
            .setMessage(message)
            .setPositiveButton(R.string.direct_rime_choose_app_directory) { _, _ ->
                pendingFallbackMessage = null
                pendingSelectionStep = STEP_APP_DIRECTORY
                chooseAppDirectory()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                pendingFallbackMessage = null
                cancelPendingImport()
            }
            .setOnCancelListener {
                pendingFallbackMessage = null
                cancelPendingImport()
            }
            .show()
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            failPendingImport(getString(R.string.addon_dir_profile_all_files_permission_required))
            return
        }
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName")
        )
        try {
            allFilesAccessLauncher.launch(intent)
        } catch (_: Exception) {
            try {
                allFilesAccessLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (e: Exception) {
                offerAppDirectoryFallback(e.message ?: getString(R.string.addon_dir_profile_all_files_permission_required))
            }
        }
    }

    private fun hasExternalStorageAccess(): Boolean = when (ExternalRimeStorage.accessForSdk(Build.VERSION.SDK_INT)) {
        ExternalRimeStorage.Access.LegacyPermission -> ContextCompat.checkSelfPermission(
            this, Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        ExternalRimeStorage.Access.Unsupported -> false
        ExternalRimeStorage.Access.AllFilesPermission ->
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
    }

    private fun resolveDestination(uri: Uri): String? = runCatching {
        val documentId = DocumentsContract.getTreeDocumentId(uri)
        ExternalRimeStorage.absolutePath(
            uri.authority,
            documentId,
            Environment.getExternalStorageDirectory().absolutePath
        )?.let { File(it).canonicalPath }
    }.getOrNull()

    private fun isPendingArchive(path: String): Boolean = runCatching {
        val file = File(path)
        file.isFile && file.canonicalFile.parentFile == File(cacheDir, "rime-import").canonicalFile
    }.getOrDefault(false)

    private fun pendingArchiveFile(): File? = pendingArchivePath?.takeIf(::isPendingArchive)?.let(::File)

    private fun importPendingArchive(destination: String?, appRelativePath: String? = null) {
        val archive = pendingArchiveFile()
        if (archive == null) {
            pendingArchivePath = null
            pendingDestinationPath = null
            pendingSelectionStep = null
            choose.isEnabled = true
            status.setText(R.string.direct_rime_archive_missing)
            return
        }
        importArchive(archive, destination.orEmpty(), appRelativePath)
    }

    private fun importArchive(archive: File, destination: String, appRelativePath: String?) {
        pendingArchivePath = null
        pendingDestinationPath = null
        pendingFallbackMessage = null
        pendingSelectionStep = null
        status.setText(R.string.direct_rime_importing)
        lifecycleScope.launch {
            try {
                connection.runOnReady {
                    setAddonSubConfig(
                        "rime",
                        "import-profile",
                        RawConfig(arrayOf(
                            RawConfig("Archive", archive.absolutePath),
                            RawConfig("Destination", destination)
                        ))
                    )
                }
                appRelativePath?.let(appDirectoryPicker::rememberDirectory)
                status.setText(R.string.direct_rime_imported)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                status.text = getString(R.string.direct_rime_import_failed, e.message.orEmpty())
            } finally {
                archive.delete()
                choose.isEnabled = true
            }
        }
    }

    private fun cancelPendingImport() {
        pendingArchiveFile()?.delete()
        pendingArchivePath = null
        pendingDestinationPath = null
        pendingFallbackMessage = null
        pendingSelectionStep = null
        choose.isEnabled = true
        status.setText(R.string.direct_rime_import_cancelled)
    }

    private fun failPendingImport(message: String) {
        cancelPendingImport()
        status.text = message
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_PENDING_ARCHIVE, pendingArchivePath)
        outState.putString(STATE_PENDING_DESTINATION, pendingDestinationPath)
        outState.putString(STATE_PENDING_FALLBACK, pendingFallbackMessage)
        outState.putString(STATE_PENDING_SELECTION_STEP, pendingSelectionStep)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (isFinishing) pendingArchiveFile()?.delete()
        super.onDestroy()
        FcitxDaemon.disconnect(javaClass.name)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private companion object {
        const val STATE_PENDING_ARCHIVE = "pending_rime_import_archive"
        const val STATE_PENDING_DESTINATION = "pending_rime_import_destination"
        const val STATE_PENDING_FALLBACK = "pending_rime_import_fallback"
        const val STATE_PENDING_SELECTION_STEP = "pending_rime_import_selection_step"
        const val STEP_DESTINATION_CHOICE = "destination_choice"
        const val STEP_APP_DIRECTORY = "app_directory"
        const val STEP_APP_FALLBACK = "app_fallback"
        const val STEP_EXTERNAL_PICKER = "external_picker"
        const val STEP_EXTERNAL_PERMISSION = "external_permission"
        const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024
    }
}
