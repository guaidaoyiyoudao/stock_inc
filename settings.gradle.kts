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
        maven("https://maven.aliyun.com/repository/google") {
            content {
                // 阿里云 google 镜像只镜像 Google Maven；必须加 content 过滤，否则它会被
                // 用来解析非 Google Maven 的依赖（如 coil/kotlin-stdlib），阿里云对非法路径
                // 返回 502 而非 404，会导致 Gradle 把整个仓库禁用、连锁失败。
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
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "StockDividend"
include(":app")
