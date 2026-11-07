package com.tinsurance.app.data

import ru.tinsurance.api.DevicePlatform

// 10.0.2.2 — localhost компьютера из эмулятора Android.
actual val defaultApiBaseUrl: String = "http://10.0.2.2:8080"

actual val devicePlatform: DevicePlatform = DevicePlatform.ANDROID
