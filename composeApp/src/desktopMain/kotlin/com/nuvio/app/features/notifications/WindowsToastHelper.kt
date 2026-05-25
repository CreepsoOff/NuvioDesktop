package com.nuvio.app.features.notifications

import com.nuvio.app.desktop.DesktopRuntimeLog
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Guid.GUID
import com.sun.jna.platform.win32.Ole32Util
import com.sun.jna.platform.win32.WinNT.HRESULT
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale

object WindowsToastHelper {
    private const val appUserModelId = "Nuvio.Desktop"
    private const val shortcutName = "Nuvio.lnk"

    private val isWindows: Boolean
        get() = System.getProperty("os.name")?.lowercase(Locale.US)?.contains("windows") == true

    val isPortableBuild: Boolean by lazy {
        val exe = executableFile() ?: return@lazy true
        val exeDir = exe.parentFile ?: return@lazy true
        val packagedNuvioExe = exe.name.equals("Nuvio.exe", ignoreCase = true)
        val portableMarker = File(exeDir, "Nuvio.portable").exists()
        val installedMarker = File(exeDir, ".installed").exists()
        val installedPath = exeDir.absolutePath.lowercase(Locale.US).contains("program files")

        !packagedNuvioExe || portableMarker || (!installedMarker && !installedPath)
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
        return WindowsToastWinRt.isToastNotifierAvailable(appUserModelId)
    }

    fun showToast(title: String, body: String, deepLinkUrl: String? = null, requestId: String? = null): Boolean {
        if (!isWindows) return false
        if (isPortableBuild) {
            DesktopRuntimeLog.info("Toast: skipping system toast (portable build)")
            return false
        }
        ensureShortcut()
        return WindowsToastWinRt.showToast(appUserModelId, title, body, deepLinkUrl, requestId)
    }

    fun scheduleToast(title: String, body: String, deepLinkUrl: String?, requestId: String?, releaseDateIso: String): Boolean {
        if (!isWindows) return false
        if (isPortableBuild) return false
        ensureShortcut()
        return WindowsToastWinRt.scheduleToast(appUserModelId, title, body, deepLinkUrl, requestId, releaseDateIso)
    }

    fun clearScheduledToasts(): Boolean {
        if (!isWindows) return false
        if (isPortableBuild) return false
        ensureShortcut()
        return WindowsToastWinRt.clearScheduledToasts(appUserModelId)
    }

    // ---- internals ----

    private fun executableDirectory(): String? = executableFile()?.parent

    private fun executableFile(): File? =
        executablePath()?.let(::File)

    private fun executablePath(): String? =
        ProcessHandle.current().info().command().orElse("").takeIf { it.isNotBlank() }

    private fun programsDirectory(): String? =
        System.getenv("APPDATA")?.let { "$it\\Microsoft\\Windows\\Start Menu\\Programs" }

    private fun createShortcut(shortcutPath: String, exePath: String, appId: String): Boolean = runCatching {
        val comScope = initializeComForShortcut()
        try {
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
        } finally {
            comScope.close()
        }
    }.onFailure { DesktopRuntimeLog.error("Toast: shortcut creation failed", it) }
        .getOrDefault(false)

    // ---- JNA COM interop ----

    private val CLSID_ShellLink = GUID.fromString("{00021401-0000-0000-C000-000000000046}")
    private val IID_IShellLinkW = GUID.fromString("{000214F9-0000-0000-C000-000000000046}")
    private val IID_IPersistFile = GUID.fromString("{0000010b-0000-0000-C000-000000000046}")
    private val IID_IPropertyStore = GUID.fromString("{00000138-0000-0000-C000-000000000046}")
    private val PROPERTYKEY_FMTID = Ole32Util.getGUIDFromString("{9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3}")
    private val PROPERTYKEY_PID = 5
    private const val COINIT_APARTMENTTHREADED = 0x2
    private const val S_OK = 0
    private const val S_FALSE = 1
    private const val RPC_E_CHANGED_MODE = -2147417850

    private const val VT_LPWSTR: Short = 31
    private const val IUNKNOWN_QUERY_INTERFACE_INDEX = 0
    private const val IUNKNOWN_RELEASE_INDEX = 2
    private const val ISHELLLINK_SET_DESCRIPTION_INDEX = 7
    private const val ISHELLLINK_SET_WORKING_DIRECTORY_INDEX = 9
    private const val ISHELLLINK_SET_ICON_LOCATION_INDEX = 17
    private const val ISHELLLINK_SET_PATH_INDEX = 20
    private const val IPERSISTFILE_SAVE_INDEX = 6
    private const val IPROPERTYSTORE_SET_VALUE_INDEX = 6
    private const val IPROPERTYSTORE_COMMIT_INDEX = 7

    private fun initializeComForShortcut(): ComScope {
        val hr = Ole32.INSTANCE.CoInitializeEx(null, COINIT_APARTMENTTHREADED).toInt()
        return when (hr) {
            S_OK, S_FALSE -> ComScope(needsUninitialize = true)
            RPC_E_CHANGED_MODE -> ComScope(needsUninitialize = false)
            else -> error("CoInitializeEx failed hr=0x${hr.toUInt().toString(16)}")
        }
    }

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
        try {
            invokeComInt(p, IUNKNOWN_RELEASE_INDEX, p)
        } catch (_: Exception) {
            // Release failures at shutdown are non-fatal
        }
    }

    private fun setShellLinkPath(link: Pointer, path: String) {
        invokeComInt(link, ISHELLLINK_SET_PATH_INDEX, link, WString(path))
    }

    private fun setShellLinkWorkingDir(link: Pointer, dir: String) {
        invokeComInt(link, ISHELLLINK_SET_WORKING_DIRECTORY_INDEX, link, WString(dir))
    }

    private fun setShellLinkDescription(link: Pointer, desc: String) {
        invokeComInt(link, ISHELLLINK_SET_DESCRIPTION_INDEX, link, WString(desc))
    }

    private fun setShellLinkIcon(link: Pointer, path: String, index: Int) {
        invokeComInt(link, ISHELLLINK_SET_ICON_LOCATION_INDEX, link, WString(path), index)
    }

    private fun setAppUserModelId(shellLink: Pointer, appId: String) {
        val propStore = queryInterface(shellLink, IID_IPropertyStore) ?: run {
            DesktopRuntimeLog.warn("Toast: QueryInterface IPropertyStore failed")
            return
        }
        try {
            val pKey = Memory(20)
            val fmtidBytes = PROPERTYKEY_FMTID.toByteArray()
            pKey.write(0, fmtidBytes, 0, 16)
            pKey.setInt(16, PROPERTYKEY_PID)

            val propVariant = Memory(24)
            propVariant.setShort(0, VT_LPWSTR)
            val appIdBytes = (appId + "\u0000").toByteArray(StandardCharsets.UTF_16LE)
            val appIdMemory = Memory(appIdBytes.size.toLong())
            appIdMemory.write(0, appIdBytes, 0, appIdBytes.size)
            propVariant.setPointer(8, appIdMemory)

            val setHr = invokeComInt(propStore, IPROPERTYSTORE_SET_VALUE_INDEX, propStore, pKey, propVariant)
            if (setHr == 0) {
                invokeComInt(propStore, IPROPERTYSTORE_COMMIT_INDEX, propStore)
            } else {
                DesktopRuntimeLog.warn("Toast: IPropertyStore.SetValue failed hr=0x${setHr.toUInt().toString(16)}")
            }
        } finally {
            releaseComObject(propStore)
        }
    }

    private fun saveShortcutFile(shellLink: Pointer, path: String) {
        val persistFile = queryInterface(shellLink, IID_IPersistFile) ?: run {
            DesktopRuntimeLog.warn("Toast: QueryInterface IPersistFile failed")
            return
        }
        try {
            invokeComInt(persistFile, IPERSISTFILE_SAVE_INDEX, persistFile, WString(path), true)
        } finally {
            releaseComObject(persistFile)
        }
    }

    private fun queryInterface(unknown: Pointer, iid: GUID): Pointer? {
        val ppv = PointerByReference()
        val hr = invokeComInt(unknown, IUNKNOWN_QUERY_INTERFACE_INDEX, unknown, iid, ppv)
        if (hr != 0 || ppv.value == null) {
            DesktopRuntimeLog.warn("Toast: QueryInterface failed iid=$iid hr=0x${hr.toUInt().toString(16)}")
            return null
        }
        return ppv.value
    }

    private fun invokeComInt(comObject: Pointer, methodIndex: Int, vararg args: Any?): Int {
        val vtable = comObject.getPointer(0)
        val method = vtable.getPointer(methodIndex * Native.POINTER_SIZE.toLong())
        val function = Function.getFunction(method, Function.ALT_CONVENTION)
        return function.invoke(Int::class.java, args) as Int
    }

    private const val CLSCTX_INPROC_SERVER = 1

    private data class ComScope(
        val needsUninitialize: Boolean,
    ) {
        fun close() {
            if (needsUninitialize) {
                Ole32.INSTANCE.CoUninitialize()
            }
        }
    }

    private interface Ole32 : StdCallLibrary {
        companion object {
            val INSTANCE: Ole32 = Native.load("ole32", Ole32::class.java)
        }
        fun CoInitializeEx(pvReserved: Pointer?, dwCoInit: Int): HRESULT
        fun CoUninitialize()
        fun CoCreateInstance(
            rclsid: GUID, pUnkOuter: Pointer?, dwClsContext: Int,
            riid: GUID, ppv: PointerByReference,
        ): HRESULT
    }

    private object WindowsToastWinRt {
        private val IID_IActivationFactory = GUID.fromString("{00000035-0000-0000-C000-000000000046}")
        private val IID_IXmlDocumentIO = GUID.fromString("{6CD0E74E-EE65-4489-9EBF-CA43E87BA637}")
        private val IID_IToastNotificationFactory = GUID.fromString("{04124B20-82C6-4229-B109-FD9ED4662B53}")
        private val IID_IScheduledToastNotificationFactory = GUID.fromString("{E7BED191-0BB9-4189-8394-31761B476FD7}")
        private val IID_IToastNotificationManagerStatics = GUID.fromString("{50AC103F-D235-4598-BBEF-98FE4D1A3AD4}")
        private const val RO_INIT_MULTITHREADED = 1
        private const val RPC_E_CHANGED_MODE = -2147417850
        private const val E_BOUNDS = -2147483637
        private const val XMLDOCUMENT_LOAD_XML_INDEX = 6
        private const val ACTIVATION_FACTORY_ACTIVATE_INSTANCE_INDEX = 6
        private const val TOAST_NOTIFICATION_FACTORY_CREATE_INDEX = 6
        private const val SCHEDULED_TOAST_FACTORY_CREATE_INDEX = 6
        private const val TOAST_MANAGER_CREATE_NOTIFIER_WITH_ID_INDEX = 7
        private const val TOAST_NOTIFIER_SHOW_INDEX = 6
        private const val TOAST_NOTIFIER_ADD_TO_SCHEDULE_INDEX = 9
        private const val TOAST_NOTIFIER_REMOVE_FROM_SCHEDULE_INDEX = 10
        private const val TOAST_NOTIFIER_GET_SCHEDULED_INDEX = 11
        private const val VECTOR_VIEW_GET_AT_INDEX = 6
        private const val VECTOR_VIEW_SIZE_INDEX = 7
        private const val WINDOWS_EPOCH_OFFSET_MILLIS = 11_644_473_600_000L
        private const val TOAST_ACTION_CONTENT = "Open"

        fun isToastNotifierAvailable(appId: String): Boolean =
            runToastCall("probe") {
                createNotifier(appId).release()
                true
            } == true

        fun showToast(
            appId: String,
            title: String,
            body: String,
            deepLinkUrl: String?,
            requestId: String?,
        ): Boolean =
            runToastCall("show") {
                val notifier = createNotifier(appId)
                val xmlDoc = createXmlDocument(toastXml(title, body, deepLinkUrl, requestId))
                val toast = createToastNotification(xmlDoc)
                try {
                    invokeComChecked(notifier.pointer, TOAST_NOTIFIER_SHOW_INDEX, notifier.pointer, toast.pointer)
                    DesktopRuntimeLog.info("Toast: native toast shown")
                    true
                } finally {
                    toast.release()
                    xmlDoc.release()
                    notifier.release()
                }
            } == true

        fun scheduleToast(
            appId: String,
            title: String,
            body: String,
            deepLinkUrl: String?,
            requestId: String?,
            releaseDateIso: String,
        ): Boolean =
            runToastCall("schedule") {
                val scheduledAt = scheduledInstant(releaseDateIso) ?: return@runToastCall true
                val notifier = createNotifier(appId)
                val xmlDoc = createXmlDocument(toastXml(title, body, deepLinkUrl, requestId))
                val scheduledToast = createScheduledToastNotification(xmlDoc, scheduledAt)
                try {
                    invokeComChecked(notifier.pointer, TOAST_NOTIFIER_ADD_TO_SCHEDULE_INDEX, notifier.pointer, scheduledToast.pointer)
                    DesktopRuntimeLog.info("Toast: native toast scheduled id=${requestId ?: "none"}")
                    true
                } finally {
                    scheduledToast.release()
                    xmlDoc.release()
                    notifier.release()
                }
            } == true

        fun clearScheduledToasts(appId: String): Boolean =
            runToastCall("clear") {
                val notifier = createNotifier(appId)
                val scheduled = PointerByReference()
                try {
                    invokeComChecked(notifier.pointer, TOAST_NOTIFIER_GET_SCHEDULED_INDEX, notifier.pointer, scheduled)
                    val scheduledVector = ComObject(scheduled.value ?: return@runToastCall true)
                    try {
                        val countRef = com.sun.jna.ptr.IntByReference()
                        invokeComChecked(scheduledVector.pointer, VECTOR_VIEW_SIZE_INDEX, scheduledVector.pointer, countRef)
                        var removed = 0
                        for (index in 0 until countRef.value) {
                            val itemRef = PointerByReference()
                            val hr = invokeComInt(scheduledVector.pointer, VECTOR_VIEW_GET_AT_INDEX, scheduledVector.pointer, index, itemRef)
                            if (hr == E_BOUNDS) continue
                            checkHr("IVectorView.GetAt", hr)
                            val scheduledToast = ComObject(itemRef.value ?: continue)
                            try {
                                invokeComChecked(
                                    notifier.pointer,
                                    TOAST_NOTIFIER_REMOVE_FROM_SCHEDULE_INDEX,
                                    notifier.pointer,
                                    scheduledToast.pointer,
                                )
                                removed += 1
                            } finally {
                                scheduledToast.release()
                            }
                        }
                        DesktopRuntimeLog.info("Toast: native scheduled toasts cleared count=$removed")
                        true
                    } finally {
                        scheduledVector.release()
                    }
                } finally {
                    notifier.release()
                }
            } == true

        private fun <T> runToastCall(action: String, block: () -> T): T? {
            val initHr = WinRt.INSTANCE.RoInitialize(RO_INIT_MULTITHREADED)
            val shouldUninitialize = initHr == S_OK || initHr == S_FALSE
            if (initHr != S_OK && initHr != S_FALSE && initHr != RPC_E_CHANGED_MODE) {
                DesktopRuntimeLog.warn("Toast: WinRT init failed action=$action hr=${hrHex(initHr)}")
                return null
            }
            return try {
                block()
            } catch (throwable: Throwable) {
                DesktopRuntimeLog.error("Toast: native WinRT $action failed", throwable)
                null
            } finally {
                if (shouldUninitialize) {
                    WinRt.INSTANCE.RoUninitialize()
                }
            }
        }

        private fun createNotifier(appId: String): ComObject {
            val factory = getFactory(
                className = "Windows.UI.Notifications.ToastNotificationManager",
                iid = IID_IToastNotificationManagerStatics,
            )
            try {
                val notifier = PointerByReference()
                withHString(appId) { hstring ->
                    invokeComChecked(
                        factory.pointer,
                        TOAST_MANAGER_CREATE_NOTIFIER_WITH_ID_INDEX,
                        factory.pointer,
                        hstring,
                        notifier,
                    )
                }
                return ComObject(notifier.value ?: error("CreateToastNotifierWithId returned null"))
            } finally {
                factory.release()
            }
        }

        private fun createXmlDocument(xml: String): ComObject {
            val factory = getFactory("Windows.Data.Xml.Dom.XmlDocument", IID_IActivationFactory)
            try {
                val documentRef = PointerByReference()
                invokeComChecked(factory.pointer, ACTIVATION_FACTORY_ACTIVATE_INSTANCE_INDEX, factory.pointer, documentRef)
                val document = ComObject(documentRef.value ?: error("XmlDocument ActivateInstance returned null"))
                val xmlIo = queryInterface(document.pointer, IID_IXmlDocumentIO)
                    ?: error("XmlDocument QueryInterface IXmlDocumentIO returned null")
                try {
                    withHString(xml) { hstring ->
                        invokeComChecked(xmlIo, XMLDOCUMENT_LOAD_XML_INDEX, xmlIo, hstring)
                    }
                    return document
                } finally {
                    releaseComObject(xmlIo)
                }
            } finally {
                factory.release()
            }
        }

        private fun createToastNotification(xmlDocument: ComObject): ComObject {
            val factory = getFactory(
                className = "Windows.UI.Notifications.ToastNotification",
                iid = IID_IToastNotificationFactory,
            )
            try {
                val toastRef = PointerByReference()
                invokeComChecked(
                    factory.pointer,
                    TOAST_NOTIFICATION_FACTORY_CREATE_INDEX,
                    factory.pointer,
                    xmlDocument.pointer,
                    toastRef,
                )
                val toast = toastRef.value ?: error("CreateToastNotification returned null")
                return ComObject(toast)
            } finally {
                factory.release()
            }
        }

        private fun createScheduledToastNotification(xmlDocument: ComObject, deliveryTime: OffsetDateTime): ComObject {
            val factory = getFactory(
                className = "Windows.UI.Notifications.ScheduledToastNotification",
                iid = IID_IScheduledToastNotificationFactory,
            )
            try {
                val toastRef = PointerByReference()
                invokeComChecked(
                    factory.pointer,
                    SCHEDULED_TOAST_FACTORY_CREATE_INDEX,
                    factory.pointer,
                    xmlDocument.pointer,
                    deliveryTime.toWinRtDateTime(),
                    toastRef,
                )
                return ComObject(toastRef.value ?: error("CreateScheduledToastNotification returned null"))
            } finally {
                factory.release()
            }
        }

        private fun getFactory(className: String, iid: GUID): ComObject {
            val factory = PointerByReference()
            withHString(className) { hstring ->
                checkHr("RoGetActivationFactory($className)", WinRt.INSTANCE.RoGetActivationFactory(hstring, iid, factory))
            }
            return ComObject(factory.value ?: error("RoGetActivationFactory($className) returned null"))
        }

        private fun toastXml(title: String, body: String, deepLinkUrl: String?, requestId: String?): String {
            val launch = requestId?.takeIf(String::isNotBlank)?.let { " launch='${it.escapeXml()}'" }.orEmpty()
            val action = deepLinkUrl
                ?.takeIf(String::isNotBlank)
                ?.let { "<actions><action content='$TOAST_ACTION_CONTENT' arguments='${it.escapeXml()}' activationType='protocol'/></actions>" }
                .orEmpty()
            return "<toast$launch><visual><binding template='ToastGeneric'>" +
                "<text>${title.escapeXml()}</text><text>${body.escapeXml()}</text>" +
                "</binding></visual>$action</toast>"
        }

        private fun scheduledInstant(releaseDateIso: String): OffsetDateTime? =
            runCatching {
                LocalDate.parse(releaseDateIso)
                    .atTime(EpisodeReleaseNotificationHour, EpisodeReleaseNotificationMinute)
                    .atZone(ZoneId.systemDefault())
                    .toOffsetDateTime()
            }.getOrNull()?.takeIf { it.toInstant().isAfter(java.time.Instant.now()) }

        private fun OffsetDateTime.toWinRtDateTime(): Long =
            (toInstant().toEpochMilli() + WINDOWS_EPOCH_OFFSET_MILLIS) * 10_000L

        private fun String.escapeXml(): String =
            buildString(length) {
                this@escapeXml.forEach { char ->
                    when (char) {
                        '&' -> append("&amp;")
                        '<' -> append("&lt;")
                        '>' -> append("&gt;")
                        '"' -> append("&quot;")
                        '\'' -> append("&apos;")
                        else -> append(char)
                    }
                }
            }

        private inline fun <T> withHString(value: String, block: (Pointer) -> T): T {
            val hstring = PointerByReference()
            checkHr("WindowsCreateString", WinRt.INSTANCE.WindowsCreateString(WString(value), value.length, hstring))
            try {
                return block(hstring.value)
            } finally {
                WinRt.INSTANCE.WindowsDeleteString(hstring.value)
            }
        }

        private fun invokeComChecked(comObject: Pointer, methodIndex: Int, vararg args: Any?) {
            checkHr("COM method $methodIndex", invokeComInt(comObject, methodIndex, *args))
        }

        private fun checkHr(operation: String, hr: Int) {
            if (hr < 0) error("$operation failed hr=${hrHex(hr)}")
        }

        private fun hrHex(hr: Int): String = "0x${hr.toUInt().toString(16).padStart(8, '0')}"

        private data class ComObject(val pointer: Pointer) {
            fun release() = releaseComObject(pointer)
        }

        private interface WinRt : StdCallLibrary {
            companion object {
                val INSTANCE: WinRt = Native.load("combase", WinRt::class.java)
            }

            fun RoInitialize(initType: Int): Int
            fun RoUninitialize()
            fun RoGetActivationFactory(activatableClassId: Pointer, iid: GUID, factory: PointerByReference): Int
            fun WindowsCreateString(sourceString: WString, length: Int, string: PointerByReference): Int
            fun WindowsDeleteString(string: Pointer?): Int
        }
    }
}
