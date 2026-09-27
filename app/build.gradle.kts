import java.util.Properties

plugins {
    // Kotlin 编译由 AGP 9.x 内置支持（无需再应用 org.jetbrains.kotlin.android）
    id("com.android.application")
}

// ---------------------------------------------------------------------------
// 发布签名材料
//
// 密钥**不入库**，两个来源（按优先级）：
//   1) 仓库根目录的 keystore.properties —— 本机构建用，已 gitignore
//   2) 环境变量 —— CI 用，由 GitHub Secrets 注入
//        KEYSTORE_FILE / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD
// 两者都取不到时不报错，只是产出的 release 包未签名。
// ---------------------------------------------------------------------------
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.isFile) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

fun signingSecret(key: String, vararg envNames: String): String? =
    keystoreProps.getProperty(key)?.takeIf { it.isNotBlank() }
        ?: envNames.firstNotNullOfOrNull { System.getenv(it)?.takeIf { v -> v.isNotBlank() } }

val releaseStorePath = signingSecret("storeFile", "KEYSTORE_FILE")
val releaseStorePassword = signingSecret("storePassword", "KEYSTORE_PASSWORD")
val releaseKeyAlias = signingSecret("keyAlias", "KEY_ALIAS")
val releaseKeyPassword = signingSecret("keyPassword", "KEY_PASSWORD")
val hasReleaseSigning = releaseStorePath != null &&
    releaseStorePassword != null &&
    releaseKeyAlias != null &&
    releaseKeyPassword != null

// 版本单一来源（与 META-INF/xposed/module.prop 由下方 checkModuleProp 守卫一致性）
val MODULE_VERSION_CODE = 5
val MODULE_VERSION_NAME = "1.1.2"

android {
    namespace = "io.github.bitstandbyyou.bilisb"
    compileSdk = 36

    defaultConfig {
        // 独立模块身份：与本机 PureMe（io.github.idongyou.pureme）不冲突，可共存。
        applicationId = "io.github.bitstandbyyou.bilisb"
        // minSdk 26：Vector 从 APK 内路径加载 libdexkit.so，需要 so 未压缩，
        // 而 useLegacyPackaging 在 minSdk >= 23 时默认为 false。
        minSdk = 26
        targetSdk = 36
        versionCode = MODULE_VERSION_CODE
        versionName = MODULE_VERSION_NAME

        // 目标设备只有 arm64-v8a，只打包该 ABI（DexKit 的多 ABI so 只留 arm64）
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            // 不启用 R8：模块靠反射与动态代理对接宿主的混淆类名，
            // 混淆自己收益极低、踩坑成本很高，这里刻意保持可读。
            isMinifyEnabled = false
            isShrinkResources = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // Vector 的模块 ClassLoader 直接从 APK 内路径（base.apk!/lib/arm64-v8a）加载 so，
        // 因此 libdexkit.so 必须保持未压缩。
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// ---------------------------------------------------------------------------
// module.prop 版本一致性守卫（两边版本必须一致）
// ---------------------------------------------------------------------------
val checkModuleProp = tasks.register("checkModuleProp") {
    val propFile = file("src/main/resources/META-INF/xposed/module.prop")
    inputs.file(propFile)
    doLast {
        val props = Properties()
        propFile.inputStream().use { props.load(it) }
        val propVersionName = props.getProperty("versionName")
        val propVersionCode = props.getProperty("versionCode")?.toIntOrNull()
        if (propVersionName != MODULE_VERSION_NAME || propVersionCode != MODULE_VERSION_CODE) {
            throw GradleException(
                "module.prop 版本($propVersionName/$propVersionCode)与 build.gradle.kts " +
                    "($MODULE_VERSION_NAME/$MODULE_VERSION_CODE) 不一致,请同步修改",
            )
        }
    }
}
tasks.named("check") { dependsOn(checkModuleProp) }
tasks.matching {
    it.name.startsWith("assemble") || it.name == "bundleDebug" || it.name == "bundleRelease"
}.configureEach {
    dependsOn(checkModuleProp)
}

dependencies {
    // 现代 libxposed API：仅编译期依赖，运行时由 Vector 提供，绝不能打包进 APK
    compileOnly("io.github.libxposed:api:101.0.1")
    // libxposed api 内部使用到的 androidx 注解
    compileOnly("androidx.annotation:annotation:1.9.1")

    // DexKit：运行时解析宿主 dex，用于定位被混淆的类 / 方法 / 字段
    implementation("org.luckypray:dexkit:2.3.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
