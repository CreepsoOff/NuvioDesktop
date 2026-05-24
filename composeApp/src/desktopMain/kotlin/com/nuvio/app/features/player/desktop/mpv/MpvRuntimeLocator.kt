package com.nuvio.app.features.player.desktop.mpv

import com.nuvio.app.desktop.DesktopRuntimeLog
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal data class MpvRuntimeResolution(
    val directory: File?,
    val checkedDirectories: List<String>,
    val diagnostics: String,
) {
    val available: Boolean get() = directory?.resolve("mediampv.dll")?.isFile == true
}

internal object MpvRuntimeLocator {
    private val isWindows: Boolean
        get() = System.getProperty("os.name")?.contains("Windows", ignoreCase = true) == true

    fun resolve(): MpvRuntimeResolution {
        if (!isWindows) {
            return MpvRuntimeResolution(
                directory = null,
                checkedDirectories = emptyList(),
                diagnostics = "non-Windows platform; explicit MPV runtime bootstrap skipped",
            )
        }

        val candidates = linkedMapOf<String, File>()
        fun add(label: String, file: File?) {
            if (file != null) candidates.putIfAbsent(label, file)
        }

        val resourcesDir = System.getProperty("compose.application.resources.dir")
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
        val appDir = resourcesDir?.parentFile
        add("appDir/native", appDir?.resolve("native"))
        add("resourcesDir/native", resourcesDir?.resolve("native"))

        add("env:NUVIO_MEDIAMP_RUNTIME_DIR", System.getenv("NUVIO_MEDIAMP_RUNTIME_DIR")?.toFileOrNull())
        System.getenv("NUVIO_MPV_DIR")?.toFileOrNull()?.let { dir ->
            add("env:NUVIO_MPV_DIR", dir)
            add("env:NUVIO_MPV_DIR/bin", dir.resolve("bin"))
        }
        add("property:nuvio.mediamp.runtime.dir", System.getProperty("nuvio.mediamp.runtime.dir")?.toFileOrNull())
        System.getProperty("nuvio.mpv.dir")?.toFileOrNull()?.let { dir ->
            add("property:nuvio.mpv.dir", dir)
            add("property:nuvio.mpv.dir/bin", dir.resolve("bin"))
        }

        javaLibraryPathEntries().forEach { entry ->
            val dir = File(entry)
            add("java.library.path:${dir.safePath()}", dir)
            add("java.library.path/native:${dir.safePath()}", dir.resolve("native"))
        }

        pathEntries().forEach { entry ->
            add("PATH:${entry.safePath()}", entry)
        }

        if (devLookupEnabled()) {
            System.getProperty("user.dir")?.takeIf { it.isNotBlank() }?.let { userDir ->
                val base = File(userDir)
                add("dev:app/native", base.resolve("app/native"))
                add("dev:native", base.resolve("native"))
                add("dev:mediamp/build-ci", base.resolve("mediamp/mediamp-mpv/build-ci"))
                add("dev:mediamp/build-ci/Release", base.resolve("mediamp/mediamp-mpv/build-ci/Release"))
                add("dev:mediamp/libmpv", base.resolve("mediamp/mediamp-mpv/libmpv/lib/windows/x86_64"))
            }
        }

        val stremioHybrid = resolveStremioHybridRuntime(candidates.values)
        stremioHybrid?.let { add("stremio:hybrid-runtime", it) }

        val checked = candidates.map { (label, dir) ->
            "$label=${dir.safePath()} exists=${dir.isDirectory} mediampv=${dir.resolve("mediampv.dll").isFile}"
        }
        val selected = stremioHybrid?.takeIf { it.resolve("mediampv.dll").isFile }
            ?: candidates.values.firstOrNull { it.resolve("mediampv.dll").isFile }
        return MpvRuntimeResolution(
            directory = selected,
            checkedDirectories = checked,
            diagnostics = "selected=${selected?.safePath() ?: "none"} checked=${checked.joinToString(" | ")}",
        )
    }

    private fun resolveStremioHybridRuntime(baseCandidates: Collection<File>): File? {
        val stremioDir = stremioLibmpvDir() ?: return null
        val stremioLibmpv = stremioDir.resolve("libmpv-2.dll")
        if (!stremioLibmpv.isFile) {
            DesktopRuntimeLog.warn("stremioHybrid: libmpv-2.dll missing dir=${stremioDir.safePath()}")
            return null
        }
        val baseRuntime = baseCandidates.firstOrNull { it.resolve("mediampv.dll").isFile } ?: return null
        val hybridDir = localAppDataDir()
            ?.resolve("Nuvio")
            ?.resolve("cache")
            ?.resolve("mpv-stremio-hybrid")
            ?: return null

        return runCatching {
            Files.createDirectories(hybridDir.toPath())
            baseRuntime.listFiles { file -> file.isFile && file.extension.equals("dll", ignoreCase = true) }
                .orEmpty()
                .forEach { source ->
                    val target = hybridDir.resolve(source.name)
                    if (!target.isFile || target.length() != source.length()) {
                        Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            Files.copy(
                stremioLibmpv.toPath(),
                hybridDir.resolve("libmpv-2.dll").toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
            DesktopRuntimeLog.info(
                "stremioHybrid: prepared runtime dir=${hybridDir.safePath()} " +
                    "base=${baseRuntime.safePath()} libmpvSize=${stremioLibmpv.length()}",
            )
            hybridDir
        }.onFailure {
            DesktopRuntimeLog.warn("stremioHybrid: prepare failed message=${it.message}")
        }.getOrNull()
    }

    private fun stremioLibmpvDir(): File? {
        val configured = System.getProperty("nuvio.stremio.libmpv.dir")
            ?: System.getenv("NUVIO_STREMIO_LIBMPV_DIR")
        return configured?.toFileOrNull()
    }

    private fun localAppDataDir(): File? =
        System.getenv("LOCALAPPDATA")
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)

    private fun devLookupEnabled(): Boolean =
        System.getenv("NUVIO_DEV_PLAYER_LOOKUP").equals("true", ignoreCase = true) ||
            System.getProperty("nuvio.dev.player.lookup").equals("true", ignoreCase = true)

    private fun javaLibraryPathEntries(): List<String> =
        System.getProperty("java.library.path")
            ?.split(File.pathSeparatorChar)
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            .orEmpty()

    private fun pathEntries(): List<File> =
        System.getenv("PATH")
            ?.split(File.pathSeparatorChar)
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.map(::File)
            .orEmpty()

    private fun String.toFileOrNull(): File? =
        takeIf { it.isNotBlank() }?.let(::File)
}

internal fun File.safePath(): String = absolutePath.replace("\\", "/")
