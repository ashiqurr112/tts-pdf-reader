buildscript {
    dependencies {
        classpath("com.google.dagger:hilt-android-gradle-plugin:2.59.2")
    }
}

plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.hilt.android) apply false
  alias(libs.plugins.ksp) apply false
}