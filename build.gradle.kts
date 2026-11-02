plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.dokka)
}

// One Dokka site for both published modules: ./gradlew :dokkaGenerate -> build/dokka/html
dokka {
    moduleName.set("play-billing-kit")
}

dependencies {
    dokka(project(":billing"))
    dokka(project(":billing-compose"))
}
