import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
}
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}