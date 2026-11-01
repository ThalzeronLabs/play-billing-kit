plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.dokka)
    `maven-publish`
}

// JitPack sets VERSION to the git tag it builds (e.g. "v0.1.0"); local and CI builds use VERSION_NAME.
val libraryVersion: String = System.getenv("VERSION") ?: providers.gradleProperty("VERSION_NAME").get()

group = "com.github.ThalzeronLabs"
version = libraryVersion

android {
    namespace = "com.thalzeronlabs.billing"
    compileSdk = 37

    defaultConfig {
        minSdk = 23
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    api(libs.billing.ktx)
    // api: StateFlow and Flow are part of the public API (BillingKit.state, BillingGateway.purchaseUpdates).
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.github.ThalzeronLabs"
            artifactId = "play-billing-kit"
            version = libraryVersion

            afterEvaluate { from(components["release"]) }

            pom {
                name.set("play-billing-kit")
                description.set("Google Play subscriptions for Android without the classic bugs: one StateFlow, tested business rules, Billing Library 8.")
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
    moduleName.set("play-billing-kit")
    dokkaSourceSets.configureEach {
        includes.from("Module.md")
        sourceLink {
            localDirectory.set(file("src/main/kotlin"))
            remoteUrl("https://github.com/ThalzeronLabs/play-billing-kit/tree/main/billing/src/main/kotlin")
            remoteLineSuffix.set("#L")
        }
    }
}
