import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.vanniktech.publish)
}

kotlin {
    // Android
    android {
        namespace = "com.trustedtime"
        compileSdk =
            libs.versions.androidCompileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.androidMinSdk
                .get()
                .toInt()
        withHostTestBuilder {} // run commonTest on the JVM for Android too
    }

    // iOS (the framework is what Swift apps, like sample-ios, link against)
    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework { baseName = "TrustedTime" }
    }

    // JVM
    jvm()

    sourceSets {
        commonMain.dependencies {
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

        jvmTest.dependencies {
            implementation(libs.kotlinx.datetime) // only to test use as a Clock (todayIn)
        }

        // iOS has no extra dependencies (uses platform APIs)
    }
}

// Android and JVM bytecode: Java 8, so the library runs on as many JVMs as possible
tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_1_8)
}

// ─── Maven Central Publishing ───────────────────────────────────────────────
mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    coordinates(
        groupId = "io.github.jeremy-gitau",
        artifactId = "trusted-time-kmp",
        version = "1.0.0",
    )

    pom {
        name.set("Trusted Time KMP")
        description.set("A Kotlin Multiplatform SDK for tamper-proof time across Android, iOS and JVM")
        url.set("https://github.com/Jeremy-Gitau/trusted-time-kmp")

        licenses {
            license {
                name.set("Apache-2.0")
                url.set("https://opensource.org/licenses/Apache-2.0")
            }
        }

        developers {
            developer {
                id.set("Jeremy-Gitau")
                name.set("Jeremy Gitau")
                email.set("56400436+Jeremy-Gitau@users.noreply.github.com")
            }
        }

        scm {
            url.set("https://github.com/Jeremy-Gitau/trusted-time-kmp")
            connection.set("scm:git:git://github.com/Jeremy-Gitau/trusted-time-kmp.git")
            developerConnection.set("scm:git:ssh://git@github.com/Jeremy-Gitau/trusted-time-kmp.git")
        }
    }
}
