package com.example.jarvis

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Ek hi worker thread se call hota hai, isliye history ko lock ki zaroorat nahi. */
class JarvisBrain {

    companion object {
        private const val TAG = "JarvisBrain"
        private const val URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"
        private const val MAX_CHARS = 600
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = "application/json; charset=utf-8".toMediaType()
    private val history = ArrayList<Pair<String, String>>() // (role, text), last 4 turns

    fun ask(question: String): String {
        return try {
            val answer = withRetry(question)
            history.add("user" to question)
            history.add("model" to answer)
            while (history.size > 8) { history.removeAt(0); history.removeAt(0) }
            answer
        } catch (e: IOException) {
            Log.w(TAG, "network: ${e.javaClass.simpleName}")
            "Internet ya server ka issue hai. Thodi der baad dobara poochein."
        } catch (e: Exception) {
            Log.e(TAG, "brain: ${e.javaClass.simpleName}")
            "Abhi jawab nahi de paa raha. Dobara poochein."
        }
    }

    private fun withRetry(q: String): String {
        try {
            return clean(send(q))
        } catch (e: IOException) {
            Thread.sleep(1500)          // ek baar retry
            return clean(send(q))
        }
    }

    private fun send(question: String): String {
        val contents = JSONArray()
        for ((role, text) in history) contents.put(turn(role, text))
        contents.put(turn("user", question))

        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts",
                JSONArray().put(JSONObject().put("text", systemPrompt()))))
            .put("contents", contents)
            .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            .put("generationConfig", JSONObject().put("maxOutputTokens", 512).put("temperature", 0.4))

        val req = Request.Builder()
            .url(URL)
            .header("x-goog-api-key", BuildConfig.GEMINI_KEY)
            .post(body.toString().toRequestBody(json))
            .build()

        http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val parts = JSONObject(raw).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            val sb = StringBuilder()
            for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
            return sb.toString().trim().ifEmpty { "Iske baare mein clear jankari nahi mili." }
        }
    }

    private fun turn(role: String, text: String) = JSONObject()
        .put("role", role)
        .put("parts", JSONArray().put(JSONObject().put("text", text)))

    private fun clean(s: String): String =
        s.replace(Regex("[*#`_>]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_CHARS)

    private fun systemPrompt(): String {
        val today = SimpleDateFormat("dd MMMM yyyy", Locale("hi", "IN")).format(Date())
        return "Tum Jarvis ho, ek bharoseimand Hindi-English (Hinglish) voice assistant. " +
            "Aaj ki date: $today. " +
            "Har sawal ka jawab Google Search se check karke current aur sahi jankari do. " +
            "Jawab 2 se 4 chhote vakya mein do, bolne layak, bina markdown, bullet, link ya emoji ke. " +
            "Agar jankari na mile to saaf kaho ki nahi mili. " +
            "Tum sirf jawab dete ho; phone, apps ya settings control nahi karte."
    }
}
