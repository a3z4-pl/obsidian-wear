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
import kotlinx.coroutines.*
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
    private var isTranscribing = false

    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

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
            if (isRecording) stopRecording()
            else if (!isTranscribing) startRecording()
        }
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        isRecording = true; recordingSeconds = 0; acquireWakeLock()

        rootLayout.setBackgroundColor(0xCC881111.toInt())
        recordButton.text = "STOP"
        statusText.text = "Nagrywam..."
        timerText.visibility = View.VISIBLE; timerText.text = "00:00"

        scope.launch {
            while (isActive && isRecording) {
                delay(1000); recordingSeconds++
                val m = recordingSeconds / 60; val s = recordingSeconds % 60
                timerText.text = "%02d:%02d".format(m, s)
            }
        }
        try {
            audioFile = File(cacheDir, "voice_note.m4a"); audioFile?.delete()
            mediaRecorder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
                MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            mediaRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100); setAudioEncodingBitRate(64000)
                setOutputFile(audioFile?.absolutePath); prepare(); start()
            }
        } catch (e: Exception) {
            stopRecordingState()
            statusText.text = "✗ ${e.message?.take(20)}"
        }
    }

    private fun stopRecording() {
        try { mediaRecorder?.stop() } catch (_: Exception) {}
        mediaRecorder?.release(); mediaRecorder = null; isRecording = false
        stopRecordingState()

        val file = audioFile
        if (file != null && file.exists() && file.length() > 0) {
            if (recordingSeconds < 2) {
                statusText.text = "Za krótkie"; file.delete(); resetUiDelayed(); return
            }
            isTranscribing = true; statusText.text = "Transkrybuję..."
            transcribeAudio(file)
        } else {
            statusText.text = "✗ Puste"; resetUiDelayed()
        }
    }

    private fun stopRecordingState() {
        isRecording = false; recordButton.text = "Nagraj"
        rootLayout.setBackgroundColor(0xFF1E1B4B.toInt())
        timerText.visibility = View.GONE; releaseWakeLock()
    }

    private fun resetUiDelayed() {
        recordButton.postDelayed({
            if (!isRecording && !isTranscribing) statusText.text = "Gotowy"
        }, 2000)
    }

    private fun tryPost(url: String, jsonPayload: String, timeoutMs: Int = 5000): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.VOICE_API_KEY}")
            conn.doOutput = true
            conn.connectTimeout = timeoutMs; conn.readTimeout = timeoutMs
            OutputStreamWriter(conn.outputStream).use { it.write(jsonPayload) }
            if (conn.responseCode == 200) conn.inputStream.bufferedReader().readText() else null
        } catch (_: Exception) { null } finally { conn?.disconnect() }
    }

    private fun transcribeAudio(file: File) {
        @OptIn(DelicateCoroutinesApi::class)
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val boundary = "B-${System.currentTimeMillis()}"
                val header = "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.m4a\"\r\nContent-Type: audio/mp4\r\n\r\n".toByteArray()
                val footer = "\r\n--$boundary\r\nContent-Disposition: form-data; name=\"language\"\r\n\r\npl\r\n--$boundary--\r\n".toByteArray()
                val body = header + file.readBytes() + footer

                // Try LAN, then Tailscale
                var resp: String? = null
                for (base in listOf(BuildConfig.SERVER_URL, BuildConfig.SERVER_URL_REMOTE)) {
                    val wUrl = "${base.replace(":5001", ":9000")}/v1/audio/transcriptions"
                    resp = tryPostMultipart(wUrl, body, boundary)
                    if (resp != null) break
                }

                if (resp != null) {
                    val text = try { JSONObject(resp).optString("text", "") } catch (_: Exception) { "" }
                    if (text.isNotBlank()) {
                        withContext(Dispatchers.Main) { statusText.text = "Zapisuję..." }
                        sendText(text)
                    } else {
                        withContext(Dispatchers.Main) {
                            statusText.text = "✗ Pusta transkrypcja"
                            isTranscribing = false; resetUiDelayed()
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        statusText.text = "✗ Brak połączenia"
                        isTranscribing = false; resetUiDelayed()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText.text = "✗ ${e.message?.take(25)}"
                    isTranscribing = false; resetUiDelayed()
                }
            } finally { file.delete() }
        }
    }

    private fun tryPostMultipart(url: String, body: ByteArray, boundary: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.WHISPER_KEY}")
            conn.doOutput = true
            conn.connectTimeout = 10000; conn.readTimeout = 30000
            conn.outputStream.use { it.write(body) }
            if (conn.responseCode == 200) conn.inputStream.bufferedReader().readText() else null
        } catch (_: Exception) { null } finally { conn?.disconnect() }
    }

    private fun sendText(text: String) {
        @OptIn(DelicateCoroutinesApi::class)
        GlobalScope.launch(Dispatchers.IO) {
            val json = JSONObject().apply { put("text", text) }.toString()
            var ok = false
            for (base in listOf(BuildConfig.SERVER_URL, BuildConfig.SERVER_URL_REMOTE)) {
                if (tryPost("$base/voice-note", json) != null) { ok = true; break }
            }
            withContext(Dispatchers.Main) {
                if (ok) {
                    statusText.text = "✓ Zapisane!"
                } else {
                    statusText.text = "✗ Błąd wysyłki"
                }
                isTranscribing = false; resetUiDelayed()
            }
        }
    }

    private fun acquireWakeLock() {
        try { if (wakeLock?.isHeld == false) wakeLock?.acquire(120_000) } catch (_: Exception) {}
    }
    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
            startRecording()
        else statusText.text = "Brak uprawnień"
    }

    override fun onDestroy() {
        super.onDestroy()
        try { mediaRecorder?.stop() } catch (_: Exception) {}
        mediaRecorder?.release(); mediaRecorder = null
        releaseWakeLock(); scope.cancel()
    }
}
