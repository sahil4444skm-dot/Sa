package com.example.jarvis

import ai.picovoice.porcupine.Porcupine
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.Locale
import java.util.concurrent.Executors

class JarvisService : Service() {

    private enum class State { WAKE, SPEAKING, LISTENING, THINKING }

    companion object {
        private const val TAG = "Jarvis"
        private const val CH_ID = "jarvis_main"
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "com.example.jarvis.STOP"
        private const val IDLE_TEXT = "Ready hai. \"Jarvis\" bolein"
        private val STOP_WORDS = listOf(
            "off jarvis", "band karo", "bas karo", "chup ho jao",
            "बंद करो", "बंद जार्विस", "ऑफ जार्विस", "चुप"
        )

        fun start(ctx: Context) {
            val i = Intent(ctx, JarvisService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val brain = JarvisBrain()

    @Volatile private var state = State.WAKE
    @Volatile private var stateSince = System.currentTimeMillis()
    @Volatile private var wakeRunning = false
    @Volatile private var resetting = false
    private var wakeThread: Thread? = null

    private var porcupine: Porcupine? = null
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val doneCallbacks = HashMap<String, () -> Unit>() // sirf main thread par

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notif = buildNotif(IDLE_TEXT)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts ?: return@TextToSpeech
                if (engine.setLanguage(Locale("hi", "IN")) < TextToSpeech.LANG_AVAILABLE) {
                    engine.setLanguage(Locale("en", "IN"))
                }
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { finishSpeech(id) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { finishSpeech(id) }
                    override fun onError(id: String?, errorCode: Int) { finishSpeech(id) }
                })
                ttsReady = true
            }
        }

        if (initPorcupine() == null) {
            updateNotif("Error: wake word start nahi hua. Picovoice key check karein.")
        } else {
            startWake()
        }
        main.postDelayed(watchdog, 5_000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) main.post { stopNow() }
        return START_STICKY
    }

    // ------------------------- WAKE WORD (offline) -------------------------

    private fun initPorcupine(): Porcupine? {
        porcupine?.let { return it }
        return try {
            Porcupine.Builder()
                .setAccessKey(BuildConfig.PICOVOICE_KEY)
                .setKeyword(Porcupine.BuiltInKeyword.JARVIS)
                .setSensitivity(0.6f)
                .build(applicationContext)
                .also { porcupine = it }
        } catch (e: Exception) {
            Log.e(TAG, "Porcupine init failed: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun startWake() {
        if (wakeRunning) return
        val p = initPorcupine() ?: return
        wakeRunning = true
        wakeThread = Thread({ wakeLoop(p) }, "jarvis-wake").also { it.start() }
    }

    /** Background thread. Mic release karne ke BAAD hi "Jarvis" ko main thread par bhejta hai. */
    private fun wakeLoop(p: Porcupine) {
        var detected = false
        var record: AudioRecord? = null
        try {
            val frameLen = p.frameLength
            val minBuf = AudioRecord.getMinBufferSize(
                p.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, p.sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, frameLen * 8)
            )
            record = rec
            if (rec.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("mic init")
            rec.startRecording()
            val frame = ShortArray(frameLen)   // ek hi buffer, har frame par allocation nahi

            while (wakeRunning) {
                val n = rec.read(frame, 0, frameLen)
                if (n < 0) throw IllegalStateException("mic read $n")
                if (n != frameLen) continue
                if (p.process(frame) >= 0) { detected = true; break }
            }
        } catch (e: Exception) {
            Log.e(TAG, "wake loop: ${e.javaClass.simpleName}")
        } finally {
            try { record?.stop() } catch (_: Exception) {}
            record?.release()
            wakeRunning = false
        }
        if (detected) {
            setState(State.SPEAKING)
            main.post { onWakeWord() }
        }
    }

    private fun onWakeWord() {
        Log.d(TAG, "wake word detected")
        updateNotif("Sun raha hoon...")
        say("Ji, boliye") { startListening() }
    }

    // ------------------------- LISTENING (Google STT) -------------------------

    private fun startListening() {
        setState(State.LISTENING)
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            say("Speech service nahi mil rahi. Google app update karein.") { toWake() }
            return
        }
        val sr = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        sr.startListening(intent)
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            if (text.isEmpty()) toWake() else handleCommand(text)
        }

        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> toWake()
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> main.postDelayed({ toWake() }, 800L)
                else -> { Log.w(TAG, "SR error $error"); toWake() }
            }
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun handleCommand(text: String) {
        val t = text.lowercase(Locale.ROOT)
        if (STOP_WORDS.any { t.contains(it) }) {
            setState(State.SPEAKING)
            say("Theek hai, band kar raha hoon") { toWake() }
            return
        }
        setState(State.THINKING)
        worker.execute {
            val answer = brain.ask(text)          // network yahin, background mein
            main.post {
                if (state != State.THINKING) return@post   // beech mein band ho gaya
                setState(State.SPEAKING)
                say(answer) { startListening() }            // bar-bar baat: wake word nahi chahiye
            }
        }
    }

    // ------------------------- STATE + CONTROL -------------------------

    private fun setState(s: State) {
        stateSince = System.currentTimeMillis()
        state = s
    }

    /** Wapas WAKE state mein (idempotent, recursion safe). */
    private fun toWake() {
        if (resetting || (state == State.WAKE && wakeRunning)) return
        resetting = true
        try {
            recognizer?.cancel()
            setState(State.WAKE)
            updateNotif(IDLE_TEXT)
            startWake()
        } finally {
            resetting = false
        }
    }

    /** Notification ka "Band karo" button ya instant stop. */
    private fun stopNow() {
        doneCallbacks.clear()
        tts?.stop()
        toWake()
    }

    /** Watchdog: koi state atak jaye to 5 sec mein self-heal. Phone hang na ho, bina user ke. */
    private val watchdog = object : Runnable {
        override fun run() {
            val limit = when (state) {
                State.WAKE -> Long.MAX_VALUE
                State.SPEAKING -> 120_000L
                State.THINKING -> 90_000L
                State.LISTENING -> 30_000L
            }
            if (System.currentTimeMillis() - stateSince > limit) {
                Log.w(TAG, "stuck in $state, resetting")
                toWake()
            } else if (state == State.WAKE && !wakeRunning) {
                startWake()
            }
            main.postDelayed(this, 5_000L)
        }
    }

    // ------------------------- TTS (bolna) -------------------------

    private fun say(text: String, then: (() -> Unit)? = null) {
        val engine = tts
        if (!ttsReady || engine == null) { then?.invoke(); return }
        doneCallbacks.clear()  // QUEUE_FLUSH purana bolna cancel karta hai
        val id = "u" + System.nanoTime()
        if (then != null) doneCallbacks[id] = then
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.ERROR) {
            doneCallbacks.remove(id)
            then?.invoke()
        }
    }

    private fun finishSpeech(id: String?) {
        main.post { id?.let { doneCallbacks.remove(it) }?.invoke() }
    }

    // ------------------------- NOTIFICATION -------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CH_ID, "Jarvis", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotif(text: String): Notification {
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, JarvisService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CH_ID)
            .setContentTitle("Jarvis")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Band karo", stopPi)
            .build()
    }

    private fun updateNotif(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotif(text))
    }

    // ------------------------- CLEANUP -------------------------

    override fun onDestroy() {
        main.removeCallbacks(watchdog)
        wakeRunning = false
        try { wakeThread?.join(500) } catch (_: InterruptedException) {}
        recognizer?.destroy()
        tts?.shutdown()
        porcupine?.delete()
        worker.shutdownNow()
        super.onDestroy()
    }
}

/** Phone restart ke baad Jarvis apne aap shuru ho (Android 12+ par kabhi-kabhi block ho sakta hai). */
class JarvisBootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            try { JarvisService.start(ctx) } catch (e: Exception) {
                Log.e("Jarvis", "boot start nahi hua: ${e.javaClass.simpleName}")
            }
        }
    }
}
