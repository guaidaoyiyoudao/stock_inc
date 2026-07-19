pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google") {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("androidx.*")
                // Google Maven 上 com.google.* 实际涉及的 group（精确列出，避免误吞 KSP/Firebase 等
                // 这些不在 Google Maven 的 artifact；KSP 在 mavenCentral/gradlePluginPortal）
                includeGroupByRegex("com\\.google\\.android\\..*")
                includeGroup("com.google.android")
                includeGroup("com.google.mlkit")
                includeGroup("com.google.gms")
                includeGroup("com.google.firebase")
                includeGroup("com.google.ar")
                includeGroup("com.google.android.gms")
                // AGP 8.7+ 传递依赖，仅托管在 Google Maven
                includeGroup("com.google.testing.platform")
            }
        }
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
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
                includeGroup("com.google.android.gms")
                // AGP 8.7+ 传递依赖，仅托管在 Google Maven
                includeGroup("com.google.testing.platform")
            }
        }
        mavenCentral()
        gradlePluginPortal()
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
