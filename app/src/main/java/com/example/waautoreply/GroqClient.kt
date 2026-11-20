package com.example.waautoreply

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed class GroqResult {
    data class Success(val text: String) : GroqResult()
    data class Failure(val message: String) : GroqResult()
}

object GroqClient {
    private const val URL = "https://api.groq.com/openai/v1/chat/completions"

    // Ganti ke "llama-3.1-8b-instant" bila ingin respons yang lebih cepat
    private const val MODEL = "llama-3.3-70b-versatile"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Tidak pernah melempar exception: semua error dibungkus [GroqResult.Failure]. */
    suspend fun chat(apiKey: String, systemPrompt: String, userMessage: String): GroqResult =
        withContext(Dispatchers.IO) {
            try {
                val messages = JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt))
                    .put(JSONObject().put("role", "user").put("content", userMessage))

                val json = JSONObject()
                    .put("model", MODEL)
                    .put("messages", messages)
                    .put("temperature", 0.7)
                    .put("max_tokens", 300)

                val request = Request.Builder()
                    .url(URL)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(json.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    when {
                        resp.isSuccessful -> {
                            val text = JSONObject(body)
                                .getJSONArray("choices")
                                .getJSONObject(0)
                                .getJSONObject("message")
                                .getString("content")
                                .trim()
                            if (text.isBlank()) GroqResult.Failure("Respons AI kosong")
                            else GroqResult.Success(text)
                        }
                        resp.code == 429 -> GroqResult.Failure("Limit kuota Groq tercapai (HTTP 429). Coba lagi nanti.")
                        resp.code == 401 -> GroqResult.Failure("API Key tidak valid (HTTP 401).")
                        else -> GroqResult.Failure("Error server Groq (HTTP ${resp.code}).")
                    }
                }
            } catch (e: IOException) {
                GroqResult.Failure("Gangguan jaringan: ${e.message}")
            } catch (e: Exception) {
                GroqResult.Failure("Error: ${e.message}")
            }
        }
}
