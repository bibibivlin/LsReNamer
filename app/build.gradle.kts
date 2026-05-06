@file:Suppress("UnstableApiUsage")

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.linxyi.lsrenamer"
    compileSdk = 37
    androidResources {
        generateLocaleConfig = true
    }
    defaultConfig {
        applicationId = "com.linxyi.lsrenamer"
        minSdk = 35
        targetSdk = 37
        versionCode = 5
        versionName = "1.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        release {
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
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.exifinterface)
    implementation(libs.activity.ktx)
    implementation(libs.fragment.ktx)
    implementation(libs.documentfile)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}