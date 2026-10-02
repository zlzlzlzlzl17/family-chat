plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp")
}

import java.util.Properties

// Local endpoint configuration is deliberately excluded from version control.
val familyChatProperties = Properties().apply {
    val localConfig = rootProject.file("familychat.properties")
    if (localConfig.exists()) localConfig.inputStream().use(::load)
}
val familyChatServerUrl = providers.gradleProperty("familychatServerUrl")
    .orElse(providers.environmentVariable("FAMILYCHAT_SERVER_URL"))
    .getOrElse(familyChatProperties.getProperty("serverUrl", "https://example.com"))
val escapedServerUrl = familyChatServerUrl.replace("\\", "\\\\").replace("\"", "\\\"")

if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use(::load)
    }
}
val hasReleaseSigning =
    keystorePropertiesFile.exists() &&
        listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all {
            !keystoreProperties.getProperty(it).isNullOrBlank()
        }

val familyChatVersionCode = 40115
val familyChatVersionName = "v4.1.15"

android {
    namespace = "com.example.chat"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.chat"
        minSdk = 26
        targetSdk = 36
        versionCode = familyChatVersionCode
        versionName = familyChatVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"$escapedServerUrl\"")
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    flavorDimensions += "releaseChannel"
    productFlavors {
        create("beta") {
            dimension = "releaseChannel"
            versionNameSuffix = "(beta)"
        }
        create("official") {
            dimension = "releaseChannel"
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    // Diagnostic escape hatch: -PfamilychatNoMinify=true builds a release-signed
    // APK with R8 off. Same signing key, so it installs in place over a normal
    // build, but stack traces are unobfuscated and any R8-specific failure
    // disappears - which is how you tell a code bug from a shrinker bug without
    // a debugger attached.
    val disableMinify = providers.gradleProperty("familychatNoMinify").orNull == "true"

    buildTypes {
        release {
            isMinifyEnabled = !disableMinify
            isShrinkResources = !disableMinify
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

val betaArtifactDir = rootProject.layout.projectDirectory.dir("artifacts/beta")
val officialArtifactDir = rootProject.layout.projectDirectory.dir("artifacts/release")

tasks.register<Copy>("copyFamilyChatBetaReleaseApk") {
    group = "familychat"
    description = "Builds the beta release APK and copies it to artifacts/beta."
    dependsOn("assembleBetaRelease")
    from(layout.buildDirectory.file("outputs/apk/beta/release/app-beta-release.apk"))
    into(betaArtifactDir)
    rename { "familychat_${familyChatVersionName}(beta).apk" }
    doFirst {
        delete(betaArtifactDir.asFileTree.matching { include("familychat_*.apk") })
    }
}

tasks.register<Copy>("copyFamilyChatOfficialReleaseApk") {
    group = "familychat"
    description = "Builds the official release APK and copies it to artifacts/release."
    dependsOn("assembleOfficialRelease")
    from(layout.buildDirectory.file("outputs/apk/official/release/app-official-release.apk"))
    into(officialArtifactDir)
    rename { "familychat_${familyChatVersionName}.apk" }
    doFirst {
        delete(officialArtifactDir.asFileTree.matching { include("familychat_*.apk") })
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.okhttp)
    implementation(libs.webrtc.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.profileinstaller)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.arch.core.testing)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.navigation.testing)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
