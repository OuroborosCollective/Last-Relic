package com.example.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Periodically monitors frame rate dips, JVM/WebGL memory usage, and combat scene metrics
 * (dungeon floor, player level, entity count, active particles, and active bullets)
 * to help satisfy Google Play Android Vitals performance standards.
 * Saves structured snapshots whenever a performance dip occurs and exposes them for
 * the Pause menu 'View Logs' modal and live debug overlay.
 */
class GameMonitor(
  private val fpsDipThreshold: Double = DEFAULT_FPS_DIP_THRESHOLD,
  private val frameTimeSpikeMsThreshold: Double = DEFAULT_FRAME_SPIKE_MS_THRESHOLD,
  private val memoryWarningRatioThreshold: Double = DEFAULT_MEMORY_WARNING_RATIO,
  private val maxLogEntries: Int = 40,
  private val logToAndroidLogcat: Boolean = true
) {

  companion object {
    const val TAG = "GameMonitor"
    const val DEFAULT_FPS_DIP_THRESHOLD = 45.0
    const val DEFAULT_FRAME_SPIKE_MS_THRESHOLD = 28.0
    const val DEFAULT_MEMORY_WARNING_RATIO = 0.80
    private const val BYTES_PER_MB = 1024.0 * 1024.0
  }

  data class DipSnapshot(
    val timestamp: String,
    val fps: Int,
    val frameTimeMs: Double,
    val dungeonFloor: Int,
    val playerLevel: Int,
    val entityCount: Int,
    val activeParticles: Int,
    val activeBullets: Int,
    val usedMemoryMb: Double,
    val webGlMemoryMb: Double
  ) {
    fun formatLogLine(): String {
      return "[$timestamp] ${fps}FPS (${String.format(Locale.US, "%.1f", frameTimeMs)}ms) · Floor $dungeonFloor · Lv $playerLevel · Entities $entityCount · Bullets $activeBullets · Particles $activeParticles · Mem ${String.format(Locale.US, "%.1f", usedMemoryMb)}MB"
    }
  }

  data class PerformanceSnapshot(
    val currentFps: Int = 60,
    val avgFrameTimeMs: Double = 16.6,
    val usedMemoryMb: Double = 0.0,
    val maxMemoryMb: Double = 0.0,
    val memoryUsagePercent: Int = 0,
    val webGlMemoryMb: Double = 0.0,
    val dungeonFloor: Int = 1,
    val playerLevel: Int = 1,
    val entityCount: Int = 0,
    val activeParticles: Int = 0,
    val activeBullets: Int = 0,
    val frameDipCount: Int = 0,
    val memoryWarningCount: Int = 0,
    val isFrameRateDip: Boolean = false,
    val isMemoryPressure: Boolean = false,
    val dipSnapshots: List<DipSnapshot> = emptyList(),
    val recentFindings: List<String> = emptyList()
  )

  private val _snapshot = MutableStateFlow(PerformanceSnapshot())
  val snapshot: StateFlow<PerformanceSnapshot> = _snapshot.asStateFlow()

  private val findingsBuffer = ArrayDeque<String>()
  private val dipSnapshotsBuffer = ArrayDeque<DipSnapshot>()
  private var totalFrameDipCount = 0
  private var totalMemoryWarningCount = 0

  /**
   * Evaluates a periodic performance sample from the game loop and JVM runtime,
   * detecting frame rate dips and high memory usage, saving a DipSnapshot with
   * dungeonFloor, playerLevel, entityCount, activeParticles, and activeBullets,
   * and logging findings to console/overlay.
   */
  @Synchronized
  fun recordSample(
    fps: Double,
    avgFrameTimeMs: Double = if (fps > 0.0) 1000.0 / fps else 100.0,
    webGlHeapMb: Double = 0.0,
    dungeonFloor: Int = _snapshot.value.dungeonFloor,
    playerLevel: Int = _snapshot.value.playerLevel,
    entityCount: Int = _snapshot.value.entityCount,
    activeParticles: Int = _snapshot.value.activeParticles,
    activeBullets: Int = _snapshot.value.activeBullets,
    overrideUsedJvmMb: Double? = null,
    overrideMaxJvmMb: Double? = null
  ): PerformanceSnapshot {
    val runtime = Runtime.getRuntime()
    val usedJvmMb = overrideUsedJvmMb
      ?: ((runtime.totalMemory() - runtime.freeMemory()).toDouble() / BYTES_PER_MB)
    val maxJvmMb = (overrideMaxJvmMb ?: (runtime.maxMemory().toDouble() / BYTES_PER_MB))
      .coerceAtLeast(1.0)

    val safeFps = fps.coerceIn(0.0, 240.0)
    val safeFrameMs = avgFrameTimeMs.coerceIn(1.0, 5000.0)
    val memRatio = (usedJvmMb / maxJvmMb).coerceIn(0.0, 1.0)
    val memPercent = (memRatio * 100.0).roundToInt()

    val safeFloor = dungeonFloor.coerceAtLeast(1)
    val safeLevel = playerLevel.coerceAtLeast(1)
    val safeEntities = entityCount.coerceAtLeast(0)
    val safeParticles = activeParticles.coerceAtLeast(0)
    val safeBullets = activeBullets.coerceAtLeast(0)

    val isDip = safeFps < fpsDipThreshold || safeFrameMs > frameTimeSpikeMsThreshold
    val isMemPressure = memRatio >= memoryWarningRatioThreshold

    if (isDip) {
      totalFrameDipCount++
      val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
      val dipSnap = DipSnapshot(
        timestamp = ts,
        fps = safeFps.roundToInt(),
        frameTimeMs = (safeFrameMs * 10.0).roundToInt() / 10.0,
        dungeonFloor = safeFloor,
        playerLevel = safeLevel,
        entityCount = safeEntities,
        activeParticles = safeParticles,
        activeBullets = safeBullets,
        usedMemoryMb = (usedJvmMb * 10.0).roundToInt() / 10.0,
        webGlMemoryMb = (webGlHeapMb.coerceAtLeast(0.0) * 10.0).roundToInt() / 10.0
      )
      if (dipSnapshotsBuffer.size >= maxLogEntries) {
        dipSnapshotsBuffer.removeFirst()
      }
      dipSnapshotsBuffer.addLast(dipSnap)

      val msg = "Frame dip detected: ${safeFps.roundToInt()} FPS (${String.format(Locale.US, "%.1f", safeFrameMs)} ms/frame) | Floor $safeFloor, Lv $safeLevel, Entities $safeEntities, Bullets $safeBullets, Particles $safeParticles (dip #$totalFrameDipCount)"
      appendFinding(msg, isWarning = true)
    }

    if (isMemPressure) {
      totalMemoryWarningCount++
      val msg = "Memory pressure: ${String.format(Locale.US, "%.1f", usedJvmMb)}/${String.format(Locale.US, "%.1f", maxJvmMb)} MB ($memPercent%, warn #$totalMemoryWarningCount) | Floor $safeFloor, Entities $safeEntities"
      appendFinding(msg, isWarning = true)
    }

    if (!isDip && !isMemPressure && findingsBuffer.isEmpty()) {
      appendFinding(
        "Healthy: ${safeFps.roundToInt()} FPS (${String.format(Locale.US, "%.1f", safeFrameMs)} ms) · Floor $safeFloor · Entities $safeEntities · Bullets $safeBullets · Particles $safeParticles",
        isWarning = false
      )
    }

    val updated = PerformanceSnapshot(
      currentFps = safeFps.roundToInt(),
      avgFrameTimeMs = (safeFrameMs * 10.0).roundToInt() / 10.0,
      usedMemoryMb = (usedJvmMb * 10.0).roundToInt() / 10.0,
      maxMemoryMb = (maxJvmMb * 10.0).roundToInt() / 10.0,
      memoryUsagePercent = memPercent,
      webGlMemoryMb = (webGlHeapMb.coerceAtLeast(0.0) * 10.0).roundToInt() / 10.0,
      dungeonFloor = safeFloor,
      playerLevel = safeLevel,
      entityCount = safeEntities,
      activeParticles = safeParticles,
      activeBullets = safeBullets,
      frameDipCount = totalFrameDipCount,
      memoryWarningCount = totalMemoryWarningCount,
      isFrameRateDip = isDip,
      isMemoryPressure = isMemPressure,
      dipSnapshots = dipSnapshotsBuffer.toList(),
      recentFindings = findingsBuffer.toList()
    )
    _snapshot.value = updated
    return updated
  }

  /**
   * Performs a periodic JVM memory and health check even when no WebGL sample was just pushed.
   */
  fun checkJvmPerformance(): PerformanceSnapshot {
    val current = _snapshot.value
    return recordSample(
      fps = current.currentFps.toDouble().coerceAtLeast(1.0),
      avgFrameTimeMs = current.avgFrameTimeMs,
      webGlHeapMb = current.webGlMemoryMb,
      dungeonFloor = current.dungeonFloor,
      playerLevel = current.playerLevel,
      entityCount = current.entityCount,
      activeParticles = current.activeParticles,
      activeBullets = current.activeBullets
    )
  }

  private fun appendFinding(entry: String, isWarning: Boolean) {
    if (findingsBuffer.size >= maxLogEntries) {
      findingsBuffer.removeFirst()
    }
    findingsBuffer.addLast(entry)
    if (logToAndroidLogcat) {
      try {
        if (isWarning) {
          Log.w(TAG, entry)
        } else {
          Log.d(TAG, entry)
        }
      } catch (_: Throwable) {
        // Safe fallback in plain JVM unit tests where android.util.Log may not be mocked
      }
    }
  }

  @Synchronized
  fun clearFindings() {
    findingsBuffer.clear()
    dipSnapshotsBuffer.clear()
    totalFrameDipCount = 0
    totalMemoryWarningCount = 0
    _snapshot.value = PerformanceSnapshot()
  }
}
