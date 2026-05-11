package com.nuvio.app.features.updater

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.i18n.localizedByteUnit
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

private const val gitHubApiBase = "https://api.github.com"

data class AppUpdate(
    val tag: String,
    val title: String,
    val notes: String,
    val releaseUrl: String?,
    val assetName: String?,
    val assetUrl: String?,
    val assetSizeBytes: Long?,
    val versionName: String?,
    val versionCode: Int?,
    val channelLabel: String,
    val availableAssets: List<AppUpdateAsset> = emptyList(),
)

enum class AppUpdateAssetKind {
    Installer,
    PortableZip,
}

data class AppUpdateAsset(
    val name: String,
    val url: String,
    val sizeBytes: Long?,
    val kind: AppUpdateAssetKind,
)

data class AppUpdaterUiState(
    val isChecking: Boolean = false,
    val update: AppUpdate? = null,
    val isUpdateAvailable: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Float? = null,
    val downloadedApkPath: String? = null,
    val showDialog: Boolean = false,
    val showUnknownSourcesDialog: Boolean = false,
    val errorMessage: String? = null,
    val nightlyBuildModeEnabled: Boolean = AppUpdaterPlatform.getNightlyBuildMode(),
    val selectedAssetKind: AppUpdateAssetKind? = null,
)

@Serializable
private data class GitHubReleaseDto(
    @SerialName("tag_name") val tagName: String? = null,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("html_url") val htmlUrl: String? = null,
    @SerialName("target_commitish") val targetCommitish: String? = null,
    val assets: List<GitHubAssetDto> = emptyList(),
)

@Serializable
private data class GitHubAssetDto(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long? = null,
    @SerialName("content_type") val contentType: String? = null,
)

private val appUpdaterJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private class NoChannelReleaseException : IllegalStateException(
    "No release has been published for this update channel yet.",
)

internal data class ReleaseVersionInfo(
    val versionName: String?,
    val versionCode: Int?,
)

internal object AppUpdateVersionComparator {
    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw.trim().removePrefix("v").removePrefix("V")
    }

    fun parseVersionParts(raw: String?): List<Int>? {
        val normalized = normalize(raw)
        if (normalized.isBlank()) return null

        val parts = normalized.split('.', '-', '_')
            .filter { it.isNotBlank() }
            .mapNotNull { token -> token.takeWhile { it.isDigit() }.toIntOrNull() }

        return parts.takeIf { it.isNotEmpty() }
    }

    fun parseReleaseVersion(tag: String?, title: String?, notes: String?): ReleaseVersionInfo {
        val candidates = listOf(notes, title, tag).filterNotNull()
        val versionName = candidates.firstNotNullOfOrNull(::findVersionName)
        val versionCode = candidates.firstNotNullOfOrNull(::findVersionCode)
        return ReleaseVersionInfo(versionName = versionName, versionCode = versionCode)
    }

    fun isUpdateAvailable(
        remoteVersionName: String?,
        remoteVersionCode: Int?,
        remoteTag: String?,
        localVersionName: String?,
        localVersionCode: Int,
    ): Boolean {
        val remoteParts = parseVersionParts(remoteVersionName ?: remoteTag)
        val localParts = parseVersionParts(localVersionName)

        if (remoteParts != null && localParts != null) {
            val versionComparison = compareVersionParts(remoteParts, localParts)
            if (versionComparison != 0) return versionComparison > 0
            if (remoteVersionCode != null) return remoteVersionCode > localVersionCode
            return false
        }

        if (remoteVersionCode != null && remoteVersionCode > localVersionCode) return true

        return isRemoteNewer(remoteTag, localVersionName)
    }

    fun compareRemoteCandidates(
        firstVersionName: String?,
        firstVersionCode: Int?,
        firstTag: String?,
        secondVersionName: String?,
        secondVersionCode: Int?,
        secondTag: String?,
    ): Int {
        val firstParts = parseVersionParts(firstVersionName ?: firstTag)
        val secondParts = parseVersionParts(secondVersionName ?: secondTag)

        if (firstParts != null && secondParts != null) {
            val versionComparison = compareVersionParts(firstParts, secondParts)
            if (versionComparison != 0) return versionComparison
        } else if (firstParts != null) {
            return 1
        } else if (secondParts != null) {
            return -1
        }

        val firstBuild = firstVersionCode ?: -1
        val secondBuild = secondVersionCode ?: -1
        if (firstBuild != secondBuild) return firstBuild.compareTo(secondBuild)

        return normalize(firstTag).compareTo(normalize(secondTag))
    }

    private fun isRemoteNewer(remote: String?, local: String?): Boolean {
        val remoteParts = parseVersionParts(remote)
        val localParts = parseVersionParts(local)

        if (remoteParts == null || localParts == null) {
            val remoteValue = normalize(remote)
            val localValue = normalize(local)
            return remoteValue.isNotBlank() && localValue.isNotBlank() && remoteValue != localValue
        }

        return compareVersionParts(remoteParts, localParts) > 0
    }

    private fun compareVersionParts(remoteParts: List<Int>, localParts: List<Int>): Int {
        val maxSize = maxOf(remoteParts.size, localParts.size)
        for (index in 0 until maxSize) {
            val remoteValue = remoteParts.getOrElse(index) { 0 }
            val localValue = localParts.getOrElse(index) { 0 }
            if (remoteValue != localValue) return remoteValue.compareTo(localValue)
        }
        return 0
    }

    private fun findVersionName(value: String): String? {
        val explicit = Regex("""(?i)\b(?:version|app_version|version_name)\s*[:=]?\s*v?(\d+(?:\.\d+){1,3})\b""")
            .find(value)
            ?.groupValues
            ?.getOrNull(1)
        if (explicit != null) return explicit

        return Regex("""(?i)\bv?(\d+(?:\.\d+){1,3})\b""")
            .find(value)
            ?.groupValues
            ?.getOrNull(1)
    }

    private fun findVersionCode(value: String): Int? {
        val patterns = listOf(
            Regex("""(?i)\b(?:build|version_code|build_number|current_project_version)\s*[:=#-]?\s*(\d+)\b"""),
            Regex("""\((\d+)\)"""),
        )
        return patterns.firstNotNullOfOrNull { pattern ->
            pattern.find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
    }
}

private object AppUpdaterRepository {
    suspend fun getLatestChannelUpdate(): Result<AppUpdate> = runCatching {
        val nightlyMode = AppUpdaterPlatform.getNightlyBuildMode()
        val nightlyTag = AppUpdaterPlatform.nightlyReleaseTag?.takeIf { it.isNotBlank() }
        val response = httpRequestRaw(
            method = "GET",
            url = "$gitHubApiBase/repos/${AppUpdaterPlatform.gitHubOwner}/${AppUpdaterPlatform.gitHubRepo}/releases?per_page=50",
            headers = mapOf(
                "Accept" to "application/vnd.github+json",
                "User-Agent" to "Nuvio",
            ),
            body = "",
        )
        if (response.status !in 200..299) {
            error("GitHub releases API error: ${response.status}")
        }

        val releases = appUpdaterJson.decodeFromString<List<GitHubReleaseDto>>(response.body)
        val stableRelease = releases.firstOrNull { it.matchesRequestedChannel() && !it.draft && !it.prerelease }
        val releaseCandidates = if (nightlyMode && nightlyTag != null) {
            buildList {
                if (stableRelease != null) add(stableRelease to "latest")
                releases.firstOrNull { release -> release.matchesNightlyTag(nightlyTag) && !release.draft }
                    ?.takeIf { nightlyRelease -> nightlyRelease.tagName != stableRelease?.tagName }
                    ?.let { nightlyRelease -> add(nightlyRelease to nightlyTag) }
            }
        } else {
            stableRelease?.let { listOf(it to "latest") }.orEmpty()
        }

        val update = releaseCandidates
            .map { (release, channelLabel) -> release.toAppUpdate(channelLabel) }
            .maxWithOrNull { first, second ->
                AppUpdateVersionComparator.compareRemoteCandidates(
                    firstVersionName = first.versionName,
                    firstVersionCode = first.versionCode,
                    firstTag = first.tag,
                    secondVersionName = second.versionName,
                    secondVersionCode = second.versionCode,
                    secondTag = second.tag,
                )
            }
            ?: throw NoChannelReleaseException()

        update
    }

    private fun GitHubReleaseDto.toAppUpdate(channelLabel: String): AppUpdate {
        val tag = tagName?.takeIf { it.isNotBlank() }
            ?: name?.takeIf { it.isNotBlank() }
            ?: error("Release has no tag or name")

        val availableAssets = buildList {
            val installerAsset = chooseInstallerAsset(assets)
            if (installerAsset != null) add(installerAsset)

            val portableZipAsset = choosePortableZipAsset(assets)
            if (portableZipAsset != null) add(portableZipAsset)
        }
        if (availableAssets.isEmpty()) {
            val exts = AppUpdaterPlatform.installerAssetExtensions.joinToString(", ")
            throw IllegalStateException("No update asset found in the release (installer extensions: $exts).")
        }
        val selectedAsset = chooseDefaultAsset(availableAssets)
        val releaseVersion = AppUpdateVersionComparator.parseReleaseVersion(
            tag = tagName,
            title = name,
            notes = body,
        )

        return AppUpdate(
            tag = tag,
            title = name?.takeIf { it.isNotBlank() } ?: tag,
            notes = body.orEmpty(),
            releaseUrl = htmlUrl,
            assetName = selectedAsset?.name,
            assetUrl = selectedAsset?.url,
            assetSizeBytes = selectedAsset?.sizeBytes,
            versionName = releaseVersion.versionName,
            versionCode = releaseVersion.versionCode,
            channelLabel = channelLabel,
            availableAssets = availableAssets,
        )
    }

    private fun GitHubReleaseDto.matchesNightlyTag(nightlyTag: String): Boolean =
        tagName?.equals(nightlyTag, ignoreCase = true) == true ||
            name?.equals(nightlyTag, ignoreCase = true) == true

    private fun GitHubReleaseDto.matchesRequestedChannel(): Boolean {
        val channel = AppUpdaterPlatform.stableReleaseChannelBranch ?: return true
        if (targetCommitish?.trim()?.equals(channel, ignoreCase = true) == true) {
            return true
        }

        return listOf(tagName, name)
            .filterNotNull()
            .any { value -> value.contains(channel, ignoreCase = true) }
    }

    private fun chooseBestApkAsset(assets: List<GitHubAssetDto>): GitHubAssetDto? {
        val apkAssets = assets.filter { asset ->
            asset.name.endsWith(".apk", ignoreCase = true) ||
                asset.contentType == "application/vnd.android.package-archive"
        }
        if (apkAssets.isEmpty()) return null
        if (apkAssets.size == 1) return apkAssets.first()

        val supportedAbis = AppUpdaterPlatform.getSupportedAbis()
        for (abi in supportedAbis) {
            val candidate = apkAssets.firstOrNull { asset ->
                asset.name.contains(abi, ignoreCase = true)
            }
            if (candidate != null) return candidate
        }

        return apkAssets.firstOrNull { asset ->
            val name = asset.name.lowercase()
            name.contains("universal") || name.contains("all")
        } ?: apkAssets.first()
    }

    private fun chooseInstallerAsset(assets: List<GitHubAssetDto>): AppUpdateAsset? {
        val installerExtensions = AppUpdaterPlatform.installerAssetExtensions
        if (installerExtensions.isEmpty()) return null

        val portableHint = AppUpdaterPlatform.portableZipAssetNameContains?.lowercase()
        val avoidPortableNames = !portableHint.isNullOrBlank()

        // Android: ABI-aware `.apk` selection.
        if (installerExtensions.any { it.equals(".apk", ignoreCase = true) }) {
            val apk = chooseBestApkAsset(assets) ?: return null
            return AppUpdateAsset(
                name = apk.name,
                url = apk.browserDownloadUrl,
                sizeBytes = apk.size,
                kind = AppUpdateAssetKind.Installer,
            )
        }

        val installerExtensionsLower = installerExtensions.map { it.lowercase() }
        val installerCandidates = assets.filter { asset ->
            val nameLower = asset.name.lowercase()
            val hasAllowedExt = installerExtensionsLower.any { ext -> nameLower.endsWith(ext) }
            val isPortable = avoidPortableNames && nameLower.contains(portableHint!!)
            hasAllowedExt && !isPortable
        }

        val picked = installerExtensionsLower.firstNotNullOfOrNull { ext ->
            installerCandidates.firstOrNull { it.name.lowercase().endsWith(ext) }
        } ?: installerCandidates.firstOrNull()

        return picked?.let { candidate ->
            AppUpdateAsset(
                name = candidate.name,
                url = candidate.browserDownloadUrl,
                sizeBytes = candidate.size,
                kind = AppUpdateAssetKind.Installer,
            )
        }
    }

    private fun choosePortableZipAsset(assets: List<GitHubAssetDto>): AppUpdateAsset? {
        val portableExtensions = AppUpdaterPlatform.portableZipAssetExtensions
        if (portableExtensions.isEmpty()) return null

        val hint = AppUpdaterPlatform.portableZipAssetNameContains?.lowercase()
        val portableExtensionsLower = portableExtensions.map { it.lowercase() }

        val candidates = assets.filter { asset ->
            val nameLower = asset.name.lowercase()
            val hasAllowedExt = portableExtensionsLower.any { ext -> nameLower.endsWith(ext) }
            val matchesHint = if (hint.isNullOrBlank()) {
                true
            } else {
                nameLower.contains(hint)
            }
            hasAllowedExt && matchesHint
        }

        val picked = candidates.firstOrNull()
        return picked?.let { candidate ->
            AppUpdateAsset(
                name = candidate.name,
                url = candidate.browserDownloadUrl,
                sizeBytes = candidate.size,
                kind = AppUpdateAssetKind.PortableZip,
            )
        }
    }

    private fun chooseDefaultAsset(assets: List<AppUpdateAsset>): AppUpdateAsset? {
        if (assets.isEmpty()) return null
        return if (AppUpdaterPlatform.prefersPortableUpdate()) {
            assets.firstOrNull { it.kind == AppUpdateAssetKind.PortableZip }
                ?: assets.first()
        } else {
            assets.firstOrNull { it.kind == AppUpdateAssetKind.Installer }
                ?: assets.first()
        }
    }
}

private fun AppUpdate.ignoreKey(): String =
    listOf(tag, versionName.orEmpty(), versionCode?.toString().orEmpty())
        .joinToString(separator = "|")

private fun AppUpdate.isIgnoredBy(storedKey: String?): Boolean {
    if (storedKey.isNullOrBlank()) return false
    if (storedKey == ignoreKey()) return true

    // Older builds stored only the tag. Keep that compatible only for releases
    // without parsed version/build metadata, otherwise a fixed tag like "pre"
    // would suppress every future nightly build.
    return storedKey == tag && versionName == null && versionCode == null
}

class AppUpdaterController internal constructor(
    private val scope: CoroutineScope,
) {
    private val _uiState = MutableStateFlow(AppUpdaterUiState())
    val uiState: StateFlow<AppUpdaterUiState> = _uiState.asStateFlow()

    private var autoCheckStarted = false
    private val downloadMutex = Mutex()

    fun ensureAutoCheckStarted() {
        if (
            autoCheckStarted ||
            !AppFeaturePolicy.inAppUpdaterEnabled ||
            !AppUpdaterPlatform.isSupported ||
            !AppUpdaterPlatform.supportsAutoCheck
        ) {
            return
        }
        autoCheckStarted = true
        checkForUpdates(force = false, showNoUpdateFeedback = false)
    }

    fun checkForUpdates(force: Boolean, showNoUpdateFeedback: Boolean) {
        if (!AppFeaturePolicy.inAppUpdaterEnabled || !AppUpdaterPlatform.isSupported) {
            if (showNoUpdateFeedback) {
                scope.launch {
                    NuvioToastController.show(getString(Res.string.updates_not_available))
                }
            }
            return
        }

        scope.launch {
            _uiState.update { state ->
                state.copy(
                    isChecking = true,
                    errorMessage = null,
                    showUnknownSourcesDialog = false,
                )
            }

            val ignoredTag = AppUpdaterPlatform.getIgnoredTag()
            val result = AppUpdaterRepository.getLatestChannelUpdate()

            result.onSuccess { update ->
                val remoteNewer = AppUpdateVersionComparator.isUpdateAvailable(
                    remoteVersionName = update.versionName,
                    remoteVersionCode = update.versionCode,
                    remoteTag = update.tag,
                    localVersionName = AppVersionConfig.VERSION_NAME,
                    localVersionCode = AppVersionConfig.VERSION_CODE,
                )
                val ignored = update.isIgnoredBy(ignoredTag)
                val shouldShowDialog = force || (remoteNewer && !ignored)

                _uiState.update { state ->
                    state.copy(
                        isChecking = false,
                        update = update.takeIf { remoteNewer },
                        isUpdateAvailable = remoteNewer,
                        isDownloading = false,
                        downloadProgress = null,
                        downloadedApkPath = state.downloadedApkPath.takeIf { remoteNewer },
                        showDialog = shouldShowDialog,
                        showUnknownSourcesDialog = false,
                        errorMessage = null,
                        selectedAssetKind = update.availableAssets.firstOrNull { it.name == update.assetName }?.kind,
                    )
                }

                if (showNoUpdateFeedback && !remoteNewer) {
                    NuvioToastController.show(getString(Res.string.updates_latest_version))
                }
            }.onFailure { error ->
                _uiState.update { state ->
                    state.copy(
                        isChecking = false,
                        isDownloading = false,
                        downloadProgress = null,
                        downloadedApkPath = null,
                        update = null,
                        isUpdateAvailable = false,
                        showDialog = force && error !is NoChannelReleaseException,
                        showUnknownSourcesDialog = false,
                        errorMessage = if (force && error !is NoChannelReleaseException) {
                            error.message ?: getString(Res.string.updates_check_failed)
                        } else {
                            null
                        },
                    )
                }

                if (showNoUpdateFeedback || error is NoChannelReleaseException) {
                    NuvioToastController.show(error.message ?: getString(Res.string.updates_check_failed))
                }
            }
        }
    }

    fun setNightlyBuildMode(enabled: Boolean) {
        AppUpdaterPlatform.setNightlyBuildMode(enabled)
        _uiState.update { state ->
            state.copy(
                nightlyBuildModeEnabled = enabled,
                update = null,
                isUpdateAvailable = false,
                downloadedApkPath = null,
                downloadProgress = null,
                errorMessage = null,
                selectedAssetKind = null,
            )
        }
    }

    fun selectUpdateAsset(kind: AppUpdateAssetKind) {
        _uiState.update { state ->
            val update = state.update ?: return@update state
            val selected = update.availableAssets.firstOrNull { it.kind == kind } ?: return@update state
            state.copy(
                selectedAssetKind = selected.kind,
                downloadedApkPath = null,
                downloadProgress = null,
                errorMessage = null,
                update = update.copy(
                    assetName = selected.name,
                    assetUrl = selected.url,
                    assetSizeBytes = selected.sizeBytes,
                ),
            )
        }
    }

    fun dismissDialog() {
        _uiState.update { state ->
            state.copy(
                showDialog = false,
                showUnknownSourcesDialog = false,
                errorMessage = null,
            )
        }
    }

    fun ignoreThisVersion() {
        val update = _uiState.value.update ?: return
        AppUpdaterPlatform.setIgnoredTag(update.ignoreKey())
        dismissDialog()
    }

    fun downloadUpdate() {
        if (uiState.value.isDownloading) return
        val update = _uiState.value.update ?: return
        if (!AppUpdaterPlatform.supportsDownloadAndInstall) {
            openReleasePage()
            return
        }
        val assetUrl = update.assetUrl
        val assetName = update.assetName
        if (assetUrl == null || assetName == null) {
            openReleasePage()
            return
        }

        scope.launch {
            downloadMutex.withLock {
                _uiState.update { state ->
                    state.copy(
                        isDownloading = true,
                        downloadProgress = 0f,
                        errorMessage = null,
                    )
                }

                val selectedAssetKind = _uiState.value.selectedAssetKind ?: AppUpdateAssetKind.Installer
                AppUpdaterPlatform.downloadApk(
                    assetUrl = assetUrl,
                    assetName = assetName,
                ) { downloadedBytes, totalBytes ->
                    val progress = if (totalBytes != null && totalBytes > 0L) {
                        (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                    } else {
                        null
                    }
                    _uiState.update { state -> state.copy(downloadProgress = progress) }
                }.onSuccess { path ->
                    _uiState.update { state ->
                        state.copy(
                            isDownloading = false,
                            downloadProgress = 1f,
                            downloadedApkPath = path,
                            errorMessage = null,
                        )
                    }
                    if (selectedAssetKind == AppUpdateAssetKind.PortableZip) {
                        AppUpdaterPlatform.openDownloadedFileLocation(path).onFailure { error ->
                            _uiState.update { state ->
                                state.copy(
                                    errorMessage = error.message ?: getString(Res.string.updates_open_release_failed),
                                    showDialog = true,
                                )
                            }
                        }
                    } else {
                        installDownloadedUpdate()
                    }
                }.onFailure { error ->
                    _uiState.update { state ->
                        state.copy(
                            isDownloading = false,
                            downloadProgress = null,
                            downloadedApkPath = null,
                            errorMessage = error.message ?: getString(Res.string.updates_download_failed),
                            showDialog = true,
                        )
                    }
                }
            }
        }
    }

    fun openReleasePage() {
        val releaseUrl = _uiState.value.update?.releaseUrl ?: return
        AppUpdaterPlatform.openReleasePage(releaseUrl).onFailure { error ->
            scope.launch {
                _uiState.update { state ->
                    state.copy(
                        errorMessage = error.message ?: getString(Res.string.updates_open_release_failed),
                        showDialog = true,
                    )
                }
            }
        }
    }

    fun installDownloadedUpdate() {
        val apkPath = _uiState.value.downloadedApkPath ?: return
        if (!AppUpdaterPlatform.canRequestPackageInstalls()) {
            _uiState.update { state -> state.copy(showUnknownSourcesDialog = true, showDialog = true) }
            return
        }

        AppUpdaterPlatform.installDownloadedApk(apkPath).onSuccess {
            _uiState.update { state -> state.copy(showUnknownSourcesDialog = false) }
        }.onFailure { error ->
            scope.launch {
                val fallbackMessage = error.message ?: getString(Res.string.updates_install_failed)
                _uiState.update { state ->
                    state.copy(
                        errorMessage = fallbackMessage,
                        showDialog = true,
                    )
                }
            }
        }
    }

    fun resumeInstallation() {
        if (AppUpdaterPlatform.canRequestPackageInstalls()) {
            installDownloadedUpdate()
        } else {
            AppUpdaterPlatform.openUnknownSourcesSettings()
        }
    }
}

@Composable
fun rememberAppUpdaterController(): AppUpdaterController {
    val scope = rememberCoroutineScope()
    return remember(scope) { AppUpdaterController(scope) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppUpdaterHost(
    controller: AppUpdaterController,
    modifier: Modifier = Modifier,
) {
    if (!AppFeaturePolicy.inAppUpdaterEnabled || !AppUpdaterPlatform.isSupported) {
        return
    }

    val state by controller.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(controller) {
        controller.ensureAutoCheckStarted()
    }

    if (!state.showDialog) return

    val showPrimaryAction =
        state.showUnknownSourcesDialog || state.isDownloading || state.downloadedApkPath != null || state.isUpdateAvailable

    BasicAlertDialog(
        onDismissRequest = {
            if (!state.isDownloading) {
                controller.dismissDialog()
            }
        },
    ) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            shadowElevation = 16.dp,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = when {
                            state.showUnknownSourcesDialog -> stringResource(Res.string.updates_title_allow_installs)
                            state.isUpdateAvailable -> state.update?.title ?: stringResource(Res.string.updates_title_available)
                            else -> stringResource(Res.string.updates_title_status)
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = when {
                            state.showUnknownSourcesDialog -> stringResource(Res.string.updates_message_allow_installs)
                            state.isDownloading -> stringResource(Res.string.updates_message_downloading)
                            state.isUpdateAvailable -> stringResource(Res.string.updates_message_ready)
                            else -> stringResource(Res.string.updates_message_no_updates)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                state.errorMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                state.update?.let { update ->
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (state.isChecking) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = update.tag,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                val assetLine = update.assetName?.let { assetName ->
                                    update.assetSizeBytes?.let(::formatFileSize)?.let { size ->
                                        stringResource(Res.string.updates_asset_line, size, assetName)
                                    } ?: assetName
                                } ?: update.versionCode?.let { build ->
                                    stringResource(Res.string.updates_release_build_line, update.channelLabel, build)
                                } ?: stringResource(Res.string.updates_release_channel_line, update.channelLabel)
                                Text(
                                    text = assetLine,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        if (update.availableAssets.size > 1 && !state.isDownloading) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                update.availableAssets.forEach { asset ->
                                    val selected = state.selectedAssetKind == asset.kind
                                    OutlinedButton(
                                        modifier = Modifier.weight(1f),
                                        onClick = { controller.selectUpdateAsset(asset.kind) },
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            containerColor = if (selected) {
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
                                            } else {
                                                MaterialTheme.colorScheme.surface
                                            },
                                            contentColor = if (selected) {
                                                MaterialTheme.colorScheme.onPrimary
                                            } else {
                                                MaterialTheme.colorScheme.primary
                                            },
                                        ),
                                    ) {
                                        Text(
                                            text = when (asset.kind) {
                                                AppUpdateAssetKind.Installer -> stringResource(Res.string.updates_asset_choice_installer)
                                                AppUpdateAssetKind.PortableZip -> stringResource(Res.string.updates_asset_choice_portable_zip)
                                            },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }

                        if (state.isDownloading || state.downloadProgress != null) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                LinearProgressIndicator(
                                    progress = { (state.downloadProgress ?: 0f).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    text = if (state.downloadProgress != null) {
                                        stringResource(
                                            Res.string.updates_downloading_progress,
                                            ((state.downloadProgress ?: 0f) * 100).toInt().coerceIn(0, 100),
                                        )
                                    } else {
                                        stringResource(Res.string.updates_preparing_download)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        if (update.notes.isNotBlank()) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = stringResource(Res.string.updates_release_notes),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = update.notes,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(180.dp)
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                        .padding(14.dp)
                                        .verticalScroll(rememberScrollState()),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (showPrimaryAction) {
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                when {
                                    state.showUnknownSourcesDialog -> controller.resumeInstallation()
                                    state.downloadedApkPath != null && state.selectedAssetKind == AppUpdateAssetKind.PortableZip -> {
                                        state.downloadedApkPath?.let { path ->
                                            AppUpdaterPlatform.openDownloadedFileLocation(path)
                                        }
                                    }
                                    state.downloadedApkPath != null -> controller.installDownloadedUpdate()
                                    !AppUpdaterPlatform.supportsDownloadAndInstall -> controller.openReleasePage()
                                    else -> controller.downloadUpdate()
                                }
                            },
                            enabled = if (state.showUnknownSourcesDialog || state.downloadedApkPath != null) {
                                true
                            } else {
                                !state.isChecking && !state.isDownloading && state.isUpdateAvailable
                            },
                        ) {
                            Text(
                                when {
                                    state.showUnknownSourcesDialog -> stringResource(Res.string.action_continue)
                                    state.downloadedApkPath != null && state.selectedAssetKind == AppUpdateAssetKind.PortableZip ->
                                        stringResource(Res.string.updates_action_open_download_folder)
                                    state.downloadedApkPath != null -> stringResource(Res.string.action_install)
                                    state.isDownloading -> stringResource(Res.string.updates_message_downloading)
                                    !AppUpdaterPlatform.supportsDownloadAndInstall -> stringResource(Res.string.action_open_release)
                                    else -> stringResource(Res.string.action_update)
                                },
                            )
                        }
                    }

                    if (state.isUpdateAvailable && !state.isDownloading && !state.showUnknownSourcesDialog) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                onClick = controller::ignoreThisVersion,
                            ) {
                                Text(stringResource(Res.string.action_ignore))
                            }

                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                onClick = controller::dismissDialog,
                                enabled = !state.isDownloading,
                            ) {
                                Text(
                                    if (state.isDownloading) {
                                        stringResource(Res.string.updates_message_downloading)
                                    } else {
                                        stringResource(Res.string.action_later)
                                    },
                                )
                            }
                        }
                    } else {
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = controller::dismissDialog,
                            enabled = !state.isDownloading,
                        ) {
                            Text(
                                if (state.isDownloading) {
                                    stringResource(Res.string.updates_message_downloading)
                                } else {
                                    stringResource(Res.string.action_later)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatFileSize(sizeBytes: Long): String {
    if (sizeBytes <= 0L) return "0 ${localizedByteUnit("B")}"
    val units = listOf("B", "KB", "MB", "GB")
    var value = sizeBytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    val roundedValue = if (value >= 10 || unitIndex == 0) {
        value.toInt().toString()
    } else {
        ((value * 10).toInt() / 10.0).toString()
    }
    return "$roundedValue ${localizedByteUnit(units[unitIndex])}"
}
