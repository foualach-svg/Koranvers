package be.koranvers.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var tvStatus: TextView
    private lateinit var tvNext: TextView
    private lateinit var tvPerms: TextView
    private lateinit var spRiwayah: Spinner
    private lateinit var spReciter: Spinner
    private lateinit var spTranslation: Spinner
    private lateinit var spInterval: Spinner
    private lateinit var spSplit: Spinner
    private lateinit var spSurah: Spinner
    private lateinit var swEnabled: Switch

    private val intervals = listOf(0 to "Elke keer", 30 to "Max. 1× per 30 sec", 60 to "Max. 1× per minuut",
        300 to "Max. 1× per 5 min", 900 to "Max. 1× per 15 min")
    private val splits = listOf(15 to "Na 15 woorden", 25 to "Na 25 woorden", 40 to "Na 40 woorden",
        100000 to "Niet splitsen")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        createChannels(this)

        tvStatus = findViewById(R.id.tvStatus)
        tvNext = findViewById(R.id.tvNext)
        tvPerms = findViewById(R.id.tvPerms)
        spRiwayah = findViewById(R.id.spRiwayah)
        spReciter = findViewById(R.id.spReciter)
        spTranslation = findViewById(R.id.spTranslation)
        spInterval = findViewById(R.id.spInterval)
        spSplit = findViewById(R.id.spSplit)
        spSurah = findViewById(R.id.spSurah)
        swEnabled = findViewById(R.id.swEnabled)

        // --- Qiraat (riwayah) en reciteur
        spRiwayah.adapter = adapter(Qiraat.riwayat)
        val current = Qiraat.byId(prefs.reciterId)
        spRiwayah.setSelection(Qiraat.riwayat.indexOf(current.riwayah))
        fillReciters(current.riwayah)
        spRiwayah.onItemSelectedListener = onSelect { pos ->
            val r = Qiraat.riwayat[pos]
            if (Qiraat.byId(prefs.reciterId).riwayah != r) {
                prefs.reciterId = Qiraat.forRiwayah(r).first().id
            }
            fillReciters(r)
        }
        spReciter.onItemSelectedListener = onSelect { pos ->
            prefs.reciterId = Qiraat.forRiwayah(Qiraat.riwayat[spRiwayah.selectedItemPosition])[pos].id
        }

        // --- Vertaling
        spTranslation.adapter = adapter(Qiraat.translations.map { it.second })
        spTranslation.setSelection(Qiraat.translations.indexOfFirst { it.first == prefs.translation }.coerceAtLeast(0))
        spTranslation.onItemSelectedListener = onSelect { pos ->
            val ed = Qiraat.translations[pos].first
            if (ed != prefs.translation) {
                prefs.translation = ed
                ensureDownloaded()
            }
        }

        // --- Frequentie en splitsen
        spInterval.adapter = adapter(intervals.map { it.second })
        spInterval.setSelection(intervals.indexOfFirst { it.first == prefs.minIntervalSec }.coerceAtLeast(0))
        spInterval.onItemSelectedListener = onSelect { prefs.minIntervalSec = intervals[it].first }

        spSplit.adapter = adapter(splits.map { it.second })
        spSplit.setSelection(splits.indexOfFirst { it.first == prefs.maxWords }.coerceAtLeast(0))
        spSplit.onItemSelectedListener = onSelect { prefs.maxWords = splits[it].first; refreshNext() }

        // --- Schakelaars
        swEnabled.isChecked = prefs.enabled
        swEnabled.setOnCheckedChangeListener { _, on ->
            prefs.enabled = on
            if (on && !Settings.canDrawOverlays(this)) askOverlay()
            if (on && Build.VERSION.SDK_INT >= 33) askNotifications()
            UnlockService.sync(this)
            refreshPerms()
        }
        bindSwitch(R.id.swLockscreen, prefs.onLockscreen) { prefs.onLockscreen = it }
        bindSwitch(R.id.swAutoplay, prefs.autoplay) { prefs.autoplay = it }
        bindSwitch(R.id.swSilent, prefs.respectSilent) { prefs.respectSilent = it }

        // --- Knoppen
        findViewById<Button>(R.id.btnOverlay).setOnClickListener { askOverlay() }
        findViewById<Button>(R.id.btnNotif).setOnClickListener { askNotifications() }
        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        findViewById<Button>(R.id.btnTest).setOnClickListener {
            if (!QuranData.isDownloaded(this, Qiraat.ARABIC_EDITION)) toast("Eerst de tekst downloaden")
            else startActivity(Intent(this, VerseActivity::class.java))
        }
        findViewById<Button>(R.id.btnJump).setOnClickListener {
            if (!QuranData.isDownloaded(this, Qiraat.ARABIC_EDITION)) return@setOnClickListener
            val s = QuranData.surahs(this)[spSurah.selectedItemPosition]
            prefs.nextIndex = s.start
            prefs.nextPart = 0
            refreshNext()
            toast("Volgend vers: ${s.englishName} ${s.number}:1")
        }
        findViewById<Button>(R.id.btnRetry).setOnClickListener { ensureDownloaded() }

        ensureDownloaded()
        if (prefs.enabled) UnlockService.sync(this)
    }

    override fun onResume() {
        super.onResume()
        refreshPerms()
        refreshNext()
    }

    /* ------------------------------------------------------------ download */

    private fun ensureDownloaded() {
        val needed = listOf(Qiraat.ARABIC_EDITION, prefs.translation).filter { !QuranData.isDownloaded(this, it) }
        if (needed.isEmpty()) {
            tvStatus.text = "✅ Koran-tekst en vertaling staan op je toestel (werkt offline; audio wordt gestreamd)."
            findViewById<View>(R.id.btnRetry).visibility = View.GONE
            fillSurahs()
            return
        }
        tvStatus.text = "⏳ Koran-tekst downloaden… (eenmalig, ±3 MB)"
        findViewById<View>(R.id.btnRetry).visibility = View.GONE
        io.execute {
            var error: String? = null
            for (ed in needed) {
                try { QuranData.download(this, ed) } catch (e: Exception) { error = e.message ?: e.toString() }
            }
            runOnUiThread {
                if (error != null) {
                    tvStatus.text = "❌ Downloaden mislukt: $error"
                    findViewById<View>(R.id.btnRetry).visibility = View.VISIBLE
                } else ensureDownloaded()
                refreshNext()
            }
        }
    }

    /* ------------------------------------------------------------ UI-hulp */

    private fun fillReciters(riwayah: String) {
        val list = Qiraat.forRiwayah(riwayah)
        spReciter.adapter = adapter(list.map { it.name })
        spReciter.setSelection(list.indexOfFirst { it.id == prefs.reciterId }.coerceAtLeast(0))
    }

    private var surahsFilled = false
    private fun fillSurahs() {
        if (surahsFilled) return
        surahsFilled = true
        spSurah.adapter = adapter(QuranData.surahs(this).map { "${it.number}. ${it.englishName}  ${it.name}  (${it.count})" })
    }

    private fun refreshNext() {
        if (!::tvNext.isInitialized) return
        if (!QuranData.isDownloaded(this, Qiraat.ARABIC_EDITION)) { tvNext.text = ""; return }
        val idx = prefs.nextIndex
        val loc = QuranData.locate(this, idx)
        val parts = Progress.partsFor(this, prefs, idx)
        val left = QuranData.TOTAL - idx
        tvNext.text = buildString {
            append("Volgend vers: ${loc.surah.englishName} ${loc.surah.number}:${loc.ayah}")
            if (parts > 1) append(" (deel ${prefs.nextPart + 1}/$parts)")
            append("\nNog $left verzen tot het einde van de Koran.")
            if (prefs.khatm > 0) append("\nVolledig gelezen: ${prefs.khatm}×")
        }
    }

    private fun refreshPerms() {
        val overlay = Settings.canDrawOverlays(this)
        val notif = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        tvPerms.text = buildString {
            append(if (overlay) "✅" else "❌").append(" Weergeven over andere apps (nodig om vanzelf te openen)\n")
            append(if (notif) "✅" else "❌").append(" Meldingen (nodig voor de achtergronddienst)")
        }
    }

    private fun askOverlay() {
        toast("Zet 'KoranVers' aan in de lijst")
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshPerms()
    }

    private fun bindSwitch(id: Int, value: Boolean, set: (Boolean) -> Unit) {
        val sw = findViewById<Switch>(id)
        sw.isChecked = value
        sw.setOnCheckedChangeListener { _, on -> set(on) }
    }

    private fun adapter(items: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)

    private fun onSelect(block: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = block(pos)
        override fun onNothingSelected(p: AdapterView<*>?) {}
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
