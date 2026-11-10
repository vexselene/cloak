import java.util.Properties
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}

fun signingValue(prop: String, env: String): String? =
    (keystoreProps.getProperty(prop) ?: System.getenv(env))?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("storeFile", "CLOAK_KEYSTORE")
    ?.let { rootProject.file(it) }
    ?.takeIf { it.isFile }
val releaseStorePassword = signingValue("storePassword", "CLOAK_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "CLOAK_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "CLOAK_KEY_PASSWORD")
val hasReleaseKey = releaseStoreFile != null && releaseStorePassword != null &&
        releaseKeyAlias != null && releaseKeyPassword != null

// Set cloak.debugSign=true to sign release builds with the debug key (personal use only).
val useDebugKey = providers.gradleProperty("cloak.debugSign").map { it.toBoolean() }.getOrElse(false)

val appVersionName = providers.gradleProperty("cloak.version").getOrElse("0.1.0")
val appVersionCode = run {
    val m = Regex("""(\d+)\.(\d+)\.(\d+)""").matchEntire(appVersionName)
        ?: throw GradleException("cloak.version must look like 1.2.3, not '$appVersionName'")
    val (major, minor, patch) = m.destructured.toList().map { it.toInt() }
    if (minor > 99 || patch > 99) throw GradleException("minor and patch must stay below 100")
    major * 10_000 + minor * 100 + patch
}

android {
    namespace = "io.cloak.os"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "io.cloak.os"
        minSdk = 35
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            signingConfig = if (useDebugKey) {
                signingConfigs.getByName("debug")
            } else {
                signingConfigs.findByName("release")
            }
        }
        debug {
            versionNameSuffix = "-debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

val releaseSigned = useDebugKey || hasReleaseKey
val requireReleaseSigning = tasks.register("requireReleaseSigning") {
    val signed = releaseSigned
    doLast {
        if (!signed) {
            throw GradleException(
                "No signing key for the release build. Either set up keystore.properties " +
                        "(see docs/RELEASING.md) or build with -Pcloak.debugSign=true for a personal build."
            )
        }
    }
}
tasks.configureEach {
    if (name == "packageRelease" || name == "bundleRelease") dependsOn(requireReleaseSigning)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
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