plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "tpt.dev.monetization"

    buildFeatures {
        viewBinding = true
    }
    compileSdk = 35

    defaultConfig {
        minSdk = 25
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

}


kotlin {
    jvmToolchain(11) // đồng bộ với compileOptions
}
dependencies {

    // Common
//    implementation(libs.androidx.constraint)
//    implementation(libs.androidx.startup)
//    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)

    // Lifecycle
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)

    // Admob
    implementation(libs.gms.ads)
    implementation(libs.gms.adsIdentifier)
    implementation(libs.ump)
    implementation(libs.skeletonlayout)

    implementation(libs.billing.ktx)
    implementation(libs.kotpref.core)
    implementation(libs.kotpref.initializer)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.cardview)
    implementation(libs.google.material)
}
