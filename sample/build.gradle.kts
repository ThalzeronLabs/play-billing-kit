plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.thalzeronlabs.billing.sample"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.thalzeronlabs.billing.sample"
        minSdk = 23
        targetSdk = 37
        versionCode = 2
        versionName = "0.2.0"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // In your app these are:
    //   implementation("com.github.ThalzeronLabs.play-billing-kit:play-billing-kit:v0.2.0")
    //   implementation("com.github.ThalzeronLabs.play-billing-kit:play-billing-kit-compose:v0.2.0") // optional
    implementation(project(":billing"))
    implementation(project(":billing-compose"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
