import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt.android)
}

val tdLibDatabaseEncryptionCandidateEnabled = providers
    .gradleProperty("cvfTdLibDatabaseEncryptionCandidateEnabled")
    .map(String::toBooleanStrict)
    .orElse(false)

android {
    namespace = "com.qixuan.channelvideoflow.telegram"

    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "boolean",
            "TDLIB_DATABASE_ENCRYPTION_CANDIDATE_ENABLED",
            tdLibDatabaseEncryptionCandidateEnabled.get().toString(),
        )
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        create("benchmark") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
        }
        configureEach {
            buildConfigField("boolean", "PERFORMANCE_DIAGNOSTICS_ENABLED", (name != "release").toString())
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core:model"))
    api(project(":core:domain"))
    implementation(project(":core:database"))
    implementation(project(":telegram:tdlib"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
