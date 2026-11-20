plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.david.deepdesk.feature.history"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    api(project(":core:ui"))
    api(project(":core:model"))
    api(project(":core:common"))
    api(project(":core:database"))
    api(project(":feature:attachments"))
    api(project(":feature:chat"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
