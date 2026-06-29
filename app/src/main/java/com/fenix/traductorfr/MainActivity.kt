package com.fenix.traductorfr

import android.Manifest
import android.app.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.fenix.traductorfr.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val REQUEST_RECORD_AUDIO = 1001
    private val REQUEST_OVERLAY = 1002
    private val NOTIFICATION_CHANNEL_ID = "floating_service_channel"
    private val NOTIFICATION_ID = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Audio permission
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO
            )
        }

        binding.btnActivar.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                // Request overlay permission
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, REQUEST_OVERLAY)
            } else {
                startFloatingService()
            }
        }
    }

    private fun startFloatingService() {
        val intent = Intent(this, FloatingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OVERLAY) {
            if (Settings.canDrawOverlays(this)) {
                startFloatingService()
            } else {
                Toast.makeText(
                    this,
                    "Permiso de superposición denegado. No se puede iniciar la burbuja.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Service implementation (must be a static-like class; declared inside the file)
    // -------------------------------------------------------------------------
    class FloatingService : Service() {

        private lateinit var windowManager: WindowManager
        private lateinit var floatingView: FrameLayout
        private lateinit var iconView: TextView
        private lateinit var panelView: LinearLayout

        private lateinit var btnVoice: Button
        private lateinit var btnText: Button

        private lateinit var tvVoiceResult: TextView

        private lateinit var etInput: EditText
        private lateinit var btnTranslate: Button
        private lateinit var btnCopy: Button
        private lateinit var tvTextResult: TextView

        private var speechRecognizer: SpeechRecognizer? = null
        private val client = OkHttpClient()
        private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

        private val NOTIFICATION_CHANNEL_ID = "floating_service_channel"
        private val NOTIFICATION_ID = 1

        override fun onBind(intent: Intent?): IBinder? = null

        override fun onCreate() {
            super.onCreate()
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            createFloatingView()
            startForeground(NOTIFICATION_ID, createNotification())
        }

        private fun createNotification(): Notification {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Floating Service",
                    NotificationManager.IMPORTANCE_LOW
                )
                val manager = getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(channel)
            }
            return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("TraductorFR")
                .setContentText("Servicio de burbuja activo")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build()
        }

        private fun createFloatingView() {
            floatingView = FrameLayout(this)

            // Icon
            iconView = TextView(this).apply {
                text = "🌐"
                textSize = 30f
                setBackgroundColor(Color.parseColor("#8800AAFF"))
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(150, 150).apply {
                    gravity = Gravity.TOP or Gravity.START
                }
            }
            floatingView.addView(iconView)

            // Panel
            panelView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.WHITE)
                setPadding(20, 20, 20, 20)
                visibility = View.GONE
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    leftMargin = 0
                    topMargin = 160
                }
            }

            // Mode buttons
            val modeButtons = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            btnVoice = Button(this).apply { text = "Voz" }
            btnText = Button(this).apply { text = "Texto" }
            modeButtons.addView(btnVoice)
            modeButtons.addView(btnText)
            panelView.addView(modeButtons)

            // Voice result
            tvVoiceResult = TextView(this).apply {
                text = ""
                setTextColor(Color.BLACK)
                visibility = View.GONE
            }
            panelView.addView(tvVoiceResult)

            // Text mode UI
            etInput = EditText(this).apply {
                hint = "Escribe aquí"
                setBackgroundColor(Color.parseColor("#FFF0F0F0"))
            }
            btnTranslate = Button(this).apply { text = "Traducir" }
            btnCopy = Button(this).apply { text = "Copiar" }
            tvTextResult = TextView(this).apply {
                text = ""
                setTextColor(Color.BLACK)
            }

            panelView.addView(etInput)
            panelView.addView(btnTranslate)
            panelView.addView(btnCopy)
            panelView.addView(tvTextResult)

            floatingView.addView(panelView)

            // Add to window
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            params.x = 0
            params.y = 100
            windowManager.addView(floatingView, params)

            // Drag handling
            iconView.setOnTouchListener(object : View.OnTouchListener {
                private var initialX = 0
                private var initialY = 0
                private var initialTouchX = 0f
                private var initialTouchY = 0f

                override fun onTouch(v: View?, event: MotionEvent): Boolean {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = params.x
                            initialY = params.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            params.x = initialX + (event.rawX - initialTouchX).toInt()
                            params.y = initialY + (event.rawY - initialTouchY).toInt()
                            windowManager.updateViewLayout(floatingView, params)
                            return true
                        }
                    }
                    return false
                }
            })

            // Click to expand/collapse panel
            iconView.setOnClickListener {
                panelView.visibility = if (panelView.visibility == View.GONE) View.VISIBLE else View.GONE
            }

            // Mode button actions
            btnVoice.setOnClickListener {
                tvVoiceResult.visibility = View.VISIBLE
                etInput.visibility = View.GONE
                btnTranslate.visibility = View.GONE
                btnCopy.visibility = View.GONE
                tvTextResult.visibility = View.GONE
                startVoiceRecognition()
            }

            btnText.setOnClickListener {
                tvVoiceResult.visibility = View.GONE
                etInput.visibility = View.VISIBLE
                btnTranslate.visibility = View.VISIBLE
                btnCopy.visibility = View.VISIBLE
                tvTextResult.visibility = View.VISIBLE
                stopVoiceRecognition()
            }

            btnTranslate.setOnClickListener {
                val text = etInput.text.toString()
                if (text.isNotBlank()) {
                    translateAndShow(text, isVoice = false)
                } else {
                    Toast.makeText(this@FloatingService, "Introduce texto", Toast.LENGTH_SHORT).show()
                }
            }

            btnCopy.setOnClickListener {
                val result = tvTextResult.text.toString()
                if (result.isNotBlank()) {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Traducción", result)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this@FloatingService, "Copiado al portapapeles", Toast.LENGTH_SHORT).show()
                }
            }
        }

        private fun startVoiceRecognition() {
            if (speechRecognizer == null) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
                speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(error: Int) {
                        // Restart listening on error
                        startListening()
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val spokenText = matches?.firstOrNull() ?: ""
                        if (spokenText.isNotBlank()) {
                            translateAndShow(spokenText, isVoice = true)
                        } else {
                            startListening()
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
            startListening()
        }

        private fun startListening() {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale("fr", "FR"))
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
            speechRecognizer?.startListening(intent)
        }

        private fun stopVoiceRecognition() {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
        }

        private fun translateAndShow(text: String, isVoice: Boolean) {
            serviceScope.launch {
                try {
                    val url = "https://api.mymemory.translated.net/get?q=${URLEncoder.encode(text, "UTF-8")}&langpair=fr|es"
                    val request = Request.Builder().url(url).build()
                    client.newCall(request).execute().use { response ->
                        val body = response.body?.string() ?: ""
                        val json = JSONObject(body)
                        val translated = json.getJSONObject("responseData").getString("translatedText")
                        runOnUiThread {
                            if (isVoice) {
                                tvVoiceResult.text = translated
                                // Continue listening
                                startListening()
                            } else {
                                tvTextResult.text = translated
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("Translate", "Error", e)
                }
            }
        }

        private fun runOnUiThread(action: () -> Unit) {
            Handler(Looper.getMainLooper()).post { action() }
        }

        override fun onDestroy() {
            super.onDestroy()
            if (::floatingView.isInitialized) {
                windowManager.removeView(floatingView)
            }
            speechRecognizer?.destroy()
            serviceScope.cancel()
        }
    }
}