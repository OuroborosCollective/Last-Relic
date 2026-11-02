package com.example.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Periodically monitors frame rate dips, JVM/WebGL memory usage, combat scene metrics
 * (dungeon floor, player level, entity count, active particles, and active bullets),
 * and houses the central [QuestManager] for Daily and Village quest tracking and validation.
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
    const val LOG_RETENTION_DAYS = 7L
    const val LOG_RETENTION_MS = LOG_RETENTION_DAYS * 24L * 60L * 60L * 1000L
    const val ONE_DAY_MS = 24L * 60L * 60L * 1000L
    private const val BYTES_PER_MB = 1024.0 * 1024.0

    val VALID_VILLAGE_TARGETS = setOf("husk", "drone", "mage", "archer", "brute")
    val VALID_DAILY_TYPES = setOf(
      "daily_kill_all",
      "daily_boss",
      "daily_chests",
      "daily_crates",
      "daily_skill",
      "daily_secrets"
    )
  }

  data class VillageQuest(
    val id: String,
    val type: String = "kill",
    val target: String,
    val amt: Int,
    val reward: Int,
    val xp: Int,
    val shards: Int,
    val epic: Boolean = false,
    val killed: Int = 0,
    val done: Boolean = false,
    val pct: Int = 0,
    val startPct: Int = 0
  )

  data class DailyQuestTemplate(
    val id: String,
    val key: String,
    val type: String,
    val amt: Int,
    val reward: Int,
    val xp: Int,
    val shards: Int,
    val tierReward: String,
    val trust: Int = 0,
    val icon: String
  )

  data class DailyQuest(
    val id: String,
    val key: String,
    val type: String,
    val amt: Int,
    val reward: Int,
    val xp: Int,
    val shards: Int,
    val tierReward: String = "epic_item",
    val trust: Int = 0,
    val icon: String = "⚔️",
    val progress: Int = 0,
    val done: Boolean = false,
    val pct: Int = 0,
    val startPct: Int = 0
  )

  data class QuestStateSnapshot(
    val villageQuests: List<VillageQuest> = emptyList(),
    val dailyQuests: List<DailyQuest> = emptyList(),
    val dailyResetTimeMs: Long = 0L,
    val totalCompletedVillageQuests: Int = 0,
    val totalCompletedDailyQuests: Int = 0
  )

  data class QuestProgressUpdateResult(
    val updatedVillageQuests: List<VillageQuest>,
    val completedVillageQuests: List<VillageQuest>,
    val updatedDailyQuests: List<DailyQuest>,
    val completedDailyQuests: List<DailyQuest>,
    val changed: Boolean
  )

  /**
   * Central QuestManager within [GameMonitor] that decouples Daily and Village quest
   * generation, validation, and progress tracking from UI rendering functions.
   */
  inner class QuestManager {

    private val dailyTemplates = listOf(
      DailyQuestTemplate("purge", "daily_q_purge", "daily_kill_all", 25, 250, 360, 8, "epic_item", 0, "⚔️"),
      DailyQuestTemplate("boss", "daily_q_boss", "daily_boss", 2, 360, 520, 14, "legendary_item", 15, "👑"),
      DailyQuestTemplate("chests", "daily_q_chests", "daily_chests", 4, 220, 300, 7, "epic_item", 0, "🎁"),
      DailyQuestTemplate("crates", "daily_q_crates", "daily_crates", 18, 200, 280, 6, "epic_item", 0, "📦"),
      DailyQuestTemplate("skill", "daily_q_skill", "daily_skill", 16, 230, 310, 7, "epic_item", 0, "⚡"),
      DailyQuestTemplate("secrets", "daily_q_secrets", "daily_secrets", 3, 270, 390, 9, "epic_item", 10, "🔍")
    )

    private val _questState = MutableStateFlow(QuestStateSnapshot())
    val questState: StateFlow<QuestStateSnapshot> = _questState.asStateFlow()

    private val villageQuestsList = mutableListOf<VillageQuest>()
    private val dailyQuestsList = mutableListOf<DailyQuest>()
    private var completedVillageCount = 0
    private var completedDailyCount = 0

    /**
     * Validates and normalizes a [VillageQuest] to guarantee consistent bounds, completion state,
     * and percentage values independent of UI rendering.
     */
    fun validateVillageQuest(quest: VillageQuest, indexHint: Int = 0): VillageQuest {
      val safeTarget = quest.target.trim().lowercase().let {
        if (it in VALID_VILLAGE_TARGETS) it else "husk"
      }
      val safeAmt = quest.amt.coerceIn(1, 500)
      val safeKilled = quest.killed.coerceIn(0, safeAmt)
      val isDone = quest.done || safeKilled >= safeAmt
      val finalKilled = if (isDone) safeAmt else safeKilled
      val computedPct = ((finalKilled.toDouble() / safeAmt.toDouble()) * 100.0)
        .roundToInt()
        .coerceIn(0, 100)
      val safeStartPct = quest.startPct.coerceIn(0, computedPct)

      return quest.copy(
        id = quest.id.ifBlank { "vq_${indexHint}_$safeTarget" },
        type = "kill",
        target = safeTarget,
        amt = safeAmt,
        reward = quest.reward.coerceIn(1, 100_000),
        xp = quest.xp.coerceIn(1, 100_000),
        shards = quest.shards.coerceIn(1, 1_000),
        killed = finalKilled,
        done = isDone,
        pct = computedPct,
        startPct = safeStartPct
      )
    }

    /**
     * Validates and normalizes a [DailyQuest] to guarantee consistent bounds, completion state,
     * and percentage values independent of UI rendering.
     */
    fun validateDailyQuest(quest: DailyQuest): DailyQuest {
      val safeType = quest.type.trim().lowercase().let {
        if (it in VALID_DAILY_TYPES) it else "daily_kill_all"
      }
      val safeAmt = quest.amt.coerceIn(1, 1_000)
      val safeProgress = quest.progress.coerceIn(0, safeAmt)
      val isDone = quest.done || safeProgress >= safeAmt
      val finalProgress = if (isDone) safeAmt else safeProgress
      val computedPct = ((finalProgress.toDouble() / safeAmt.toDouble()) * 100.0)
        .roundToInt()
        .coerceIn(0, 100)
      val safeStartPct = quest.startPct.coerceIn(0, computedPct)
      val safeTierReward = if (quest.tierReward == "legendary_item") "legendary_item" else "epic_item"

      return quest.copy(
        id = quest.id.ifBlank { safeType.removePrefix("daily_") },
        key = quest.key.ifBlank { "daily_q_${safeType.removePrefix("daily_")}" },
        type = safeType,
        amt = safeAmt,
        reward = quest.reward.coerceIn(1, 250_000),
        xp = quest.xp.coerceIn(1, 250_000),
        shards = quest.shards.coerceIn(1, 5_000),
        tierReward = safeTierReward,
        trust = quest.trust.coerceIn(0, 500),
        progress = finalProgress,
        done = isDone,
        pct = computedPct,
        startPct = safeStartPct
      )
    }

    /**
     * Generates a fresh set of 3 floor-scaled Village Quests (2 standard + 1 epic bounty).
     */
    @Synchronized
    fun generateVillageQuests(floor: Int = 1): List<VillageQuest> {
      val safeFloor = floor.coerceAtLeast(1)
      val targets = listOf("husk", "drone", "mage")
      villageQuestsList.clear()

      targets.forEachIndexed { idx, targetKey ->
        val isEpic = idx == 2
        val amt = (if (idx == 0) 5 else if (idx == 1) 4 else 5) + safeFloor * (if (isEpic) 2 else 1)
        val rewardGold = (if (isEpic) 65 else 25) + safeFloor * (if (isEpic) 18 else 8) + idx * 10
        val rewardXp = (if (isEpic) 90 else 40) + safeFloor * (if (isEpic) 28 else 15) + idx * 15
        val rewardShards = (if (isEpic) 3 else 1) + (safeFloor / 2)

        villageQuestsList.add(
          validateVillageQuest(
            VillageQuest(
              id = "vq_${safeFloor}_${idx}_$targetKey",
              type = "kill",
              target = targetKey,
              amt = amt,
              reward = rewardGold,
              xp = rewardXp,
              shards = rewardShards,
              epic = isEpic,
              killed = 0,
              done = false,
              pct = 0,
              startPct = 0
            ),
            indexHint = idx
          )
        )
      }
      publishQuestState()
      return villageQuestsList.toList()
    }

    /**
     * Ensures valid Village Quests exist; generates a new set if none exist or all are completed.
     */
    @Synchronized
    fun ensureVillageQuests(floor: Int = 1): List<VillageQuest> {
      if (villageQuestsList.isEmpty() || villageQuestsList.all { it.done }) {
        return generateVillageQuests(floor)
      }
      val normalized = villageQuestsList.mapIndexed { idx, q ->
        validateVillageQuest(q, indexHint = idx)
      }
      villageQuestsList.clear()
      villageQuestsList.addAll(normalized)
      publishQuestState()
      return villageQuestsList.toList()
    }

    /**
     * Generates 3 Daily Quests scaled by [floor] and updates the 24h reset timestamp.
     */
    @Synchronized
    fun generateDailyQuests(
      floor: Int = 1,
      currentTimeMs: Long = System.currentTimeMillis(),
      nextResetTimeMs: Long = currentTimeMs + ONE_DAY_MS,
      deterministicOrder: Boolean = false
    ): List<DailyQuest> {
      val safeFloor = floor.coerceAtLeast(1)
      val safeReset = if (nextResetTimeMs > currentTimeMs) nextResetTimeMs else currentTimeMs + ONE_DAY_MS
      trackedDailyResetTime = safeReset

      val selectedTemplates = if (deterministicOrder) {
        dailyTemplates.take(3)
      } else {
        dailyTemplates.shuffled().take(3)
      }

      dailyQuestsList.clear()
      selectedTemplates.forEach { tpl ->
        dailyQuestsList.add(
          validateDailyQuest(
            DailyQuest(
              id = tpl.id,
              key = tpl.key,
              type = tpl.type,
              amt = tpl.amt,
              reward = tpl.reward + safeFloor * 15,
              xp = tpl.xp + safeFloor * 25,
              shards = tpl.shards + (safeFloor / 3),
              tierReward = tpl.tierReward,
              trust = tpl.trust,
              icon = tpl.icon,
              progress = 0,
              done = false,
              pct = 0,
              startPct = 0
            )
          )
        )
      }
      publishQuestState()
      return dailyQuestsList.toList()
    }

    /**
     * Ensures active Daily Quests exist and have not expired past the 24-hour cycle.
     */
    @Synchronized
    fun ensureDailyQuests(
      floor: Int = 1,
      currentTimeMs: Long = System.currentTimeMillis(),
      deterministicOrder: Boolean = false
    ): List<DailyQuest> {
      if (
        trackedDailyResetTime <= 0L ||
        currentTimeMs >= trackedDailyResetTime ||
        dailyQuestsList.isEmpty()
      ) {
        return generateDailyQuests(
          floor = floor,
          currentTimeMs = currentTimeMs,
          nextResetTimeMs = currentTimeMs + ONE_DAY_MS,
          deterministicOrder = deterministicOrder
        )
      }
      val normalized = dailyQuestsList.map { validateDailyQuest(it) }
      dailyQuestsList.clear()
      dailyQuestsList.addAll(normalized)
      publishQuestState()
      return dailyQuestsList.toList()
    }

    /**
     * Advances Village Quest progress for a specific [enemyType] kill outside UI rendering.
     */
    @Synchronized
    fun recordVillageKill(
      enemyType: String,
      amount: Int = 1,
      floor: Int = 1,
      timestampMs: Long = System.currentTimeMillis()
    ): QuestProgressUpdateResult {
      if (villageQuestsList.isEmpty()) {
        ensureVillageQuests(floor)
      }
      val safeInc = amount.coerceAtLeast(1)
      val normalizedTarget = enemyType.trim().lowercase()
      val newlyCompleted = mutableListOf<VillageQuest>()
      var anyChanged = false

      for (i in villageQuestsList.indices) {
        val current = villageQuestsList[i]
        if (!current.done && current.type == "kill" && current.target == normalizedTarget) {
          val nextKilled = (current.killed + safeInc).coerceAtMost(current.amt)
          val nowDone = nextKilled >= current.amt
          val updated = validateVillageQuest(
            current.copy(
              killed = nextKilled,
              done = nowDone,
              startPct = current.pct
            ),
            indexHint = i
          )
          villageQuestsList[i] = updated
          anyChanged = true
          if (nowDone) {
            completedVillageCount++
            newlyCompleted.add(updated)
            appendFinding(
              "Village quest completed: ${updated.amt}x ${updated.target} (+${updated.reward}G, +${updated.shards} Shards)",
              isWarning = false,
              timestampMs = timestampMs
            )
          }
        }
      }

      if (anyChanged) {
        publishQuestState()
      }

      return QuestProgressUpdateResult(
        updatedVillageQuests = villageQuestsList.toList(),
        completedVillageQuests = newlyCompleted,
        updatedDailyQuests = dailyQuestsList.toList(),
        completedDailyQuests = emptyList(),
        changed = anyChanged
      )
    }

    /**
     * Advances Daily Quest progress for a specific [eventType] outside UI rendering.
     */
    @Synchronized
    fun recordDailyProgress(
      eventType: String,
      amount: Int = 1,
      floor: Int = 1,
      currentTimeMs: Long = System.currentTimeMillis()
    ): QuestProgressUpdateResult {
      ensureDailyQuests(floor = floor, currentTimeMs = currentTimeMs)
      val safeInc = amount.coerceAtLeast(1)
      val normalizedType = eventType.trim().lowercase()
      val newlyCompleted = mutableListOf<DailyQuest>()
      var anyChanged = false

      for (i in dailyQuestsList.indices) {
        val current = dailyQuestsList[i]
        if (!current.done && current.type == normalizedType) {
          val nextProg = (current.progress + safeInc).coerceAtMost(current.amt)
          val nowDone = nextProg >= current.amt
          val updated = validateDailyQuest(
            current.copy(
              progress = nextProg,
              done = nowDone,
              startPct = current.pct
            )
          )
          dailyQuestsList[i] = updated
          anyChanged = true
          if (nowDone) {
            completedDailyCount++
            newlyCompleted.add(updated)
            appendFinding(
              "Daily quest completed: ${updated.id} (${updated.progress}/${updated.amt}) (+${updated.reward}G, +${updated.shards} Shards)",
              isWarning = false,
              timestampMs = currentTimeMs
            )
          }
        }
      }

      if (anyChanged) {
        publishQuestState()
      }

      return QuestProgressUpdateResult(
        updatedVillageQuests = villageQuestsList.toList(),
        completedVillageQuests = emptyList(),
        updatedDailyQuests = dailyQuestsList.toList(),
        completedDailyQuests = newlyCompleted,
        changed = anyChanged
      )
    }

    /**
     * Unified combat hook that records an enemy defeat across both Village Quests and Daily Quests.
     */
    @Synchronized
    fun recordEnemyDefeated(
      enemyType: String,
      isBoss: Boolean = false,
      floor: Int = 1,
      currentTimeMs: Long = System.currentTimeMillis()
    ): QuestProgressUpdateResult {
      val vRes = recordVillageKill(
        enemyType = enemyType,
        amount = 1,
        floor = floor,
        timestampMs = currentTimeMs
      )
      val dKillRes = recordDailyProgress(
        eventType = "daily_kill_all",
        amount = 1,
        floor = floor,
        currentTimeMs = currentTimeMs
      )
      val dBossRes = if (isBoss) {
        recordDailyProgress(
          eventType = "daily_boss",
          amount = 1,
          floor = floor,
          currentTimeMs = currentTimeMs
        )
      } else {
        null
      }

      val completedDaily = buildList {
        addAll(dKillRes.completedDailyQuests)
        if (dBossRes != null) addAll(dBossRes.completedDailyQuests)
      }

      return QuestProgressUpdateResult(
        updatedVillageQuests = villageQuestsList.toList(),
        completedVillageQuests = vRes.completedVillageQuests,
        updatedDailyQuests = dailyQuestsList.toList(),
        completedDailyQuests = completedDaily,
        changed = vRes.changed || dKillRes.changed || (dBossRes?.changed == true)
      )
    }

    /**
     * Returns a read-only snapshot of Village Quests for UI rendering without mutating quest progress.
     */
    @Synchronized
    fun getVillageQuestsForRender(): List<VillageQuest> {
      return villageQuestsList.toList()
    }

    /**
     * Returns a read-only snapshot of Daily Quests for UI rendering without mutating quest progress.
     */
    @Synchronized
    fun getDailyQuestsForRender(): List<DailyQuest> {
      return dailyQuestsList.toList()
    }

    /**
     * Synchronizes and validates Village and Daily quests from serialized JSON payloads.
     */
    @Synchronized
    fun syncFromJson(
      villageQuestsJson: String?,
      dailyQuestsJson: String?,
      dailyResetTimeMs: Long,
      floor: Int = 1
    ): QuestStateSnapshot {
      if (dailyResetTimeMs > 0L) {
        trackedDailyResetTime = dailyResetTimeMs
      }

      if (!villageQuestsJson.isNullOrBlank()) {
        try {
          val arr = JSONArray(villageQuestsJson)
          val parsed = mutableListOf<VillageQuest>()
          for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            parsed.add(
              validateVillageQuest(
                VillageQuest(
                  id = obj.optString("id", "vq_${floor}_$i"),
                  type = obj.optString("type", "kill"),
                  target = obj.optString("target", "husk"),
                  amt = obj.optInt("amt", 5),
                  reward = obj.optInt("reward", 25),
                  xp = obj.optInt("xp", 40),
                  shards = obj.optInt("shards", 1),
                  epic = obj.optBoolean("epic", i == 2),
                  killed = obj.optInt("killed", 0),
                  done = obj.optBoolean("done", false),
                  pct = obj.optInt("pct", 0),
                  startPct = obj.optInt("lastPct", 0)
                ),
                indexHint = i
              )
            )
          }
          if (parsed.isNotEmpty()) {
            villageQuestsList.clear()
            villageQuestsList.addAll(parsed)
          }
        } catch (_: Exception) {
        }
      }

      if (!dailyQuestsJson.isNullOrBlank()) {
        try {
          val arr = JSONArray(dailyQuestsJson)
          val parsed = mutableListOf<DailyQuest>()
          for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            parsed.add(
              validateDailyQuest(
                DailyQuest(
                  id = obj.optString("id", "purge"),
                  key = obj.optString("key", "daily_q_purge"),
                  type = obj.optString("type", "daily_kill_all"),
                  amt = obj.optInt("amt", 25),
                  reward = obj.optInt("reward", 250),
                  xp = obj.optInt("xp", 360),
                  shards = obj.optInt("shards", 8),
                  tierReward = obj.optString("tierReward", "epic_item"),
                  trust = obj.optInt("trust", 0),
                  icon = obj.optString("icon", "⚔️"),
                  progress = obj.optInt("progress", 0),
                  done = obj.optBoolean("done", false),
                  pct = obj.optInt("pct", 0),
                  startPct = obj.optInt("lastPct", 0)
                )
              )
            )
          }
          if (parsed.isNotEmpty()) {
            dailyQuestsList.clear()
            dailyQuestsList.addAll(parsed)
          }
        } catch (_: Exception) {
        }
      }

      publishQuestState()
      return _questState.value
    }

    @Synchronized
    fun resetQuests() {
      villageQuestsList.clear()
      dailyQuestsList.clear()
      completedVillageCount = 0
      completedDailyCount = 0
      publishQuestState()
    }

    private fun publishQuestState() {
      val nextState = QuestStateSnapshot(
        villageQuests = villageQuestsList.toList(),
        dailyQuests = dailyQuestsList.toList(),
        dailyResetTimeMs = trackedDailyResetTime,
        totalCompletedVillageQuests = completedVillageCount,
        totalCompletedDailyQuests = completedDailyCount
      )
      _questState.value = nextState
    }
  }

  val questManager = QuestManager()

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
    val webGlMemoryMb: Double,
    val createdAtMs: Long = System.currentTimeMillis()
  ) {
    fun formatLogLine(): String {
      return "[$timestamp] ${fps}FPS (${String.format(Locale.US, "%.1f", frameTimeMs)}ms) · Floor $dungeonFloor · Lv $playerLevel · Entities $entityCount · Bullets $activeBullets · Particles $activeParticles · Mem ${String.format(Locale.US, "%.1f", usedMemoryMb)}MB"
    }
  }

  private data class TimestampedFinding(
    val message: String,
    val createdAtMs: Long
  )

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
    val dailyResetTime: Long = 0L,
    val isDailyCycleExpired: Boolean = false,
    val dailyCycleResetCount: Int = 0,
    val prunedLogsCount: Int = 0,
    val dipSnapshots: List<DipSnapshot> = emptyList(),
    val recentFindings: List<String> = emptyList()
  )

  private val _snapshot = MutableStateFlow(PerformanceSnapshot())
  val snapshot: StateFlow<PerformanceSnapshot> = _snapshot.asStateFlow()

  private val findingsBuffer = ArrayDeque<TimestampedFinding>()
  private val dipSnapshotsBuffer = ArrayDeque<DipSnapshot>()
  private var totalFrameDipCount = 0
  private var totalMemoryWarningCount = 0
  private var trackedDailyResetTime = 0L
  private var lastNotifiedResetTime = 0L
  private var totalDailyCycleResets = 0
  private var totalPrunedLogsCount = 0

  /**
   * Automatically prunes DipSnapshots and findings older than 7 days (`maxAgeMs`)
   * when the user opens the 'View Logs' menu to keep the app lightweight and compliant
   * with data management best practices.
   */
  @Synchronized
  fun pruneOldLogs(
    currentTimeMs: Long = System.currentTimeMillis(),
    maxAgeMs: Long = LOG_RETENTION_MS
  ): Int {
    val cutoffMs = currentTimeMs - maxAgeMs
    val initialDipCount = dipSnapshotsBuffer.size
    val initialFindingCount = findingsBuffer.size

    dipSnapshotsBuffer.removeAll { it.createdAtMs < cutoffMs }
    findingsBuffer.removeAll { it.createdAtMs < cutoffMs }

    val prunedDips = initialDipCount - dipSnapshotsBuffer.size
    val prunedFindings = initialFindingCount - findingsBuffer.size
    val prunedTotal = prunedDips + prunedFindings

    if (prunedTotal > 0) {
      totalPrunedLogsCount += prunedTotal
    }

    _snapshot.value = _snapshot.value.copy(
      prunedLogsCount = totalPrunedLogsCount,
      dipSnapshots = dipSnapshotsBuffer.toList(),
      recentFindings = findingsBuffer.map { it.message }
    )
    return prunedTotal
  }

  /**
   * Invoked when the user opens the 'View Logs' menu; prunes logs older than 7 days
   * and returns the refreshed PerformanceSnapshot.
   */
  @Synchronized
  fun onOpenViewLogs(currentTimeMs: Long = System.currentTimeMillis()): PerformanceSnapshot {
    pruneOldLogs(currentTimeMs = currentTimeMs)
    return _snapshot.value
  }

  /**
   * Monitors the 24-hour daily quest cycle (`dailyResetTime`) during active gameplay.
   * Returns a non-intrusive toast message when the 24-hour cycle expires so the UI can
   * notify the player without interrupting combat, and records the event in GameMonitor findings.
   */
  @Synchronized
  fun monitorDailyResetCycle(
    dailyResetTimeMs: Long,
    currentTimeMs: Long = System.currentTimeMillis(),
    isActiveGameplay: Boolean = true
  ): String? {
    if (dailyResetTimeMs > 0L) {
      trackedDailyResetTime = dailyResetTimeMs
    }
    val targetReset = trackedDailyResetTime
    if (!isActiveGameplay || targetReset <= 0L) {
      return null
    }

    val isExpired = currentTimeMs >= targetReset && lastNotifiedResetTime != targetReset
    if (isExpired) {
      lastNotifiedResetTime = targetReset
      totalDailyCycleResets++
      questManager.generateDailyQuests(
        floor = _snapshot.value.dungeonFloor,
        currentTimeMs = currentTimeMs,
        nextResetTimeMs = currentTimeMs + ONE_DAY_MS
      )
      val toastMsg = "📅 24h-Zyklus abgelaufen! Neue Tages-Aufgaben im Quest-Log verfügbar."
      appendFinding(
        "Daily quest 24h cycle expired (reset #$totalDailyCycleResets) — non-intrusive toast triggered during active gameplay",
        isWarning = false,
        timestampMs = currentTimeMs
      )
      _snapshot.value = _snapshot.value.copy(
        dailyResetTime = targetReset,
        isDailyCycleExpired = true,
        dailyCycleResetCount = totalDailyCycleResets,
        recentFindings = findingsBuffer.map { it.message }
      )
      return toastMsg
    }

    _snapshot.value = _snapshot.value.copy(
      dailyResetTime = targetReset,
      isDailyCycleExpired = false
    )
    return null
  }

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
    overrideMaxJvmMb: Double? = null,
    sampleTimeMs: Long = System.currentTimeMillis()
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
      val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(sampleTimeMs))
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
        webGlMemoryMb = (webGlHeapMb.coerceAtLeast(0.0) * 10.0).roundToInt() / 10.0,
        createdAtMs = sampleTimeMs
      )
      if (dipSnapshotsBuffer.size >= maxLogEntries) {
        dipSnapshotsBuffer.removeFirst()
      }
      dipSnapshotsBuffer.addLast(dipSnap)

      val msg = "Frame dip detected: ${safeFps.roundToInt()} FPS (${String.format(Locale.US, "%.1f", safeFrameMs)} ms/frame) | Floor $safeFloor, Lv $safeLevel, Entities $safeEntities, Bullets $safeBullets, Particles $safeParticles (dip #$totalFrameDipCount)"
      appendFinding(msg, isWarning = true, timestampMs = sampleTimeMs)
    }

    if (isMemPressure) {
      totalMemoryWarningCount++
      val msg = "Memory pressure: ${String.format(Locale.US, "%.1f", usedJvmMb)}/${String.format(Locale.US, "%.1f", maxJvmMb)} MB ($memPercent%, warn #$totalMemoryWarningCount) | Floor $safeFloor, Entities $safeEntities"
      appendFinding(msg, isWarning = true, timestampMs = sampleTimeMs)
    }

    if (!isDip && !isMemPressure && findingsBuffer.isEmpty()) {
      appendFinding(
        "Healthy: ${safeFps.roundToInt()} FPS (${String.format(Locale.US, "%.1f", safeFrameMs)} ms) · Floor $safeFloor · Entities $safeEntities · Bullets $safeBullets · Particles $safeParticles",
        isWarning = false,
        timestampMs = sampleTimeMs
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
      dailyResetTime = trackedDailyResetTime,
      isDailyCycleExpired = _snapshot.value.isDailyCycleExpired,
      dailyCycleResetCount = totalDailyCycleResets,
      prunedLogsCount = totalPrunedLogsCount,
      dipSnapshots = dipSnapshotsBuffer.toList(),
      recentFindings = findingsBuffer.map { it.message }
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

  private fun appendFinding(
    entry: String,
    isWarning: Boolean,
    timestampMs: Long = System.currentTimeMillis()
  ) {
    if (findingsBuffer.size >= maxLogEntries) {
      findingsBuffer.removeFirst()
    }
    findingsBuffer.addLast(TimestampedFinding(entry, timestampMs))
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
    trackedDailyResetTime = 0L
    lastNotifiedResetTime = 0L
    totalDailyCycleResets = 0
    totalPrunedLogsCount = 0
    questManager.resetQuests()
    _snapshot.value = PerformanceSnapshot()
  }
}
