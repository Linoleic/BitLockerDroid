pluginManagement {
    val useAliyunMirror = when (System.getenv("USE_ALIYUN_MIRROR")?.lowercase()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> !(System.getenv("CI").equals("true", ignoreCase = true) ||
            System.getenv("GITHUB_ACTIONS").equals("true", ignoreCase = true))
    }
    repositories {
        if (useAliyunMirror) {
            maven("https://maven.aliyun.com/repository/google") {
                content {
                    includeGroupByRegex("com\\.android.*")
                    includeGroupByRegex("com\\.google.*")
                    includeGroupByRegex("androidx.*")
                }
            }
            maven("https://maven.aliyun.com/repository/public")
            maven("https://maven.aliyun.com/repository/gradle-plugin")
        }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    val useAliyunMirror = when (System.getenv("USE_ALIYUN_MIRROR")?.lowercase()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> !(System.getenv("CI").equals("true", ignoreCase = true) ||
            System.getenv("GITHUB_ACTIONS").equals("true", ignoreCase = true))
    }
    repositories {
        if (useAliyunMirror) {
            maven("https://maven.aliyun.com/repository/google") {
                content {
                    includeGroupByRegex("com\\.android.*")
                    includeGroupByRegex("com\\.google.*")
                    includeGroupByRegex("androidx.*")
                }
            }
            maven("https://maven.aliyun.com/repository/public")
        }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "BitLockerDroid"
include(":app")
