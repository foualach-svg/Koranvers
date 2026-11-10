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
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, f)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Prefs(this).enabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun onScreenEvent(action: String?) {
        val prefs = Prefs(this)
        if (!prefs.enabled) return
        // Op vergrendelscherm: reageren op 'scherm aan'. Anders: pas na ontgrendelen.
        val wanted = if (prefs.onLockscreen) Intent.ACTION_SCREEN_ON else Intent.ACTION_USER_PRESENT
        if (action != wanted) return
        // Niet storen tijdens (inkomende) oproepen.
        val am = getSystemService(AudioManager::class.java)
        if (am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION ||
            am.mode == AudioManager.MODE_RINGTONE
        ) return
        if (System.currentTimeMillis() - prefs.lastShown < prefs.minIntervalSec * 1000L) return
        launchVerse(this)
    }

    companion object {
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

/** Opent het versscherm. Zonder 'weergeven over andere apps' valt het terug op een melding. */
fun launchVerse(ctx: Context) {
    val i = Intent(ctx, VerseActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    if (Settings.canDrawOverlays(ctx)) {
        try {
            ctx.startActivity(i); return
        } catch (_: Exception) {
        }
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
            .build()
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
