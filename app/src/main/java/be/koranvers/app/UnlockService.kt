package be.koranvers.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings

const val CHANNEL_SERVICE = "service"
const val CHANNEL_VERSE = "verse"
const val NOTIF_SERVICE = 1
const val NOTIF_VERSE = 2

/** Luistert zolang de app aan staat naar 'scherm aan' / 'ontgrendeld'. */
class UnlockService : Service() {

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = onScreenEvent(i.action)
    }

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
        val n = Notification.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("KoranVers staat aan")
            .setContentText("Bij elk ontgrendelen verschijnt het volgende vers")
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_SERVICE, n)
        }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, f)
        running = true
        DebugLog.add(this, "Achtergronddienst gestart")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Prefs(this).enabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        DebugLog.add(this, "Achtergronddienst gestopt")
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun onScreenEvent(action: String?) {
        if (action == Intent.ACTION_SCREEN_OFF) {
            VerseOverlay.hide(this); return
        }
        val prefs = Prefs(this)
        if (!prefs.enabled) return
        // Niet storen tijdens (inkomende) oproepen.
        val am = getSystemService(AudioManager::class.java)
        if (am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION ||
            am.mode == AudioManager.MODE_RINGTONE
        ) { DebugLog.add(this, "Overgeslagen: gesprek bezig"); return }

        val now = System.currentTimeMillis()
        val tooSoon = now - prefs.lastShown < prefs.minIntervalSec * 1000L

        if (action == Intent.ACTION_SCREEN_ON && prefs.onLockscreen) {
            DebugLog.add(this, "Scherm aan")
            if (tooSoon) return
            launchVerse(this)
            return
        }
        if (action == Intent.ACTION_USER_PRESENT) {
            DebugLog.add(this, "Ontgrendeld")
            // Op vergrendelscherm al getoond (minder dan 20 s geleden)? Dan niets meer doen.
            if (prefs.onLockscreen && now - prefs.lastShown < 20_000L) return
            if (tooSoon) return
            showAfterUnlock(this)
        }
    }

    companion object {
        @Volatile var running = false

        fun sync(ctx: Context) {
            val i = Intent(ctx, UnlockService::class.java)
            if (Prefs(ctx).enabled) ctx.startForegroundService(i) else ctx.stopService(i)
        }
    }
}

fun createChannels(ctx: Context) {
    val nm = ctx.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(
        NotificationChannel(CHANNEL_SERVICE, "Achtergronddienst", NotificationManager.IMPORTANCE_MIN)
    )
    nm.createNotificationChannel(
        NotificationChannel(CHANNEL_VERSE, "Vers bij ontgrendelen", NotificationManager.IMPORTANCE_HIGH)
    )
}

/** Na ontgrendelen: vers als overlay over het scherm (meest betrouwbaar). */
fun showAfterUnlock(ctx: Context) {
    if (Settings.canDrawOverlays(ctx)) {
        try {
            VerseOverlay.show(ctx)
            DebugLog.add(ctx, "Overlay getoond")
            return
        } catch (e: Exception) {
            DebugLog.add(ctx, "Overlay mislukt: ${e.javaClass.simpleName} ${e.message}")
        }
    } else DebugLog.add(ctx, "Geen toestemming 'weergeven over andere apps'")
    launchVerse(ctx)
}

/** Opent het versscherm als activity (ook boven het vergrendelscherm). Valt terug op een melding. */
fun launchVerse(ctx: Context) {
    val i = Intent(ctx, VerseActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    try {
        ctx.startActivity(i)
        DebugLog.add(ctx, "Versscherm gestart")
    } catch (e: Exception) {
        DebugLog.add(ctx, "Versscherm mislukt: ${e.javaClass.simpleName}")
    }
    try {
        createChannels(ctx)
        val pi = PendingIntent.getActivity(
            ctx, 1, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(ctx, CHANNEL_VERSE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Uw volgende vers")
            .setContentText("Tik om het vers te lezen en te beluisteren")
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000L)
            .build()
        // Melding als vangnet; wordt meteen weggehaald zodra het vers getoond is.
        ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_VERSE, n)
    } catch (_: Exception) {
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (Prefs(c).enabled) {
            try { UnlockService.sync(c) } catch (_: Exception) {}
        }
    }
}
