import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 正式签名只从本机未跟踪文件或环境变量读取，仓库中不保存密钥与密码
val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use(keystoreProperties::load)
}

fun signingValue(environmentKey: String, propertyKey: String): String? =
    System.getenv(environmentKey)?.takeIf { it.isNotBlank() }
        ?: keystoreProperties.getProperty(propertyKey)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("BOLT_STORE_FILE", "storeFile")
val releaseStorePassword = signingValue("BOLT_STORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingValue("BOLT_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = signingValue("BOLT_KEY_PASSWORD", "keyPassword")
val releaseSigningValues = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
)
val releaseSigningEnabled = releaseSigningValues.all { it != null }
require(releaseSigningEnabled || releaseSigningValues.all { it == null }) {
    "正式签名配置不完整：请同时设置 storeFile/storePassword/keyAlias/keyPassword 或对应的 BOLT_* 环境变量"
}

android {
    namespace = "io.opentv.live"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.opentv.live"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (releaseSigningEnabled) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigningEnabled) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-datasource:1.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // JLayer：纯 Java 的 MPEG Layer I/II/III 解码器，用于补齐系统缺失的 MP2 软解
    implementation("com.googlecode.soundlibs:jlayer:1.0.1.4")
    // 二维码生成：扫码添加播放源时在电视上展示局域网地址
    implementation("com.google.zxing:core:3.5.3")
}
