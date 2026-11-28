package com.andserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.io.File

/**
 * Serviço de primeiro plano do Android (Foreground Service) do AndServer.
 * Gerencia o ciclo de vida da execução em background, notificação persistente,
 * descoberta mDNS / NSD na rede local, gerenciamento inteligente de energia
 * e particionamento de armazenamento (Interno vs Externo/SD Card).
 */
class ServerService : Service() {
    private val binder = LocalBinder()
    @Volatile private var server: LanHttpServer? = null
    @Volatile var activePort: Int? = null
        private set
    @Volatile private var generation = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private var nsdManager: NsdManager? = null
    private var nsdListener: NsdManager.RegistrationListener? = null
    val powerManager by lazy { DevicePowerManager(this) }

    fun getStorageRoot(location: String): File {
        val base = if (location == "external") {
            getExternalFilesDir(null) ?: filesDir
        } else {
            filesDir
        }
        return File(base, "hosted").apply { mkdirs() }
    }

    fun snapshot(): ServerSnapshot {
        val prefs = getSharedPreferences("andserver", MODE_PRIVATE)
        val loc = prefs.getString("storage_location", "internal") ?: "internal"
        return server?.snapshot() ?: ServerSnapshot(
            false, null, 0, 0,
            getStorageRoot(loc).listFiles()?.count { it.isFile } ?: 0,
            0, emptyList(), "web", 0, 0,
            prefs.getString("network_scope", "local") ?: "local",
            null,
            storageLocation = loc,
            powerProfile = powerManager.getPowerProfileLabel()
        )
    }

    inner class LocalBinder : Binder() {
        fun service() = this@ServerService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServer()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_START -> {
                val loc = intent.getStringExtra(EXTRA_STORAGE_LOCATION) ?: "internal"
                launchServer(ServerConfig(
                    intent.getIntExtra(EXTRA_PORT, DEFAULT_TLS_PORT).coerceIn(1024, 65535),
                    intent.getStringExtra(EXTRA_TYPE) ?: "web",
                    intent.getStringArrayListExtra(EXTRA_FILTERS)?.toSet() ?: setOf("all"),
                    intent.getIntExtra(EXTRA_WORKERS, powerManager.cpuCores.coerceIn(2, 16)).coerceIn(2, 32),
                    intent.getStringExtra(EXTRA_PASSWORD).orEmpty(),
                    intent.getStringExtra(EXTRA_NETWORK_SCOPE) ?: "local",
                    storageLocation = loc
                ))
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopServer()
        unregisterDiscovery()
        powerManager.releaseAll()
        super.onDestroy()
    }

    private fun stopServer() {
        generation++
        server?.stop()
        server = null
        activePort = null
        unregisterDiscovery()
        powerManager.releaseAll()
    }

    private fun launchServer(config: ServerConfig) {
        val settings = getSharedPreferences("andserver", MODE_PRIVATE)
        settings.edit()
            .putInt("port", config.port)
            .putString("type", config.type)
            .putStringSet("filters", config.filters)
            .putInt("workers", config.workers)
            .putString("password", config.password)
            .putString("network_scope", config.networkScope)
            .putString("storage_location", config.storageLocation)
            .apply()

        startForeground(NOTIFICATION_ID, notification(config.port, config.networkScope, config.storageLocation))
        server?.stop()
        val ownGeneration = ++generation
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        } catch (_: Exception) { "1.0" }

        val root = getStorageRoot(config.storageLocation)
        SiteTemplateManager.ensureSiteTemplate(root)

        val next = LanHttpServer(
            config.port,
            root,
            config.type,
            config.filters,
            config.workers,
            config.password,
            config.networkScope,
            TlsIdentity.sslContext(),
            TlsIdentity.certificatePem(),
            appVersion = version,
            storageLocation = config.storageLocation,
            powerManager = powerManager,
            onConfigure = { updated ->
                mainHandler.postDelayed({
                    if (generation == ownGeneration) launchServer(updated)
                }, 300)
            },
            onExit = {
                if (generation == ownGeneration) {
                    server = null
                    activePort = null
                    stopSelf()
                }
            }
        )
        server = next
        activePort = config.port
        next.start()
        advertise(config.port)
    }

    private fun advertise(port: Int) {
        unregisterDiscovery()
        try {
            val manager = (getSystemService(Context.NSD_SERVICE) as? NsdManager) ?: return
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) { }
                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) { nsdListener = null }
                override fun onServiceUnregistered(info: NsdServiceInfo) { }
                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) { }
            }
            val info = NsdServiceInfo().apply {
                serviceName = "AndServer"
                serviceType = "_https._tcp."
                setPort(port)
            }
            nsdManager = manager
            nsdListener = listener
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {
            nsdListener = null
        }
    }

    private fun unregisterDiscovery() {
        val listener = nsdListener ?: return
        try { nsdManager?.unregisterService(listener) } catch (_: Exception) { }
        nsdListener = null
    }

    private fun notification(port: Int, scope: String, storageLocation: String): Notification {
        val channelId = "andserver"
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(channelId, "Servidor AndServer", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId) else Notification.Builder(this)
        val flags = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), flags)
        val stop = PendingIntent.getService(this, 2, Intent(this, ServerService::class.java).setAction(ACTION_STOP), flags)
        val scopeLabel = if (scope == "internet") "Internet (Público)" else "Rede Local (LAN)"
        val storageLabel = if (storageLocation == "external") "SD/Externo" else "Interno"
        val powerLabel = if (powerManager.isCharging) "⚡ Máx Desempenho" else "🔋 Bateria (${powerManager.batteryPercent}%)"

        return builder.setContentTitle("AndServer Ativo · $scopeLabel")
            .setContentText("HTTPS na porta $port · $storageLabel · $powerLabel")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "Parar", stop)
            .build()
    }

    companion object {
        const val ACTION_START = "com.andserver.START"
        const val ACTION_STOP = "com.andserver.STOP"
        const val EXTRA_PORT = "port"
        const val EXTRA_TYPE = "server_type"
        const val EXTRA_FILTERS = "file_filters"
        const val EXTRA_WORKERS = "workers"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_NETWORK_SCOPE = "network_scope"
        const val EXTRA_STORAGE_LOCATION = "storage_location"
        const val DEFAULT_TLS_PORT = 8443
        const val NOTIFICATION_ID = 41
    }
}
