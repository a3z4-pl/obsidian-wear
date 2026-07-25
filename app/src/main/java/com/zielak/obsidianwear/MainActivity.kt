package com.zielak.obsidianwear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {

    private lateinit var statusText: TextView
    private lateinit var recordButton: Button
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        recordButton = findViewById(R.id.record_button)

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        speechRecognizer?.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onResults(results: Bundle?) {
                isListening = false
                recordButton.text = "Nagraj"
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""
                if (text.isNotBlank()) {
                    statusText.text = "Wysyłam..."
                    sendToServer(text)
                } else {
                    statusText.text = "Nic nie usłyszałem"
                }
            }

            override fun onReadyForSpeech(params: Bundle?) {
                statusText.text = "Mów..."
                recordButton.text = "⏺ Nagrywam"
            }

            override fun onError(error: Int) {
                isListening = false
                recordButton.text = "Nagraj"
                statusText.text = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "Nie zrozumiałem"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Cisza..."
                    SpeechRecognizer.ERROR_NETWORK -> "Brak sieci"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Brak uprawnień"
                    else -> "Błąd ($error)"
                }
            }

            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                statusText.text = "Przetwarzam..."
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onRmsChanged(rmsdB: Float) {}
        })

        recordButton.setOnClickListener {
            if (isListening) {
                speechRecognizer?.stopListening()
                isListening = false
                recordButton.text = "Nagraj"
            } else {
                startListening()
            }
        }
    }

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }

        isListening = true
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pl-PL")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun sendToServer(text: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("${BuildConfig.SERVER_URL}/voice-note")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer ***")
                conn.doOutput = true
                conn.connectTimeout = 5000
                conn.readTimeout = 5000

                val json = JSONObject().apply { put("text", text) }
                OutputStreamWriter(conn.outputStream).use { it.write(json.toString()) }

                if (conn.responseCode == 200) {
                    withContext(Dispatchers.Main) {
                        statusText.text = "✓ Zapisane!"
                        recordButton.postDelayed({ statusText.text = "Gotowy" }, 2000)
                    }
                } else {
                    val code = conn.responseCode
                    withContext(Dispatchers.Main) {
                        statusText.text = "✗ Błąd HTTP $code"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText.text = "✗ ${e.message?.take(30)}"
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
    }
}
