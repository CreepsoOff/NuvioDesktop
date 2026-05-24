package com.nuvio.app.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary
import java.io.File

internal object WindowsNativeBootstrap {
    private const val LOAD_LIBRARY_SEARCH_DEFAULT_DIRS = 0x00001000
    private const val LOAD_LIBRARY_SEARCH_USER_DIRS = 0x00000400
    private const val DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2 = -4L
    private const val PROCESS_PER_MONITOR_DPI_AWARE = 2

    private val isWindows: Boolean
        get() = System.getProperty("os.name")?.contains("Windows", ignoreCase = true) == true

    private var bootstrapped = false
    private var dpiAwarenessConfigured = false

    @Synchronized
    fun configureProcessDpiAwareness() {
        if (!isWindows) {
            DesktopRuntimeLog.info("dpiAwareness skipped: non-Windows platform")
            return
        }
        if (dpiAwarenessConfigured) {
            DesktopRuntimeLog.info("dpiAwareness skipped: already attempted")
            return
        }
        dpiAwarenessConfigured = true

        val user32 = runCatching { Native.load("user32", User32::class.java) }
            .onFailure { DesktopRuntimeLog.error("dpiAwareness failed: cannot load user32", it) }
            .getOrNull()

        if (user32 != null) {
            val context = Pointer.createConstant(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2)
            val perMonitorV2 = runCatching { user32.SetProcessDpiAwarenessContext(context) }
                .onFailure { DesktopRuntimeLog.warn("dpiAwareness SetProcessDpiAwarenessContext threw ${it::class.simpleName}:${it.message}") }
                .getOrNull()
            val lastError = Native.getLastError()
            DesktopRuntimeLog.info(
                "dpiAwareness SetProcessDpiAwarenessContext(PER_MONITOR_AWARE_V2) result=$perMonitorV2 lastError=$lastError",
            )
            if (perMonitorV2 == true) return
        }

        val shcore = runCatching { Native.load("shcore", Shcore::class.java) }
            .onFailure { DesktopRuntimeLog.warn("dpiAwareness fallback unavailable: cannot load shcore ${it::class.simpleName}:${it.message}") }
            .getOrNull()
        if (shcore != null) {
            val result = runCatching { shcore.SetProcessDpiAwareness(PROCESS_PER_MONITOR_DPI_AWARE) }
                .onFailure { DesktopRuntimeLog.warn("dpiAwareness SetProcessDpiAwareness threw ${it::class.simpleName}:${it.message}") }
                .getOrNull()
            DesktopRuntimeLog.info("dpiAwareness SetProcessDpiAwareness(PER_MONITOR) hresult=$result")
            if (result == 0) return
        }

        if (user32 != null) {
            val systemAware = runCatching { user32.SetProcessDPIAware() }
                .onFailure { DesktopRuntimeLog.warn("dpiAwareness SetProcessDPIAware threw ${it::class.simpleName}:${it.message}") }
                .getOrNull()
            DesktopRuntimeLog.info("dpiAwareness SetProcessDPIAware fallback result=$systemAware lastError=${Native.getLastError()}")
        }
    }

    @Synchronized
    fun bootstrap() {
        if (!isWindows) {
            DesktopRuntimeLog.info("nativeBootstrap skipped: non-Windows platform")
            return
        }
        if (bootstrapped) {
            DesktopRuntimeLog.info("nativeBootstrap skipped: already completed")
            return
        }

        val nativeDir = resolveNativeDir()
        DesktopRuntimeLog.info(
            "nativeBootstrap mode=${nativeDir?.mode ?: "unresolved"} " +
                "nativeDir=${nativeDir?.dir?.safePath() ?: "unresolved"}",
        )
        val nativeDirectory = nativeDir?.dir
        if (nativeDirectory == null || !nativeDirectory.isDirectory) {
            DesktopRuntimeLog.error("nativeBootstrap failed: native directory not found")
            return
        }

        logNativeInventory(nativeDirectory)

        val mediampDll = nativeDirectory.resolve("mediampv.dll")
        if (!mediampDll.isFile) {
            DesktopRuntimeLog.error("nativeBootstrap failed: mediampv.dll missing at ${mediampDll.safePath()}")
            return
        }

        val kernel32 = runCatching { Native.load("kernel32", Kernel32::class.java) }
            .onFailure { DesktopRuntimeLog.error("nativeBootstrap failed: cannot load kernel32", it) }
            .getOrNull()
            ?: return

        val flags = LOAD_LIBRARY_SEARCH_DEFAULT_DIRS or LOAD_LIBRARY_SEARCH_USER_DIRS
        val setDefaultResult = runCatching { kernel32.SetDefaultDllDirectories(flags) }
        DesktopRuntimeLog.info(
            "nativeBootstrap SetDefaultDllDirectories(flags=$flags) result=${setDefaultResult.getOrNull()} lastError=${Native.getLastError()}",
        )
        setDefaultResult.onFailure {
            DesktopRuntimeLog.error("nativeBootstrap SetDefaultDllDirectories threw", it)
        }

        val addResult = runCatching { kernel32.AddDllDirectory(WString(nativeDirectory.absolutePath)) }
        DesktopRuntimeLog.info(
            "nativeBootstrap AddDllDirectory result=${addResult.getOrNull() != null} lastError=${Native.getLastError()}",
        )
        addResult.onFailure {
            DesktopRuntimeLog.error("nativeBootstrap AddDllDirectory threw", it)
        }

        val setDirResult = runCatching { kernel32.SetDllDirectoryW(WString(nativeDirectory.absolutePath)) }
        DesktopRuntimeLog.info(
            "nativeBootstrap SetDllDirectoryW result=${setDirResult.getOrNull()} lastError=${Native.getLastError()}",
        )
        setDirResult.onFailure {
            DesktopRuntimeLog.error("nativeBootstrap SetDllDirectoryW threw", it)
        }

        runCatching {
            System.load(mediampDll.absolutePath)
        }.onSuccess {
            bootstrapped = true
            DesktopRuntimeLog.info("nativeBootstrap System.load success dll=${mediampDll.safePath()}")
        }.onFailure {
            DesktopRuntimeLog.error("nativeBootstrap System.load failed dll=${mediampDll.safePath()}", it)
        }
    }

    private data class NativeDir(
        val dir: File,
        val mode: String,
    )

    private fun resolveNativeDir(): NativeDir? {
        val resourcesDir = System.getProperty("compose.application.resources.dir")
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
        val appDir = resourcesDir?.parentFile
        val candidates = buildList {
            appDir?.resolve("native")?.let { add(NativeDir(it, "distributable")) }
            javaLibraryPathEntries().map(::File).forEach { entry ->
                add(NativeDir(entry, "java.library.path"))
                add(NativeDir(entry.resolve("native"), "java.library.path/native"))
            }
            System.getProperty("user.dir")?.takeIf { it.isNotBlank() }?.let { userDir ->
                val base = File(userDir)
                add(NativeDir(File(base, "app/native"), "user.dir/app/native"))
                add(NativeDir(File(base, "native"), "user.dir/native"))
                add(NativeDir(File(base, "mediamp/mediamp-mpv/build-ci"), "dev/build-ci"))
                add(NativeDir(File(base, "mediamp/mediamp-mpv/build-ci/Release"), "dev/build-ci/Release"))
                add(NativeDir(File(base, "mediamp/mediamp-mpv/libmpv/lib/windows/x86_64"), "dev/libmpv"))
            }
        }
        return candidates.firstOrNull { it.dir.resolve("mediampv.dll").isFile }
            ?: candidates.firstOrNull { it.dir.isDirectory }
    }

    private fun javaLibraryPathEntries(): List<String> =
        System.getProperty("java.library.path")
            ?.split(File.pathSeparatorChar)
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            .orEmpty()

    private fun logNativeInventory(nativeDir: File) {
        val dlls = nativeDir.listFiles { file -> file.isFile && file.extension.equals("dll", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
        DesktopRuntimeLog.info("nativeBootstrap dllCount=${dlls.size}")
        DesktopRuntimeLog.info("nativeBootstrap has mediampv.dll=${nativeDir.resolve("mediampv.dll").isFile}")
        DesktopRuntimeLog.info("nativeBootstrap has libmpv-2.dll=${nativeDir.resolve("libmpv-2.dll").isFile}")
        DesktopRuntimeLog.info("nativeBootstrap has MSVCP140.dll=${nativeDir.hasDll("MSVCP140.dll")}")
        DesktopRuntimeLog.info("nativeBootstrap has VCRUNTIME140.dll=${nativeDir.hasDll("VCRUNTIME140.dll")}")
        DesktopRuntimeLog.info("nativeBootstrap has VCRUNTIME140_1.dll=${nativeDir.hasDll("VCRUNTIME140_1.dll")}")
        DesktopRuntimeLog.info("nativeBootstrap dlls=${dlls.joinToString(",") { it.name }}")
    }

    private fun File.safePath(): String = DesktopRuntimeLog.safePath(this)

    private fun File.hasDll(name: String): Boolean =
        listFiles { file -> file.isFile && file.name.equals(name, ignoreCase = true) }?.isNotEmpty() == true

    private interface Kernel32 : StdCallLibrary, Library {
        fun SetDefaultDllDirectories(directoryFlags: Int): Boolean
        fun AddDllDirectory(newDirectory: WString): Pointer?
        fun SetDllDirectoryW(pathName: WString): Boolean
    }

    private interface User32 : StdCallLibrary, Library {
        fun SetProcessDpiAwarenessContext(value: Pointer): Boolean
        fun SetProcessDPIAware(): Boolean
    }

    private interface Shcore : StdCallLibrary, Library {
        fun SetProcessDpiAwareness(value: Int): Int
    }
}
