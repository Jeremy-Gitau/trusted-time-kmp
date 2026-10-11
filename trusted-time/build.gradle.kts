import com.vanniktech.maven.publish.SonatypeHost

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.vanniktech.publish)
}

kotlin {
    // Android
    androidTarget {
        compilations.all {
            kotlinOptions { jvmTarget = "1.8" }
        }
        publishLibraryVariants("release")
    }

    // iOS
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    // JVM
    jvm {
        compilations.all {
            kotlinOptions { jvmTarget = "1.8" }
        }
    }

    // JS
    js(IR) {
        browser()
        nodejs()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        androidMain.dependencies {
            implementation(libs.play.services.time)
            implementation(libs.kotlinx.coroutines.android)
        }

        jvmMain.dependencies {
            implementation(libs.apache.commons.net)
        }

        // iOS and JS have no extra dependencies (use platform APIs)
    }
}

android {
    namespace = "com.trustedtime"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.androidMinSdk.get().toInt()
    }
}

// ─── Maven Central Publishing ───────────────────────────────────────────────
mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()

    coordinates(
        groupId = "io.github.yourusername",   // ← change to your GitHub username
        artifactId = "trusted-time-kmp",
        version = "1.0.0"
    )

    pom {
        name.set("Trusted Time KMP")
        description.set("A Kotlin Multiplatform SDK for tamper-proof time across Android, iOS, JVM and JS")
        url.set("https://github.com/yourusername/trusted-time-kmp") // ← change

        licenses {
            license {
                name.set("Apache-2.0")
                url.set("https://opensource.org/licenses/Apache-2.0")
            }
        }

        developers {
            developer {
                id.set("yourusername")      // ← change
                name.set("Your Name")       // ← change
                email.set("you@email.com")  // ← change
            }
        }

        scm {
            url.set("https://github.com/yourusername/trusted-time-kmp") // ← change
        }
    }
}
