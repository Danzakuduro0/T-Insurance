plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

// Общие модели запросов/ответов: сервер (jvm), веб оператора (wasmJs, js), приложение (jvm-вариант для Android, iOS).
// Android-таргета нет — Android-приложение использует jvm-вариант, поэтому Android SDK бэкенду не нужен.
kotlin {
    jvm()
    js { browser() }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs { browser() }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serializationJson)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
