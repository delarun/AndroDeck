package org.evileat.deck

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/** Связь с компьютером одна на весь процесс: ею пользуются и экран, и фоновая служба */
class DeckApplication : Application() {
    lateinit var client: DeckClient
        private set

    /** Deck сейчас на экране — уведомления показывает он сам, баннером */
    @Volatile var visible = false

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("deck", MODE_PRIVATE)
        client = DeckClient(
            prefs.getString("url", "http://192.168.1.10:8765")!!,
            prefs.getString("token", "")!!,
        )
        client.start()
    }
}

/**
 * Держит процесс и соединение живыми, когда Deck не на экране, и показывает уведомления с компьютера
 * системными — со звуком и кнопкой, которая открывает ссылку на компьютере.
 */
class DeckService : Service() {
    private val app get() = application as DeckApplication
    private val manager get() = getSystemService(NotificationManager::class.java)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        manager.createNotificationChannel(
            NotificationChannel(LINK, "Связь с компьютером", NotificationManager.IMPORTANCE_MIN)
        )
        manager.createNotificationChannel(
            NotificationChannel(ALERTS, "Уведомления с компьютера", NotificationManager.IMPORTANCE_HIGH)
        )
        val ongoing = NotificationCompat.Builder(this, LINK)
            .setSmallIcon(R.drawable.ic_stat_deck)
            .setContentTitle("Deck на связи с компьютером")
            .setContentIntent(openApp())
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        // система может не дать стать foreground (перезапуск службы из фона на Android 12+) — тогда живём без неё
        if (runCatching { ServiceCompat.startForeground(this, ONGOING_ID, ongoing, type) }.isFailure) {
            stopSelf()
            return
        }
        app.client.background = ::show
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_OPEN) {
            intent.getStringExtra(EXTRA_ID)?.let { id ->
                app.client.openNote(id)
                manager.cancel(id, NOTE_ID)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        app.client.background = null
        super.onDestroy()
    }

    /** true — уведомление показано системой, баннер в приложении не нужен */
    private fun show(n: Note, ttlMs: Long): Boolean {
        if (app.visible) return false
        val b = NotificationCompat.Builder(this, ALERTS)
            .setSmallIcon(R.drawable.ic_stat_deck)
            .setContentTitle(n.title)
            .setContentText(n.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.body))
            .setSubText(n.app.ifEmpty { null })
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (n.urgent) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .setTimeoutAfter(ttlMs)
        if (n.action.isNotEmpty()) {
            val open = Intent(this, DeckService::class.java).setAction(ACTION_OPEN).putExtra(EXTRA_ID, n.id)
            b.addAction(
                0, n.action,
                PendingIntent.getService(this, n.id.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
            )
        }
        manager.notify(n.id, NOTE_ID, b.build())
        return true
    }

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val LINK = "link"
        private const val ALERTS = "alerts"
        private const val ONGOING_ID = 1
        private const val NOTE_ID = 2
        private const val ACTION_OPEN = "org.evileat.deck.OPEN_NOTE"
        private const val EXTRA_ID = "id"

        fun start(context: Context) {
            androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, DeckService::class.java))
        }
    }
}
