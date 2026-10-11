plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

application {
    mainClass.set("com.trustedtime.sample.jvm.MainKt")
}

dependencies {
    implementation(project(":trusted-time"))
    implementation(libs.kotlinx.coroutines.core)
}
