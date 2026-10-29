plugins {
    alias(libs.plugins.android.application) apply false
    // AGP 9 内置 Kotlin：不再声明 org.jetbrains.kotlin.android
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
