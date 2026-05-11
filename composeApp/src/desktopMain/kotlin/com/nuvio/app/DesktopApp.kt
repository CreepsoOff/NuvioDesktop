package com.nuvio.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.nuvio.app.core.deeplink.handleAppUrl
import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.network.SupabaseConfig
import com.nuvio.app.desktop.DesktopBorderlessFullscreenController
import com.nuvio.app.desktop.DesktopPlayerRegistry
import com.nuvio.app.desktop.DesktopPreferences
import com.nuvio.app.desktop.DesktopRuntimeLog
import com.nuvio.app.desktop.DesktopSingleInstanceManager
import com.nuvio.app.desktop.DesktopUriHandler
import com.nuvio.app.desktop.DesktopWindowStateStore
import com.nuvio.app.desktop.WindowsNativeBootstrap
import com.nuvio.app.desktop.WindowsUrlProtocolRegistrar
import com.nuvio.app.features.notifications.WindowsToastHelper
import com.nuvio.app.features.trakt.TraktAuthRepository
import io.ktor.http.Url
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.nuvio_window_icon
import org.jetbrains.compose.resources.painterResource
import java.awt.EventQueue
import java.awt.Frame
import java.awt.Window as AwtWindow
import java.awt.Color as AwtColor
import java.awt.GraphicsEnvironment
import kotlin.system.exitProcess

private val DesktopWindowBackground = AwtColor(0x0D, 0x0D, 0x0D)
@Volatile
private var desktopMainWindow: AwtWindow? = null

private fun configureMacOsNativeAppearance() {
    val osName = System.getProperty("os.name")?.lowercase() ?: return
    if (!osName.contains("mac")) return
    System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua")
}

private fun computeStartupWindowSize(): DpSize {
    val displayBounds = GraphicsEnvironment.getLocalGraphicsEnvironment()
        .defaultScreenDevice
        .defaultConfiguration
        .bounds

    val isLargeDisplay = displayBounds.width > 1920 || displayBounds.height > 1080
    val targetWidth = if (isLargeDisplay) 1920 else 1280
    val targetHeight = if (isLargeDisplay) 1080 else 720

    val clampedWidth = targetWidth.coerceAtMost(displayBounds.width).coerceAtLeast(1)
    val clampedHeight = targetHeight.coerceAtMost(displayBounds.height).coerceAtLeast(1)
    return DpSize(clampedWidth.dp, clampedHeight.dp)
}

private fun clampDpSizeToDisplay(size: DpSize): DpSize {
    val displayBounds = GraphicsEnvironment.getLocalGraphicsEnvironment()
        .defaultScreenDevice
        .defaultConfiguration
        .bounds
    val maxW = displayBounds.width.coerceAtLeast(1)
    val maxH = displayBounds.height.coerceAtLeast(1)
    val w = size.width.value.toInt().coerceIn(400, maxW)
    val h = size.height.value.toInt().coerceIn(300, maxH)
    return DpSize(w.dp, h.dp)
}

fun main(args: Array<String>) {
    DesktopRuntimeLog.initialize(
        enabled = DesktopPreferences.getBoolean("nuvio_debug", "debug_logs_enabled") ?: false,
    )
    WindowsNativeBootstrap.configureProcessDpiAwareness()
    DesktopRuntimeLog.installGlobalExceptionHandlers()
    DesktopRuntimeLog.info("Toast: portable=${WindowsToastHelper.isPortableBuild} systemSupported=${WindowsToastHelper.systemToastsSupported}")
    val pid = DesktopRuntimeLog.processPid()
    DesktopRuntimeLog.info("app startup pid=$pid")
    DesktopRuntimeLog.info(
        "NUVIO_RUNTIME_PATCH_MARKER=cursor-player-session-render-shutdown-v2 " +
            "pid=$pid ts=${System.currentTimeMillis()} user.dir=${System.getProperty("user.dir")} " +
            "buildCommit=${System.getProperty("nuvio.git.commit") ?: "unknown"} " +
            "buildBranch=${System.getProperty("nuvio.git.branch") ?: "unknown"}",
    )
    Runtime.getRuntime().addShutdownHook(
        Thread {
            val startMs = System.currentTimeMillis()
            DesktopRuntimeLog.info("shutdownHook start pid=$pid")
            DesktopRuntimeLog.logNonDaemonThreads("shutdownHook:beforeClose")
            DesktopPlayerRegistry.releaseAll("shutdownHook")
            DesktopPlayerRegistry.closeAll("shutdownHook")
            // Give in-flight `mpv_terminate_destroy` calls a bounded window to
            // release the audio device and event/render threads before the JVM
            // process exits. The close threads are daemons so they do not keep
            // the JVM alive on their own; the explicit wait here is what makes
            // a clean Windows-X close actually clean.
            DesktopPlayerRegistry.awaitAllCloses(timeoutMs = 3000L)
            DesktopRuntimeLog.logNonDaemonThreads("shutdownHook:afterClose")
            DesktopRuntimeLog.info("shutdownHook end pid=$pid elapsedMs=${System.currentTimeMillis() - startMs}")
        },
    )
    DesktopRuntimeLog.info("version=${AppVersionConfig.VERSION_NAME}(${AppVersionConfig.VERSION_CODE})")
    DesktopRuntimeLog.info("os=${System.getProperty("os.name")} ${System.getProperty("os.version")}")
    DesktopRuntimeLog.info("java=${System.getProperty("java.version")}")
    DesktopRuntimeLog.info("user.dir=${System.getProperty("user.dir")}")
    DesktopRuntimeLog.info("compose.resources.dir=${System.getProperty("compose.application.resources.dir") ?: "unset"}")
    DesktopRuntimeLog.info("java.library.path=${System.getProperty("java.library.path") ?: "unset"}")
    DesktopRuntimeLog.info("supabase.url=${SupabaseConfig.URL}")
    DesktopRuntimeLog.info("supabase.anon.present=${SupabaseConfig.ANON_KEY.isNotBlank()} length=${SupabaseConfig.ANON_KEY.length}")
    ensureWindowsUrlProtocolRegistration()
    val startupUrls = extractStartupDeepLinks(args)
    when (
        val ipcResult = DesktopSingleInstanceManager.resolveStartup(
            startupUrls = startupUrls,
            onUrlReceived = ::handleIncomingDeepLink,
            onFocusRequested = ::focusMainWindow,
        )
    ) {
        DesktopSingleInstanceManager.StartResult.ForwardedToPrimary -> {
            DesktopRuntimeLog.info(
                "single-instance: exiting after forwarding deepLinkCount=${startupUrls.size} to primary",
            )
            exitProcess(0)
        }

        is DesktopSingleInstanceManager.StartResult.Primary -> {
            Runtime.getRuntime().addShutdownHook(Thread { ipcResult.close() })
        }

        DesktopSingleInstanceManager.StartResult.NoPrimaryAvailable -> {
            // IPC disabled; full app still starts (no second short-circuit exit).
        }
    }
    startupUrls.forEach(::handleIncomingDeepLink)
    WindowsNativeBootstrap.bootstrap()
    configureMacOsNativeAppearance()
    application {
        DesktopRuntimeLog.info("window composition start pid=$pid")
        val defaultWindowSize = computeStartupWindowSize()
        val savedWindow = DesktopWindowStateStore.load()
        val initialSize = savedWindow?.let {
            clampDpSizeToDisplay(DpSize(it.widthDp.dp, it.heightDp.dp))
        } ?: defaultWindowSize
        val initialPlacement = if (savedWindow?.maximized == true) {
            WindowPlacement.Maximized
        } else {
            WindowPlacement.Floating
        }
        val startupWindowState = rememberWindowState(
            size = initialSize,
            position = WindowPosition.Aligned(Alignment.Center),
            placement = initialPlacement,
        )
        Window(
            onCloseRequest = {
                if (DesktopBorderlessFullscreenController.isFullscreenActive) {
                    DesktopRuntimeLog.info("windowClose skipped window-state save while borderless fullscreen is active")
                } else {
                    DesktopWindowStateStore.save(startupWindowState.size, startupWindowState.placement)
                }
                val closeStartMs = System.currentTimeMillis()
                DesktopRuntimeLog.info("windowClose requested pid=$pid")
                DesktopRuntimeLog.logNonDaemonThreads("windowClose:beforeCleanup")
                DesktopPlayerRegistry.releaseAll("windowClose")
                DesktopRuntimeLog.info("windowClose releaseAll done pid=$pid")
                DesktopPlayerRegistry.closeAll("windowClose")
                DesktopRuntimeLog.info("windowClose closeAll done pid=$pid")
                DesktopPlayerRegistry.awaitAllCloses(timeoutMs = 1500L)
                DesktopRuntimeLog.logNonDaemonThreads("windowClose:beforeExitApplication")
                DesktopRuntimeLog.info("windowClose exitApplication pid=$pid elapsedMs=${System.currentTimeMillis() - closeStartMs}")
                exitApplication()
                val forceExitEnabled = System.getProperty("nuvio.desktop.forceExitOnClose", "false")
                    .equals("true", ignoreCase = true)
                if (forceExitEnabled) {
                    DesktopRuntimeLog.warn("windowClose force exitProcess enabled pid=$pid")
                    DesktopRuntimeLog.logNonDaemonThreads("windowClose:beforeForceExit")
                    exitProcess(0)
                }
            },
            title = "Nuvio",
            icon = painterResource(Res.drawable.nuvio_window_icon),
            state = startupWindowState,
        ) {
            DisposableEffect(window) {
                desktopMainWindow = window
                window.background = DesktopWindowBackground
                window.contentPane.background = DesktopWindowBackground
                window.rootPane.background = DesktopWindowBackground
                onDispose { desktopMainWindow = null }
            }

            val desktopUriHandler = remember { DesktopUriHandler() }
            CompositionLocalProvider(
                LocalDesktopWindow provides window,
                LocalUriHandler provides desktopUriHandler,
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    App()
                }
            }
        }
    }
}

private fun ensureWindowsUrlProtocolRegistration() {
    val result = WindowsUrlProtocolRegistrar.ensureRegisteredForCurrentExecutable()
    result.diagnostics.forEach { line ->
        DesktopRuntimeLog.info("protocol registration detail: $line")
    }
    if (result.success) {
        DesktopRuntimeLog.info("protocol registration: ${result.message}")
    } else {
        DesktopRuntimeLog.error("protocol registration failed: ${result.message}")
        TraktAuthRepository.onAuthLaunchFailed(
            "Unable to register Windows protocol nuvio://. Trakt sign-in callback may fail.",
        )
    }
}

private fun extractStartupDeepLinks(args: Array<String>): List<String> =
    args.filter { it.startsWith("nuvio://", ignoreCase = true) }

private fun handleIncomingDeepLink(callbackUrl: String) {
    DesktopRuntimeLog.info("received startup deep link ${sanitizeDeepLinkForLog(callbackUrl)}")
    handleAppUrl(callbackUrl)
    focusMainWindow()
}

private fun focusMainWindow() {
    val window = desktopMainWindow ?: return
    EventQueue.invokeLater {
        if (window is Frame && window.state == Frame.ICONIFIED) {
            window.state = Frame.NORMAL
        }
        window.isVisible = true
        window.toFront()
        window.requestFocus()
    }
}

private fun sanitizeDeepLinkForLog(url: String): String {
    val parsed = runCatching { Url(url) }.getOrNull() ?: return "unparsed"
    val keys = parsed.parameters.names().sorted()
    val safeKeys = keys.joinToString(separator = ",")
    return buildString {
        append("scheme=").append(parsed.protocol.name)
        append(" host=").append(parsed.host)
        append(" path=").append(parsed.encodedPath)
        if (safeKeys.isNotBlank()) {
            append(" queryKeys=").append(safeKeys)
        }
    }
}
