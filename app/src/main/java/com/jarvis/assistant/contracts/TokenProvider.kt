package com.jarvis.assistant.contracts

interface TokenProvider {
    suspend fun getGigaChatToken(): String
    suspend fun getSaluteToken(): String
}
