import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Подпись release: keystore.properties (не в git) или переменные окружения TELIQ_* (GitHub Actions).
// Без ключа release собирается неподписанным. Обновления поверх установленной версии возможны только
// при подписи ТЕМ ЖЕ ключом — ключ нельзя терять и менять.
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun signingValue(prop: String, env: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(prop)
val releaseStoreFile = signingValue("storeFile", "TELIQ_KEYSTORE")

android {
    namespace = "com.teliq.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.teliq.tv"
        minSdk = 23           // Android TV API 23+ покрывает почти все приставки
        targetSdk = 34
        versionCode = (project.property("teliq.versionCode") as String).toInt()
        versionName = project.property("teliq.versionName") as String
        // Репозиторий GitHub, из релизов которого приложение обновляет себя
        buildConfigField("String", "UPDATE_REPO_OWNER", "\"${project.property("teliq.updateRepoOwner")}\"")
        buildConfigField("String", "UPDATE_REPO_NAME", "\"${project.property("teliq.updateRepoName")}\"")
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = signingValue("storePassword", "TELIQ_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "TELIQ_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "TELIQ_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfigs.findByName("release")?.let { signingConfig = it }
            // libVLC весит ~50 МБ на архитектуру: в релиз только ARM (все ТВ-приставки), x86 — для эмулятора (debug)
            ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.leanback:leanback:1.0.0")
    implementation("androidx.leanback:leanback-preference:1.0.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Coroutines — для парсинга в фоне
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // libVLC for Android — тот же движок, что и у VLC / Perfect Player
    // Поддерживает HEVC, MPEG-TS, RTMP, UDP-мультикаст, нестандартные потоки
    implementation("org.videolan.android:libvlc-all:3.6.0")

    // Сеть и загрузка логотипов
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil:2.7.0")
}
