package be.koranvers.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/* ---------------------------------------------------------------- Instellingen */

class Prefs(context: Context) {
    private val p: SharedPreferences =
        context.applicationContext.getSharedPreferences("koranvers", Context.MODE_PRIVATE)

    var nextIndex: Int
        get() = p.getInt("nextIndex", 0)
        set(v) = p.edit().putInt("nextIndex", v).apply()
    var nextPart: Int
        get() = p.getInt("nextPart", 0)
        set(v) = p.edit().putInt("nextPart", v).apply()
    var reciterId: String
        get() = p.getString("reciter", "alafasy") ?: "alafasy"
        set(v) = p.edit().putString("reciter", v).apply()
    var translation: String
        get() = p.getString("translation", "nl.siregar") ?: "nl.siregar"
        set(v) = p.edit().putString("translation", v).apply()
    var enabled: Boolean
        get() = p.getBoolean("enabled", false)
        set(v) = p.edit().putBoolean("enabled", v).apply()
    var onLockscreen: Boolean
        get() = p.getBoolean("onLockscreen", false)
        set(v) = p.edit().putBoolean("onLockscreen", v).apply()
    var autoplay: Boolean
        get() = p.getBoolean("autoplay", true)
        set(v) = p.edit().putBoolean("autoplay", v).apply()
    var respectSilent: Boolean
        get() = p.getBoolean("respectSilent", true)
        set(v) = p.edit().putBoolean("respectSilent", v).apply()
    var minIntervalSec: Int
        get() = p.getInt("minInterval", 0)
        set(v) = p.edit().putInt("minInterval", v).apply()
    var maxWords: Int
        get() = p.getInt("maxWords", 25)
        set(v) = p.edit().putInt("maxWords", v).apply()
    var lastShown: Long
        get() = p.getLong("lastShown", 0L)
        set(v) = p.edit().putLong("lastShown", v).apply()
    var khatm: Int
        get() = p.getInt("khatm", 0)
        set(v) = p.edit().putInt("khatm", v).apply()
}

/* ---------------------------------------------------------------- Qiraat & reciteurs */

data class Reciter(val id: String, val name: String, val riwayah: String, val folder: String)

object Qiraat {
    const val HAFS = "Ḥafṣ ʿan ʿĀṣim"
    const val WARSH = "Warsh ʿan Nāfiʿ"
    const val ARABIC_EDITION = "quran-uthmani"

    val riwayat = listOf(HAFS, WARSH)

    // Audio per vers van everyayah.com (bestandsnaam SSSAAA.mp3, 6236-verzen-telling).
    val reciters = listOf(
        Reciter("alafasy", "Mishary Rashid Alafasy", HAFS, "Alafasy_128kbps"),
        Reciter("husary", "Mahmoud Khalil Al-Husary", HAFS, "Husary_128kbps"),
        Reciter("minshawy", "Mohamed Siddiq Al-Minshawi", HAFS, "Minshawy_Murattal_128kbps"),
        Reciter("abdulbasit", "Abdul Basit (murattal)", HAFS, "Abdul_Basit_Murattal_192kbps"),
        Reciter("sudais", "Abdurrahman As-Sudais", HAFS, "Abdurrahmaan_As-Sudais_192kbps"),
        Reciter("warsh_dosary", "Ibrahim Al-Dosary", WARSH, "warsh/warsh_ibrahim_aldosary_128kbps"),
        Reciter("warsh_jazaery", "Yassin Al-Jazaery", WARSH, "warsh/warsh_yassin_al_jazaery_64kbps"),
        Reciter("warsh_abdulbasit", "Abdul Basit", WARSH, "warsh/warsh_Abdul_Basit_128kbps"),
    )

    val translations = listOf(
        "nl.siregar" to "Sofian S. Siregar (De Edele Koran)",
        "nl.leemhuis" to "Fred Leemhuis",
        "nl.keyzer" to "Salomo Keyzer",
        "en.sahih" to "Saheeh International (Engels)",
        "fr.hamidullah" to "Muhammad Hamidullah (Frans)",
        "" to "Geen vertaling",
    )

    fun byId(id: String) = reciters.firstOrNull { it.id == id } ?: reciters[0]
    fun forRiwayah(r: String) = reciters.filter { it.riwayah == r }
    fun audioUrl(r: Reciter, surah: Int, ayah: Int) =
        "https://everyayah.com/data/${r.folder}/" + String.format("%03d%03d.mp3", surah, ayah)
}

/* ---------------------------------------------------------------- Koran-tekst */

class Surah(val number: Int, val name: String, val englishName: String, val count: Int, val start: Int)
data class Loc(val surah: Surah, val ayah: Int)

object QuranData {
    const val TOTAL = 6236
    private var surahCache: List<Surah>? = null
    private val texts = HashMap<String, Array<String>>()

    private fun file(ctx: Context, ed: String) = File(ctx.filesDir, "text_$ed.json")
    private fun metaFile(ctx: Context) = File(ctx.filesDir, "surahs.json")

    fun isDownloaded(ctx: Context, ed: String) =
        ed.isEmpty() || (file(ctx, ed).exists() && metaFile(ctx).exists())

    /** Downloadt een volledige editie (Arabisch of vertaling) van api.alquran.cloud. */
    fun download(ctx: Context, ed: String) {
        if (ed.isEmpty()) return
        val root = JSONObject(httpGet("https://api.alquran.cloud/v1/quran/$ed"))
        if (root.optInt("code") != 200) throw IOException("Server gaf code ${root.optInt("code")}")
        val sArr = root.getJSONObject("data").getJSONArray("surahs")
        val out = JSONArray()
        val meta = JSONArray()
        for (i in 0 until sArr.length()) {
            val s = sArr.getJSONObject(i)
            val ayahs = s.getJSONArray("ayahs")
            meta.put(
                JSONObject().put("number", s.getInt("number")).put("name", s.getString("name"))
                    .put("englishName", s.getString("englishName")).put("count", ayahs.length())
            )
            for (j in 0 until ayahs.length()) out.put(ayahs.getJSONObject(j).getString("text").trim())
        }
        if (out.length() != TOTAL) throw IOException("Onverwacht aantal verzen: ${out.length()}")
        writeAtomic(file(ctx, ed), out.toString())
        if (!metaFile(ctx).exists()) writeAtomic(metaFile(ctx), meta.toString())
        synchronized(texts) { texts.remove(ed) }
    }

    fun text(ctx: Context, ed: String, idx: Int): String {
        if (ed.isEmpty()) return ""
        val arr = synchronized(texts) {
            texts.getOrPut(ed) {
                val a = JSONArray(file(ctx, ed).readText())
                Array(a.length()) { a.getString(it) }
            }
        }
        return arr.getOrElse(idx) { "" }
    }

    fun surahs(ctx: Context): List<Surah> {
        surahCache?.let { return it }
        val a = JSONArray(metaFile(ctx).readText())
        var start = 0
        val list = (0 until a.length()).map {
            val o = a.getJSONObject(it)
            val s = Surah(o.getInt("number"), o.getString("name"), o.getString("englishName"), o.getInt("count"), start)
            start += s.count
            s
        }
        surahCache = list
        return list
    }

    fun locate(ctx: Context, idx: Int): Loc {
        val s = surahs(ctx).last { it.start <= idx }
        return Loc(s, idx - s.start + 1)
    }

    private fun writeAtomic(f: File, content: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }
}

fun httpGet(url: String): String {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 15000
    c.readTimeout = 60000
    try {
        if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
        return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    } finally {
        c.disconnect()
    }
}

/* ---------------------------------------------------------------- Lange verzen opsplitsen */

object Splitter {
    private fun words(t: String) = t.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    fun partCount(arabic: String, maxWords: Int): Int =
        max(1, ceil(words(arabic).size / maxWords.toDouble()).toInt())

    fun part(text: String, parts: Int, k: Int): String {
        val w = words(text)
        if (parts <= 1 || w.isEmpty()) return text
        val size = ceil(w.size / parts.toDouble()).toInt()
        val from = min(k * size, w.size)
        val to = min(from + size, w.size)
        val body = w.subList(from, to).joinToString(" ")
        return (if (k > 0) "… " else "") + body + (if (k < parts - 1) " …" else "")
    }
}

/* ---------------------------------------------------------------- Voortgang */

object Progress {
    fun partsFor(ctx: Context, prefs: Prefs, idx: Int) =
        Splitter.partCount(QuranData.text(ctx, Qiraat.ARABIC_EDITION, idx), prefs.maxWords)

    /** Zet de wijzer op het volgende deel / vers. Na 6236 verzen: khatm +1, opnieuw beginnen. */
    fun advance(ctx: Context, prefs: Prefs) {
        val idx = prefs.nextIndex
        val part = prefs.nextPart
        if (part + 1 < partsFor(ctx, prefs, idx)) {
            prefs.nextPart = part + 1
        } else {
            prefs.nextPart = 0
            if (idx + 1 >= QuranData.TOTAL) {
                prefs.nextIndex = 0
                prefs.khatm = prefs.khatm + 1
            } else prefs.nextIndex = idx + 1
        }
    }
}

/* ---------------------------------------------------------------- Audio-cache */

object AudioCache {
    private val exec = Executors.newSingleThreadExecutor()

    fun fileFor(ctx: Context, url: String): File {
        val dir = File(ctx.cacheDir, "audio").apply { mkdirs() }
        return File(dir, url.substringAfter("/data/").replace('/', '_'))
    }

    fun prefetch(ctx: Context, url: String) {
        val app = ctx.applicationContext
        exec.execute {
            val f = fileFor(app, url)
            if (f.exists() && f.length() > 0) return@execute
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 60000
                if (c.responseCode in 200..299) {
                    val tmp = File(f.parentFile, f.name + ".part")
                    c.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
                    tmp.renameTo(f)
                }
                c.disconnect()
            } catch (_: Exception) {
            }
            trim(app)
        }
    }

    private fun trim(ctx: Context) {
        val files = File(ctx.cacheDir, "audio").listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(40).forEach { it.delete() }
    }
}

/* ---------------------------------------------------------------- Logboek (voor probleemoplossing) */

object DebugLog {
    private const val KEY = "log"

    fun add(ctx: Context, msg: String) {
        val p = ctx.applicationContext.getSharedPreferences("koranvers_log", Context.MODE_PRIVATE)
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())
        val lines = (p.getString(KEY, "") ?: "").lines().filter { it.isNotBlank() }.takeLast(14)
        p.edit().putString(KEY, (lines + "$time  $msg").joinToString("\n")).apply()
    }

    fun read(ctx: Context): String =
        ctx.applicationContext.getSharedPreferences("koranvers_log", Context.MODE_PRIVATE)
            .getString(KEY, "") ?: ""
}
