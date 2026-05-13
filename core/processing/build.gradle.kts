plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

val skipNativeJpegBuild = providers.gradleProperty("imageshare.skipNativeJpegBuild")
    .map(String::toBoolean)
    .getOrElse(false)
val skipNativeAvifBuild = providers.gradleProperty("imageshare.skipNativeAvifBuild")
    .map(String::toBoolean)
    .getOrElse(false)
val configureNativeBuild = (!skipNativeJpegBuild || !skipNativeAvifBuild) && gradle.startParameter.taskNames.let { requestedTasks ->
    requestedTasks.isEmpty() || requestedTasks.any { taskName ->
        val normalized = taskName.lowercase()
        !normalized.contains("lint") &&
            !normalized.contains("detekt") &&
            !normalized.contains("unittest") &&
            normalized != "test" &&
            !normalized.endsWith(":test")
    }
}

android {
    namespace = "com.imageshare.core.processing"
    compileSdk = 36
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "ENABLE_NATIVE_JPEG", (!skipNativeJpegBuild).toString())
        buildConfigField("boolean", "ENABLE_NATIVE_AVIF", (!skipNativeAvifBuild).toString())

        if (configureNativeBuild) {
            externalNativeBuild {
                cmake {
                    cppFlags += "-std=c++17"
                    arguments += listOf(
                        "-DANDROID_STL=c++_static",
                        "-DCMAKE_BUILD_TYPE=Release",
                        "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                        "-DIMAGESHARE_BUILD_JPEG=${if (skipNativeJpegBuild) "OFF" else "ON"}",
                        "-DIMAGESHARE_BUILD_AVIF=${if (skipNativeAvifBuild) "OFF" else "ON"}",
                    )
                }
            }
        }

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    if (configureNativeBuild) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    buildFeatures {
        buildConfig = true
        prefab = true
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.heifwriter)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
