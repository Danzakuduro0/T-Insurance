package com.tinsurance.app.data

import ru.tinsurance.api.DevicePlatform

actual val defaultApiBaseUrl: String = "http://localhost:8080"

// В DevicePlatform нет WEB: веб-версия приложения клиента пока входит как Android-устройство.
actual val devicePlatform: DevicePlatform = DevicePlatform.ANDROID
