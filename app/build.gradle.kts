import com.android.build.api.variant.impl.VariantOutputImpl
import java.util.Properties

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) load(file.inputStream())
}

val appVersionName = "0.13.2"

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.detekt)
}

android {
    namespace = "com.github.jvsena42.mandacaru"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.github.jvsena42.mandacaru"
        minSdk = 29
        targetSdk = 36
        versionCode = 32
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
            abiFilters += "x86_64"
        }
    }

    signingConfigs {
        val keystoreFile = localProperties["KEYSTORE_FILE"] as? String
        if (keystoreFile != null) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = localProperties["KEYSTORE_PASSWORD"] as String
                keyAlias = localProperties["KEY_ALIAS"] as String
                keyPassword = localProperties["KEY_PASSWORD"] as String
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    lint {
        lintConfig = file("lint.xml")
    }
}

androidComponents {
    onVariants { variant ->
        // AGP 9 removed the legacy applicationVariants output API; the APK file
        // name is now set through the internal VariantOutputImpl.
        variant.outputs.forEach { output ->
            (output as? VariantOutputImpl)?.outputFileName?.set("Mandacaru-$appVersionName.apk")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom("$rootDir/config/detekt/detekt.yml")
    parallel = true
    source.setFrom(
        "src/main/java/com/github/jvsena42/mandacaru"
    )
}

dependencies {
    detektPlugins(libs.detekt.compose.rules)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.compose.navigation)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.gson)
    implementation(libs.jna) { artifact { type = "aar" } }

    implementation(libs.okhttp)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)

    implementation(libs.hummingbird)
    implementation(libs.bdk.android)
    implementation(libs.androidx.security.crypto)
    implementation(libs.secp256k1.kmp)
    implementation(libs.secp256k1.kmp.jni.android)
    implementation(libs.bouncycastle.prov)

    implementation(project.dependencies.platform(libs.koin.bom))
    implementation(libs.koin.core)
    implementation(libs.koin.viewmodel)
    implementation(libs.koin.compose)
    implementation(libs.koin.android)
    implementation(libs.androidx.compose.material.icons.extended)

    testImplementation(libs.junit)
    testImplementation(libs.koin.test)
    testImplementation(libs.org.json)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockito.core)
    // secp256k1-kmp resolves the "android" JNI native lib for this module's classpath, since the
    // app is a single-platform Android target - but unit tests execute on the host JVM, which
    // can't load Android's Bionic-linked .so. Pull the desktop-native artifact for tests only, so
    // NostrCrypto (BIP340/ECDH, used throughout the CoinJoin round) actually works under `test`.
    testImplementation(libs.secp256k1.kmp.jni.jvm)
    // Same reasoning as secp256k1-kmp-jni-jvm above: bdk-android's native lib is Android-only,
    // so WalletManagerImplTest pulls the desktop-native bdk-jvm build (same BDK version/API) to
    // exercise real BIP39/BIP84 derivation under `test` instead of a keystore-only fake.
    testImplementation(libs.bdk.jvm)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

// Unlike secp256k1-kmp (a true Kotlin Multiplatform project whose native-only jni-android/jni-jvm
// artifacts carry no Kotlin classes, only the .so), BDK's UniFFI-generated bindings bundle the
// full org.bitcoindevkit.* Kotlin wrapper *and* the native lib together in each of bdk-android and
// bdk-jvm. Both end up on the unit test runtime classpath (bdk-android via `implementation`,
// bdk-jvm via `testImplementation`) with the same class names, so whichever the JVM happens to
// load first wins - excluding bdk-android from the unit test configurations guarantees it's
// bdk-jvm's desktop-native classes that get used when running under `test`.
//
// AGP does NOT name the resolved unit-test classpaths with a "test" prefix - they're named after
// the build type, e.g. "debugUnitTestRuntimeClasspath"/"debugUnitTestCompileClasspath". Matching
// only `name.startsWith("test")` (as an earlier version of this block did) only reaches
// source-set-level configurations like `testImplementation`, never the classpath Gradle actually
// resolves to run `testDebugUnitTest` - so bdk-android was never really excluded from it. Match on
// "UnitTest" (AGP's actual naming) as well so the exclude reaches the classpath that matters, while
// leaving the real app's "debugRuntimeClasspath"/"debugCompileClasspath" (which need bdk-android)
// untouched.
configurations.matching {
    it.name.startsWith("test", ignoreCase = true) || it.name.contains("UnitTest")
}.configureEach {
    exclude(group = "org.bitcoindevkit", module = "bdk-android")
}

// Gradle's default test console output is a bare "ExceptionType at File:Line" per failure -
// nowhere near enough to diagnose a native-binding failure. Print the full stack trace/cause
// chain for failed tests so CI logs are actually actionable without downloading the HTML/XML
// report artifact.
tasks.withType<Test>().configureEach {
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showCauses = true
        showStackTraces = true
        showExceptions = true
        events("failed")
    }
}
