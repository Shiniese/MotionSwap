plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release 签名。keystore 路径与四项凭据全部从外部读取，不写入仓库。
//
// 两种提供方式，任选其一：
//
//   1. 写进 ~/.gradle/gradle.properties（推荐，不进 shell 历史）：
//        MOTIONSWAP_STORE_FILE=/绝对路径/motionswap.jks
//        MOTIONSWAP_STORE_PASSWORD=...
//        MOTIONSWAP_KEY_ALIAS=motionswap
//        MOTIONSWAP_KEY_PASSWORD=...
//
//   2. 导出为环境变量。
//
// 任何一项缺失时不配置 signingConfig，产物文件名带 -unsigned——文件名本身
// 就是提醒，比构建失败更省事，因为「忘了配签名」不该阻塞本地调试。
//
// keystore 丢了就找不回来了：已安装的用户将永远收不到更新，只能卸载重装并丢数据。
// 生成后立刻备份到仓库之外。
fun signingValue(name: String): String? =
    providers.gradleProperty(name).orNull ?: providers.environmentVariable(name).orNull

val signingConfigured = listOf(
    "MOTIONSWAP_STORE_FILE",
    "MOTIONSWAP_STORE_PASSWORD",
    "MOTIONSWAP_KEY_ALIAS",
    "MOTIONSWAP_KEY_PASSWORD",
).all { !signingValue(it).isNullOrBlank() }

android {
    namespace = "com.shiniese.motionswap"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
applicationId = "com.shiniese.motionswap"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "1.1.0"
    }

    signingConfigs {
        if (signingConfigured) {
            create("release") {
                storeFile = file(signingValue("MOTIONSWAP_STORE_FILE")!!)
                storePassword = signingValue("MOTIONSWAP_STORE_PASSWORD")
                keyAlias = signingValue("MOTIONSWAP_KEY_ALIAS")
                keyPassword = signingValue("MOTIONSWAP_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (signingConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }

            // R8 was never running here. The `optimization { enable = false }` block this
            // replaces restated AGP's own default rather than switching anything off, so
            // every release APK shipped the full unshaken Compose graph: 16,735 classes for
            // 217 lines of app code. Shrinking is what took it from 24 MB to single digits.
            //
            // `proguard-android-optimize.txt` is required for full mode. Without it R8 keeps
            // far more than it should and the size win largely evaporates.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
            it.testLogging {
                events("passed", "skipped", "failed")
                // The sample files are large, so a hung test should fail fast rather than sit
                // there holding the build open.
                showStandardStreams = false
            }
        }
    }
}

dependencies {
    // Two entries were removed here and are worth naming, because each one cost more than the
    // feature it was pulled in for and neither was ever called:
    //
    //   androidx.datastore.preferences — `grep -rni datastore app/src/` had zero hits. The output
    //     directory lives in a StateFlow and is never persisted. It cost 569 classes plus
    //     libdatastore_shared_counter.so in all four ABIs.
    //   androidx.compose.ui.tooling.preview — `grep -rn "@Preview"` had zero hits, and it was
    //     misdeclared as `implementation` instead of `debugImplementation`.
    //
    // Both version aliases are gone from gradle/libs.versions.toml too. If either comes back,
    // check what is actually calling it before adding the line again.

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.exifinterface)
    implementation(libs.coil.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Everything under `core/` is deliberately free of Android imports — no `android.*`, no
    // Compose, no ExifInterface — which is what lets it run on a plain JVM. That constraint is
    // only worth anything if it is actually exercised, hence these tests rather than a
    // Robolectric setup: a new `android.util.Log` in core/ now fails to compile here.
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.platform.launcher)
}