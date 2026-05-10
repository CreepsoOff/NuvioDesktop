package com.nuvio.app.features.notifications

import com.nuvio.app.desktop.DesktopRuntimeLog
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Guid.GUID
import com.sun.jna.platform.win32.Ole32Util
import com.sun.jna.platform.win32.WinNT.HRESULT
import com.sun.jna.ptr.PointerByReference
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.*

object WindowsToastHelper {
    private const val appUserModelId = "Nuvio.Desktop"
    private const val shortcutName = "Nuvio.lnk"

    private val isWindows: Boolean
        get() = System.getProperty("os.name")?.lowercase(Locale.US)?.contains("windows") == true

    val isPortableBuild: Boolean by lazy {
        val exeDir = executableDirectory() ?: return@lazy true
        !File(exeDir, ".installed").exists() &&
            exeDir.lowercase().let { !it.contains("program files") }
    }

    val systemToastsSupported: Boolean by lazy {
        isWindows && !isPortableBuild
    }

    fun ensureShortcut(): Boolean {
        if (!isWindows) return false
        val programsDir = programsDirectory() ?: run {
            DesktopRuntimeLog.warn("Toast: could not resolve Start Menu Programs directory")
            return false
        }
        val nuvioDir = File(programsDir, "Nuvio")
        nuvioDir.mkdirs()
        val shortcutFile = File(nuvioDir, shortcutName)
        val exePath = executablePath() ?: run {
            DesktopRuntimeLog.warn("Toast: could not resolve executable path")
            return false
        }
        return createShortcut(shortcutFile.absolutePath, exePath, appUserModelId).also { success ->
            if (success) DesktopRuntimeLog.info("Toast: shortcut created appId=$appUserModelId exe=$exePath")
            else DesktopRuntimeLog.error("Toast: failed to create shortcut")
        }
    }

    fun isToastNotifierAvailable(): Boolean {
        if (!isWindows) return false
        return runPowerShellProbe()
    }

    fun showToast(title: String, body: String, deepLinkUrl: String? = null, requestId: String? = null): Boolean {
        if (!isWindows) return false
        if (isPortableBuild) {
            DesktopRuntimeLog.info("Toast: skipping system toast (portable build)")
            return false
        }
        ensureShortcut()
        return showPowerShellToast(title, body, deepLinkUrl, requestId, scheduled = false)
    }

    fun scheduleToast(title: String, body: String, deepLinkUrl: String?, requestId: String?, releaseDateIso: String): Boolean {
        if (!isWindows) return false
        if (isPortableBuild) return false
        ensureShortcut()
        return showPowerShellToast(title, body, deepLinkUrl, requestId, scheduled = true, releaseDateIso)
    }

    fun clearScheduledToasts(): Boolean {
        if (!isWindows) return false
        return runCatching { runPowerShell(clearScheduledScript) }.isSuccess
    }

    // ---- internals ----

    private fun executableDirectory(): String? {
        val path = executablePath() ?: return null
        return File(path).parent
    }

    private fun executablePath(): String? =
        ProcessHandle.current().info().command().orElse("")

    private fun programsDirectory(): String? =
        System.getenv("APPDATA")?.let { "$it\\Microsoft\\Windows\\Start Menu\\Programs" }

    private fun createShortcut(shortcutPath: String, exePath: String, appId: String): Boolean = runCatching {
        val shellLink = createComObject(CLSID_ShellLink, IID_IShellLinkW)
            ?: error("Could not create ShellLink COM object")
        try {
            setShellLinkPath(shellLink, exePath)
            setShellLinkWorkingDir(shellLink, File(exePath).parent ?: "")
            setShellLinkDescription(shellLink, "Nuvio")
            setShellLinkIcon(shellLink, exePath, 0)
            setAppUserModelId(shellLink, appId)
            saveShortcutFile(shellLink, shortcutPath)
            true
        } finally {
            releaseComObject(shellLink)
        }
    }.onFailure { DesktopRuntimeLog.error("Toast: shortcut creation failed", it) }
        .getOrDefault(false)

    private fun runPowerShellProbe(): Boolean {
        val env = mapOf("NUVIO_TOAST_AUMID" to appUserModelId)
        return runPowerShell("""
            Add-Type -AssemblyName System.Runtime.WindowsRuntime | Out-Null
            ${'$'}notifier = [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier(${'$'}env:NUVIO_TOAST_AUMID)
            if (${'$'}null -eq ${'$'}notifier) { throw 'CreateToastNotifier returned null' }
            Write-Output 'ok'
        """.trimIndent(), env).isSuccess
    }

    private fun showPowerShellToast(title: String, body: String, deepLinkUrl: String?, requestId: String?, scheduled: Boolean, releaseDateIso: String? = null): Boolean {
        val env = mutableMapOf(
            "NUVIO_TOAST_AUMID" to appUserModelId,
            "NUVIO_TOAST_TITLE" to title,
            "NUVIO_TOAST_BODY" to body,
        )
        if (!deepLinkUrl.isNullOrBlank()) env["NUVIO_TOAST_DEEP_LINK"] = deepLinkUrl
        if (!requestId.isNullOrBlank()) env["NUVIO_TOAST_REQUEST_ID"] = requestId
        if (scheduled && releaseDateIso != null) env["NUVIO_TOAST_RELEASE_DATE"] = releaseDateIso

        val script = if (scheduled && releaseDateIso != null) scheduleToastScript() else showToastScript()
        return runPowerShell(script, env).isSuccess
    }

    private fun scheduleToastScript() = """
        Add-Type -AssemblyName System.Runtime.WindowsRuntime | Out-Null
        function Escape-Xml([string]${'$'}v) { if ([string]::IsNullOrEmpty(${'$'}v)) { return '' }; return [System.Security.SecurityElement]::Escape(${'$'}v) }
        ${'$'}rd = ${'$'}env:NUVIO_TOAST_RELEASE_DATE
        ${'$'}pd = [datetime]::ParseExact(${'$'}rd, 'yyyy-MM-dd', [System.Globalization.CultureInfo]::InvariantCulture)
        ${'$'}off = [TimeZoneInfo]::Local.GetUtcOffset(${'$'}pd)
        ${'$'}sat = [datetimeoffset]::new(${'$'}pd.Year, ${'$'}pd.Month, ${'$'}pd.Day, 9, 0, 0, ${'$'}off)
        if (${'$'}sat -le [datetimeoffset]::Now) { exit 0 }
        ${'$'}t = Escape-Xml ${'$'}env:NUVIO_TOAST_TITLE
        ${'$'}b = Escape-Xml ${'$'}env:NUVIO_TOAST_BODY
        ${'$'}dl = Escape-Xml ${'$'}env:NUVIO_TOAST_DEEP_LINK
        ${'$'}rid = Escape-Xml ${'$'}env:NUVIO_TOAST_REQUEST_ID
        ${'$'}act = ''; if (-not [string]::IsNullOrWhiteSpace(${'$'}dl)) { ${'$'}act = \"<actions><action content='Open' arguments='${'$'}dl' activationType='protocol'/></actions>\" }
        ${'$'}x = \"<toast launch='${'$'}rid'><visual><binding template='ToastGeneric'><text>${'$'}t</text><text>${'$'}b</text></binding></visual>${'$'}act</toast>\"
        ${'$'}d = New-Object Windows.Data.Xml.Dom.XmlDocument; ${'$'}d.LoadXml(${'$'}x)
        ${'$'}st = New-Object Windows.UI.Notifications.ScheduledToastNotification ${'$'}d, ${'$'}sat
        [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier(${'$'}env:NUVIO_TOAST_AUMID).AddToSchedule(${'$'}st)
        Write-Output 'scheduled'
    """.trimIndent()

    private fun showToastScript() = """
        Add-Type -AssemblyName System.Runtime.WindowsRuntime | Out-Null
        function Escape-Xml([string]${'$'}v) { if ([string]::IsNullOrEmpty(${'$'}v)) { return '' }; return [System.Security.SecurityElement]::Escape(${'$'}v) }
        ${'$'}t = Escape-Xml ${'$'}env:NUVIO_TOAST_TITLE
        ${'$'}b = Escape-Xml ${'$'}env:NUVIO_TOAST_BODY
        ${'$'}dl = Escape-Xml ${'$'}env:NUVIO_TOAST_DEEP_LINK
        ${'$'}act = ''; if (-not [string]::IsNullOrWhiteSpace(${'$'}dl)) { ${'$'}act = \"<actions><action content='Open' arguments='${'$'}dl' activationType='protocol'/></actions>\" }
        ${'$'}x = \"<toast><visual><binding template='ToastGeneric'><text>${'$'}t</text><text>${'$'}b</text></binding></visual>${'$'}act</toast>\"
        ${'$'}d = New-Object Windows.Data.Xml.Dom.XmlDocument; ${'$'}d.LoadXml(${'$'}x)
        ${'$'}toast = New-Object Windows.UI.Notifications.ToastNotification ${'$'}d
        [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier(${'$'}env:NUVIO_TOAST_AUMID).Show(${'$'}toast)
        Write-Output 'shown'
    """.trimIndent()

    private val clearScheduledScript = """
        Add-Type -AssemblyName System.Runtime.WindowsRuntime | Out-Null
        ${'$'}n = [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier(${'$'}env:NUVIO_TOAST_AUMID)
        foreach (${'$'}s in ${'$'}n.GetScheduledToastNotifications()) { ${'$'}n.RemoveFromSchedule(${'$'}s) }
        Write-Output 'cleared'
    """.trimIndent()

    private fun runPowerShell(script: String, env: Map<String, String> = emptyMap()): Result<String> = runCatching {
        val encoded = Base64.getEncoder()
            .encodeToString(script.replace("\n", "\r\n").toByteArray(StandardCharsets.UTF_16LE))
        val process = ProcessBuilder(
            "powershell.exe", "-NoProfile", "-NonInteractive",
            "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded,
        ).apply {
            redirectErrorStream(true)
            environment().putAll(env)
        }.start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        val exitCode = process.waitFor()
        if (exitCode != 0) error("powershell exit=$exitCode output=$output")
        output
    }

    // ---- JNA COM interop ----

    private val CLSID_ShellLink = GUID.fromString("{00021401-0000-0000-C000-000000000046}")
    private val IID_IShellLinkW = GUID.fromString("{000214F9-0000-0000-C000-000000000046}")
    private val IID_IPersistFile = GUID.fromString("{0000010b-0000-0000-C000-000000000046}")
    private val IID_IPropertyStore = GUID.fromString("{00000138-0000-0000-C000-000000000046}")
    private val PROPERTYKEY_FMTID = Ole32Util.getGUIDFromString("{9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3}")
    private val PROPERTYKEY_PID = 5

    private fun createComObject(clsid: GUID, iid: GUID): Pointer? {
        val ppv = PointerByReference()
        val hr = Ole32.INSTANCE.CoCreateInstance(clsid, null, CLSCTX_INPROC_SERVER, iid, ppv).toInt()
        if (hr != 0) {
            DesktopRuntimeLog.error("Toast: CoCreateInstance failed hr=0x${hr.toUInt().toString(16)}")
            return null
        }
        return ppv.value
    }

    private fun releaseComObject(p: Pointer) {
        Unknown.INSTANCE.Release(p)
    }

    private fun setShellLinkPath(link: Pointer, path: String) {
        IShellLinkW.INSTANCE.SetPath(link, WString(path))
    }

    private fun setShellLinkWorkingDir(link: Pointer, dir: String) {
        IShellLinkW.INSTANCE.SetWorkingDirectory(link, WString(dir))
    }

    private fun setShellLinkDescription(link: Pointer, desc: String) {
        IShellLinkW.INSTANCE.SetDescription(link, WString(desc))
    }

    private fun setShellLinkIcon(link: Pointer, path: String, index: Int) {
        IShellLinkW.INSTANCE.SetIconLocation(link, WString(path), index)
    }

    private fun setAppUserModelId(shellLink: Pointer, appId: String) {
        val pps = PointerByReference()
        val hr = IShellLinkW.INSTANCE.QueryInterface(shellLink, IID_IPropertyStore, pps).toInt()
        if (hr != 0 || pps.value == null) {
            DesktopRuntimeLog.warn("Toast: QueryInterface IPropertyStore failed hr=0x${hr.toUInt().toString(16)}")
            return
        }
        val propStore = pps.value
        try {
            val pKey = Memory(20)
            val fmtidBytes = PROPERTYKEY_FMTID.toByteArray()
            pKey.write(0, fmtidBytes, 0, 16)
            pKey.setInt(16, PROPERTYKEY_PID)

            val propVariant = Memory(24)
            propVariant.setShort(0, 31) // VT_LPWSTR = 31
            val appIdBytes = (appId + "\u0000").toByteArray(StandardCharsets.UTF_16LE)
            val appIdMemory = Memory(appIdBytes.size.toLong())
            appIdMemory.write(0, appIdBytes, 0, appIdBytes.size)
            propVariant.setPointer(8, appIdMemory)

            val setHr = IPropertyStore.INSTANCE.SetValue(propStore, pKey, propVariant).toInt()
            if (setHr == 0) {
                IPropertyStore.INSTANCE.Commit(propStore)
            } else {
                DesktopRuntimeLog.warn("Toast: IPropertyStore.SetValue failed hr=0x${setHr.toUInt().toString(16)}")
            }
        } finally {
            Unknown.INSTANCE.Release(propStore)
        }
    }

    private fun saveShortcutFile(shellLink: Pointer, path: String) {
        val pps = PointerByReference()
        val hr = IShellLinkW.INSTANCE.QueryInterface(shellLink, IID_IPersistFile, pps).toInt()
        if (hr != 0 || pps.value == null) {
            DesktopRuntimeLog.warn("Toast: QueryInterface IPersistFile failed hr=0x${hr.toUInt().toString(16)}")
            return
        }
        IPersistFile.INSTANCE.Save(pps.value, WString(path), true)
    }

    private const val CLSCTX_INPROC_SERVER = 1

    private interface Ole32 : com.sun.jna.Library {
        companion object {
            val INSTANCE: Ole32 = Native.load("ole32", Ole32::class.java)
        }
        fun CoCreateInstance(
            rclsid: GUID, pUnkOuter: Pointer?, dwClsContext: Int,
            riid: GUID, ppv: PointerByReference,
        ): HRESULT
    }

    private interface Unknown : com.sun.jna.Library {
        companion object {
            val INSTANCE: Unknown = Native.load("ole32", Unknown::class.java)
        }
        fun Release(pUnknown: Pointer): Int
    }

    private interface IShellLinkW : com.sun.jna.Library {
        companion object {
            val INSTANCE: IShellLinkW = Native.load("shell32", IShellLinkW::class.java)
        }
        fun SetPath(link: Pointer, path: WString): HRESULT
        fun SetWorkingDirectory(link: Pointer, dir: WString): HRESULT
        fun SetDescription(link: Pointer, desc: WString): HRESULT
        fun SetIconLocation(link: Pointer, path: WString, index: Int): HRESULT
        fun QueryInterface(link: Pointer, riid: GUID, ppv: PointerByReference): HRESULT
    }

    private interface IPersistFile : com.sun.jna.Library {
        companion object {
            val INSTANCE: IPersistFile = Native.load("ole32", IPersistFile::class.java)
        }
        fun Save(persistFile: Pointer, path: WString, remember: Boolean): HRESULT
    }

    private interface IPropertyStore : com.sun.jna.Library {
        companion object {
            val INSTANCE: IPropertyStore = Native.load("ole32", IPropertyStore::class.java)
        }
        fun SetValue(propStore: Pointer, key: Pointer, value: Pointer): HRESULT
        fun Commit(propStore: Pointer): HRESULT
    }
}
