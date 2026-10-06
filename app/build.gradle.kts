plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Only public GitHub owner/repo identifiers are accepted; never put a token in the APK.
fun releaseSetting(name: String): String = providers.gradleProperty(name)
    .orElse(providers.environmentVariable(name)).orNull.orEmpty()

val updateRepository = releaseSetting("MOGE_UPDATE_REPOSITORY").trim()
val updateMirror = releaseSetting("MOGE_UPDATE_MIRROR").trim().ifEmpty { "https://ghfast.top/" }
require(Regex("https://[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?/").matches(updateMirror)) {
    "MOGE_UPDATE_MIRROR must be an HTTPS origin ending with /"
}
require(updateRepository.isEmpty() ||
    Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?/[A-Za-z0-9_.-]{1,100}")
        .matches(updateRepository) && updateRepository.substringAfter('/') !in setOf(".", "..")) {
    "MOGE_UPDATE_REPOSITORY must be a public GitHub owner/repo identifier"
}
val releaseStorePath = releaseSetting("MOGE_RELEASE_STORE_FILE")
val releaseStorePassword = releaseSetting("MOGE_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = releaseSetting("MOGE_RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseSetting("MOGE_RELEASE_KEY_PASSWORD")
val signingValues = listOf(releaseStorePath, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
val officialSigning = signingValues.all { it.isNotEmpty() }
require(signingValues.all { it.isEmpty() } || officialSigning) {
    "Provide all four MOGE_RELEASE_* signing settings, or none for a developer release"
}
if (officialSigning) {
    require(file(releaseStorePath).isFile &&
        file(releaseStorePath).canonicalFile != file("debug.keystore").canonicalFile &&
        !releaseKeyAlias.equals("androiddebugkey", ignoreCase = true)) {
        "Official signing requires an existing dedicated release keystore and alias"
    }
}
require(!releaseSetting("MOGE_REQUIRE_OFFICIAL_SIGNING").equals("true", ignoreCase = true) ||
    officialSigning && updateRepository.isNotEmpty()) {
    "Publishing requires official signing credentials and MOGE_UPDATE_REPOSITORY"
}
logger.lifecycle("Moge release signing: ${if (officialSigning) "OFFICIAL" else "DEVELOPER (debug key; updates disabled)"}")

android {
    namespace = "com.moge.app"
    compileSdk = 36
    // Pin the build tools version used locally and by CI.
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.moge.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 18
        versionName = "0.3.8"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        buildConfigField("String", "UPDATE_MIRROR", "\"$updateMirror\"")

        // 真机分发只打 arm64-v8a；debug 另加 x86_64 供模拟器使用
        ndk {
            abiFilters += setOf("arm64-v8a")
        }
    }

    signingConfigs {
        getByName("debug") {
            // Preserve an existing local developer identity; new clones use AGP's default debug key.
            val localDebugKeystore = file("debug.keystore")
            if (localDebugKeystore.isFile) {
                storeFile = localDebugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        if (officialSigning) {
            create("officialRelease") {
                storeFile = file(releaseStorePath)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("String", "UPDATE_REPOSITORY", "\"\"")
            buildConfigField("boolean", "UPDATE_ENABLED", "false")
            buildConfigField("String", "SIGNING_STATUS", "\"DEBUG\"")
            ndk {
                abiFilters += "x86_64"
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName(if (officialSigning) "officialRelease" else "debug")
            buildConfigField("String", "UPDATE_REPOSITORY", "\"${if (officialSigning) updateRepository else ""}\"")
            buildConfigField("boolean", "UPDATE_ENABLED", (officialSigning && updateRepository.isNotEmpty()).toString())
            buildConfigField("String", "SIGNING_STATUS", "\"${if (officialSigning) "OFFICIAL" else "DEVELOPER"}\"")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        // 依赖版本钉死在离线缓存里（本机拉包不稳），「有新版本」类提醒只是噪音；
        // 正式包只发 arm64-v8a，ChromeOS 的 x86_64 不在目标内。
        disable += setOf(
            "NewerVersionAvailable",
            "GradleDependency",
            "AndroidGradlePluginVersion",
            "ChromeOsAbiSupport",
        )
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    // 让单元测试能读到 Room 导出的 schema JSON（迁移测试用）
    sourceSets {
        getByName("test") {
            resources.srcDir("$projectDir/schemas")
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation(libs.androidx.exifinterface)

    // 回答渲染：Markdown + 本地 JLatexMath 公式（不用 WebView / CDN）
    implementation(libs.markwon.core)
    implementation(libs.markwon.inline.parser)
    implementation(libs.markwon.ext.latex)
    implementation(libs.markwon.ext.tables)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockk)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.compose.ui.test.junit4)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
