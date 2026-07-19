pluginManagement {
    repositories {
        // Plugin marker artifact（如 KSP）只在官方 Maven Central / Gradle Portal 有，
        // 阿里云镜像不全且 404 会让 Gradle 不 fallback。故 plugin 解析官方仓库优先。
        gradlePluginPortal()
        mavenCentral()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.google\\.android\\..*")
                includeGroup("com.google.android")
                includeGroup("com.google.mlkit")
                includeGroup("com.google.gms")
                includeGroup("com.google.firebase")
                includeGroup("com.google.ar")
                includeGroup("com.google.testing.platform")
            }
        }
        maven("https://maven.aliyun.com/repository/google") {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.google\\.android\\..*")
                includeGroup("com.google.android")
                includeGroup("com.google.mlkit")
                includeGroup("com.google.gms")
                includeGroup("com.google.firebase")
                includeGroup("com.google.ar")
                includeGroup("com.google.testing.platform")
            }
        }
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "StockDividend"
include(":app")
