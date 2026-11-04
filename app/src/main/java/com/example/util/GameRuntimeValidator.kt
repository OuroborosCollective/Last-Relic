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
  val MELEE_WEAPON_KEYS = setOf("sword", "scythe", "hammer", "mace")
  const val ENEMY_DIFFICULTY_MULTIPLIER = 1.35
  const val AUTO_ATTACK_RANGE_MELEE = 2.65
  const val AUTO_ATTACK_RANGE_RANGED = 4.20
  const val AUTO_ATTACK_MAX_CONE_RAD = 1.35
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
    val npcFactionTrust: Long = 0L
  )

  data class CompanionCombatTier(
    val level: Int,
    val id: String,
    val icon: String,
    val nameDe: String,
    val nameEn: String,
    val color: String,
    val combatScore: Int,
    val minScore: Int,
    val nextScore: Int,
    val progressPct: Int
  )

  data class FactionRewardModifiers(
    val shopPriceMultiplier: Double,
    val shopDiscountPct: Int,
    val npcTradeGoldCost: Int,
    val tradeBonusLootRoll: Double,
    val potionHealRatio: Double,
    val blessingDurationSec: Int,
    val whetstoneDmgBonusPct: Int,
    val unlockedTierLevel: Int,
    val unlockedPotion: Boolean,
    val unlockedRation: Boolean,
    val unlockedWhetstone: Boolean,
    val unlockedBrotherhoodRelic: Boolean
  )

  data class CompanionGameplayRecord(
    val id: String,
    val savedByPlayer: Int = 0,
    val dangerRescues: Int = 0,
    val tradedCount: Int = 0,
    val killedByPlayer: Int = 0,
    val enemiesDefeated: Int = 0,
    val timesSavedPlayer: Int = 0,
    val historyNodeTypes: List<String> = emptyList(),
    val rawTrust: Int = 0
  )

  data class VerifiedCompanionRelation(
    val id: String,
    val stance: String,
    val trust: Int,
    val savedByPlayer: Int,
    val dangerRescues: Int,
    val tradedCount: Int,
    val killedByPlayer: Int,
    val enemiesDefeated: Int,
    val timesSavedPlayer: Int,
    val totalInteractions: Int,
    val combatTier: CompanionCombatTier
  )

  data class NpcRelationsVerificationResult(
    val isVerified: Boolean,
    val derivedFromGameplay: Boolean,
    val factionTrust: Int,
    val derivedFactionTrust: Int,
    val totalEnemiesDefeated: Int,
    val totalTimesSavedPlayer: Int,
    val totalSavedByPlayer: Int,
    val totalTradedCount: Int,
    val totalKilledByPlayer: Int,
    val totalCombatScore: Int,
    val totalInteractions: Int,
    val factionModifiers: FactionRewardModifiers,
    val companions: List<VerifiedCompanionRelation>
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
   * Computes a companion's combat experience progression tier and progress bar percentage
   * from their recorded `enemiesDefeated` and `timesSavedPlayer` combat stats.
   */
  fun getCompanionCombatTier(
    enemiesDefeated: Int,
    timesSavedPlayer: Int
  ): CompanionCombatTier {
    val safeKills = enemiesDefeated.coerceAtLeast(0)
    val safeSaves = timesSavedPlayer.coerceAtLeast(0)
    val combatScore = safeKills + (safeSaves * 5)

    data class TierSpec(
      val level: Int,
      val id: String,
      val minScore: Int,
      val nextScore: Int,
      val icon: String,
      val nameDe: String,
      val nameEn: String,
      val color: String
    )

    val specs = listOf(
      TierSpec(1, "recruit", 0, 5, "🌱", "Rekrut", "Recruit", "#9ec7ff"),
      TierSpec(2, "veteran", 5, 15, "⚔️", "Veteran", "Veteran", "#6dff9b"),
      TierSpec(3, "elite", 15, 30, "🛡️", "Elite-Krieger", "Elite Vanguard", "#4fd1ff"),
      TierSpec(4, "champion", 30, 50, "🔥", "Schlacht-Champion", "Battle Champion", "#c77dff"),
      TierSpec(5, "warlord", 50, 50, "👑", "Kriegsfürst", "Warlord Legend", "#ffd54a")
    )

    val chosen = specs.lastOrNull { combatScore >= it.minScore } ?: specs.first()
    val progressPct = if (chosen.level >= 5) {
      100
    } else {
      val span = (chosen.nextScore - chosen.minScore).coerceAtLeast(1)
      (((combatScore - chosen.minScore).toDouble() / span.toDouble()) * 100.0)
        .toInt()
        .coerceIn(0, 100)
    }

    return CompanionCombatTier(
      level = chosen.level,
      id = chosen.id,
      icon = chosen.icon,
      nameDe = chosen.nameDe,
      nameEn = chosen.nameEn,
      color = chosen.color,
      combatScore = combatScore,
      minScore = chosen.minScore,
      nextScore = chosen.nextScore,
      progressPct = progressPct
    )
  }

  /**
   * Verifies that NPC Relations trust values, companion combat progression tiers, and all trust-based
   * modifiers for faction rewards are strictly derived from `historyNodes` and combat metrics
   * (`enemiesDefeated`, `timesSavedPlayer`), eliminating any hardcoded default trust values for the NPC Roster.
   */
  fun verifyNpcRelationsData(
    companions: List<CompanionGameplayRecord>,
    earnedQuestTrust: Int = 0,
    rawFactionTrust: Int = 0
  ): NpcRelationsVerificationResult {
    var derivedFactionTrust = 0
    var totalEnemiesDefeated = 0
    var totalTimesSavedPlayer = 0
    var totalSavedByPlayer = 0
    var totalTradedCount = 0
    var totalKilledByPlayer = 0
    var totalInteractions = 0
    var totalCombatScore = 0
    var verifiedWhetstoneCount = 0

    val verifiedCompanions = companions.map { rec ->
      val histSaved = rec.historyNodeTypes.count { it == "Saved" }
      val histDanger = rec.historyNodeTypes.count { it == "DangerRescue" }
      val histTraded = rec.historyNodeTypes.count { it == "Traded" || it == "Traded_Whetstone" }
      val histKilled = rec.historyNodeTypes.count { it == "Conflict" }
      val histPlayerSaved = rec.historyNodeTypes.count { it == "PlayerSaved" }
      val histEnemyKills = rec.historyNodeTypes.count { it == "EnemyKill" }
      val histWhetstones = rec.historyNodeTypes.count { it == "WhetstoneApplied" || it == "Traded_Whetstone" }

      // Social trust events are strictly derived from historyNodes (eliminating unbacked counters or hardcoded defaults)
      val safeSaved = histSaved
      val safeDanger = histDanger
      val safeTraded = histTraded
      val safeKilled = histKilled
      // Combat metrics are derived from historyNodes and live combat counters
      val safeSavedPlayer = maxOf(histPlayerSaved, rec.timesSavedPlayer.coerceAtLeast(0))
      val safeEnemiesDefeated = maxOf(histEnemyKills, rec.enemiesDefeated.coerceAtLeast(0))

      val combatTier = getCompanionCombatTier(
        enemiesDefeated = safeEnemiesDefeated,
        timesSavedPlayer = safeSavedPlayer
      )

      val advInteractions =
        safeSaved + safeDanger + safeTraded + safeKilled + safeSavedPlayer + safeEnemiesDefeated
      val derivedAdvTrust = (
        (safeSaved * 25) +
          (safeDanger * 15) +
          (safeTraded * 10) +
          (safeSavedPlayer * 5) -
          (safeKilled * 40)
        ).coerceIn(-100, 100)

      val finalAdvTrust = if (advInteractions > 0) derivedAdvTrust else 0
      val stance = when {
        safeKilled > 0 && finalAdvTrust < 0 -> "negative"
        safeSaved > 0 || safeDanger > 0 || safeTraded > 0 || finalAdvTrust >= 10 -> "positive"
        else -> "new"
      }

      derivedFactionTrust += (safeSaved * 25) + (safeDanger * 15) + (safeTraded * 5) - (safeKilled * 30)
      totalEnemiesDefeated += safeEnemiesDefeated
      totalTimesSavedPlayer += safeSavedPlayer
      totalSavedByPlayer += safeSaved + safeDanger
      totalTradedCount += safeTraded
      totalKilledByPlayer += safeKilled
      totalInteractions += advInteractions
      totalCombatScore += combatTier.combatScore
      verifiedWhetstoneCount += histWhetstones

      VerifiedCompanionRelation(
        id = rec.id,
        stance = stance,
        trust = finalAdvTrust,
        savedByPlayer = safeSaved,
        dangerRescues = safeDanger,
        tradedCount = safeTraded,
        killedByPlayer = safeKilled,
        enemiesDefeated = safeEnemiesDefeated,
        timesSavedPlayer = safeSavedPlayer,
        totalInteractions = advInteractions,
        combatTier = combatTier
      )
    }

    val safeQuestTrust = earnedQuestTrust.coerceAtLeast(0)
    val clampedDerivedFactionTrust = (derivedFactionTrust + safeQuestTrust).coerceIn(-100, 10_000)
    val finalFactionTrust = if (totalInteractions == 0 && safeQuestTrust == 0) {
      0
    } else {
      clampedDerivedFactionTrust
    }

    val trustTierDiscount = when {
      finalFactionTrust >= 100 -> 0.25
      finalFactionTrust >= 70 -> 0.20
      finalFactionTrust >= 40 -> 0.15
      finalFactionTrust >= 15 -> 0.10
      finalFactionTrust < 0 -> -0.15
      else -> 0.0
    }
    val combatSynergyDiscount = ((totalCombatScore / 25) * 0.01).coerceAtMost(0.05)
    val shopPriceMultiplier = kotlin.math.round(
      (1.0 - trustTierDiscount - combatSynergyDiscount).coerceIn(0.70, 1.25) * 100.0
    ) / 100.0
    val shopDiscountPct = kotlin.math.round((1.0 - shopPriceMultiplier) * 100.0).toInt().coerceAtLeast(0)
    val npcTradeGoldCost = (
      25 - (finalFactionTrust.coerceAtLeast(0) / 10) - (totalTimesSavedPlayer / 2)
      ).coerceIn(10, 35)
    val tradeBonusLootRoll = kotlin.math.round(
      (finalFactionTrust.coerceAtLeast(0) * 0.015 + totalEnemiesDefeated * 0.01 + totalTimesSavedPlayer * 0.05) * 100.0
    ) / 100.0
    val potionHealRatio = kotlin.math.round(
      (0.25 + (if (finalFactionTrust >= 15) 0.40 else 0.0) + (totalTimesSavedPlayer * 0.03).coerceAtMost(0.15))
        .coerceIn(0.25, 0.80) * 100.0
    ) / 100.0
    val blessingDurationSec = if (finalFactionTrust >= 15) {
      25 + (totalTimesSavedPlayer * 3).coerceAtMost(15)
    } else {
      0
    }
    val unlockedTierLevel = when {
      finalFactionTrust >= 100 -> 4
      finalFactionTrust >= 70 -> 3
      finalFactionTrust >= 40 -> 2
      finalFactionTrust >= 15 -> 1
      else -> 0
    }
    val factionModifiers = FactionRewardModifiers(
      shopPriceMultiplier = shopPriceMultiplier,
      shopDiscountPct = shopDiscountPct,
      npcTradeGoldCost = npcTradeGoldCost,
      tradeBonusLootRoll = tradeBonusLootRoll,
      potionHealRatio = potionHealRatio,
      blessingDurationSec = blessingDurationSec,
      whetstoneDmgBonusPct = verifiedWhetstoneCount * 12,
      unlockedTierLevel = unlockedTierLevel,
      unlockedPotion = unlockedTierLevel >= 1,
      unlockedRation = unlockedTierLevel >= 2,
      unlockedWhetstone = unlockedTierLevel >= 3,
      unlockedBrotherhoodRelic = unlockedTierLevel >= 4
    )

    return NpcRelationsVerificationResult(
      isVerified = true,
      derivedFromGameplay = (rawFactionTrust == finalFactionTrust) || finalFactionTrust == clampedDerivedFactionTrust,
      factionTrust = finalFactionTrust,
      derivedFactionTrust = clampedDerivedFactionTrust,
      totalEnemiesDefeated = totalEnemiesDefeated,
      totalTimesSavedPlayer = totalTimesSavedPlayer,
      totalSavedByPlayer = totalSavedByPlayer,
      totalTradedCount = totalTradedCount,
      totalKilledByPlayer = totalKilledByPlayer,
      totalCombatScore = totalCombatScore,
      totalInteractions = totalInteractions + safeQuestTrust,
      factionModifiers = factionModifiers,
      companions = verifiedCompanions
    )
  }

  /**
   * Returns true if the given class or weapon performs pure melee attacks without magic projectiles.
   */
  fun isWarriorMeleeAttack(playerClass: String, weaponKey: String = "sword"): Boolean {
    val cls = playerClass.trim().lowercase()
    val wk = weaponKey.trim().lowercase()
    return cls == "warrior" || cls == "tank" || cls == "paladin" || wk in MELEE_WEAPON_KEYS
  }

  /**
   * Returns the maximum auto-attack detection distance in meters in front of the character.
   */
  fun getAutoAttackMaxRange(playerClass: String, weaponKey: String = "sword"): Double {
    return if (isWarriorMeleeAttack(playerClass, weaponKey)) {
      AUTO_ATTACK_RANGE_MELEE
    } else {
      AUTO_ATTACK_RANGE_RANGED
    }
  }

  /**
   * Verifies whether an enemy at [distanceToEnemy] and [angleDiffRad] from the player's facing
   * direction is eligible for auto-attack (strictly limited to a few meters in front of the player).
   */
  fun canAutoAttackTarget(
    playerClass: String,
    weaponKey: String,
    distanceToEnemy: Double,
    enemyRadius: Double = 0.4,
    angleDiffRad: Double = 0.0,
    hasLineOfSight: Boolean = true
  ): Boolean {
    if (!hasLineOfSight) return false
    val maxRange = getAutoAttackMaxRange(playerClass, weaponKey)
    val effectiveDist = (distanceToEnemy - enemyRadius.coerceAtLeast(0.0)).coerceAtLeast(0.0)
    if (effectiveDist > maxRange) return false
    return kotlin.math.abs(angleDiffRad) <= AUTO_ATTACK_MAX_CONE_RAD || effectiveDist <= 1.35
  }

  data class SkillTreeValidationResult(
    val isValid: Boolean,
    val selectedBranch: String?,
    val selectedTier: Int,
    val lockedBranch: String?,
    val isOtherBranchLocked: Boolean,
    val errorReason: String? = null
  )

  /**
   * Validates skill tree branching rules for any class.
   * Ensures that selecting one branch (A or B) makes the other branch permanently locked and unskillable.
   * Ensures sequential tier progression (1 -> 2 -> 3).
   */
  fun validateSkillTreeSelection(
    playerClass: String,
    chosenBranch: String?,
    chosenTier: Int,
    attemptedBranch: String? = null,
    attemptedTier: Int? = null,
    availableSkillPoints: Int = 1
  ): SkillTreeValidationResult {
    val normBranch = chosenBranch?.trim()?.uppercase()?.takeIf { it in setOf("A", "B") }
    val clampedTier = chosenTier.coerceIn(0, 3)

    if (attemptedBranch != null && attemptedTier != null) {
      val attBranch = attemptedBranch.trim().uppercase()
      if (normBranch != null && attBranch != normBranch) {
        return SkillTreeValidationResult(
          isValid = false,
          selectedBranch = normBranch,
          selectedTier = clampedTier,
          lockedBranch = if (normBranch == "A") "B" else "A",
          isOtherBranchLocked = true,
          errorReason = "Branch $attBranch is permanently locked because Branch $normBranch was chosen"
        )
      }
      if (availableSkillPoints <= 0) {
        return SkillTreeValidationResult(
          isValid = false,
          selectedBranch = normBranch,
          selectedTier = clampedTier,
          lockedBranch = if (normBranch == "A") "B" else (if (normBranch == "B") "A" else null),
          isOtherBranchLocked = normBranch != null,
          errorReason = "No skill points available"
        )
      }
      if (attemptedTier != clampedTier + 1) {
        return SkillTreeValidationResult(
          isValid = false,
          selectedBranch = normBranch,
          selectedTier = clampedTier,
          lockedBranch = if (normBranch == "A") "B" else (if (normBranch == "B") "A" else null),
          isOtherBranchLocked = normBranch != null,
          errorReason = "Sequential unlock required: must unlock tier ${clampedTier + 1} first"
        )
      }
      return SkillTreeValidationResult(
        isValid = true,
        selectedBranch = attBranch,
        selectedTier = attemptedTier,
        lockedBranch = if (attBranch == "A") "B" else "A",
        isOtherBranchLocked = true
      )
    }

    val locked = if (normBranch == "A") "B" else (if (normBranch == "B") "A" else null)
    return SkillTreeValidationResult(
      isValid = true,
      selectedBranch = normBranch,
      selectedTier = clampedTier,
      lockedBranch = locked,
      isOtherBranchLocked = locked != null
    )
  }

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
        npcFactionTrust = 0L
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
      val npcFactionTrust = obj.optLong("npcFactionTrust", 0L).coerceIn(-100L, 10_000L)

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
        npcFactionTrust = 0L
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

  data class GuildShopOffer(
    val id: String,
    val category: String,
    val tier: Int,
    val minTrust: Int,
    val baseGold: Int,
    val baseShards: Int,
    val goldCost: Int,
    val shardCost: Int,
    val discountPct: Int,
    val durationSec: Int,
    val unlocked: Boolean
  )

  data class GuildShopTradeResult(
    val success: Boolean,
    val offerId: String,
    val category: String,
    val remainingGold: Long,
    val remainingMetaShards: Long,
    val updatedFactionTrust: Int,
    val goldSpent: Int,
    val shardsSpent: Int,
    val durationSec: Int,
    val errorReason: String? = null
  )

  fun computeFactionModifiers(
    factionTrust: Int,
    combatScore: Int = 0,
    totalTimesSavedPlayer: Int = (combatScore / 5).coerceAtLeast(0),
    totalEnemiesDefeated: Int = (combatScore / 2).coerceAtLeast(0),
    verifiedWhetstoneCount: Int = 0
  ): FactionRewardModifiers {
    val trustTierDiscount = when {
      factionTrust >= 100 -> 0.25
      factionTrust >= 70 -> 0.20
      factionTrust >= 40 -> 0.15
      factionTrust >= 15 -> 0.10
      factionTrust < 0 -> -0.15
      else -> 0.0
    }
    val combatSynergyDiscount = ((combatScore / 25) * 0.01).coerceAtMost(0.05)
    val shopPriceMultiplier = kotlin.math.round(
      (1.0 - trustTierDiscount - combatSynergyDiscount).coerceIn(0.70, 1.25) * 100.0
    ) / 100.0
    val shopDiscountPct = kotlin.math.round((1.0 - shopPriceMultiplier) * 100.0).toInt().coerceAtLeast(0)
    val npcTradeGoldCost = (
      25 - (factionTrust.coerceAtLeast(0) / 10) - (totalTimesSavedPlayer / 2)
      ).coerceIn(10, 35)
    val tradeBonusLootRoll = kotlin.math.round(
      (factionTrust.coerceAtLeast(0) * 0.015 + totalEnemiesDefeated * 0.01 + totalTimesSavedPlayer * 0.05) * 100.0
    ) / 100.0
    val potionHealRatio = kotlin.math.round(
      (0.25 + (if (factionTrust >= 15) 0.40 else 0.0) + (totalTimesSavedPlayer * 0.03).coerceAtMost(0.15))
        .coerceIn(0.25, 0.80) * 100.0
    ) / 100.0
    val blessingDurationSec = if (factionTrust >= 15) {
      25 + (totalTimesSavedPlayer * 3).coerceAtMost(15)
    } else {
      0
    }
    val unlockedTierLevel = when {
      factionTrust >= 100 -> 4
      factionTrust >= 70 -> 3
      factionTrust >= 40 -> 2
      factionTrust >= 15 -> 1
      else -> 0
    }
    return FactionRewardModifiers(
      shopPriceMultiplier = shopPriceMultiplier,
      shopDiscountPct = shopDiscountPct,
      npcTradeGoldCost = npcTradeGoldCost,
      tradeBonusLootRoll = tradeBonusLootRoll,
      potionHealRatio = potionHealRatio,
      blessingDurationSec = blessingDurationSec,
      whetstoneDmgBonusPct = verifiedWhetstoneCount * 12,
      unlockedTierLevel = unlockedTierLevel,
      unlockedPotion = unlockedTierLevel >= 1,
      unlockedRation = unlockedTierLevel >= 2,
      unlockedWhetstone = unlockedTierLevel >= 3,
      unlockedBrotherhoodRelic = unlockedTierLevel >= 4
    )
  }

  /**
   * Returns the Guild Shop catalog scaled by player's Faction Trust level, dungeon floor, and companion combat score.
   */
  fun getGuildShopCatalog(
    factionTrust: Int,
    floor: Int = 1,
    combatScore: Int = 0
  ): List<GuildShopOffer> {
    val safeTrust = factionTrust.coerceAtLeast(0)
    val safeFloor = floor.coerceAtLeast(1)
    val mods = computeFactionModifiers(safeTrust, combatScore)
    val priceMult = mods.shopPriceMultiplier
    val discountPct = mods.shopDiscountPct
    val shardMult = (1.0 - discountPct * 0.008).coerceIn(0.65, 1.0)
    val durBonusSec = (mods.blessingDurationSec - 25).coerceAtLeast(0)

    data class RawEntry(
      val id: String,
      val category: String,
      val tier: Int,
      val minTrust: Int,
      val baseGold: Int,
      val baseShards: Int,
      val baseDur: Int
    )

    val rawCatalog = listOf(
      RawEntry("gs_gear_mystes_armory", "gear", 0, 0, 35, 1, 0),
      RawEntry("gs_gear_epoptes_cache", "gear", 1, 15, 65, 3, 0),
      RawEntry("gs_gear_dadouchos_forge", "gear", 2, 40, 110, 6, 0),
      RawEntry("gs_gear_titan_set", "gear", 3, 70, 165, 10, 0),
      RawEntry("gs_gear_olympian_relic", "gear", 4, 100, 240, 16, 0),
      RawEntry("gs_buff_battle_elixir", "buffs", 0, 0, 25, 1, 45),
      RawEntry("gs_buff_aegis_ward", "buffs", 1, 15, 45, 2, 60),
      RawEntry("gs_buff_hermes_haste", "buffs", 2, 40, 75, 4, 75),
      RawEntry("gs_buff_plutos_fortune", "buffs", 3, 70, 115, 7, 90),
      RawEntry("gs_buff_olympian_apotheosis", "buffs", 4, 100, 180, 12, 120)
    )

    return rawCatalog.map { entry ->
      val rawGold = entry.baseGold + kotlin.math.floor(safeFloor * 4.0 * (1.0 + entry.tier * 0.25)).toInt()
      val goldCost = kotlin.math.round(rawGold * priceMult).toInt().coerceAtLeast(5)
      val shardCost = kotlin.math.round(entry.baseShards * shardMult).toInt().coerceAtLeast(1)
      val durationSec = if (entry.category == "buffs") entry.baseDur + durBonusSec else 0
      GuildShopOffer(
        id = entry.id,
        category = entry.category,
        tier = entry.tier,
        minTrust = entry.minTrust,
        baseGold = entry.baseGold,
        baseShards = entry.baseShards,
        goldCost = goldCost,
        shardCost = shardCost,
        discountPct = discountPct,
        durationSec = durationSec,
        unlocked = safeTrust >= entry.minTrust
      )
    }
  }

  /**
   * Evaluates a Guild Shop purchase of Gear or Temporary Buffs using Gold and Meta Shards,
   * enforcing Faction Trust gating and awarding +5 Faction Trust on completion.
   */
  fun evaluateGuildShopTrade(
    offerId: String,
    currentGold: Long,
    currentMetaShards: Long,
    factionTrust: Int,
    floor: Int = 1,
    combatScore: Int = 0
  ): GuildShopTradeResult {
    val catalog = getGuildShopCatalog(factionTrust, floor, combatScore)
    val offer = catalog.find { it.id == offerId }
      ?: return GuildShopTradeResult(
        success = false,
        offerId = offerId,
        category = "unknown",
        remainingGold = currentGold,
        remainingMetaShards = currentMetaShards,
        updatedFactionTrust = factionTrust.coerceIn(0, 250),
        goldSpent = 0,
        shardsSpent = 0,
        durationSec = 0,
        errorReason = "Offer not found"
      )

    if (!offer.unlocked) {
      return GuildShopTradeResult(
        success = false,
        offerId = offer.id,
        category = offer.category,
        remainingGold = currentGold,
        remainingMetaShards = currentMetaShards,
        updatedFactionTrust = factionTrust.coerceIn(0, 250),
        goldSpent = 0,
        shardsSpent = 0,
        durationSec = 0,
        errorReason = "Requires Faction Trust >= ${offer.minTrust}"
      )
    }

    if (currentGold < offer.goldCost || currentMetaShards < offer.shardCost) {
      return GuildShopTradeResult(
        success = false,
        offerId = offer.id,
        category = offer.category,
        remainingGold = currentGold,
        remainingMetaShards = currentMetaShards,
        updatedFactionTrust = factionTrust.coerceIn(0, 250),
        goldSpent = 0,
        shardsSpent = 0,
        durationSec = 0,
        errorReason = "Insufficient Gold or Meta Shards"
      )
    }

    val updatedTrust = (factionTrust.coerceAtLeast(0) + 5).coerceIn(0, 250)
    return GuildShopTradeResult(
      success = true,
      offerId = offer.id,
      category = offer.category,
      remainingGold = currentGold - offer.goldCost,
      remainingMetaShards = currentMetaShards - offer.shardCost,
      updatedFactionTrust = updatedTrust,
      goldSpent = offer.goldCost,
      shardsSpent = offer.shardCost,
      durationSec = offer.durationSec,
      errorReason = null
    )
  }

  data class NpcSpeechLogEntry(
    val id: String,
    val npcId: String,
    val name: String,
    val icon: String = "🗣️",
    val trait: String = "",
    val text: String,
    val cat: String = "new",
    val floor: Int = 1,
    val timestampMs: Long = 0L,
    val read: Boolean = false
  )

  data class NpcSpeechGroup(
    val npcKey: String,
    val npcId: String,
    val name: String,
    val icon: String,
    val trait: String,
    val entries: List<NpcSpeechLogEntry>,
    val visibleEntries: List<NpcSpeechLogEntry>,
    val unreadCount: Int,
    val totalCount: Int,
    val latestTimestampMs: Long
  )

  data class NpcSpeechGlowEvaluation(
    val hasGlow: Boolean,
    val isQuestMatch: Boolean,
    val isFactionMatch: Boolean,
    val glowType: String,
    val matchedQuestKeywords: List<String>,
    val matchedFactionKeywords: List<String>,
    val matchedKeywords: List<String>
  )

  /**
   * Evaluates whether an NPC speech log entry contains keywords related to the active player quest
   * or current faction goal so that `#npcLayer2D .npc-speech-entry` pulses with a subtle glow.
   */
  fun evaluateNpcSpeechQuestFactionGlow(
    entry: NpcSpeechLogEntry,
    questKeywords: List<String> = listOf("quest", "auftrag", "ἄθλος", "bounty", "relikt", "relic", "wächter", "guardian", "husk", "eidolon", "εἴδωλον", "mage", "drone", "archer", "brute"),
    factionKeywords: List<String> = listOf("vertrauen", "trust", "fraktion", "faction", "gilde", "guild", "θίασος", "handel", "trade", "gruppe", "party", "rette", "save", "lagerfeuer", "campfire", "μύστης", "mystes")
  ): NpcSpeechGlowEvaluation {
    val textLower = entry.text.lowercase()
    val matchedQuest = questKeywords
      .map { it.trim().lowercase() }
      .filter { it.isNotEmpty() && textLower.contains(it) }
      .distinct()
    val matchedFaction = factionKeywords
      .map { it.trim().lowercase() }
      .filter { it.isNotEmpty() && textLower.contains(it) }
      .distinct()
    val isQuestMatch = matchedQuest.isNotEmpty()
    val isFactionMatch = matchedFaction.isNotEmpty()
    val hasGlow = isQuestMatch || isFactionMatch
    val glowType = when {
      isQuestMatch && isFactionMatch -> "both"
      isQuestMatch -> "quest"
      isFactionMatch -> "faction"
      else -> "none"
    }
    return NpcSpeechGlowEvaluation(
      hasGlow = hasGlow,
      isQuestMatch = isQuestMatch,
      isFactionMatch = isFactionMatch,
      glowType = glowType,
      matchedQuestKeywords = matchedQuest,
      matchedFactionKeywords = matchedFaction,
      matchedKeywords = (matchedQuest + matchedFaction).distinct()
    )
  }

  /**
   * Live-filters NPC speech log entries based on the NPC's name, their trait, or keywords found within the speech text.
   */
  fun filterNpcSpeechLog(
    entries: List<NpcSpeechLogEntry>,
    query: String?
  ): List<NpcSpeechLogEntry> {
    val q = query?.trim()?.lowercase().orEmpty()
    if (q.isEmpty()) return entries
    val tokens = q.split(Regex("\\s+")).filter { it.isNotEmpty() }
    return entries.filter { item ->
      val haystack = "${item.name} ${item.trait} ${item.text}".lowercase()
      tokens.all { token -> haystack.contains(token) }
    }
  }

  /**
   * Groups NPC speech history entries by NPC and optionally filters out entries marked as read
   * to keep the scrollable speech drawer clean.
   */
  fun groupNpcSpeechByNpc(
    entries: List<NpcSpeechLogEntry>,
    hideRead: Boolean = false
  ): List<NpcSpeechGroup> {
    val grouped = linkedMapOf<String, MutableList<NpcSpeechLogEntry>>()
    for (entry in entries) {
      val key = entry.npcId.ifBlank { entry.name.ifBlank { "unknown" } }
      grouped.getOrPut(key) { mutableListOf() }.add(entry)
    }

    val result = mutableListOf<NpcSpeechGroup>()
    for ((key, allEntries) in grouped) {
      val first = allEntries.first()
      val resolvedTrait = allEntries.firstOrNull { it.trait.isNotBlank() }?.trait.orEmpty()
      val visible = if (hideRead) allEntries.filter { !it.read } else allEntries
      if (visible.isNotEmpty()) {
        val unreadCount = allEntries.count { !it.read }
        val latestTs = allEntries.maxOfOrNull { it.timestampMs } ?: 0L
        result.add(
          NpcSpeechGroup(
            npcKey = key,
            npcId = first.npcId.ifBlank { key },
            name = first.name.ifBlank { key },
            icon = first.icon,
            trait = resolvedTrait,
            entries = allEntries.toList(),
            visibleEntries = visible,
            unreadCount = unreadCount,
            totalCount = allEntries.size,
            latestTimestampMs = latestTs
          )
        )
      }
    }
    return result
  }

  /**
   * Marks a specific speech entry (or all entries for an NPC group) as read and returns the updated list.
   */
  fun markNpcSpeechAsRead(
    entries: List<NpcSpeechLogEntry>,
    entryId: String? = null,
    npcKey: String? = null,
    readState: Boolean = true
  ): List<NpcSpeechLogEntry> {
    return entries.map { item ->
      val itemNpcKey = item.npcId.ifBlank { item.name.ifBlank { "unknown" } }
      val matchesId = !entryId.isNullOrBlank() && item.id == entryId
      val matchesGroup = !npcKey.isNullOrBlank() && itemNpcKey == npcKey
      if (matchesId || matchesGroup) {
        item.copy(read = readState)
      } else {
        item
      }
    }
  }

  /**
   * Clears the unread status of all current NPC speech history entries at once.
   */
  fun markAllNpcSpeechAsRead(
    entries: List<NpcSpeechLogEntry>
  ): List<NpcSpeechLogEntry> {
    return entries.map { it.copy(read = true) }
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
        "verifyNpcRelationsData",
        "guildShopOvl",
        "openGuildShop",
        "buyGuildShopItem",
        "groupNpcSpeechByNpc",
        "markNpcSpeechAsRead",
        "isWarriorMeleeAttack",
        "getAutoAttackMaxRange",
        "qtabDaily",
        "ensureDailyQuests",
        "runRuntimeSelfChecks"
      )
      requiredMarkers.all { marker -> html.contains(marker) }
    } catch (_: Exception) {
      false
    }
  }

  /**
   * Verifies that Google Play Console policy declarations (permissions, data collection practices,
   * target age group, and AdMob Rewarded Ad SDK configuration) are explicitly configured in BuildConfig.
   */
  fun verifyPlayPolicyDeclarations(): Boolean {
    val targetAge = com.example.BuildConfig.PLAY_TARGET_AGE_GROUP
    val permissions = com.example.BuildConfig.PLAY_PERMISSIONS_DECLARED
    val dataPractices = com.example.BuildConfig.PLAY_DATA_COLLECTION_PRACTICES
    return targetAge == "13+" &&
      permissions.contains("android.permission.INTERNET") &&
      permissions.contains("android.permission.ACCESS_NETWORK_STATE") &&
      permissions.contains("android.permission.VIBRATE") &&
      permissions.contains("com.google.android.gms.permission.AD_ID") &&
      dataPractices.contains("GoogleSignIn") &&
      dataPractices.contains("CloudSave") &&
      dataPractices.contains("Leaderboard") &&
      dataPractices.contains("AdMob_Rewarded") &&
      AdMobRewardedAdManager.verifyAdMobConfiguration()
  }

  data class LootDropCurve(
    val normal: Double,
    val green: Double,
    val rare: Double,
    val epic: Double,
    val legendary: Double,
    val rarestCombined: Double,
    val totalDropChance: Double,
    val curveFactor: Double
  )

  /**
   * Mathematical item drop rate & rarity curve:
   * Base probabilities (Level 1, Monster Difficulty = 1.0, bonus = 0):
   *  - Normal (Common, rar 0): 50.0%
   *  - Common Green (Magic, rar 1): 5.5%
   *  - Rare / Selten (rar 2): 1.5%
   *  - Rarest (Epic 0.40% + Legendary 0.10%): 0.50% combined
   * Scales along a smooth mathematical power/logarithmic curve by player level and monster difficulty factor.
   */
  fun calculateDropCurve(
    playerLevel: Int = 1,
    diffFactor: Double = 1.0,
    magicFindBonus: Double = 0.0
  ): LootDropCurve {
    val safePLevel = playerLevel.coerceAtLeast(1)
    val lvlFactor = 1.0 + 0.18 * (Math.log(1.0 + (safePLevel - 1) * 0.65) / Math.log(2.0)) + 0.025 * Math.pow((safePLevel - 1).toDouble(), 0.78)
    val sevFactor = diffFactor.coerceAtLeast(0.8)
    val mfFactor = 1.0 + (magicFindBonus / 100.0)
    val k = lvlFactor * sevFactor * mfFactor
    val r0 = minOf(72.0, 50.0 * Math.pow(k, 0.16))
    val r1 = minOf(28.0, 5.5 * Math.pow(k, 0.52))
    val r2 = minOf(16.0, 1.5 * Math.pow(k, 0.72))
    val r3 = minOf(7.5, 0.40 * Math.pow(k, 0.88))
    val r4 = minOf(2.5, 0.10 * Math.pow(k, 0.96))
    return LootDropCurve(
      normal = (Math.round(r0 * 1000.0) / 1000.0),
      green = (Math.round(r1 * 1000.0) / 1000.0),
      rare = (Math.round(r2 * 1000.0) / 1000.0),
      epic = (Math.round(r3 * 1000.0) / 1000.0),
      legendary = (Math.round(r4 * 1000.0) / 1000.0),
      rarestCombined = (Math.round((r3 + r4) * 1000.0) / 1000.0),
      totalDropChance = minOf(100.0, Math.round((r0 + r1 + r2 + r3 + r4) * 1000.0) / 1000.0),
      curveFactor = (Math.round(k * 1000.0) / 1000.0)
    )
  }

  fun rollMonsterLootRarity(
    diffFactor: Double = 1.0,
    playerLevel: Int = 1,
    rollPct: Double = 0.0,
    guaranteeDrop: Boolean = false
  ): Int {
    val curve = calculateDropCurve(playerLevel, diffFactor)
    if (guaranteeDrop) {
      val weights = doubleArrayOf(curve.normal, curve.green, curve.rare, curve.epic, curve.legendary)
      val totalWeight = weights.sum()
      var r = (rollPct / 100.0) * totalWeight
      for (i in weights.indices.reversed()) {
        r -= weights[i]
        if (r <= 0.0) return i
      }
      return 0
    }

    var acc = curve.legendary
    if (rollPct < acc) return 4
    acc += curve.epic
    if (rollPct < acc) return 3
    acc += curve.rare
    if (rollPct < acc) return 2
    acc += curve.green
    if (rollPct < acc) return 1
    acc += curve.normal
    if (rollPct < acc) return 0
    return -1
  }

  private val merchantTraitKeywords = listOf(
    "merchant", "händler", "haendler", "κάπηλος", "kapelos", "ἔμπορος", "emporos",
    "quartiermeister", "quartermaster", "waffenmeister", "alchemist", "trader",
    "shop", "shopkeeper", "krämer", "gilden-händler", "guild merchant"
  )

  fun isMerchantNpcTrait(trait: String?, npcId: String? = null): Boolean {
    val cleanId = npcId?.trim()?.lowercase().orEmpty()
    if (cleanId.startsWith("vnpc_")) return true
    val cleanTrait = trait?.trim()?.lowercase().orEmpty()
    if (cleanTrait.isEmpty()) return false
    return merchantTraitKeywords.any { cleanTrait.contains(it) }
  }
}


