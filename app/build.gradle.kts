plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val releaseStoreFilePathProvider =
    providers
        .gradleProperty("HELIOLAN_RELEASE_STORE_FILE")
        .orElse(providers.environmentVariable("HELIOLAN_RELEASE_STORE_FILE"))
val releaseStorePasswordProvider =
    providers
        .gradleProperty("HELIOLAN_RELEASE_STORE_PASSWORD")
        .orElse(providers.environmentVariable("HELIOLAN_RELEASE_STORE_PASSWORD"))
val releaseKeyAliasProvider =
    providers
        .gradleProperty("HELIOLAN_RELEASE_KEY_ALIAS")
        .orElse(providers.environmentVariable("HELIOLAN_RELEASE_KEY_ALIAS"))
val releaseKeyPasswordProvider =
    providers
        .gradleProperty("HELIOLAN_RELEASE_KEY_PASSWORD")
        .orElse(providers.environmentVariable("HELIOLAN_RELEASE_KEY_PASSWORD"))
val useChangesApiForAutomaticSyncProvider =
    providers
        .gradleProperty("HELIOLAN_USE_CHANGES_API_AUTOMATIC")
        .orElse(providers.environmentVariable("HELIOLAN_USE_CHANGES_API_AUTOMATIC"))
        .map { raw ->
            when (raw.trim().lowercase()) {
                "1",
                "true",
                "yes",
                "on",
                -> "true"
                else -> "false"
            }
        }.orElse("false")

val releaseSigningConfigured =
    !releaseStoreFilePathProvider.orNull.isNullOrBlank() &&
        !releaseStorePasswordProvider.orNull.isNullOrBlank() &&
        !releaseKeyAliasProvider.orNull.isNullOrBlank() &&
        !releaseKeyPasswordProvider.orNull.isNullOrBlank()

android {
    namespace = "com.heliolan.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.heliolan.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField(
            "boolean",
            "USE_CHANGES_API_AUTOMATIC_SYNC",
            useChangesApiForAutomaticSyncProvider.get(),
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            if (releaseSigningConfigured) {
                storeFile = rootProject.file(releaseStoreFilePathProvider.get())
                storePassword = releaseStorePasswordProvider.get()
                keyAlias = releaseKeyAliasProvider.get()
                keyPassword = releaseKeyPasswordProvider.get()
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Module dependencies
    implementation(project(":data"))
    implementation(project(":healthconnect"))
    implementation(project(":sync"))
    implementation(project(":server"))
    implementation(project(":dashboard"))

    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.splash)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.material)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // QR Code
    implementation(libs.zxing.core)
    implementation(libs.zxing.android.embedded)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.truth)
    testImplementation(libs.turbine)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.runtime)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.mockk.android)
}
