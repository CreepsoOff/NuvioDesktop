package com.nuvio.app.features.notifications

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.desktop.DesktopPreferences
import com.nuvio.app.desktop.DesktopRuntimeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.Locale

internal actual object EpisodeReleaseNotificationsStorage {
    private const val preferencesName = "nuvio_episode_release_notifications"
    private const val payloadKey = "episode_release_notifications_payload"

    actual fun loadPayload(): String? =
        DesktopPreferences.getString(preferencesName, ProfileScopedKey.of(payloadKey))

    actual fun savePayload(payload: String) {
        DesktopPreferences.putString(preferencesName, ProfileScopedKey.of(payloadKey), payload)
    }
}

internal actual object EpisodeReleaseNotificationPlatform {
    actual suspend fun notificationsAuthorized(): Boolean = withContext(Dispatchers.IO) {
        if (!WindowsToastHelper.systemToastsSupported) {
            DesktopRuntimeLog.info("Toast: system toasts not supported (portable build or non-Windows), using in-app fallback")
            return@withContext true  // do NOT block — use in-app fallback
        }
        val shortcutOk = WindowsToastHelper.ensureShortcut()
        val notifierOk = WindowsToastHelper.isToastNotifierAvailable()
        val authorized = shortcutOk && notifierOk
        DesktopRuntimeLog.info("Toast: authorized=$authorized shortcut=$shortcutOk notifier=$notifierOk")
        authorized
    }

    actual suspend fun requestAuthorization(): Boolean = withContext(Dispatchers.IO) {
        val portable = WindowsToastHelper.isPortableBuild
        if (portable) {
            NuvioToastController.show(
                "Notifications: In-app notification mode active. Install via Inno Setup for Windows system notifications.",
                durationMillis = 4000L,
            )
            DesktopRuntimeLog.info("Toast: portable build, using in-app notifications")
            return@withContext true
        }

        val authorized = notificationsAuthorized()
        if (authorized) return@withContext true

        val testResult = WindowsToastHelper.showToast("Nuvio", "Notifications are ready.")
        if (testResult) {
            DesktopRuntimeLog.info("Toast: test toast shown successfully")
            return@withContext true
        }

        NuvioToastController.show(
            "System notifications unavailable. In-app notifications will be used.",
            durationMillis = 4000L,
        )
        DesktopRuntimeLog.warn("Toast: requestAuthorization fell back to app")
        true  // always allow toggle
    }

    actual suspend fun scheduleEpisodeReleaseNotifications(requests: List<EpisodeReleaseNotificationRequest>) {
        withContext(Dispatchers.IO) {
            if (!WindowsToastHelper.systemToastsSupported) {
                DesktopRuntimeLog.info("Toast: skipping schedule (portable or unsupported)")
                return@withContext
            }
            clearScheduledEpisodeReleaseNotifications()

            val scheduled = requests
                .filter { req -> scheduledNotificationTime(req.releaseDateIso) != null }
                .count { req ->
                    WindowsToastHelper.scheduleToast(
                        title = req.notificationTitle,
                        body = req.notificationBody,
                        deepLinkUrl = req.deepLinkUrl,
                        requestId = req.requestId,
                        releaseDateIso = req.releaseDateIso,
                    ).also { ok ->
                        if (!ok) {
                            DesktopRuntimeLog.warn("Toast: schedule failed id=${req.requestId}")
                        }
                    }
                }
            DesktopRuntimeLog.info("Toast: scheduled $scheduled notifications")
        }
    }

    actual suspend fun clearScheduledEpisodeReleaseNotifications() {
        withContext(Dispatchers.IO) {
            if (!WindowsToastHelper.systemToastsSupported) return@withContext
            WindowsToastHelper.clearScheduledToasts()
        }
    }

    actual suspend fun showTestNotification(request: EpisodeReleaseNotificationRequest) {
        withContext(Dispatchers.IO) {
            if (!WindowsToastHelper.systemToastsSupported) {
                NuvioToastController.show(
                    request.notificationBody,
                    durationMillis = 4000L,
                )
                return@withContext
            }

            val ok = WindowsToastHelper.showToast(
                title = request.notificationTitle,
                body = request.notificationBody,
                deepLinkUrl = request.deepLinkUrl,
                requestId = request.requestId,
            )

            if (!ok) {
                DesktopRuntimeLog.warn("Toast: test notification failed, showing in-app fallback")
                NuvioToastController.show(
                    request.notificationBody,
                    durationMillis = 4000L,
                )
            }
        }
    }

    private fun scheduledNotificationTime(releaseDateIso: String): Instant? {
        val date = try {
            LocalDate.parse(releaseDateIso)
        } catch (_: DateTimeParseException) {
            return null
        }
        val scheduledInstant = date
            .atTime(EpisodeReleaseNotificationHour, EpisodeReleaseNotificationMinute)
            .atZone(ZoneId.systemDefault())
            .toInstant()
        return scheduledInstant.takeIf { it.isAfter(Instant.now()) }
    }
}

internal actual object EpisodeReleaseNotificationsClock {
    actual fun isoDateFromEpochMs(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .toString()
}
