package com.kafkasl.phonewhisper

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object Http {
    /**
     * Shared client. OkHttp's 10 s defaults are too short for transcribing longer recordings,
     * slow cleanup models, and large uploads on mobile data.
     */
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
}
