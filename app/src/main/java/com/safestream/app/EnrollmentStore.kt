package com.safestream.app

import android.content.Context

/**
 * Persists the streamer's enrolled face embeddings locally (SharedPreferences).
 * Each logged-in account has its own separate storage, so one user's
 * registered face is never used for another user.
 *
 * Nothing here ever leaves the device (Zero-Cloud Leakage).
 */
class EnrollmentStore(context: Context) {

    private val prefs = context.getSharedPreferences(storeNameFor(context), Context.MODE_PRIVATE)

    /** Adds one more sample without erasing previously stored ones. */
    fun addSample(embedding: FloatArray) {
        val existing = loadAllRaw().toMutableList()
        existing.add(serialize(embedding))
        prefs.edit().putString(KEY_EMBEDDINGS, existing.joinToString(SAMPLE_SEPARATOR)).apply()
    }

    /** Replaces ALL stored samples with a single one. */
    fun save(embedding: FloatArray) {
        prefs.edit().putString(KEY_EMBEDDINGS, serialize(embedding)).apply()
    }

    /** Returns every stored embedding sample for the current account. */
    fun loadAll(): List<FloatArray> = loadAllRaw().mapNotNull { deserialize(it) }

    fun load(): FloatArray? = loadAll().firstOrNull()

    fun isEnrolled(): Boolean = loadAllRaw().isNotEmpty()

    fun sampleCount(): Int = loadAllRaw().size

    /** Wipes this account's samples. */
    fun clear() {
        prefs.edit().remove(KEY_EMBEDDINGS).apply()
    }

    private fun loadAllRaw(): List<String> {
        val stored = prefs.getString(KEY_EMBEDDINGS, null) ?: return emptyList()
        return stored.split(SAMPLE_SEPARATOR).filter { it.isNotBlank() }
    }

    private fun serialize(embedding: FloatArray): String = embedding.joinToString(",")

    private fun deserialize(raw: String): FloatArray? {
        return try {
            raw.split(",").map { it.toFloat() }.toFloatArray()
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val KEY_EMBEDDINGS = "streamer_embeddings"
        private const val SAMPLE_SEPARATOR = ";"

        /** Storage name unique to the logged-in account. */
        private fun storeNameFor(context: Context): String {
            val email = context.getSharedPreferences("SafeStreamSession", Context.MODE_PRIVATE)
                .getString("current_user", null) ?: "guest"
            val safe = email.replace(Regex("[^a-z0-9]"), "_")
            return "safestream_enrollment_${safe}_${email.hashCode().toString(16)}"
        }
    }
}