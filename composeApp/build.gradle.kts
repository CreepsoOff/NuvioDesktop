import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.api.DefaultTask
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Properties
import javax.imageio.ImageIO
import javax.inject.Inject

abstract class WindowsPackageAppImageBuildService : BuildService<BuildServiceParameters.None>

abstract class PackageWindowsNativeRuntimeTask : DefaultTask() {
    @get:Internal
    abstract val mediampNativeBuildDir: DirectoryProperty

    @get:Internal
    abstract val mediampPrebuiltDir: DirectoryProperty

    @get:Internal
    abstract val system32Dir: DirectoryProperty

    @get:Internal
    abstract val appDir: DirectoryProperty

    @get:OutputDirectory
    abstract val nativeDir: DirectoryProperty

    @get:OutputDirectory
    abstract val launcherDir: DirectoryProperty

    @get:Optional
    @get:Input
    abstract val stremioLibmpvDir: Property<String>

    @get:Internal
    abstract val lockFile: RegularFileProperty

    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun packageRuntime() {
        val lock = lockFile.get().asFile
        lock.parentFile.mkdirs()
        RandomAccessFile(lock, "rw").channel.use { channel ->
            channel.lock().use {
                copyNativeDlls()
                overrideLibmpvFromStremioIfConfigured()
                patchLauncherConfig()
                copyLauncherFallbackDlls()
                verifyRequiredDlls()
            }
        }
    }

    private fun copyNativeDlls() {
        fileSystemOperations.copy {
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            from(mediampNativeBuildDir) {
                include("*.dll")
            }
            from(mediampNativeBuildDir.dir("Release")) {
                include("*.dll")
            }
            from(mediampPrebuiltDir) {
                include("*.dll")
            }
            from(system32Dir) {
                include("MSVCP140.dll", "msvcp140.dll")
                include("VCRUNTIME140.dll", "vcruntime140.dll")
                include("VCRUNTIME140_1.dll", "vcruntime140_1.dll")
            }
            into(nativeDir)
        }
    }

    private fun overrideLibmpvFromStremioIfConfigured() {
        val configuredDir = stremioLibmpvDir.orNull
            ?: System.getProperty("nuvio.stremio.libmpv.dir")
            ?: System.getenv("NUVIO_STREMIO_LIBMPV_DIR")
        if (configuredDir.isNullOrBlank()) return

        val baseDir = File(configuredDir)
        check(baseDir.isDirectory) {
            "Stremio libmpv directory is required but was not found: ${baseDir.absolutePath}. " +
                "Run `git submodule update --init --recursive stremio-community-v5` or set NUVIO_STREMIO_LIBMPV_DIR."
        }
        val source = baseDir.resolve("libmpv-2.dll")
        val resolvedSource = when {
            source.isFile -> source
            else -> extractLibmpvFromRarIfNeeded(baseDir)
        }
        check(resolvedSource != null && resolvedSource.isFile) {
            "Stremio libmpv-2.dll is required but could not be resolved from ${baseDir.absolutePath}. " +
                "Expected libmpv-2.dll or extractable libmpv-2.dll.rar; set NUVIO_7Z if 7z is not on PATH."
        }

        val destination = nativeDir.get().asFile.resolve("libmpv-2.dll")
        resolvedSource.copyTo(destination, overwrite = true)

        val sourceHash = resolvedSource.sha256()
        val destinationHash = destination.sha256()
        check(sourceHash == destinationHash) {
            "Stremio libmpv-2.dll copy verification failed: source sha256=$sourceHash destination sha256=$destinationHash"
        }
        logger.lifecycle(
            "packageWindowsNativeRuntime: using Stremio libmpv-2.dll " +
                "size=${destination.length()} sha256=${destinationHash.take(12)}...",
        )
    }

    private fun extractLibmpvFromRarIfNeeded(baseDir: File): File? {
        val rarFile = baseDir.resolve("libmpv-2.dll.rar")
        if (!rarFile.isFile) return null

        val outputDir = File(temporaryDir, "stremio-libmpv").apply { mkdirs() }
        val outputDll = outputDir.resolve("libmpv-2.dll")
        if (outputDll.isFile && outputDll.length() > 0) return outputDll

        val sevenZip = System.getenv("NUVIO_7Z")?.takeIf(String::isNotBlank) ?: "7z"
        return runCatching {
            logger.lifecycle("packageWindowsNativeRuntime: extracting Stremio libmpv-2.dll from ${rarFile.absolutePath}")
            execOperations.exec {
                commandLine(
                    sevenZip,
                    "x",
                    "-y",
                    "-o${outputDir.absolutePath}",
                    rarFile.absolutePath,
                )
            }.assertNormalExitValue()
            outputDll.takeIf { it.isFile }
        }.onFailure {
            logger.warn("packageWindowsNativeRuntime: failed to extract Stremio libmpv from ${rarFile.absolutePath}: ${it.message}")
        }.getOrNull()
    }

    private fun patchLauncherConfig() {
        val cfgFile = appDir.get().asFile.resolve("Nuvio.cfg")
        if (!cfgFile.isFile) return

        val libraryPathOption = "java-options=-Djava.library.path=\$APPDIR/native"
        // Hi-DPI on Windows OpenJDK builds is not auto-enabled even with a
        // Per-Monitor V2 manifest; without these two flags AWT reports
        // scaleX=1.0 on a 125%/150%/200% display, Compose renders at 1x,
        // and the Windows compositor upscales the bitmap with a cheap
        // bilinear (visible as jagged posters/avatars on the home shelves).
        // See WindowsNativeBootstrap.primeJava2dUiScale for the runtime
        // counterpart and the rationale.
        val uiScaleOption = "java-options=-Dsun.java2d.uiScale.enabled=true"
        val dpiAwareOption = "java-options=-Dsun.java2d.dpiaware=true"
        val lines = cfgFile.readLines()
        var libraryPathReplaced = false
        val patchedLines = lines.map { line ->
            if (line.startsWith("java-options=-Djava.library.path=")) {
                libraryPathReplaced = true
                libraryPathOption
            } else {
                line
            }
        }.toMutableList()

        fun ensureJavaOption(option: String, prefix: String) {
            if (patchedLines.any { it.startsWith(prefix) }) return
            val javaOptionsIndex = patchedLines.indexOf("[JavaOptions]")
            if (javaOptionsIndex >= 0) {
                patchedLines.add(javaOptionsIndex + 1, option)
            } else {
                patchedLines.add("")
                patchedLines.add("[JavaOptions]")
                patchedLines.add(option)
            }
        }

        if (!libraryPathReplaced) {
            ensureJavaOption(libraryPathOption, "java-options=-Djava.library.path=")
        }
        ensureJavaOption(uiScaleOption, "java-options=-Dsun.java2d.uiScale.enabled=")
        ensureJavaOption(dpiAwareOption, "java-options=-Dsun.java2d.dpiaware=")

        cfgFile.writeText(patchedLines.joinToString(System.lineSeparator()) + System.lineSeparator())
    }

    private fun copyLauncherFallbackDlls() {
        val nativeDirectory = nativeDir.get().asFile
        val launcherDirectory = launcherDir.get().asFile
        launcherDirectory.mkdirs()
        nativeDirectory.listFiles { file -> file.isFile && file.extension.equals("dll", ignoreCase = true) }
            .orEmpty()
            .forEach { dll ->
                dll.copyTo(launcherDirectory.resolve(dll.name), overwrite = true)
            }
    }

    private fun verifyRequiredDlls() {
        val nativeDirectory = nativeDir.get().asFile
        val launcherDirectory = launcherDir.get().asFile
        val requiredDlls = listOf(
            "mediampv.dll",
            "libmpv-2.dll",
            "avcodec-61.dll",
            "avformat-61.dll",
            "avutil-59.dll",
            "swscale-8.dll",
            "vulkan-1.dll",
            "MSVCP140.dll",
            "VCRUNTIME140.dll",
            "VCRUNTIME140_1.dll",
        )

        fun File.hasDll(name: String): Boolean =
            listFiles { file -> file.isFile && file.name.equals(name, ignoreCase = true) }?.isNotEmpty() == true

        val missingFromNative = requiredDlls.filterNot { nativeDirectory.hasDll(it) }
        val missingFromLauncher = requiredDlls.filterNot { launcherDirectory.hasDll(it) }
        check(missingFromNative.isEmpty()) {
            "Windows native runtime is incomplete in ${nativeDirectory.absolutePath}: missing ${missingFromNative.joinToString()}"
        }
        check(missingFromLauncher.isEmpty()) {
            "Windows launcher native fallback is incomplete in ${launcherDirectory.absolutePath}: missing ${missingFromLauncher.joinToString()}"
        }
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}

abstract class GenerateRuntimeConfigsTask : DefaultTask() {
    private val defaultNuvioSupabaseUrl = "https://api.nuvio.tv"
    private val defaultNuvioPublishableKey = "sb_publishable_1Clq8rlTVACkdcZuqr6_AD__xUUC_EN"

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Optional
    @get:InputFile
    abstract val localPropertiesFile: RegularFileProperty

    @get:Optional
    @get:InputFile
    abstract val releasePropertiesFile: RegularFileProperty

    @get:Input
    abstract val appVersionName: Property<String>

    @get:Input
    abstract val appVersionCode: Property<Int>

    private fun loadProperties(file: File?): Properties =
        Properties().apply {
            file
                ?.takeIf(File::exists)
                ?.inputStream()
                ?.use(::load)
        }

    private fun resolveRuntimeValue(
        key: String,
        releaseProperties: Properties,
        localProperties: Properties,
        defaultValue: String = "",
    ): String {
        System.getenv(key)
            ?.takeIf(String::isNotBlank)
            ?.let { return it }

        localProperties.getProperty(key)
            ?.takeIf(String::isNotBlank)
            ?.let { return it }

        releaseProperties.getProperty(key)
            ?.takeIf(String::isNotBlank)
            ?.let { return it }

        return defaultValue
    }

    private fun kotlinStringLiteral(value: String): String =
        buildString(value.length + 8) {
            value.forEach { character ->
                when (character) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(character)
                }
            }
        }

    @TaskAction
    fun generate() {
        val releaseProperties = loadProperties(releasePropertiesFile.asFile.orNull)
        val localProperties = loadProperties(localPropertiesFile.asFile.orNull)
        val supabaseUrl = resolveRuntimeValue("SUPABASE_URL", releaseProperties, localProperties)
        val supabaseAnonKey = resolveRuntimeValue("SUPABASE_ANON_KEY", releaseProperties, localProperties)
        val nuvioSupabaseUrl = resolveRuntimeValue(
            "NUVIO_SUPABASE_URL",
            releaseProperties,
            localProperties,
            supabaseUrl.ifBlank { defaultNuvioSupabaseUrl },
        )
        val nuvioSupabaseAnonKey = resolveRuntimeValue(
            "NUVIO_SUPABASE_ANON_KEY",
            releaseProperties,
            localProperties,
            supabaseAnonKey.ifBlank { defaultNuvioPublishableKey },
        )
        val syncBackendManifestUrl = resolveRuntimeValue(
            "SYNC_BACKEND_MANIFEST_URL",
            releaseProperties,
            localProperties,
        )
        val traktClientId = resolveRuntimeValue("TRAKT_CLIENT_ID", releaseProperties, localProperties)
        val traktClientSecret = resolveRuntimeValue("TRAKT_CLIENT_SECRET", releaseProperties, localProperties)
        val traktRedirectUri = resolveRuntimeValue(
            "TRAKT_REDIRECT_URI",
            releaseProperties,
            localProperties,
            "nuvio://auth/trakt",
        )
        val introDbUrl = resolveRuntimeValue("INTRODB_API_URL", releaseProperties, localProperties)
        val contributionsUrl = resolveRuntimeValue("CONTRIBUTIONS_URL", releaseProperties, localProperties)
        val donationsBaseUrl = resolveRuntimeValue("DONATIONS_BASE_URL", releaseProperties, localProperties)
        val donationsDonateUrl = resolveRuntimeValue("DONATIONS_DONATE_URL", releaseProperties, localProperties)
        val contributionsExtra = resolveRuntimeValue("CONTRIBUTIONS_EXTRA", releaseProperties, localProperties)
        val imdbRatingsApiBaseUrl = resolveRuntimeValue("IMDB_RATINGS_API_BASE_URL", releaseProperties, localProperties)
        val imdbTapframeApiBaseUrl = resolveRuntimeValue("IMDB_TAPFRAME_API_BASE_URL", releaseProperties, localProperties)
        val directDebridApiBaseUrl = resolveRuntimeValue("DIRECT_DEBRID_API_BASE_URL", releaseProperties, localProperties)
        val premiumizeClientId = resolveRuntimeValue("PREMIUMIZE_CLIENT_ID", releaseProperties, localProperties)

        val outDir = outputDir.get().asFile
        outDir.deleteRecursively()
        outDir.resolve("com/nuvio/app/core/network").apply {
            mkdirs()
            resolve("SupabaseConfig.kt").writeText(
                """
                |package com.nuvio.app.core.network
                |
                |object SupabaseConfig {
                |    const val URL = "${kotlinStringLiteral(supabaseUrl)}"
                |    const val ANON_KEY = "${kotlinStringLiteral(supabaseAnonKey)}"
                |    const val NUVIO_URL = "${kotlinStringLiteral(nuvioSupabaseUrl)}"
                |    const val NUVIO_ANON_KEY = "${kotlinStringLiteral(nuvioSupabaseAnonKey)}"
                |}
                """.trimMargin()
            )
            resolve("SyncBackendBootstrapConfig.kt").writeText(
                """
                |package com.nuvio.app.core.network
                |
                |object SyncBackendBootstrapConfig {
                |    const val SWITCH_MANIFEST_URL = "${kotlinStringLiteral(syncBackendManifestUrl)}"
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/features/tmdb/TmdbConfig.kt").delete()

        outDir.resolve("com/nuvio/app/features/trakt").apply {
            mkdirs()
            resolve("TraktConfig.kt").writeText(
                """
                |package com.nuvio.app.features.trakt
                |
                |object TraktConfig {
                |    const val CLIENT_ID = "${kotlinStringLiteral(traktClientId)}"
                |    const val CLIENT_SECRET = "${kotlinStringLiteral(traktClientSecret)}"
                |    const val REDIRECT_URI = "${kotlinStringLiteral(traktRedirectUri)}"
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/features/player/skip").apply {
            mkdirs()
            resolve("IntroDbConfig.kt").writeText(
                """
                |package com.nuvio.app.features.player.skip
                |
                |object IntroDbConfig {
                |    const val URL = "${kotlinStringLiteral(introDbUrl)}"
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/features/details").apply {
            mkdirs()
            resolve("ImdbEpisodeRatingsConfig.kt").writeText(
                """
                |package com.nuvio.app.features.details
                |
                |object ImdbEpisodeRatingsConfig {
                |    const val IMDB_RATINGS_API_BASE_URL = "${kotlinStringLiteral(imdbRatingsApiBaseUrl)}"
                |    const val IMDB_TAPFRAME_API_BASE_URL = "${kotlinStringLiteral(imdbTapframeApiBaseUrl)}"
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/features/debrid").apply {
            mkdirs()
            resolve("PremiumizeConfig.kt").writeText(
                """
                |package com.nuvio.app.features.debrid
                |
                |object PremiumizeConfig {
                |    const val CLIENT_ID = "${kotlinStringLiteral(premiumizeClientId)}"
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/core/build").apply {
            mkdirs()
            resolve("AppVersionConfig.kt").writeText(
                """
                |package com.nuvio.app.core.build
                |
                |object AppVersionConfig {
                |    const val VERSION_NAME = "${appVersionName.get()}"
                |    const val VERSION_CODE = ${appVersionCode.get()}
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/features/settings").apply {
            mkdirs()
            resolve("CommunityConfig.kt").writeText(
                """
                |package com.nuvio.app.features.settings
                |
                |object CommunityConfig {
                |    const val CONTRIBUTIONS_URL = "${kotlinStringLiteral(contributionsUrl)}"
                |    const val DONATIONS_BASE_URL = "${kotlinStringLiteral(donationsBaseUrl)}"
                |    const val DONATIONS_DONATE_URL = "${kotlinStringLiteral(donationsDonateUrl)}"
                |    const val CONTRIBUTIONS_EXTRA = "${kotlinStringLiteral(contributionsExtra)}"
                |}
                """.trimMargin()
            )
        }
    }
}

abstract class RenameReleaseDmgTask : DefaultTask() {
    @get:Input
    abstract val versionName: Property<String>

    @get:OutputDirectory
    abstract val dmgDirectory: DirectoryProperty

    @TaskAction
    fun renameArtifact() {
        val dmgDir = dmgDirectory.get().asFile
        val targetFile = dmgDir.resolve("Nuvio-${versionName.get()}.dmg")
        val sourceFile = dmgDir.listFiles()
            ?.filter { it.extension == "dmg" && it.name.startsWith("Nuvio-") }
            ?.maxByOrNull { it.lastModified() }
            ?: error("No DMG output found in ${dmgDir.path}")

        if (sourceFile.absolutePath != targetFile.absolutePath) {
            targetFile.delete()
            sourceFile.copyTo(targetFile, overwrite = true)
            sourceFile.delete()
        }
    }
}

abstract class SyncWindowsPackageResourcesTask : DefaultTask() {
    @get:InputDirectory
    abstract val sourceDirectory: DirectoryProperty

    @get:Internal
    abstract val targetDirectory: DirectoryProperty

    @get:InputFile
    abstract val installerSidebarPng: RegularFileProperty

    @get:InputFile
    abstract val installerBannerBmp: RegularFileProperty

    @get:InputFile
    abstract val installerSetupIconIco: RegularFileProperty

    @TaskAction
    fun sync() {
        val sourceRoot = sourceDirectory.get().asFile
        val targetRoot = targetDirectory.get().asFile
        sourceRoot.walkTopDown()
            .filter { it.isFile }
            .forEach { sourceFile ->
                val targetFile = targetRoot.resolve(sourceFile.relativeTo(sourceRoot))
                targetFile.parentFile.mkdirs()
                sourceFile.copyTo(targetFile, overwrite = true)
            }

        val sidebarPngFile = installerSidebarPng.get().asFile
        val sidebarBmpTarget = targetRoot.resolve("BackgroundImage.bmp")
        sidebarBmpTarget.parentFile.mkdirs()
        val sidebarImage = ImageIO.read(sidebarPngFile)
            ?: error("Unable to read Windows installer sidebar PNG: ${sidebarPngFile.absolutePath}")
        check(ImageIO.write(sidebarImage, "bmp", sidebarBmpTarget)) {
            "Unable to write WiX sidebar BMP: ${sidebarBmpTarget.absolutePath}"
        }

        val bannerBmpFile = installerBannerBmp.get().asFile
        val bannerBmpTarget = targetRoot.resolve("bannrbmp.bmp")
        bannerBmpTarget.parentFile.mkdirs()
        bannerBmpFile.copyTo(bannerBmpTarget, overwrite = true)

        val setupIconFile = installerSetupIconIco.get().asFile
        val setupIconTarget = targetRoot.resolve("JavaApp.ico")
        setupIconTarget.parentFile.mkdirs()
        setupIconFile.copyTo(setupIconTarget, overwrite = true)
    }
}

fun readXcconfigValue(file: File, key: String): String? {
    if (!file.exists()) return null
    return file.readLines()
        .asSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
        .map { line ->
            val separatorIndex = line.indexOf('=')
            line.substring(0, separatorIndex).trim() to line.substring(separatorIndex + 1).trim()
        }
        .firstOrNull { (entryKey, _) -> entryKey == key }
        ?.second
}

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinxSerialization)
}

val supabaseProps = Properties().apply {
    val propsFile = rootProject.file("local.properties")
    if (propsFile.exists()) propsFile.inputStream().use { load(it) }
}
val releaseStoreFile = supabaseProps.getProperty("NUVIO_RELEASE_STORE_FILE")?.takeIf { it.isNotBlank() }
val releaseStorePassword = supabaseProps.getProperty("NUVIO_RELEASE_STORE_PASSWORD")?.takeIf { it.isNotBlank() }
val releaseKeyAlias = supabaseProps.getProperty("NUVIO_RELEASE_KEY_ALIAS")?.takeIf { it.isNotBlank() }
val releaseKeyPassword = supabaseProps.getProperty("NUVIO_RELEASE_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
val releaseKeystore = releaseStoreFile?.let(rootProject::file)
val appVersionConfigFile = rootProject.file("iosApp/Configuration/Version.xcconfig")
val releaseAppVersionName = readXcconfigValue(appVersionConfigFile, "MARKETING_VERSION")
    ?: error("MARKETING_VERSION is missing from ${appVersionConfigFile.path}")
val releaseAppVersionCode = readXcconfigValue(appVersionConfigFile, "CURRENT_PROJECT_VERSION")
    ?.toIntOrNull()
    ?: error("CURRENT_PROJECT_VERSION is missing or invalid in ${appVersionConfigFile.path}")
val iosDistribution = (
    providers.gradleProperty("nuvio.ios.distribution").orNull
        ?: System.getenv("NUVIO_IOS_DISTRIBUTION")
        ?: supabaseProps.getProperty("NUVIO_IOS_DISTRIBUTION")
        ?: "appstore"
    ).trim().lowercase()
require(iosDistribution == "appstore" || iosDistribution == "full") {
    "NUVIO_IOS_DISTRIBUTION must be 'appstore' or 'full'."
}
val iosDistributionSourceDir = if (iosDistribution == "full") {
    "src/iosFull/kotlin"
} else {
    "src/iosAppStore/kotlin"
}
val iosFrameworkBundleId = "com.nuvio.media"
val fullCommonSourceDir = project.file("src/fullCommonMain/kotlin")
val generatedRuntimeConfigDir = layout.buildDirectory.dir("generated/runtime-config/kotlin")
val requestedGradleTasks = gradle.startParameter.taskNames.map { taskName ->
    taskName.substringAfterLast(':').lowercase()
}
val isAndroidAppBundleBuild = requestedGradleTasks.any { taskName ->
    taskName == "bundle" ||
        taskName == "bundlerelease" ||
        taskName == "bundledebug" ||
        taskName.startsWith("bundleplaystore") ||
        taskName.startsWith("bundlefull") ||
        taskName.endsWith("bundle")
}

val generateRuntimeConfigs = tasks.register<GenerateRuntimeConfigsTask>("generateRuntimeConfigs") {
    outputDir.set(generatedRuntimeConfigDir)
    localPropertiesFile.set(rootProject.layout.projectDirectory.file("local.properties"))
    val releaseProperties = layout.projectDirectory.file("runtime-config/release.properties")
    if (releaseProperties.asFile.exists()) {
        releasePropertiesFile.set(releaseProperties)
    }
    appVersionName.set(releaseAppVersionName)
    appVersionCode.set(releaseAppVersionCode)
}

tasks.withType<KotlinCompilationTask<*>>().configureEach {
    dependsOn(generateRuntimeConfigs)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    
    val iosTargets = listOf(
        iosArm64(),
        iosSimulatorArm64()
    )

    iosTargets.forEach { iosTarget ->
        iosTarget.compilations.getByName("main") {
            cinterops {
                create("commoncrypto") {
                    defFile(project.file("src/nativeInterop/cinterop/commoncrypto.def"))
                    compilerOpts("-I${project.projectDir}/src/nativeInterop/cinterop")
                }
            }

            if (iosDistribution == "full") {
                defaultSourceSet.kotlin.srcDir(fullCommonSourceDir)
            }
            defaultSourceSet.kotlin.srcDir(project.file(iosDistributionSourceDir))
            defaultSourceSet.dependencies {
                implementation(libs.ktor.client.darwin)
                if (iosDistribution == "full") {
                    implementation(libs.quickjs.kt)
                    implementation(libs.ksoup)
                }
            }
        }

        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
            freeCompilerArgs += listOf("-Xbinary=bundleId=$iosFrameworkBundleId")
        }
    }
    
    sourceSets {
        val commonMain by getting {
            kotlin.srcDir(generatedRuntimeConfigDir)
        }
        val desktopMain by getting {
            kotlin.srcDir(fullCommonSourceDir)
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.ktor.client.java)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.quickjs.kt)
                implementation(libs.ksoup)
                implementation(libs.jna)
                implementation("net.java.dev.jna:jna-platform:5.14.0")
                implementation("com.squareup.okhttp3:okhttp:4.12.0")
                implementation("org.openani.mediamp:mediamp-api:0.1.0-dev-1")
                implementation("org.openani.mediamp:mediamp-mpv:0.1.0-dev-1") { attributes { attribute(Attribute.of("org.jetbrains.kotlin.platform.type", String::class.java), "jvm") } }
            }
        }
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.appcompat)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.splashscreen)
            implementation(libs.androidx.work.runtime)
            implementation(libs.coil.gif)
            implementation("androidx.recyclerview:recyclerview:1.4.0")
            implementation("com.squareup.okhttp3:okhttp:4.12.0")
            implementation("com.google.code.gson:gson:2.11.0")
            implementation("io.github.peerless2012:ass-media:0.4.0-beta01")
            implementation(libs.ktor.client.android)
            implementation(libs.androidx.media3.exoplayer.hls)
            implementation(libs.androidx.media3.exoplayer.dash)
            implementation(libs.androidx.media3.exoplayer.smoothstreaming)
            implementation(libs.androidx.media3.exoplayer.rtsp)
            implementation(libs.androidx.media3.datasource)
            implementation(libs.androidx.media3.datasource.okhttp)
            implementation(libs.androidx.media3.decoder)
            implementation(libs.androidx.media3.session)
            implementation(libs.androidx.media3.common)
            implementation(libs.androidx.media3.container)
            implementation(libs.androidx.media3.extractor)
            implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("lib-*.aar"))))
        }
        commonMain.dependencies {
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)
            implementation(libs.coil.svg)
            implementation("dev.chrisbanes.haze:haze:1.7.2")
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.androidx.navigation.compose)
            implementation(libs.kermit)
            implementation(libs.supabase.postgrest)
            implementation(libs.supabase.auth)
            implementation(libs.supabase.functions)
            implementation(libs.reorderable)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

afterEvaluate {
    dependencies {
        add("fullImplementation", files("libs/quickjs-kt-android-1.0.5-nuvio.aar"))
        add("fullImplementation", libs.ksoup)
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    debugImplementation(libs.compose.uiTooling)
}

compose.desktop {
    application {
        mainClass = "com.nuvio.app.DesktopAppKt"

        val mediampRootDir = rootProject.file("mediamp")
        val mediampNativeBuildDir = mediampRootDir.resolve("mediamp-mpv/build-ci")
        val mediampPrebuiltDir = mediampRootDir.resolve("mediamp-mpv/libmpv/lib/windows/x86_64")
        fun File.safePath(): String = absolutePath.replace("\\", "/")

        jvmArgs(
            "-Dskiko.renderApi=OPENGL",
            // Hi-DPI on Windows OpenJDK builds is not auto-enabled even with
            // a Per-Monitor V2 manifest; AWT silently reports scaleX=1.0 and
            // Compose ends up rendering at 1x while the Windows compositor
            // upscales the bitmap with a cheap bilinear (visible as jagged
            // posters/avatars on home shelves at 125%/150% scale). Setting
            // `sun.java2d.uiScale.enabled=true` makes AWT honor the system
            // scale; the historical `sun.java2d.dpiaware` flag is included
            // for older code paths. WindowsNativeBootstrap pins the
            // resolved scale via JNA at startup as a second line of defense
            // for `gradle run`, but having the flags here is what makes
            // `compileKotlinDesktop` runs DPI-aware from the very first AWT
            // class init.
            "-Dsun.java2d.uiScale.enabled=true",
            "-Dsun.java2d.dpiaware=true",
            "-Djava.library.path=" + listOf(
                mediampNativeBuildDir.safePath(),
                mediampNativeBuildDir.resolve("Debug").safePath(),
                mediampNativeBuildDir.resolve("Release").safePath(),
                mediampPrebuiltDir.safePath(),
                System.getenv("NUVIO_MPV_DIR")?.let { "$it/bin" } ?: "",
            ).filter { it.isNotEmpty() }.joinToString(System.getProperty("path.separator")),
        )

        buildTypes.release.proguard {
            configurationFiles.from(project.file("desktop-proguard-rules.pro"))
        }

        nativeDistributions {
            packageName = "Nuvio"
            packageVersion = releaseAppVersionName
            vendor = "Creepso"
            modules("java.net.http")

            val hostOs = System.getProperty("os.name").lowercase()
            when {
                hostOs.contains("windows") -> targetFormats(TargetFormat.Exe, TargetFormat.Msi)
                hostOs.contains("mac") -> targetFormats(TargetFormat.Dmg)
            }

            windows {
                iconFile.set(project.file("desktop-icons/nuvio-windows.ico"))
                menu = true
                shortcut = true
                menuGroup = "Nuvio"
                exePackageVersion = releaseAppVersionName
                msiPackageVersion = releaseAppVersionName
            }

            macOS {
                dockName = "Nuvio"
                iconFile.set(project.file("desktop-icons/nuvio.icns"))
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSRequiresAquaSystemAppearance</key>
                        <false/>
                    """.trimIndent()
                }
            }
        }
    }
}

val windowsNativeRuntimeLockFile = layout.buildDirectory.file("compose/tmp/windows-native-runtime.lock")

val packageWindowsNativeRuntime = tasks.register<PackageWindowsNativeRuntimeTask>("packageWindowsNativeRuntime") {
    val mediampRootDir = rootProject.file("mediamp")
    val mediampNativeBuildDir = mediampRootDir.resolve("mediamp-mpv/build-ci")
    val mediampPrebuiltDir = mediampRootDir.resolve("mediamp-mpv/libmpv/lib/windows/x86_64")
    val system32Dir = File(System.getenv("WINDIR") ?: "C:/Windows", "System32")
    val appDir = layout.buildDirectory.dir("compose/binaries/main-release/app/Nuvio/app")
    val nativeDir = appDir.map { it.dir("native") }
    val launcherDir = layout.buildDirectory.dir("compose/binaries/main-release/app/Nuvio")

    group = "compose desktop"
    description = "Copies MediaMP/MPV native DLLs into the Windows app image and points java.library.path at them."

    this.mediampNativeBuildDir.set(mediampNativeBuildDir)
    this.mediampPrebuiltDir.set(mediampPrebuiltDir)
    this.system32Dir.set(system32Dir)
    this.appDir.set(appDir)
    this.nativeDir.set(nativeDir)
    this.launcherDir.set(launcherDir)
    val defaultStremioDir = rootProject.file("stremio-community-v5/deps/libmpv/x86_64")
    this.stremioLibmpvDir.set(
        providers.gradleProperty("nuvio.stremio.libmpv.dir")
            .orElse(providers.environmentVariable("NUVIO_STREMIO_LIBMPV_DIR"))
            .orElse(defaultStremioDir.absolutePath),
    )
    this.lockFile.set(windowsNativeRuntimeLockFile)
}

val windowsPackageAppImageService = gradle.sharedServices.registerIfAbsent(
    "windowsPackageAppImage",
    WindowsPackageAppImageBuildService::class,
) {
    maxParallelUsages.set(1)
}

packageWindowsNativeRuntime.configure {
    usesService(windowsPackageAppImageService)
}

tasks.matching { it.name == "createReleaseDistributable" }.configureEach {
    finalizedBy(packageWindowsNativeRuntime)
}

packageWindowsNativeRuntime.configure {
    mustRunAfter(tasks.matching { it.name == "createReleaseDistributable" })
}

val windowsPackageResourcesSource = layout.projectDirectory.dir("src/windowsPackageResources").asFile
val composeWindowsResourceDir = layout.buildDirectory.dir("compose/tmp/resources")
val windowsInstallerSidebarPngFile = layout.projectDirectory.file("desktop-icons/nuvio-installer-sidebar.png").asFile
val windowsInstallerBannerBmpFile = layout.projectDirectory.file("desktop-icons/nuvio-installer-banner.bmp").asFile
val windowsInstallerSetupIconFile = layout.projectDirectory.file("desktop-icons/nuvio-installer.ico").asFile

val syncWindowsPackageResources = tasks.register<SyncWindowsPackageResourcesTask>("syncWindowsPackageResources") {
    group = "compose desktop"
    description = "Copies Windows jpackage/WiX override resources into the jpackage --resource-dir used by packageReleaseExe."
    sourceDirectory.set(windowsPackageResourcesSource)
    targetDirectory.set(composeWindowsResourceDir)
    installerSidebarPng.set(windowsInstallerSidebarPngFile)
    installerBannerBmp.set(windowsInstallerBannerBmpFile)
    installerSetupIconIco.set(windowsInstallerSetupIconFile)
}

tasks.matching {
    it.name == "packageReleaseDistributionForCurrentOS" ||
        it.name == "packageReleaseExe" ||
        it.name == "packageReleaseMsi"
}.configureEach {
    usesService(windowsPackageAppImageService)
    dependsOn("createReleaseDistributable")
    dependsOn(packageWindowsNativeRuntime)
    dependsOn(syncWindowsPackageResources)
    inputs.dir(windowsPackageResourcesSource)
}

syncWindowsPackageResources.configure {
    mustRunAfter(tasks.matching {
        it.name == "createReleaseDistributable" || it.name == "createRuntimeImage"
    })
}

tasks.matching { it.name == "runReleaseDistributable" }.configureEach {
    dependsOn(packageWindowsNativeRuntime)
}

val packageReleaseInnoExe = tasks.register<Exec>("packageReleaseInnoExe") {
    group = "compose desktop"
    description = "Builds a Windows installer with Inno Setup (no WiX), using the release app image."
    usesService(windowsPackageAppImageService)
    dependsOn("createReleaseDistributable")
    dependsOn(packageWindowsNativeRuntime)

    val appImageDir = layout.buildDirectory.dir("compose/binaries/main-release/app/Nuvio").get().asFile.absolutePath
    val outputDir = layout.buildDirectory.dir("compose/binaries/main-release/inno").get().asFile.absolutePath
    val scriptPath = layout.projectDirectory.file("scripts/package-release-inno.ps1").asFile.absolutePath
    val setupIcon = layout.projectDirectory.file("desktop-icons/nuvio-windows.ico").asFile.absolutePath
    val appIcon = layout.projectDirectory.file("desktop-icons/nuvio-windows.ico").asFile.absolutePath
    val sidebarPng = layout.projectDirectory.file("desktop-icons/nuvio-installer-sidebar.png").asFile.absolutePath

    commandLine(
        "powershell",
        "-NoProfile",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        scriptPath,
        "-AppDir",
        appImageDir,
        "-OutputDir",
        outputDir,
        "-AppVersion",
        releaseAppVersionName,
        "-AppBuild",
        releaseAppVersionCode.toString(),
        "-SetupIcon",
        setupIcon,
        "-AppIcon",
        appIcon,
        "-SidebarPng",
        sidebarPng,
    )
}

tasks.register<Zip>("packageReleasePortableZip") {
    group = "compose desktop"
    description = "Builds a portable Windows ZIP package (no installer, no WiX/NSIS/Inno)."
    usesService(windowsPackageAppImageService)
    dependsOn("createReleaseDistributable")
    dependsOn(packageWindowsNativeRuntime)

    val portableRootName = "Nuvio-${releaseAppVersionName}-portable"
    val appImageDir = layout.buildDirectory.dir("compose/binaries/main-release/app/Nuvio")
    val portableMarkerFile = layout.buildDirectory.file("compose/tmp/portable-marker/Nuvio.portable")

    archiveBaseName.set("Nuvio-${releaseAppVersionName}-portable")
    archiveExtension.set("zip")
    destinationDirectory.set(layout.buildDirectory.dir("compose/binaries/main-release/portable"))

    // Ensure the portable marker sits next to `Nuvio.exe` inside the generated ZIP root.
    into(portableRootName)
    from(appImageDir)
    doFirst {
        val markerFile = portableMarkerFile.get().asFile
        markerFile.parentFile.mkdirs()
        markerFile.writeText("")
    }
    from(portableMarkerFile)
}

val renameReleaseDmgArtifact = tasks.register<RenameReleaseDmgTask>("renameReleaseDmgArtifact") {
    versionName.set(releaseAppVersionName)
    dmgDirectory.set(layout.buildDirectory.dir("compose/binaries/main-release/dmg"))
}

tasks.matching { it.name == "packageReleaseDmg" }.configureEach {
    finalizedBy(renameReleaseDmgArtifact)
}

configurations.all {
    exclude(group = "androidx.media3", module = "media3-exoplayer")
    exclude(group = "androidx.media3", module = "media3-ui")
}

android {
    namespace = "com.nuvio.app"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    signingConfigs {
        create("release") {
            if (releaseKeystore != null && releaseStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null) {
                storeFile = releaseKeystore
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.nuvio.app"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = releaseAppVersionCode
        versionName = releaseAppVersionName
    }
    flavorDimensions += "distribution"
    productFlavors {
        create("full") {
            dimension = "distribution"
        }
        create("playstore") {
            dimension = "distribution"
        }
    }
    sourceSets.getByName("full") {
        manifest.srcFile("src/androidFull/AndroidManifest.xml")
        java.srcDir(fullCommonSourceDir)
    }
    splits {
        abi {
            isEnable = !isAndroidAppBundleBuild
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = false
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += listOf(
                "lib/*/libc++_shared.so",
                "lib/*/libavcodec.so",
                "lib/*/libavutil.so",
                "lib/*/libswscale.so",
                "lib/*/libswresample.so"
            )
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
            ndk {
                debugSymbolLevel = "FULL"
            }
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
