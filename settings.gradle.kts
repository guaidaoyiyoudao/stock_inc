// 本地开发用国内镜像加速（临时改为镜像优先、官方兜底）；CI 环境通过 USE_CHINA_MIRROR=false
// 直连官方仓库。阿里云镜像存在间歇性 502 且对部分 artifact 不全（如 KSP plugin marker），
// 海外 CI runner 直连官方更稳定。网络恢复后可把镜像块移回官方仓库之后。
//
// 注意：pluginManagement {} 块在 Kotlin DSL 里有独立编译作用域，无法引用脚本顶层的
// val/fun，因此两个块内各自直接读取环境变量。

pluginManagement {
    // pluginManagement 块在 Kotlin DSL 里独立编译，无法引用脚本顶层 val/fun，只能内联。
    val useChinaMirror = (System.getenv("USE_CHINA_MIRROR") ?: "true")
        .let { it != "false" && it != "0" }
    repositories {
        if (useChinaMirror) {
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
            // 临时：镜像优先（含 plugin marker / 依赖 jar），官方仓库兜底
            maven("https://maven.aliyun.com/repository/central")
            maven("https://maven.aliyun.com/repository/gradle-plugin")
        }
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
    }
}

dependencyResolutionManagement {
    val useChinaMirror = (System.getenv("USE_CHINA_MIRROR") ?: "true")
        .let { it != "false" && it != "0" }
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (useChinaMirror) {
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
            maven("https://maven.aliyun.com/repository/public")
        }
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
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "StockDividend"
include(":app")
