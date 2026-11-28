package com.andserver

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * Gerenciador inteligente de energia e desempenho (Battery & Hardware Aware).
 * Otimiza o uso de hardware, memória e CPU conforme o estado da bateria:
 * - Carregador conectado: Libera potência máxima dos núcleos de CPU e Wi-Fi High Performance.
 * - Na bateria: Ajusta o pool de threads e gerencia Wi-Fi lock para permitir modo Doze/Repouso.
 * - Bateria fraca (< 15%): Limita concorrência e reduz keep-alive para evitar esgotamento rápido.
 */
class DevicePowerManager(private val context: Context) {

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private var wifiLock: WifiManager.WifiLock? = null
    private val activeTransfers = AtomicInteger(0)

    val cpuCores: Int
        get() = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    val isCharging: Boolean
        get() {
            return try {
                val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                val batteryStatus = context.registerReceiver(null, filter)
                val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            } catch (_: Exception) {
                false
            }
        }

    val batteryPercent: Int
        get() {
            return try {
                val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                val batteryStatus = context.registerReceiver(null, filter)
                val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                if (level >= 0 && scale > 0) (level * 100) / scale else 100
            } catch (_: Exception) {
                100
            }
        }

    val isPowerSaveMode: Boolean
        get() = powerManager?.isPowerSaveMode == true

    /**
     * Retorna a descrição amigável do perfil energético ativo.
     */
    fun getPowerProfileLabel(): String {
        return when {
            isCharging -> "⚡ Conectado à energia · Potência máxima liberada"
            isPowerSaveMode -> "🔋 Economia de bateria ativa · Operação otimizada"
            batteryPercent <= 20 -> "⚠️ Bateria fraca ($batteryPercent%) · Modo ultra-eficiente"
            else -> "🌿 Bateria ($batteryPercent%) · Equilíbrio inteligente"
        }
    }

    /**
     * Calcula o limite ideal de threads para o hardware atual sem sobreaquecer o aparelho.
     */
    fun getOptimalWorkers(configuredWorkers: Int): Int {
        return when {
            isCharging -> maxOf(cpuCores, configuredWorkers).coerceIn(2, 32)
            batteryPercent <= 15 && !isCharging -> 2.coerceAtMost(configuredWorkers)
            batteryPercent <= 30 && !isCharging -> minOf(cpuCores, configuredWorkers).coerceAtLeast(2)
            else -> configuredWorkers.coerceIn(2, 16)
        }
    }

    /**
     * Registra início de transferência de arquivo para manter Wi-Fi em alto desempenho.
     */
    fun onTransferStart() {
        if (activeTransfers.incrementAndGet() == 1) {
            acquireWifiLock()
        }
    }

    /**
     * Registra fim de transferência de arquivo e desativa Wi-Fi Lock quando ocioso.
     */
    fun onTransferEnd() {
        if (activeTransfers.decrementAndGet() <= 0) {
            activeTransfers.set(0)
            releaseWifiLock()
        }
    }

    private synchronized fun acquireWifiLock() {
        if (wifiLock == null && wifiManager != null) {
            try {
                val mode = if (Build.VERSION.SDK_INT >= 29) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wifiManager.createWifiLock(mode, "AndServer:HighPerfLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (_: Exception) { }
        }
    }

    private synchronized fun releaseWifiLock() {
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        } catch (_: Exception) { }
        wifiLock = null
    }

    fun releaseAll() {
        activeTransfers.set(0)
        releaseWifiLock()
    }
}
