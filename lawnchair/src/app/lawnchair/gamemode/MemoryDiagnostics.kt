package app.lawnchair.gamemode

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Debug
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Amostras leves de memória para depurar encerramentos por pressão de RAM.
 * Só funciona em builds debuggable e grava no registro exportável do modo jogo.
 */
object MemoryDiagnostics {

    private val installed = AtomicBoolean(false)

    fun install(context: Context) {
        val app = context.applicationContext
        if (app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        if (!installed.compareAndSet(false, true)) return

        (app as? Application)?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: android.os.Bundle?) {
                captureIfGameActivity(activity, "onCreate")
            }

            override fun onActivityStarted(activity: Activity) = Unit

            override fun onActivityResumed(activity: Activity) {
                captureIfGameActivity(activity, "onResume")
            }

            override fun onActivityPaused(activity: Activity) {
                captureIfGameActivity(activity, "onPause")
            }

            override fun onActivityStopped(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(activity: Activity, state: android.os.Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) {
                captureIfGameActivity(activity, "onDestroy")
            }
        })

        app.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            override fun onLowMemory() {
                capture(app, "pressão-de-memória:onLowMemory")
            }

            override fun onTrimMemory(level: Int) {
                capture(app, "pressão-de-memória:trim-$level")
            }
        })

        capture(app, "modo-jogo:diagnóstico-inicializado")
    }

    private fun captureIfGameActivity(activity: Activity, event: String) {
        val name = activity.javaClass.simpleName
        if (name == "GameModeActivity" || name == "GameSessionActivity") {
            capture(activity.applicationContext, "$name:$event")
        }
    }

    fun capture(context: Context, stage: String) {
        val app = context.applicationContext
        if (app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return

        Thread({
            runCatching {
                val runtime = Runtime.getRuntime()
                val processMemory = app.getSystemService(ActivityManager::class.java)
                    ?.getProcessMemoryInfo(intArrayOf(Process.myPid()))
                    ?.firstOrNull()
                val javaUsed = runtime.totalMemory() - runtime.freeMemory()
                val nativeUsed = Debug.getNativeHeapAllocatedSize()
                val coverCount = RomMCovers.cacheEntryCount()
                val coverBytes = RomMCovers.estimatedMemoryBytes()
                val artCount = GameArt.cacheEntryCount()
                val artBytes = GameArt.cacheBytes()

                GameLog.log(
                    "memória",
                    "etapa=$stage " +
                        "PSS=${processMemory?.totalPss ?: -1}KB " +
                        "DalvikPSS=${processMemory?.dalvikPss ?: -1}KB " +
                        "NativePSS=${processMemory?.nativePss ?: -1}KB " +
                        "heapJava=${javaUsed / 1024}KB/${runtime.maxMemory() / 1024}KB " +
                        "heapNativo=${nativeUsed / 1024}KB " +
                        "RomMCovers=$coverCount/${coverBytes / 1024}KB " +
                        "GameArt=$artCount/${artBytes / 1024}KB",
                )
            }.onFailure { GameLog.error("memória", "falha ao coletar amostra ($stage)", it) }
        }, "tandem-memory-diagnostics").apply {
            isDaemon = true
            start()
        }
    }
}
