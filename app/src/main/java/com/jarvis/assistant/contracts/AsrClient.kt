package com.jarvis.assistant.contracts

interface AsrClient {
    suspend fun recognizeStreaming(pcm: ByteArray): AsrResult
}