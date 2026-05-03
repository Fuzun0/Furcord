import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvm("desktop")

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
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.androidx.lifecycle.runtime.compose)
        }

        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
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

        val appJvmArgs = mutableListOf("-Xmx512m")
        if (!relayHost.isNullOrEmpty() && relayPort != null) {
            appJvmArgs += "-Dfurcord.relay.host=$relayHost"
            appJvmArgs += "-Dfurcord.relay.port=$relayPort"
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
            }
        }
    }
}
