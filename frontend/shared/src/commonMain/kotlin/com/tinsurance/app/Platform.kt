package com.tinsurance.app

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform