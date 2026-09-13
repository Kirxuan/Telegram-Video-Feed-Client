import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt.android)
}

val defaultsProperties = Properties().apply {
    rootProject.file("secrets.defaults.properties").inputStream().use(::load)
}

val localProperties = Properties().apply {
    rootProject.file("local.properties")
        .takeIf { it.isFile }
        ?.inputStream()
        ?.use(::load)
}

fun telegramProperty(name: String): String =
    localProperties.getProperty(name, defaultsProperties.getProperty(name, "")).trim()

fun String.asBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

val r8CandidateEnabled = providers.gradleProperty("cvfR8CandidateEnabled")
    .map(String::toBooleanStrict)
    .orElse(false)

android {
    namespace = "com.qixuan.channelvideoflow"

    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.qixuan.channelvideoflow"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.2"

        ndk {
            abiFilters += "arm64-v8a"
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        create("instrumentation") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".instrumentation"
            matchingFallbacks += listOf("debug")
        }

        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
            isMinifyEnabled = r8CandidateEnabled.get()
            isShrinkResources = r8CandidateEnabled.get()
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }

        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    testBuildType = "instrumentation"

    buildFeatures {
        buildConfig = true
        compose = true
    }

    buildTypes.configureEach {
        val credentialValue: (String) -> String = { propertyName ->
            if (name == "debug") telegramProperty(propertyName) else ""
        }
        buildConfigField(
            "String",
            "TELEGRAM_API_ID",
            credentialValue("TELEGRAM_API_ID").asBuildConfigString(),
        )
        buildConfigField(
            "String",
            "TELEGRAM_API_HASH",
            credentialValue("TELEGRAM_API_HASH").asBuildConfigString(),
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }

    sourceSets {
        getByName("androidTest").java.srcDir("src/sharedTest/java")
        getByName("testInstrumentation").java.srcDir("src/sharedTest/java")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

androidComponents {
    onVariants(selector().withBuildType("instrumentation")) { variant ->
        variant.packaging.jniLibs.excludes.add("**/*.so")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

composeCompiler {
    val reportsEnabled = providers.gradleProperty("cvfComposeCompilerReports")
        .map(String::toBooleanStrict)
        .orElse(false)
    if (reportsEnabled.get()) {
        reportsDestination.set(layout.buildDirectory.dir("compose-compiler/reports"))
        metricsDestination.set(layout.buildDirectory.dir("compose-compiler/metrics"))
    }
}

kapt {
    correctErrorTypes = true
}

hilt {
    enableAggregatingTask = true
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(project(":core:database"))
    implementation(project(":telegram"))
    implementation(project(":player"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.media3.ui)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    kaptTest(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.robolectric)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    kaptAndroidTest(libs.hilt.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    add("instrumentationImplementation", libs.androidx.compose.ui.test.manifest)
}
