pluginManagement {
    repositories {
        // Google maven via aliyun mirror (dl.google.com is blocked on this network)
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Google maven mirror (AGP, AndroidX, Material, security-crypto)
        maven("https://maven.aliyun.com/repository/google")
        // jcenter mirror (LSPosed API de.robv.android.xposed:api:82)
        maven("https://maven.aliyun.com/repository/jcenter")
        mavenCentral()
    }
}

rootProject.name = "BitLockerDroid"
include(":app")
