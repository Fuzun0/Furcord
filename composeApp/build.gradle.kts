import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvm("desktop") {
        @Suppress("OPT_IN_USAGE")
        mainRun {
            mainClass.set("com.furcord.MainKt")
        }
    }

    targets.withType<org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget>().configureEach {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
                }
            }
        }
    }

    sourceSets {
        val desktopMain by getting

        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.kotlinx.serialization.json)
        }

        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)

            // ── WebRTC (Google libwebrtc via JNI) ─────────────────────────
            implementation(libs.webrtc.java)
            runtimeOnly("dev.onvoid.webrtc:webrtc-java:0.8.0:windows-x86_64")

            // ── LiveKit Access Token (JWT) ─────────────────────────────────
            implementation(libs.jjwt.api)
            runtimeOnly(libs.jjwt.impl)
            runtimeOnly(libs.jjwt.jackson)

            // ── LiveKit Signaling (WebSocket) ─────────────────────────────
            implementation(libs.okhttp)
            // NOT: protobuf-java kaldırıldı — LiveKitProto.kt kendi hand-crafted codec'ini kullanıyor
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.furcord.MainKt"

        val relayHost = providers.gradleProperty("furcordRelayHost")
            .orElse(providers.environmentVariable("FURCORD_RELAY_HOST"))
            .orNull
            ?.trim()

        val relayPort = providers.gradleProperty("furcordRelayPort")
            .orElse(providers.environmentVariable("FURCORD_RELAY_PORT"))
            .orNull
            ?.trim()
            ?.toIntOrNull()

        val googleClientId = providers.gradleProperty("furcordGoogleClientId")
            .orElse(providers.environmentVariable("FURCORD_GOOGLE_CLIENT_ID"))
            .orNull?.trim()

        val googleClientSecret = providers.gradleProperty("furcordGoogleClientSecret")
            .orElse(providers.environmentVariable("FURCORD_GOOGLE_CLIENT_SECRET"))
            .orNull?.trim()

        val livekitUrl = providers.gradleProperty("livekitUrl")
            .orElse(providers.environmentVariable("LIVEKIT_URL")).orNull?.trim()
        val livekitApiKey = providers.gradleProperty("livekitApiKey")
            .orElse(providers.environmentVariable("LIVEKIT_API_KEY")).orNull?.trim()
        val livekitApiSecret = providers.gradleProperty("livekitApiSecret")
            .orElse(providers.environmentVariable("LIVEKIT_API_SECRET")).orNull?.trim()

        val appJvmArgs = mutableListOf("-Xmx512m", "-Dskiko.renderApi=SOFTWARE")
        if (!relayHost.isNullOrEmpty() && relayPort != null) {
            appJvmArgs += "-Dfurcord.relay.host=$relayHost"
            appJvmArgs += "-Dfurcord.relay.port=$relayPort"
        }
        if (!googleClientId.isNullOrEmpty()) {
            appJvmArgs += "-Dfurcord.google.clientId=$googleClientId"
        }
        if (!googleClientSecret.isNullOrEmpty()) {
            appJvmArgs += "-Dfurcord.google.clientSecret=$googleClientSecret"
        }
        if (!livekitUrl.isNullOrEmpty()) {
            appJvmArgs += "-Dfurcord.livekit.url=$livekitUrl"
        }
        if (!livekitApiKey.isNullOrEmpty()) {
            appJvmArgs += "-Dfurcord.livekit.apiKey=$livekitApiKey"
        }
        if (!livekitApiSecret.isNullOrEmpty()) {
            appJvmArgs += "-Dfurcord.livekit.apiSecret=$livekitApiSecret"
        }

        jvmArgs(*appJvmArgs.toTypedArray())

        nativeDistributions {
            val appVersionRaw = providers.gradleProperty("appVersion")
                .orElse(providers.environmentVariable("APP_VERSION"))
                .orElse("1.0.0")
                .get()

            val winVersionParts = appVersionRaw
                .split(".")
                .map { it.filter(Char::isDigit) }
                .mapNotNull { it.toIntOrNull() }

            val winMajor = winVersionParts.getOrElse(0) { 1 }.coerceIn(0, 255)
            val winMinor = winVersionParts.getOrElse(1) { 0 }.coerceIn(0, 255)
            val winBuild = winVersionParts.getOrElse(2) { 0 }.coerceIn(0, 65535)
            val windowsPackageVersion = "$winMajor.$winMinor.$winBuild"

            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Furcord"
            packageVersion = windowsPackageVersion
            description = "Furcord Voice Chat"
            includeAllModules = true
            val localJavaHome = "C:\\jdk21_temp\\jdk-21.0.7+6"
            if (file(localJavaHome).exists()) {
                javaHome = localJavaHome
            }

            windows {
                packageVersion = windowsPackageVersion
                msiPackageVersion = windowsPackageVersion
                exePackageVersion = windowsPackageVersion
                menuGroup = "Furcord"
                upgradeUuid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
                perUserInstall = true
                shortcut = true
                iconFile.set(project.file("src/desktopMain/resources/furcord.ico"))
            }
        }
    }
}

