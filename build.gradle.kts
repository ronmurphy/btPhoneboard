// Top-level build file. The plugins are declared here (but not applied) so
// every module uses the same versions; the app module applies them.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
