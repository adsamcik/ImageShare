pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ImageShare"

include(":app")
include(":sdk:imageshare-api")
include(":core:processing")
include(":core:io")
include(":feature:preset")
include(":benchmark:micro")
include(":benchmark:macro")
include(":samples:minimal-host")
include(":samples:picker-host")
