package com.obsidianwear.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PowerManager
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {

    private lateinit var statusText: TextView
    private lateinit var recordButton: Button
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    // WakeLock utrzymuje CPU przy życiu gdy ekran zgasłby → SpeechRecognizer nie przerywa.
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        recordButton = findViewById(R.id.record_button)

        // Utrzymuj ekran włączony gdy Activity na wierzchu → zegarek nie gaśnie podczas nagrywania.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ObsidianWear:record")

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        speechRecognizer?.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onResults(results: Bundle?) {
                stopListeningState()
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
                stopListeningState()
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
                stopSpeech()
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
        acquireWakeLock()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pl-PL")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        speechRecognizer?.startListening(intent)
    }

    /** Stop nasłuchu zainicjowany przez użytkownika. */
    private fun stopSpeech() {
        speechRecognizer?.stopListening()
        stopListeningState()
    }

    /** Reset UI + wake lock po zakończeniu nasłuchu (results/error/manual). */
    private fun stopListeningState() {
        isListening = false
        recordButton.text = "Nagraj"
        releaseWakeLock()
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == false) {
                // timeout 60s — bezpiecznik: nigdy nie zawiesi CPU na amen
                wakeLock?.acquire(60_000)
            }
        } catch (_: Exception) { }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) { }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            statusText.text = "Brak uprawnień"
        }
    }

    private fun sendToServer(text: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val url = URL("${BuildConfig.SERVER_URL}/voice-note")
                conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.VOICE_API_KEY}")
                conn.doOutput = true
                conn.connectTimeout = 5000
                conn.readTimeout = 5000

                val json = JSONObject().apply { put("text", text) }
                OutputStreamWriter(conn.outputStream).use { it.write(json.toString()) }

                val code = conn.responseCode
                val ok = code == 200
                conn.errorStream?.bufferedReader()?.use { it.readText() }

                withContext(Dispatchers.Main) {
                    if (ok) {
                        statusText.text = "✓ Zapisane!"
                        recordButton.postDelayed({ statusText.text = "Gotowy" }, 2000)
                    } else {
                        statusText.text = "✗ Błąd HTTP $code"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText.text = "✗ ${e.message?.take(30)}"
                }
            } finally {
                conn?.disconnect()
            }
        }
    }

    // NB: NIE stopujemy nasłuchu w onPause — to właśnie gaszenie ekranu przerywało nagranie.
    // Jeśli apka zostanie wypchnięta do tła, SpeechRecognizer działa dalej dzięki wake lock.

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        releaseWakeLock()
    }
}