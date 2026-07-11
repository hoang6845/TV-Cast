// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.google.ksp) apply false
    alias(libs.plugins.google.android.libraries.mapsplatform.secrets.gradle.plugin) apply false
    id("com.google.firebase.crashlytics") version "3.0.7" apply false


}

buildscript {
    dependencies {
        classpath(libs.google.services)
        classpath(libs.androidx.navigation.safe.args.gradle.plugin)
        classpath(libs.androidx.navigation.safe.args.gradle.plugin.v277)
        classpath ("com.google.dagger:hilt-android-gradle-plugin:2.56")

    }
}