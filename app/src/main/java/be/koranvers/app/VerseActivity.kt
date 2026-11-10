package be.koranvers.app

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * De logica van het versscherm: tekst tonen, voortgang bijhouden, audio afspelen.
 * Wordt gebruikt door VerseActivity (vergrendelscherm) én door VerseOverlay (na ontgrendelen).
 */
class VerseController(private val ctx: Context, root: View, private val onClose: () -> Unit) {

    private val prefs = Prefs(ctx)
    private val tvHeader: TextView = root.findViewById(R.id.tvHeader)
    private val tvArabic: TextView = root.findViewById(R.id.tvArabic)
    private val tvTranslation: TextView = root.findViewById(R.id.tvTranslation)
    private val tvProgress: TextView = root.findViewById(R.id.tvProgress)
    private val btnPlay: Button = root.findViewById(R.id.btnPlay)
    private val scroll: ScrollView = root.findViewById(R.id.scroll)

    private var player: MediaPlayer? = null
    private var prepared = false
    private var currentUrl: String? = null
    private var focusRequest: AudioFocusRequest? = null

    private val attrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    init {
        btnPlay.setOnClickListener { togglePlay() }
        root.findViewById<Button>(R.id.btnNext).setOnClickListener { showNext(prefs.autoplay) }
        root.findViewById<Button>(R.id.btnClose).setOnClickListener { onClose() }
    }

    fun showNext(autoplay: Boolean) {
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_VERSE)
        if (!QuranData.isDownloaded(ctx, Qiraat.ARABIC_EDITION)) {
            tvHeader.text = "KoranVers"
            tvArabic.text = ""
            tvTranslation.text = "Open de app KoranVers om de Koran-tekst te downloaden."
            btnPlay.visibility = View.GONE
            return
        }
        btnPlay.visibility = View.VISIBLE

        val idx = prefs.nextIndex.coerceIn(0, QuranData.TOTAL - 1)
        val arabic = QuranData.text(ctx, Qiraat.ARABIC_EDITION, idx)
        val parts = Splitter.partCount(arabic, prefs.maxWords)
        val part = prefs.nextPart.coerceIn(0, parts - 1)
        val loc = QuranData.locate(ctx, idx)

        tvHeader.text = buildString {
            append("${loc.surah.englishName}  ·  ${loc.surah.name}\n")
            append("Vers ${loc.surah.number}:${loc.ayah}")
            if (parts > 1) append("  ·  deel ${part + 1}/$parts")
        }
        tvArabic.text = Splitter.part(arabic, parts, part)

        val tr = prefs.translation
        tvTranslation.text =
            if (tr.isNotEmpty() && QuranData.isDownloaded(ctx, tr))
                Splitter.part(QuranData.text(ctx, tr, idx), parts, part)
            else ""

        val pct = (idx + 1) * 100.0 / QuranData.TOTAL
        tvProgress.text = buildString {
            append("Vers ${idx + 1} van ${QuranData.TOTAL}  ·  ")
            append(String.format(Locale("nl", "BE"), "%.1f%%", pct))
            if (prefs.khatm > 0) append("  ·  khatm ×${prefs.khatm}")
        }
        scroll.scrollTo(0, 0)

        prefs.lastShown = System.currentTimeMillis()
        DebugLog.add(ctx, "Vers ${loc.surah.number}:${loc.ayah} getoond")
        Progress.advance(ctx, prefs)

        val reciter = Qiraat.byId(prefs.reciterId)
        val url = Qiraat.audioUrl(reciter, loc.surah.number, loc.ayah)
        currentUrl = url
        stopAudio()
        if (autoplay && part == 0 && mayAutoplay()) play(url) else AudioCache.prefetch(ctx, url)

        val nLoc = QuranData.locate(ctx, prefs.nextIndex)
        AudioCache.prefetch(ctx, Qiraat.audioUrl(reciter, nLoc.surah.number, nLoc.ayah))
    }

    private fun mayAutoplay(): Boolean {
        val am = ctx.getSystemService(AudioManager::class.java)
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
        val am = ctx.getSystemService(AudioManager::class.java)
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
            val cached = AudioCache.fileFor(ctx, url)
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
            Toast.makeText(ctx, "Audio niet beschikbaar (geen internet?)", Toast.LENGTH_SHORT).show()
            abandonFocus()
            true
        }
        player = mp
        prepared = false
        btnPlay.text = "…  laden"
        mp.prepareAsync()
    }

    fun stopAudio() {
        player?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        player = null
        prepared = false
        btnPlay.text = "▶  Beluister"
        abandonFocus()
    }

    private fun abandonFocus() {
        focusRequest?.let { ctx.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focusRequest = null
    }
}

/** Versscherm als gewone activity — nodig om bóven het vergrendelscherm te tonen. */
class VerseActivity : Activity() {

    private lateinit var controller: VerseController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        setContentView(R.layout.activity_verse)
        controller = VerseController(this, findViewById(R.id.verseRoot)) { finish() }
        controller.showNext(Prefs(this).autoplay)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        controller.showNext(Prefs(this).autoplay)
    }

    override fun onStop() {
        super.onStop()
        controller.stopAudio()
        if (!isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        controller.stopAudio()
        super.onDestroy()
    }
}

/**
 * Versscherm als overlay-venster ('weergeven over andere apps').
 * Werkt na ontgrendelen zonder dat Android een activity-start uit de achtergrond moet toelaten.
 */
object VerseOverlay {
    private var view: View? = null
    private var controller: VerseController? = null

    val isShowing get() = view != null

    fun show(service: Context) {
        val app = service.applicationContext
        if (view != null) {
            controller?.showNext(Prefs(app).autoplay); return
        }
        val themed = ContextThemeWrapper(app, R.style.VerseTheme)
        val frame = object : FrameLayout(themed) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    hide(app); return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        val content = LayoutInflater.from(themed).inflate(R.layout.activity_verse, frame, false)
        val extraTop = (40 * app.resources.displayMetrics.density).toInt()
        content.setPadding(content.paddingLeft, content.paddingTop + extraTop, content.paddingRight, content.paddingBottom)
        frame.addView(content)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE
        )
        app.getSystemService(WindowManager::class.java).addView(frame, lp)
        view = frame
        val c = VerseController(app, content) { hide(app) }
        controller = c
        c.showNext(Prefs(app).autoplay)
    }

    fun hide(ctx: Context) {
        controller?.stopAudio()
        controller = null
        view?.let {
            try { ctx.applicationContext.getSystemService(WindowManager::class.java).removeView(it) } catch (_: Exception) {}
        }
        view = null
    }
}
