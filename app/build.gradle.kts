import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.baselineprofile)
    alias(libs.plugins.ksp)
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.isFile) {
        localPropertiesFile.inputStream().use(::load)
    }
}

fun releaseSigningValue(name: String): String? =
    System.getenv(name)?.takeIf(String::isNotBlank)
        ?: localProperties.getProperty(name)?.takeIf(String::isNotBlank)

val releaseSigningValues = mapOf(
    "IMAGESHARE_KEYSTORE_PATH" to releaseSigningValue("IMAGESHARE_KEYSTORE_PATH"),
    "IMAGESHARE_KEYSTORE_PASSWORD" to releaseSigningValue("IMAGESHARE_KEYSTORE_PASSWORD"),
    "IMAGESHARE_KEY_ALIAS" to releaseSigningValue("IMAGESHARE_KEY_ALIAS"),
    "IMAGESHARE_KEY_PASSWORD" to releaseSigningValue("IMAGESHARE_KEY_PASSWORD"),
)
val releaseSigningConfigured = releaseSigningValues.values.all { it != null }

android {
    namespace = "com.imageshare.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.imageshare.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "TRANSFORM_API_ENABLED", "true")
        buildConfigField("int", "TRANSFORM_RATE_LIMIT_PER_UID_PER_MINUTE", "100")
        buildConfigField("int", "TRANSFORM_MAX_CONCURRENT_PER_UID", "2")
        buildConfigField("int", "TRANSFORM_MAX_CONCURRENT_PROCESS_WIDE", "8")
        buildConfigField("long", "TRANSFORM_MAX_PIXELS", "200_000_000L")
        buildConfigField("long", "TRANSFORM_MAX_TARGET_BYTES", "100L * 1024L * 1024L")
    }

    signingConfigs {
        create("release") {
            if (releaseSigningConfigured) {
                storeFile = rootProject.file(checkNotNull(releaseSigningValues["IMAGESHARE_KEYSTORE_PATH"]))
                storePassword = releaseSigningValues["IMAGESHARE_KEYSTORE_PASSWORD"]
                keyAlias = releaseSigningValues["IMAGESHARE_KEY_ALIAS"]
                keyPassword = releaseSigningValues["IMAGESHARE_KEY_PASSWORD"]
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    bundle {
        abi {
            enableSplit = true
        }
        density {
            enableSplit = true
        }
        language {
            enableSplit = true
        }
    }

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

val validateReleaseSigning by tasks.registering {
    group = "verification"
    description = "Fails unless all credentials required for a Play release bundle are configured."

    doLast {
        val missing = releaseSigningValues.filterValues { it == null }.keys
        check(missing.isEmpty()) {
            "Release signing is not configured. Missing: ${missing.sorted().joinToString()}. " +
                "See docs/RELEASE_SIGNING_SETUP.md."
        }
        val keystoreFile = rootProject.file(checkNotNull(releaseSigningValues["IMAGESHARE_KEYSTORE_PATH"]))
        check(keystoreFile.isFile) {
            "Release keystore does not exist or is not a file: ${keystoreFile.absolutePath}"
        }
    }
}

tasks.configureEach {
    if (name == "bundleRelease") {
        dependsOn(validateReleaseSigning)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core:io"))
    implementation(project(":core:processing"))
    implementation(project(":feature:preset"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.coil.compose)
    implementation(libs.google.material)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    baselineProfile(project(":benchmark:macro"))

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.work.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.exifinterface)
    androidTestImplementation(project(":sdk:imageshare-api"))
}
