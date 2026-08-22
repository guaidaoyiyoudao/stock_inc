plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.stock.dividend"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.stock.dividend"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "3.1.1"
    }

    signingConfigs {
        create("release") {
            val storeFilePath = System.getenv("KEYSTORE_FILE")
            val storePassword = System.getenv("KEYSTORE_PASSWORD")
            val keyAlias = System.getenv("KEY_ALIAS")
            val keyPassword = System.getenv("KEY_PASSWORD")

            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                this.storePassword = storePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val storeFilePath = System.getenv("KEYSTORE_FILE")
            if (storeFilePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            // Robolectric 需要读取 manifest/resources 才能提供真实 Android 环境
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources {
            // ADK 传递依赖（google-auth 等）的 META-INF 清单冲突
            merges += "**/META-INF/INDEX.LIST"
            merges += "**/META-INF/DEPENDENCIES"
        }
    }

}

dependencies {
    // Compose BOM
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    // AndroidX
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    implementation(libs.work.runtime.ktx)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.androidx.compiler)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Network
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.retrofit.scalars) // 基金分红 f10 HTML 原文响应（ScalarsConverter）
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Coroutines
    implementation(libs.coroutines.android)

    // ADK（Android Agent Development Kit）
    // - RoomSessionService 本期不用 → 排除 Room 传递依赖
    // - ML Kit GenAI（Gemini Nano）本期不用 → 排除 genai-prompt，避免其 beta 组件把
    //   kotlin-stdlib 顶到 2.3.x（Kotlin 2.1 编译器无法读取 2.3 元数据）
    implementation(libs.adk.kotlin.core) {
        exclude(group = "com.google.mlkit", module = "genai-prompt")
        // kxml2 自带 org.xmlpull.v1 实现，与 Android 框架类冲突导致 R8 报错；系统自带该 API
        exclude(group = "net.sf.kxml")
    }

    // AI 聊天 Markdown 渲染
    implementation(libs.compose.markdown)

    // stdlib 强制对齐 2.1.21（Vico 2.1.3 所需版本；Kotlin 2.1.20 编译器可读其 2.1 元数据），
    // 防止 ADK 传递依赖（kotlinx-coroutines 1.11 等）把 stdlib 顶到 2.2/2.3 造成编译失败。
    configurations.configureEach {
        resolutionStrategy {
            force(
                "org.jetbrains.kotlin:kotlin-stdlib:2.1.21",
                "org.jetbrains.kotlin:kotlin-stdlib-common:2.1.21",
                "org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.1.21",
                "org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.1.21"
            )
        }
    }

    // Image loading (SVG logos)
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)
    implementation(libs.coil.network.okhttp)

    // OCR (Chinese text recognition via Play Services; model downloaded on demand to keep APK small)
    implementation(libs.mlkit.text.recognition)

    // Glance (Compose for Widgets) — 桌面行情 Widget
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    // Charts
    implementation(libs.vico.compose)
    implementation(libs.vico.compose.m3)
    implementation(libs.mpandroidchart)

    // Desugaring
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.runner)
    testImplementation(libs.mockwebserver)
    debugImplementation(libs.compose.test.manifest)
    androidTestImplementation(composeBom)
    androidTestImplementation(libs.compose.junit)
    androidTestImplementation(libs.junit)
}
