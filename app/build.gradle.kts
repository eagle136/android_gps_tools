import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 统一签名：读取仓库内 signing/keystore.properties，CI 可用 SIGNING_* 环境变量覆盖
val signingProperties = Properties().apply {
    val f = rootProject.file("signing/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingValue(env: String, key: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: signingProperties.getProperty(key)

val signingStoreFile = signingValue("SIGNING_STORE_FILE", "storeFile")
    ?.let { rootProject.file(it) }
    ?.takeIf { it.exists() }
val signingStorePassword = signingValue("SIGNING_STORE_PASSWORD", "storePassword")
val signingKeyAlias = signingValue("SIGNING_KEY_ALIAS", "keyAlias")
val signingKeyPassword = signingValue("SIGNING_KEY_PASSWORD", "keyPassword")
val hasSigningKey = signingStoreFile != null && signingStorePassword != null &&
    signingKeyAlias != null && signingKeyPassword != null

// 版本号：默认 1 / 1.0，CI 按 tag 传入 -Pgps.versionCode / -Pgps.versionName
val appVersionCode = providers.gradleProperty("gps.versionCode").orNull?.toInt() ?: 1
val appVersionName = providers.gradleProperty("gps.versionName").getOrElse("1.0")

android {
    signingConfigs {
        create("projectKey") {
            if (signingStoreFile != null) storeFile = signingStoreFile
            storePassword = signingStorePassword
            keyAlias = signingKeyAlias
            keyPassword = signingKeyPassword
        }
    }
    namespace = "com.gpstesttools"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.gpstesttools"
        minSdk = 28
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 只保留中英文资源，剔除 androidx 等库里用不到的几十种语言翻译（缩 APK 体积）
        resourceConfigurations.addAll(listOf("zh", "zh-rCN", "zh-rTW", "en"))
    }

    buildTypes {
        debug {
            if (hasSigningKey) signingConfig = signingConfigs.getByName("projectKey")
        }
        release {
            // AGP 9.3+ 优化开关：代码裁剪/混淆/内联（等价 proguard-android-optimize）
            // + 资源裁剪 + R8 资源优化，自定义 keep 规则放 src/main/keepRules/*.keep
            optimization {
                enable = true
            }
            if (hasSigningKey) signingConfig = signingConfigs.getByName("projectKey")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}