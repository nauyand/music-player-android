package com.nada.music.jarvis

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Merekam satu perintah setelah "Jarvis" terdengar, memakai pengenal suara bawaan Android
 * (bahasa Indonesia, dengan bahasa Inggris tetap dikenali untuk judul lagu).
 *
 * Di tempat ramai (jalan, motor, keramaian) pengenal suara Android sering tidak pernah
 * menganggap ucapan "selesai", karena bising jalanan terus terdengar seperti suara orang.
 * Akibatnya Jarvis seolah mendengarkan terus. Karena itu ada batas waktu keras:
 * - [MAX_LISTEN_MS] setelah mulai, rekaman dihentikan paksa ([SpeechRecognizer.stopListening]),
 *   lalu hasil yang sudah ada dipakai.
 * - Kalau setelah [FINALIZE_GRACE_MS] hasil akhir belum juga datang, hasil sementara
 *   (partial result) terakhir yang dipakai.
 *
 * Harus dipanggil dari main thread.
 */
class CommandListener(private val context: Context) {

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    /** Mengembalikan semua kemungkinan teks (paling yakin di depan), atau daftar kosong. */
    suspend fun listenOnce(): List<String> = suspendCancellableCoroutine { cont ->
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val handler = Handler(Looper.getMainLooper())
        var partial: List<String> = emptyList()
        var done = false

        fun finish(result: List<String>, reason: String) {
            if (done) return
            done = true
            handler.removeCallbacksAndMessages(null)
            Log.d(TAG, "Selesai ($reason): $result")
            runCatching { recognizer.cancel() }
            runCatching { recognizer.destroy() }
            if (cont.isActive) cont.resume(result)
        }

        // Batas waktu keras: hentikan rekaman, tunggu hasil akhir sebentar, lalu pakai partial.
        handler.postDelayed({
            if (done) return@postDelayed
            Log.d(TAG, "Batas waktu ${MAX_LISTEN_MS} ms tercapai, rekaman dihentikan")
            runCatching { recognizer.stopListening() }
            handler.postDelayed({ finish(partial, "timeout") }, FINALIZE_GRACE_MS)
        }, MAX_LISTEN_MS)

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    .filter { it.isNotBlank() }
                finish(list.ifEmpty { partial }, "hasil")
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    .filter { it.isNotBlank() }
                if (list.isNotEmpty()) partial = list
            }

            // Kalau error tapi sudah ada potongan kalimat (mis. jaringan putus di tengah), tetap dipakai.
            override fun onError(error: Int) = finish(partial, "error $error")

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "id-ID")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // Perintah biasanya pendek; berhenti cepat setelah pengguna diam.
            // (Sebagian HP mengabaikan nilai ini, makanya ada batas waktu keras di atas.)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
        }
        recognizer.startListening(intent)
        cont.invokeOnCancellation {
            handler.post {
                done = true
                handler.removeCallbacksAndMessages(null)
                runCatching { recognizer.destroy() }
            }
        }
    }

    private companion object {
        const val TAG = "JarvisListen"

        /** Perintah terpanjang ("putar lagu Tulus Hati-Hati di Jalan") cukup ±4 detik. */
        const val MAX_LISTEN_MS = 7000L

        /** Waktu tunggu hasil akhir setelah rekaman dihentikan paksa. */
        const val FINALIZE_GRACE_MS = 1500L
    }
}
