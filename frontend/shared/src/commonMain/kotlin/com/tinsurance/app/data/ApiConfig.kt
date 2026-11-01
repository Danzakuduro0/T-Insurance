package com.tinsurance.app.data

import ru.tinsurance.api.DevicePlatform

/** Адрес backend для локальной разработки (`./gradlew :server:run` слушает 8080). */
expect val defaultApiBaseUrl: String

expect val devicePlatform: DevicePlatform
