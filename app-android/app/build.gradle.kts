plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "co.saerling.rutas"
    compileSdk = 35
    testOptions { unitTests { isIncludeAndroidResources = true; all { it.maxHeapSize = "3g"; it.systemProperty("robolectric.graphicsMode", "NATIVE"); it.systemProperty("robolectric.pixelCopyRenderMode", "hardware"); it.systemProperty("roborazzi.test.record", "true") } } }

    defaultConfig {
        applicationId = "co.saerling.rutas"
        minSdk = 26
        targetSdk = 35
        // Sigue la numeración de las compilaciones de GitHub, así cada versión nueva se instala encima de la anterior.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 4
        versionName = "0.2.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
        // Solo los procesadores de los celulares Android reales: el APK pesa la mitad.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        buildConfigField("String", "API", "\"https://rutas-acme-44c2.vercel.app\"")
    }

    signingConfigs {
        // La misma llave de las versiones anteriores: Android instala la nueva encima sin borrar nada.
        create("rutas") {
            storeFile = file("../../credentials/rutas.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("rutas") }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("rutas")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources { excludes += setOf("/META-INF/{AL2.0,LGPL2.1}") } }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.maplibre.gl:android-sdk:11.8.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation(platform("androidx.compose:compose-bom:2024.10.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.43.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.43.1")
}
