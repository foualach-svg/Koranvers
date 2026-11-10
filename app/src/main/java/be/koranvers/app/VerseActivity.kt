package be.koranvers.app

import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class VerseActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var tvHeader: TextView
    private lateinit var tvArabic: TextView
    private lateinit var tvTranslation: TextView
    private lateinit var tvProgress: TextView
    private lateinit var btnPlay: Button
    private lateinit var scroll: ScrollView

    private var player: MediaPlayer? = null
    private var prepared = false
    private var currentUrl: String? = null
    private var focusRequest: AudioFocusRequest? = null

    private val attrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        setContentView(R.layout.activity_verse)
        prefs = Prefs(this)

        tvHeader = findViewById(R.id.tvHeader)
        tvArabic = findViewById(R.id.tvArabic)
        tvTranslation = findViewById(R.id.tvTranslation)
        tvProgress = findViewById(R.id.tvProgress)
        btnPlay = findViewById(R.id.btnPlay)
        scroll = findViewById(R.id.scroll)

        btnPlay.setOnClickListener { togglePlay() }
        findViewById<Button>(R.id.btnNext).setOnClickListener { showNext(prefs.autoplay) }
        findViewById<Button>(R.id.btnClose).setOnClickListener { finish() }

        showNext(prefs.autoplay)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showNext(prefs.autoplay)
    }

    override fun onStop() {
        super.onStop()
        // Scherm uit of gebruiker gaat verder: audio stoppen en scherm sluiten.
        stopAudio()
        if (!isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        stopAudio()
        super.onDestroy()
    }

    private fun showNext(autoplay: Boolean) {
        getSystemService(NotificationManager::class.java).cancel(NOTIF_VERSE)
        if (!QuranData.isDownloaded(this, Qiraat.ARABIC_EDITION)) {
            tvHeader.text = "KoranVers"
            tvArabic.text = ""
            tvTranslation.text = "Open de app KoranVers om de Koran-tekst te downloaden."
            btnPlay.visibility = View.GONE
            return
        }
        btnPlay.visibility = View.VISIBLE

        val idx = prefs.nextIndex.coerceIn(0, QuranData.TOTAL - 1)
        val arabic = QuranData.text(this, Qiraat.ARABIC_EDITION, idx)
        val parts = Splitter.partCount(arabic, prefs.maxWords)
        val part = prefs.nextPart.coerceIn(0, parts - 1)
        val loc = QuranData.locate(this, idx)

        tvHeader.text = buildString {
            append("${loc.surah.englishName}  ·  ${loc.surah.name}\n")
            append("Vers ${loc.surah.number}:${loc.ayah}")
            if (parts > 1) append("  ·  deel ${part + 1}/$parts")
        }
        tvArabic.text = Splitter.part(arabic, parts, part)

        val tr = prefs.translation
        tvTranslation.text =
            if (tr.isNotEmpty() && QuranData.isDownloaded(this, tr))
                Splitter.part(QuranData.text(this, tr, idx), parts, part)
            else ""

        val pct = (idx + 1) * 100.0 / QuranData.TOTAL
        tvProgress.text = buildString {
            append("Vers ${idx + 1} van ${QuranData.TOTAL}  ·  ")
            append(String.format(Locale("nl", "BE"), "%.1f%%", pct))
            if (prefs.khatm > 0) append("  ·  khatm ×${prefs.khatm}")
        }
        scroll.scrollTo(0, 0)

        prefs.lastShown = System.currentTimeMillis()
        Progress.advance(this, prefs)

        // Audio: het hele vers wordt afgespeeld bij het eerste deel; daarna kan opnieuw via de knop.
        val reciter = Qiraat.byId(prefs.reciterId)
        val url = Qiraat.audioUrl(reciter, loc.surah.number, loc.ayah)
        currentUrl = url
        stopAudio()
        if (autoplay && part == 0 && mayAutoplay()) play(url) else AudioCache.prefetch(this, url)

        // Volgend vers alvast ophalen zodat het meteen speelt.
        val nLoc = QuranData.locate(this, prefs.nextIndex)
        AudioCache.prefetch(this, Qiraat.audioUrl(reciter, nLoc.surah.number, nLoc.ayah))
    }

    private fun mayAutoplay(): Boolean {
        val am = getSystemService(AudioManager::class.java)
        if (prefs.respectSilent && am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return false
        if (am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION) return false
        return true
    }

    private fun togglePlay() {
        val p = player
        try {
            if (p != null && prepared) {
                if (p.isPlaying) {
                    p.pause(); btnPlay.text = "▶  Beluister"
                } else {
                    p.start(); btnPlay.text = "⏸  Pauze"
                }
                return
            }
        } catch (_: Exception) {
        }
        currentUrl?.let { play(it) }
    }

    private fun play(url: String) {
        stopAudio()
        val am = getSystemService(AudioManager::class.java)
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    try { player?.pause() } catch (_: Exception) {}
                    btnPlay.text = "▶  Beluister"
                }
            }
            .build()
        if (am.requestAudioFocus(req) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        focusRequest = req

        val mp = MediaPlayer()
        mp.setAudioAttributes(attrs)
        try {
            val cached = AudioCache.fileFor(this, url)
            if (cached.exists() && cached.length() > 0) mp.setDataSource(cached.absolutePath)
            else mp.setDataSource(url)
        } catch (e: Exception) {
            mp.release(); abandonFocus(); return
        }
        mp.setOnPreparedListener {
            prepared = true
            it.start()
            btnPlay.text = "⏸  Pauze"
        }
        mp.setOnCompletionListener {
            btnPlay.text = "▶  Opnieuw"
            abandonFocus()
        }
        mp.setOnErrorListener { _, _, _ ->
            btnPlay.text = "▶  Beluister"
            Toast.makeText(this, "Audio niet beschikbaar (geen internet?)", Toast.LENGTH_SHORT).show()
            abandonFocus()
            true
        }
        player = mp
        prepared = false
        btnPlay.text = "…  laden"
        mp.prepareAsync()
    }

    private fun stopAudio() {
        player?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        player = null
        prepared = false
        if (::btnPlay.isInitialized) btnPlay.text = "▶  Beluister"
        abandonFocus()
    }

    private fun abandonFocus() {
        focusRequest?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focusRequest = null
    }
}
