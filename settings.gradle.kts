pluginManagement {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // jcenter mirror
        maven("https://repo.huaweicloud.com/repository/maven")
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
plugins {
    id("com.gradle.enterprise") version "3.8"
}

gradleEnterprise {
    buildScan {
        termsOfServiceUrl = "https://gradle.com/terms-of-service"
        termsOfServiceAgree = "yes"
    }
}

rootProject.name="MiraiAndroid"
include(":app")
