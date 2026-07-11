plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.google.ksp)
    id("kotlin-parcelize")
    id ("kotlin-kapt")
}

android {
    namespace = "hoang.dqm.codebase"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
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
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(project(":monetization"))
    implementation("com.facebook.shimmer:shimmer:0.5.0")
    //Navigation
    api(libs.androidx.navigation.fragment.ktx)
    api(libs.androidx.navigation.ui.ktx)

    // Android
    api(libs.androidx.core.ktx)
    api(libs.androidx.appcompat)
    api(libs.androidx.appcompat.resources)
    api(libs.material)
    api(libs.androidx.constraintlayout)
    api(libs.androidx.recyclerview)
    api(libs.androidx.lifecycle.livedata.ktx)
    api(libs.androidx.lifecycle.viewmodel.ktx)
    api(libs.androidx.lifecycle.runtime.ktx)
    api(libs.androidx.lifecycle.extensions)
    api(libs.androidx.fragment.ktx)

    // Coroutines
    api(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.coroutines.core)
    api(libs.androidx.collection.ktx)

    //Utils
    api(libs.androidx.media3.exoplayer.v131)
    api(libs.permissionx)
    api(libs.glide)
    api(libs.dotsindicator)
    api(libs.lottie)
    api(libs.roundedimageview)
    api(libs.sdp.android)
    api(libs.ssp.android)
    api(libs.gson)

    //retrofit
    api(libs.retrofit)
    api(libs.retrofit.gson)
    api(libs.okhttp.logging)
    api(libs.scalars)
    //hilt
    api(libs.hilt.android)
    implementation(libs.firebase.config)
    kapt(libs.hilt.compiler)

    implementation(libs.gms.ads)

}