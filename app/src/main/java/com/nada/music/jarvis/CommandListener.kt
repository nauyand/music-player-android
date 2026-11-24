package com.nada.music.jarvis

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Merekam satu perintah setelah "Jarvis" terdengar, memakai pengenal suara bawaan Android
 * (bahasa Indonesia, dengan bahasa Inggris tetap dikenali untuk judul lagu).
 *
 * Harus dipanggil dari main thread.
 */
class CommandListener(private val context: Context) {

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    /** Mengembalikan semua kemungkinan teks (paling yakin di depan), atau daftar kosong. */
    suspend fun listenOnce(): List<String> = suspendCancellableCoroutine { cont ->
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)

        fun finish(result: List<String>) {
            recognizer.destroy()
            if (cont.isActive) cont.resume(result)
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) =
                finish(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty())

            override fun onError(error: Int) = finish(emptyList())
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "id-ID")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // Perintah biasanya pendek; berhenti cepat setelah pengguna diam.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        }
        recognizer.startListening(intent)
        cont.invokeOnCancellation { recognizer.destroy() }
    }
}
