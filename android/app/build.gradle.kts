import java.io.File

plugins {
    id("com.android.application")
    id("androidx.room")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.compose")
}

val androidKeystorePath = System.getenv("VELIN_ANDROID_KEYSTORE")?.trim().orEmpty()
val androidStoreFile =
    androidKeystorePath.takeIf { it.isNotEmpty() }?.let { path ->
        val candidate = File(path)
        if (candidate.isAbsolute) candidate else rootProject.file(path)
    }
if (androidKeystorePath.isNotEmpty() && androidStoreFile?.isFile != true) {
    error("VELIN_ANDROID_KEYSTORE is set but is not a readable file: $androidKeystorePath")
}
val requireReleaseSigning =
    providers.gradleProperty("velin.requireReleaseSigning").orNull == "true"
if (requireReleaseSigning && androidStoreFile == null) {
    error("Release packaging requires VELIN_ANDROID_KEYSTORE")
}
val androidStorePassword = System.getenv("VELIN_ANDROID_STORE_PASSWORD") ?: ""
val androidKeyAlias = System.getenv("VELIN_ANDROID_KEY_ALIAS") ?: ""
val androidKeyPassword = System.getenv("VELIN_ANDROID_KEY_PASSWORD") ?: ""
if (androidStoreFile != null &&
    (androidStorePassword.isEmpty() || androidKeyAlias.isEmpty() || androidKeyPassword.isEmpty())
) {
    error("Release signing requires VELIN_ANDROID_STORE_PASSWORD, VELIN_ANDROID_KEY_ALIAS, and VELIN_ANDROID_KEY_PASSWORD")
}

android {
    namespace = "com.haraldmue.velin"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.haraldmue.velin"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (androidStoreFile != null) {
            create("release") {
                storeFile = androidStoreFile
                storePassword = androidStorePassword
                keyAlias = androidKeyAlias
                keyPassword = androidKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (androidStoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.paging:paging-runtime-ktx:3.3.6")
    implementation("androidx.paging:paging-compose:3.3.6")
    implementation("androidx.room:room-ktx:2.8.4")
    implementation("androidx.room:room-paging:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    implementation("com.google.zxing:core:3.5.4")
    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.media3:media3-session:1.11.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.0")
    implementation("io.coil-kt.coil3:coil-compose:3.4.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.4.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.room:room-testing:2.8.4")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.json:json:20250517")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.5.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
