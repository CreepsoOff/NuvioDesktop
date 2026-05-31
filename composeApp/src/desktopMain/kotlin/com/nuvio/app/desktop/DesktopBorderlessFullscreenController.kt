package com.nuvio.app.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.WindowPlacement
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import java.awt.Frame
import java.awt.Rectangle
import java.awt.Toolkit

internal object DesktopBorderlessFullscreenController {
    private const val GWL_STYLE = -16
    private const val GWL_EXSTYLE = -20

    private const val WS_CAPTION = 0x00C00000L
    private const val WS_SYSMENU = 0x00080000L
    private const val WS_THICKFRAME = 0x00040000L
    private const val WS_MINIMIZEBOX = 0x00020000L
    private const val WS_MAXIMIZEBOX = 0x00010000L
    private const val WS_POPUP = 0x80000000L
    private const val WS_OVERLAPPEDWINDOW = WS_CAPTION or WS_SYSMENU or WS_THICKFRAME or WS_MINIMIZEBOX or WS_MAXIMIZEBOX

    private const val WS_EX_DLGMODALFRAME = 0x00000001L
    private const val WS_EX_WINDOWEDGE = 0x00000100L
    private const val WS_EX_CLIENTEDGE = 0x00000200L
    private const val WS_EX_STATICEDGE = 0x00020000L

    private const val SWP_NOOWNERZORDER = 0x0200
    private const val SWP_FRAMECHANGED = 0x0020
    private const val SWP_SHOWWINDOW = 0x0040
    private const val MONITOR_DEFAULTTONEAREST = 0x00000002

    private val HWND_TOPMOST: Pointer = Pointer.createConstant(-1)
    private val HWND_NOTOPMOST: Pointer = Pointer.createConstant(-2)

    private val isWindows: Boolean
        get() = System.getProperty("os.name")?.contains("Windows", ignoreCase = true) == true

    private var snapshot: FullscreenSnapshot? = null
    private val user32: User32? by lazy {
        runCatching { Native.load("user32", User32::class.java) }
            .onFailure { DesktopRuntimeLog.error("borderlessFullscreen: cannot load user32", it) }
            .getOrNull()
    }

    var revision by mutableIntStateOf(0)
        private set

    val isFullscreenActive: Boolean
        get() = snapshot != null

    fun toggle(window: ComposeWindow) {
        DesktopRuntimeLog.info(
            "borderlessFullscreen: toggle requested fullscreen=${isFullscreen(window)} " +
                "placement=${window.placement} extendedState=${window.extendedState} bounds=${window.bounds.shortLog()}",
        )
        if (isFullscreen(window)) {
            exit(window)
        } else {
            enter(window)
        }
    }

    fun enter(window: ComposeWindow) {
        if (!isWindows) {
            enterComposeFullscreen(window)
            return
        }
        if (isFullscreen(window)) return

        val handle = resolveHandle(window)
        val native = user32
        if (handle == null || native == null) {
            DesktopRuntimeLog.warn("borderlessFullscreen: native handle unavailable, falling back to Compose fullscreen")
            enterComposeFullscreen(window)
            return
        }

        val currentStyle = native.getWindowLongPtr(handle, GWL_STYLE)
        val currentExStyle = native.getWindowLongPtr(handle, GWL_EXSTYLE)
        val previousBounds = Rectangle(window.bounds)
        val previousNativeBounds = native.currentWindowBounds(handle)
        val targetBounds = native.currentMonitorBounds(handle) ?: window.currentScreenBounds()
        DesktopRuntimeLog.info(
            "borderlessFullscreen: enter request hwnd=$handle placement=${window.placement} " +
                "extendedState=${window.extendedState} bounds=${previousBounds.shortLog()} " +
                "nativeBounds=${previousNativeBounds?.shortLog() ?: "none"} " +
                "target=${targetBounds.shortLog()} style=${currentStyle.hexStyle()} exStyle=${currentExStyle.hexStyle()}",
        )

        snapshot = FullscreenSnapshot(
            window = window,
            placement = window.placement,
            extendedState = window.extendedState,
            bounds = previousBounds,
            nativeBounds = previousNativeBounds,
            style = currentStyle,
            exStyle = currentExStyle,
            mode = FullscreenMode.WindowsBorderless,
        )

        runCatching {
            window.placement = WindowPlacement.Floating
            window.extendedState = window.extendedState and Frame.MAXIMIZED_BOTH.inv()
            native.setWindowLongPtr(handle, GWL_STYLE, (currentStyle and WS_OVERLAPPEDWINDOW.inv()) or WS_POPUP)
            native.setWindowLongPtr(
                handle,
                GWL_EXSTYLE,
                currentExStyle and (WS_EX_DLGMODALFRAME or WS_EX_WINDOWEDGE or WS_EX_CLIENTEDGE or WS_EX_STATICEDGE).inv(),
            )
            native.applyFrameBounds(handle, targetBounds, HWND_TOPMOST)
            window.toFront()
            window.requestFocus()
            window.repaint()
        }.onSuccess {
            val appliedStyle = native.getWindowLongPtr(handle, GWL_STYLE)
            val appliedExStyle = native.getWindowLongPtr(handle, GWL_EXSTYLE)
            DesktopRuntimeLog.info(
                "borderlessFullscreen: entered bounds=${targetBounds.shortLog()} " +
                    "previousPlacement=${snapshot?.placement} style=${appliedStyle.hexStyle()} " +
                    "exStyle=${appliedExStyle.hexStyle()}",
            )
            bumpRevision()
        }.onFailure {
            DesktopRuntimeLog.error("borderlessFullscreen: enter failed, restoring window state", it)
            restoreSnapshot(window)
            enterComposeFullscreen(window)
        }
    }

    fun exit(window: ComposeWindow) {
        val active = snapshot
        DesktopRuntimeLog.info(
            "borderlessFullscreen: exit requested hasSnapshot=${active != null} " +
                "placement=${window.placement} extendedState=${window.extendedState} bounds=${window.bounds.shortLog()}",
        )
        if (active?.window === window) {
            restoreSnapshot(window)
            DesktopRuntimeLog.info("borderlessFullscreen: exited mode=${active.mode}")
            bumpRevision()
            return
        }

        val device = window.graphicsConfiguration?.device
        if (device?.fullScreenWindow === window) {
            device.fullScreenWindow = null
        }
        if (window.placement == WindowPlacement.Fullscreen) {
            window.placement = WindowPlacement.Floating
            window.extendedState = window.extendedState and Frame.MAXIMIZED_BOTH.inv()
            DesktopRuntimeLog.info("borderlessFullscreen: exited legacy Compose fullscreen")
            bumpRevision()
        }
    }

    fun isFullscreen(window: ComposeWindow): Boolean {
        val device = window.graphicsConfiguration?.device
        return snapshot?.window === window ||
            window.placement == WindowPlacement.Fullscreen ||
            device?.fullScreenWindow === window
    }

    private fun enterComposeFullscreen(window: ComposeWindow) {
        snapshot = FullscreenSnapshot(
            window = window,
            placement = window.placement.takeIf { it != WindowPlacement.Fullscreen } ?: WindowPlacement.Floating,
            extendedState = window.extendedState,
            bounds = Rectangle(window.bounds),
            nativeBounds = null,
            style = null,
            exStyle = null,
            mode = FullscreenMode.ComposeFallback,
        )
        window.placement = WindowPlacement.Fullscreen
        DesktopRuntimeLog.warn("borderlessFullscreen: entered Compose fullscreen fallback")
        bumpRevision()
    }

    private fun restoreSnapshot(window: ComposeWindow) {
        val active = snapshot ?: return
        snapshot = null

        val handle = resolveHandle(window)
        val native = user32
        val restoreBoundsFirst = active.placement != WindowPlacement.Maximized &&
            active.extendedState and Frame.MAXIMIZED_BOTH == 0
        DesktopRuntimeLog.info(
            "borderlessFullscreen: restore snapshot mode=${active.mode} hwnd=$handle " +
                "restoreBoundsFirst=$restoreBoundsFirst savedPlacement=${active.placement} " +
                "savedExtendedState=${active.extendedState} savedBounds=${active.bounds.shortLog()} " +
                "savedNativeBounds=${active.nativeBounds?.shortLog() ?: "none"} " +
                "savedStyle=${active.style?.hexStyle() ?: "none"} savedExStyle=${active.exStyle?.hexStyle() ?: "none"}",
        )

        if (handle != null && native != null && active.style != null && active.exStyle != null) {
            native.setWindowLongPtr(handle, GWL_STYLE, active.style)
            native.setWindowLongPtr(handle, GWL_EXSTYLE, active.exStyle)
            native.applyFrameBounds(handle, active.nativeBounds ?: active.bounds, HWND_NOTOPMOST)
        }

        val device = window.graphicsConfiguration?.device
        if (device?.fullScreenWindow === window) {
            device.fullScreenWindow = null
        }

        window.placement = WindowPlacement.Floating
        window.extendedState = active.extendedState and Frame.MAXIMIZED_BOTH.inv()
        if (restoreBoundsFirst) {
            window.bounds = active.bounds
        }

        if (active.placement == WindowPlacement.Maximized ||
            active.extendedState and Frame.MAXIMIZED_BOTH != 0
        ) {
            window.placement = WindowPlacement.Maximized
            window.extendedState = active.extendedState or Frame.MAXIMIZED_BOTH
        }
        window.repaint()
        DesktopRuntimeLog.info(
            "borderlessFullscreen: restore complete placement=${window.placement} " +
                "extendedState=${window.extendedState} bounds=${window.bounds.shortLog()}",
        )
    }

    private fun resolveHandle(window: ComposeWindow): Pointer? =
        runCatching { Native.getWindowPointer(window) }
            .onFailure { DesktopRuntimeLog.warn("borderlessFullscreen: cannot resolve HWND ${it.message}") }
            .getOrNull()

    private fun ComposeWindow.currentScreenBounds(): Rectangle {
        val bounds = graphicsConfiguration?.bounds
        if (bounds != null && bounds.width > 0 && bounds.height > 0) {
            return Rectangle(bounds)
        }
        val size = Toolkit.getDefaultToolkit().screenSize
        return Rectangle(0, 0, size.width, size.height)
    }

    private fun User32.currentMonitorBounds(handle: Pointer): Rectangle? {
        val monitor = MonitorFromWindow(handle, MONITOR_DEFAULTTONEAREST) ?: return null
        val info = MonitorInfo().apply { cbSize = size() }
        if (!GetMonitorInfoW(monitor, info)) return null
        val width = info.rcMonitor.right - info.rcMonitor.left
        val height = info.rcMonitor.bottom - info.rcMonitor.top
        if (width <= 0 || height <= 0) return null
        return Rectangle(info.rcMonitor.left, info.rcMonitor.top, width, height)
    }

    private fun User32.currentWindowBounds(handle: Pointer): Rectangle? {
        val rect = NativeRect()
        if (!GetWindowRect(handle, rect)) return null
        val width = rect.right - rect.left
        val height = rect.bottom - rect.top
        if (width <= 0 || height <= 0) return null
        return Rectangle(rect.left, rect.top, width, height)
    }

    private fun User32.getWindowLongPtr(handle: Pointer, index: Int): Long =
        if (Native.POINTER_SIZE == 8) {
            GetWindowLongPtrW(handle, index)
        } else {
            GetWindowLongW(handle, index).toLong()
        }

    private fun User32.setWindowLongPtr(handle: Pointer, index: Int, value: Long) {
        if (Native.POINTER_SIZE == 8) {
            SetWindowLongPtrW(handle, index, value)
        } else {
            SetWindowLongW(handle, index, value.toInt())
        }
    }

    private fun User32.applyFrameBounds(handle: Pointer, bounds: Rectangle, insertAfter: Pointer?) {
        SetWindowPos(
            handle,
            insertAfter,
            bounds.x,
            bounds.y,
            bounds.width,
            bounds.height,
            SWP_NOOWNERZORDER or SWP_FRAMECHANGED or SWP_SHOWWINDOW,
        )
    }

    private fun bumpRevision() {
        revision += 1
    }

    private fun Rectangle.shortLog(): String = "${x},${y} ${width}x${height}"

    private fun Long.hexStyle(): String = "0x${toULong().toString(16).uppercase()}"

    private enum class FullscreenMode {
        WindowsBorderless,
        ComposeFallback,
    }

    private data class FullscreenSnapshot(
        val window: ComposeWindow,
        val placement: WindowPlacement,
        val extendedState: Int,
        val bounds: Rectangle,
        val nativeBounds: Rectangle?,
        val style: Long?,
        val exStyle: Long?,
        val mode: FullscreenMode,
    )

    private interface User32 : StdCallLibrary, Library {
        fun GetWindowLongW(hWnd: Pointer, nIndex: Int): Int
        fun SetWindowLongW(hWnd: Pointer, nIndex: Int, dwNewLong: Int): Int
        fun GetWindowLongPtrW(hWnd: Pointer, nIndex: Int): Long
        fun SetWindowLongPtrW(hWnd: Pointer, nIndex: Int, dwNewLong: Long): Long
        fun MonitorFromWindow(hWnd: Pointer, dwFlags: Int): Pointer?
        fun GetMonitorInfoW(hMonitor: Pointer, lpmi: MonitorInfo): Boolean
        fun GetWindowRect(hWnd: Pointer, lpRect: NativeRect): Boolean
        fun SetWindowPos(
            hWnd: Pointer,
            hWndInsertAfter: Pointer?,
            x: Int,
            y: Int,
            cx: Int,
            cy: Int,
            uFlags: Int,
        ): Boolean
    }

    @Suppress("MemberVisibilityCanBePrivate")
    class MonitorInfo : com.sun.jna.Structure() {
        @JvmField
        var cbSize: Int = 0

        @JvmField
        var rcMonitor: NativeRect = NativeRect()

        @JvmField
        var rcWork: NativeRect = NativeRect()

        @JvmField
        var dwFlags: Int = 0

        override fun getFieldOrder(): List<String> = listOf("cbSize", "rcMonitor", "rcWork", "dwFlags")
    }

    @Suppress("MemberVisibilityCanBePrivate")
    class NativeRect : com.sun.jna.Structure() {
        @JvmField
        var left: Int = 0

        @JvmField
        var top: Int = 0

        @JvmField
        var right: Int = 0

        @JvmField
        var bottom: Int = 0

        override fun getFieldOrder(): List<String> = listOf("left", "top", "right", "bottom")
    }
}
