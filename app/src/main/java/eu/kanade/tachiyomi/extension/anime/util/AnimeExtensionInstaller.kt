package eu.kanade.tachiyomi.extension.anime.util

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.extension.InstallStep
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.anime.installer.InstallerAnime
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.isPackageInstalled
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.transformWhile
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * The installer which installs, updates and uninstalls the extensions.
 *
 * @param context The application context.
 */
internal class AnimeExtensionInstaller(private val context: Context) {

    /**
     * The currently requested downloads, with the package name (unique id) as key, and the id
     * used for tracking.
     */
    private val activeDownloads = hashMapOf<String, Long>()

    private val downloadsStateFlows = hashMapOf<Long, MutableStateFlow<InstallStep>>()

    private val extensionInstaller = Injekt.get<BasePreferences>().extensionInstaller()

    /**
     * Adds the given extension to the downloads queue and returns an observable containing its
     * step in the installation process.
     *
     * @param url The url of the apk.
     * @param extension The extension to install.
     */
    fun downloadAndInstall(url: String, extension: AnimeExtension): Flow<InstallStep> {
        val pkgName = extension.pkgName

        val oldDownload = activeDownloads[pkgName]
        if (oldDownload != null) {
            deleteDownload(pkgName)
        }

        val id = pkgName.hashCode().toLong()
        activeDownloads[pkgName] = id

        val downloadStateFlow = MutableStateFlow(InstallStep.Pending)
        downloadsStateFlows[id] = downloadStateFlow

        return flow {
            emit(InstallStep.Pending)

            val file = File(context.cacheDir, "$pkgName.apk")
            try {
                emit(InstallStep.Downloading)
                val networkHelper = Injekt.get<NetworkHelper>()
                val response = networkHelper.client.newCall(GET(url)).awaitSuccess()
                response.body.byteStream().use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                val uri = file.getUriCompat(context)
                installApk(id, uri)

                // Monitor the installation state
                emitAll(
                    downloadStateFlow.filter {
                        it != InstallStep.Pending && it != InstallStep.Downloading
                    },
                )
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to download extension" }
                emit(InstallStep.Error)
            }
        }.transformWhile {
            emit(it)
            // Stop when the application is installed or errors
            !it.isCompleted()
        }.onCompletion {
            // Always notify on main thread
            withUIContext {
                // Always remove the download when unsubscribed
                deleteDownload(pkgName)
            }
        }
    }

    /**
     * Starts an intent to install the extension at the given uri.
     *
     * @param uri The uri of the extension to install.
     */
    fun installApk(downloadId: Long, uri: Uri) {
        when (val installer = extensionInstaller.get()) {
            BasePreferences.ExtensionInstaller.LEGACY -> {
                val intent = Intent(context, AnimeExtensionInstallActivity::class.java)
                    .setDataAndType(uri, APK_MIME)
                    .putExtra(EXTRA_DOWNLOAD_ID, downloadId)
                    .setFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )

                context.startActivity(intent)
            }
            BasePreferences.ExtensionInstaller.PRIVATE -> {
                val extensionManager = Injekt.get<AnimeExtensionManager>()
                val tempFile = File(context.cacheDir, "temp_$downloadId")

                if (tempFile.exists() && !tempFile.delete()) {
                    // Unlikely but just in case
                    extensionManager.updateInstallStep(downloadId, InstallStep.Error)
                    return
                }

                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }

                    if (AnimeExtensionLoader.installPrivateExtensionFile(context, tempFile)) {
                        extensionManager.updateInstallStep(downloadId, InstallStep.Installed)
                    } else {
                        extensionManager.updateInstallStep(downloadId, InstallStep.Error)
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Failed to read downloaded extension file." }
                    extensionManager.updateInstallStep(downloadId, InstallStep.Error)
                }

                tempFile.delete()
            }
            else -> {
                val intent =
                    AnimeExtensionInstallService.getIntent(context, downloadId, uri, installer)
                ContextCompat.startForegroundService(context, intent)
            }
        }
    }

    /**
     * Cancels extension install and remove from download manager and installer.
     */
    fun cancelInstall(pkgName: String) {
        val downloadId = activeDownloads.remove(pkgName) ?: return
        InstallerAnime.cancelInstallQueue(context, downloadId)
    }

    /**
     * Starts an intent to uninstall the extension by the given package name.
     *
     * @param pkgName The package name of the extension to uninstall
     */
    fun uninstallApk(pkgName: String) {
        if (context.isPackageInstalled(pkgName)) {
            @Suppress("DEPRECATION")
            val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, "package:$pkgName".toUri())
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } else {
            AnimeExtensionLoader.uninstallPrivateExtension(context, pkgName)
            AnimeExtensionInstallReceiver.notifyRemoved(context, pkgName)
        }
    }

    /**
     * Sets the step of the installation of an extension.
     *
     * @param downloadId The id of the download.
     * @param step New install step.
     */
    fun updateInstallStep(downloadId: Long, step: InstallStep) {
        downloadsStateFlows[downloadId]?.let { it.value = step }
    }

    /**
     * Deletes the download for the given package name.
     *
     * @param pkgName The package name of the download to delete.
     */
    private fun deleteDownload(pkgName: String) {
        val downloadId = activeDownloads.remove(pkgName)
        if (downloadId != null) {
            downloadsStateFlows.remove(downloadId)
        }
    }

    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        const val EXTRA_DOWNLOAD_ID = "AnimeExtensionInstaller.extra.DOWNLOAD_ID"
    }
}
