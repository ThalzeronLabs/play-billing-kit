plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.dokka)
    `maven-publish`
}

// Same versioning as :billing. JitPack sets VERSION to the git tag it builds.
val libraryVersion: String = System.getenv("VERSION") ?: providers.gradleProperty("VERSION_NAME").get()

group = "com.github.ThalzeronLabs"
version = libraryVersion

android {
    namespace = "com.thalzeronlabs.billing.compose"
    compileSdk = 37

    defaultConfig {
        minSdk = 23
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true // Robolectric renders the composables on the JVM
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    api(project(":billing"))

    api(platform(libs.compose.bom))
    api(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest) // the empty activity the Compose test rule launches
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.github.ThalzeronLabs"
            artifactId = "play-billing-kit-compose"
            version = libraryVersion

            afterEvaluate { from(components["release"]) }

            // Write resolved versions into the POM (Compose versions come from the BOM).
            versionMapping {
                allVariants { fromResolutionResult() }
            }

            pom {
                name.set("play-billing-kit-compose")
                description.set("Ready-made Jetpack Compose paywall components for play-billing-kit: plan cards with Play's prices, free-trial badge, restore button, terms and manage-subscription links.")
                url.set("https://github.com/ThalzeronLabs/play-billing-kit")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("ThalzeronLabs")
                        name.set("Esdras Souza")
                    }
                }
                scm {
                    url.set("https://github.com/ThalzeronLabs/play-billing-kit")
                    connection.set("scm:git:https://github.com/ThalzeronLabs/play-billing-kit.git")
                }
            }
        }
    }
}

dokka {
    moduleName.set("play-billing-kit-compose")
    dokkaSourceSets.configureEach {
        includes.from("Module.md")
        sourceLink {
            localDirectory.set(file("src/main/kotlin"))
            remoteUrl("https://github.com/ThalzeronLabs/play-billing-kit/tree/main/billing-compose/src/main/kotlin")
            remoteLineSuffix.set("#L")
        }
    }
}
