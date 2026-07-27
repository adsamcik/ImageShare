import org.gradle.api.publish.maven.MavenPublication

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    id("maven-publish")
}

android {
    namespace = "com.imageshare.api"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("proguard-consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.10.0")
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])

                groupId = "com.imageshare"
                artifactId = "imageshare-api"
                version = "0.1.0"

                pom {
                    name.set("ImageShare API")
                    description.set("Kotlin SDK for the ImageShare Transform API — on-device image format/quality/resize/metadata transformations via ContentProvider IPC.")
                    url.set("https://github.com/adsamcik/ImageShare")

                    licenses {
                        license {
                            name.set("GNU General Public License v3.0")
                            url.set("https://www.gnu.org/licenses/gpl-3.0.txt")
                        }
                    }

                    developers {
                        developer {
                            id.set("adsamcik")
                            name.set("adsamcik")
                            url.set("https://github.com/adsamcik")
                        }
                    }

                    scm {
                        connection.set("scm:git:git://github.com/adsamcik/ImageShare.git")
                        developerConnection.set("scm:git:ssh://git@github.com/adsamcik/ImageShare.git")
                        url.set("https://github.com/adsamcik/ImageShare")
                    }

                    issueManagement {
                        system.set("GitHub Issues")
                        url.set("https://github.com/adsamcik/ImageShare/issues")
                    }
                }
            }
        }
    }
}
