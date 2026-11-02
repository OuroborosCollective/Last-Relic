package com.example.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Runtime validation, sanitization, and integrity checks for Last Relic.
 * Ensures data durability, anti-tamper bounds, worldwide device compatibility,
 * and Google Play policy compliance at runtime.
 */
object GameRuntimeValidator {

  val VALID_CLASSES = setOf("warrior", "rogue", "mage", "tank", "necromancer")
  const val MAX_SAVE_JSON_CHARS = 190_000
  const val MAX_PLAYER_NAME_LENGTH = 40
  const val MAX_APP_TITLE_LENGTH = 30

  data class ValidatedSaveData(
    val sanitizedJson: String,
    val level: Long,
    val floor: Long,
    val gold: Long,
    val metaShards: Long,
    val playerClass: String,
    val isValid: Boolean,
    val npcFactionTrust: Long = 10L
  )

  data class ValidatedLeaderboardSubmission(
    val playerName: String,
    val playerClass: String,
    val score: Long,
    val level: Long,
    val floor: Long,
    val kills: Long,
    val itemsFound: Long
  )

  /**
   * Validates and sanitizes raw JS save JSON before syncing to Cloud Save or applying locally.
   */
  fun validateAndSanitizeSaveJson(rawJson: String?): ValidatedSaveData {
    if (rawJson.isNullOrBlank() || rawJson.length > MAX_SAVE_JSON_CHARS) {
      return ValidatedSaveData(
        sanitizedJson = "{}",
        level = 1L,
        floor = 1L,
        gold = 0L,
        metaShards = 0L,
        playerClass = "warrior",
        isValid = false,
        npcFactionTrust = 10L
      )
    }

    return try {
      val obj = JSONObject(rawJson)
      val level = obj.optLong("lvl", 1L).coerceIn(1L, 9999L)
      val floor = obj.optLong("floor", 1L).coerceIn(1L, 9999L)
      val gold = obj.optLong("gold", 0L).coerceIn(0L, 100_000_000L)
      val metaShards = obj.optLong("metaShards", 0L).coerceIn(0L, 100_000_000L)
      val rawClass = obj.optString("playerClass", "warrior").trim().lowercase()
      val safeClass = if (rawClass in VALID_CLASSES) rawClass else "warrior"
      val shakeMult = obj.optDouble("shakeMult", 1.0).let {
        if (it.isNaN() || it.isInfinite()) 1.0 else it.coerceIn(0.0, 2.0)
      }
      val npcFactionTrust = obj.optLong("npcFactionTrust", 10L).coerceIn(-100L, 10_000L)

      obj.put("lvl", level)
      obj.put("floor", floor)
      obj.put("gold", gold)
      obj.put("metaShards", metaShards)
      obj.put("playerClass", safeClass)
      obj.put("shakeMult", shakeMult)
      obj.put("npcFactionTrust", npcFactionTrust)

      if (!obj.has("scrapFilter") || obj.optJSONArray("scrapFilter") == null) {
        obj.put("scrapFilter", JSONArray(listOf(true, false, false)))
      }

      val sanitizedStr = obj.toString().take(MAX_SAVE_JSON_CHARS)
      ValidatedSaveData(
        sanitizedJson = sanitizedStr,
        level = level,
        floor = floor,
        gold = gold,
        metaShards = metaShards,
        playerClass = safeClass,
        isValid = true,
        npcFactionTrust = npcFactionTrust
      )
    } catch (_: Exception) {
      ValidatedSaveData(
        sanitizedJson = "{}",
        level = 1L,
        floor = 1L,
        gold = 0L,
        metaShards = 0L,
        playerClass = "warrior",
        isValid = false,
        npcFactionTrust = 10L
      )
    }
  }

  /**
   * Sanitizes and clamps leaderboard submissions to prevent malformed or out-of-bounds records.
   */
  fun validateLeaderboardSubmission(
    playerName: String?,
    playerClass: String?,
    score: Long,
    level: Long,
    floor: Long,
    kills: Long,
    itemsFound: Long,
    fallbackName: String = "RelicHunter"
  ): ValidatedLeaderboardSubmission {
    val cleanedName = (playerName ?: "")
      .replace(Regex("[\\p{Cntrl}]"), "")
      .trim()
      .ifEmpty { fallbackName.trim().ifEmpty { "RelicHunter" } }
      .take(MAX_PLAYER_NAME_LENGTH)

    val cleanedClass = (playerClass ?: "warrior").trim().lowercase().let {
      if (it in VALID_CLASSES) it else "warrior"
    }

    return ValidatedLeaderboardSubmission(
      playerName = cleanedName,
      playerClass = cleanedClass,
      score = score.coerceIn(0L, 1_000_000_000L),
      level = level.coerceIn(1L, 9999L),
      floor = floor.coerceIn(1L, 9999L),
      kills = kills.coerceIn(0L, 10_000_000L),
      itemsFound = itemsFound.coerceIn(0L, 10_000_000L)
    )
  }

  /**
   * Calculates the maximum number of active minions allowed for a given class and equipment bonus.
   */
  fun calculateMaxMinions(playerClass: String, bonusSummons: Int): Int {
    val safeBonus = bonusSummons.coerceIn(0, 20)
    val classBonus = if (playerClass.trim().lowercase() == "necromancer") 2 else 0
    return 3 + safeBonus + classBonus
  }

  /**
   * Clamps active minion count and determines whether the Necromancer Overload state is active.
   */
  fun isNecromancerOverload(activeMinions: Int, maxMinions: Int): Boolean {
    val safeMax = maxMinions.coerceAtLeast(1)
    return activeMinions >= safeMax
  }

  /**
   * Verifies that bundled WebGL game assets exist and contain required HUD/runtime structures.
   */
  fun verifyBundledAssets(context: Context): Boolean {
    return try {
      val assets = context.assets
      val assetList = assets.list("")?.toSet() ?: emptySet()
      if ("index.html" !in assetList || "three.min.js" !in assetList) {
        return false
      }
      val html = assets.open("index.html").bufferedReader().use { it.readText() }
      val requiredMarkers = listOf(
        "#hud",
        ".minion-badge",
        ".minion-skull",
        "#bossb",
        "AudioManager",
        "quest_complete",
        "GameMonitor",
        "btnRelations",
        "npcRelationsOvl",
        "getFactionTier",
        "runRuntimeSelfChecks"
      )
      requiredMarkers.all { marker -> html.contains(marker) }
    } catch (_: Exception) {
      false
    }
  }

  /**
   * Verifies that Google Play Console policy declarations (permissions, data collection practices,
   * and target age group) are explicitly configured in BuildConfig.
   */
  fun verifyPlayPolicyDeclarations(): Boolean {
    val targetAge = com.example.BuildConfig.PLAY_TARGET_AGE_GROUP
    val permissions = com.example.BuildConfig.PLAY_PERMISSIONS_DECLARED
    val dataPractices = com.example.BuildConfig.PLAY_DATA_COLLECTION_PRACTICES
    return targetAge == "13+" &&
      permissions.contains("android.permission.INTERNET") &&
      permissions.contains("android.permission.ACCESS_NETWORK_STATE") &&
      permissions.contains("android.permission.VIBRATE") &&
      dataPractices.contains("GoogleSignIn") &&
      dataPractices.contains("CloudSave") &&
      dataPractices.contains("Leaderboard")
  }
}

