plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

// Общие модели запросов/ответов. В бэкенде собираются только под JVM — для сервера не нужен Android SDK
kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serializationJson)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
