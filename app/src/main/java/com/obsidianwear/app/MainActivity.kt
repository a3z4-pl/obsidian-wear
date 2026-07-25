package com.obsidianwear.app

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Bundle
import android.os.PowerManager
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {

    private lateinit var rootLayout: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var timerText: TextView
    private lateinit var recordButton: Button
    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var isRecording = false
    private var recordingSeconds = 0

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rootLayout = findViewById(R.id.root_layout)
        statusText = findViewById(R.id.status_text)
        timerText = findViewById(R.id.timer_text)
        recordButton = findViewById(R.id.record_button)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ObsidianWear:record")

        recordButton.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                startRecording()
            }
        }
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }

        isRecording = true
        recordingSeconds = 0
        acquireWakeLock()

        // Visual feedback
        rootLayout.setBackgroundColor(0xCC991100.toInt()) // dark red bg
        recordButton.text = "⏹ Zakończ"
        recordButton.setTextColor(0xFFFFFFFF.toInt())
        statusText.text = "Nagrywam..."
        timerText.visibility = View.VISIBLE

        // Start timer
        lifecycleScope.launch {
            while (isActive && isRecording) {
                delay(1000)
                recordingSeconds++
                val min = recordingSeconds / 60
                val sec = recordingSeconds % 60
                timerText.text = "%02d:%02d".format(min, sec)
            }
        }

        // Start audio recording
        try {
            audioFile = File(cacheDir, "voice_note.webm")
            audioFile?.delete()
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.WEBM)
                setAudioEncoder(MediaRecorder.AudioEncoder.VORBIS)
                setAudioSamplingRate(16000)
                setOutputFile(audioFile?.absolutePath)
                prepare()
                start()
            }
        } catch (e: Exception) {
            stopRecordingState()
            statusText.text = "✗ Błąd mikrofonu"
        }
    }

    private fun stopRecording() {
        try {
            mediaRecorder?.stop()
        } catch (_: Exception) {}
        mediaRecorder?.release()
        mediaRecorder = null
        isRecording = false

        stopRecordingState()

        val file = audioFile
        if (file != null && file.exists() && file.length() > 0) {
            statusText.text = "Transkrybuję..."
            transcribeAudio(file)
        } else {
            statusText.text = "✗ Nagranie puste"
            resetUiDelayed()
        }
    }

    private fun stopRecordingState() {
        isRecording = false
        recordButton.text = "Nagraj"
        rootLayout.setBackgroundColor(0xFF1E1B4B.toInt()) // normal dark bg
        timerText.visibility = View.GONE
        releaseWakeLock()
    }

    private fun resetUiDelayed() {
        recordButton.postDelayed({
            if (!isRecording) {
                statusText.text = "Gotowy"
            }
        }, 2000)
    }

    private fun transcribeAudio(file: File) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Send to Whisper API
                val whisperUrl = "${BuildConfig.SERVER_URL.replace(":5001", ":9000")}/v1/audio/transcriptions"
                val boundary = "Boundary-${System.currentTimeMillis()}"
                val conn = URL(whisperUrl).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.WHISPER_KEY}")
                conn.doOutput = true
                conn.connectTimeout = 30000
                conn.readTimeout = 30000

                // Build multipart body
                val body = buildMultipartBody(boundary, file, "pl")
                conn.outputStream.use { it.write(body) }

                if (conn.responseCode == 200) {
                    val resp = conn.inputStream.bufferedReader().readText()
                    val text = extractTextFromJson(resp)
                    if (text.isNotBlank()) {
                        withContext(Dispatchers.Main) { statusText.text = "Zapisuję..." }
                        sendTextToReceiver(text)
                    } else {
                        withContext(Dispatchers.Main) {
                            statusText.text = "✗ Pusta transkrypcja"
                            resetUiDelayed()
                        }
                    }
                } else {
                    val err = conn.errorStream?.bufferedReader()?.readText() ?: "?"
                    withContext(Dispatchers.Main) {
                        statusText.text = "✗ Whisper: ${conn.responseCode}"
                        resetUiDelayed()
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText.text = "✗ ${e.message?.take(30)}"
                    resetUiDelayed()
                }
            }
        }
    }

    private fun sendTextToReceiver(text: String) {
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

                if (conn.responseCode == 200) {
                    withContext(Dispatchers.Main) {
                        statusText.text = "✓ Zapisane!"
                        resetUiDelayed()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        statusText.text = "✗ Błąd ${conn.responseCode}"
                        resetUiDelayed()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText.text = "✗ ${e.message?.take(30)}"
                    resetUiDelayed()
                }
            } finally {
                conn?.disconnect()
            }
        }
    }

    private fun buildMultipartBody(boundary: String, file: File, language: String): ByteArray {
        val sb = StringBuilder()
        sb.append("--$boundary\r\n")
        sb.append("Content-Disposition: form-data; name=\"file\"; filename=\"audio.webm\"\r\n")
        sb.append("Content-Type: audio/webm\r\n\r\n")
        val header = sb.toString().toByteArray()
        val footer = "\r\n--$boundary\r\nContent-Disposition: form-data; name=\"language\"\r\n\r\n$language\r\n--$boundary--\r\n".toByteArray()
        val fileBytes = file.readBytes()
        return header + fileBytes + footer
    }

    private fun extractTextFromJson(json: String): String {
        return try {
            val obj = JSONObject(json)
            obj.optString("text", "")
        } catch (_: Exception) { "" }
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(120_000)
            }
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            statusText.text = "Brak uprawnień"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { mediaRecorder?.stop() } catch (_: Exception) {}
        mediaRecorder?.release()
        mediaRecorder = null
        releaseWakeLock()
    }
}
