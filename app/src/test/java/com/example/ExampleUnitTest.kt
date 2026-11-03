package com.example

import com.example.util.GameRuntimeValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

  @Test
  fun `calculateMaxMinions returns 5 base for necromancer and 3 for warrior`() {
    assertEquals(5, GameRuntimeValidator.calculateMaxMinions("necromancer", 0))
    assertEquals(7, GameRuntimeValidator.calculateMaxMinions("necromancer", 2))
    assertEquals(3, GameRuntimeValidator.calculateMaxMinions("warrior", 0))
    assertEquals(4, GameRuntimeValidator.calculateMaxMinions("mage", 1))
  }

  @Test
  fun `isNecromancerOverload detects maximum minion capacity`() {
    val maxMinions = GameRuntimeValidator.calculateMaxMinions("necromancer", 1) // 6
    assertFalse(GameRuntimeValidator.isNecromancerOverload(4, maxMinions))
    assertFalse(GameRuntimeValidator.isNecromancerOverload(5, maxMinions))
    assertTrue(GameRuntimeValidator.isNecromancerOverload(6, maxMinions))
    assertTrue(GameRuntimeValidator.isNecromancerOverload(7, maxMinions))
  }

  @Test
  fun `validateLeaderboardSubmission sanitizes name class and clamps bounds`() {
    val validated = GameRuntimeValidator.validateLeaderboardSubmission(
      playerName = "   \u0000ShadowNecro   ",
      playerClass = "NECROMANCER",
      score = 2_000_000_000L,
      level = 0L,
      floor = -5L,
      kills = 450L,
      itemsFound = 35L,
      fallbackName = "FallbackHero"
    )
    assertEquals("ShadowNecro", validated.playerName)
    assertEquals("necromancer", validated.playerClass)
    assertEquals(1_000_000_000L, validated.score)
    assertEquals(1L, validated.level)
    assertEquals(1L, validated.floor)
    assertEquals(450L, validated.kills)
    assertEquals(35L, validated.itemsFound)
  }

  @Test
  fun `validateLeaderboardSubmission falls back on blank name and invalid class`() {
    val validated = GameRuntimeValidator.validateLeaderboardSubmission(
      playerName = "   ",
      playerClass = "unknown_class",
      score = 1500L,
      level = 5L,
      floor = 3L,
      kills = 40L,
      itemsFound = 8L,
      fallbackName = "RelicHunter"
    )
    assertEquals("RelicHunter", validated.playerName)
    assertEquals("warrior", validated.playerClass)
  }

  @Test
  fun `GameMonitor detects frame rate dips and high memory usage and logs findings`() {
    val monitor = com.example.util.GameMonitor(
      fpsDipThreshold = 45.0,
      frameTimeSpikeMsThreshold = 28.0,
      memoryWarningRatioThreshold = 0.80,
      logToAndroidLogcat = false
    )

    // Healthy sample with combat scene telemetry
    val healthy = monitor.recordSample(
      fps = 60.0,
      avgFrameTimeMs = 16.6,
      webGlHeapMb = 22.4,
      dungeonFloor = 3,
      playerLevel = 7,
      entityCount = 14,
      activeParticles = 85,
      activeBullets = 24,
      overrideUsedJvmMb = 64.0,
      overrideMaxJvmMb = 256.0
    )
    assertEquals(60, healthy.currentFps)
    assertEquals(3, healthy.dungeonFloor)
    assertEquals(7, healthy.playerLevel)
    assertEquals(14, healthy.entityCount)
    assertEquals(85, healthy.activeParticles)
    assertEquals(24, healthy.activeBullets)
    assertFalse(healthy.isFrameRateDip)
    assertFalse(healthy.isMemoryPressure)
    assertEquals(0, healthy.frameDipCount)
    assertTrue(healthy.dipSnapshots.isEmpty())
    assertEquals(25, healthy.memoryUsagePercent)

    // Frame dip + memory pressure sample saves a DipSnapshot with floor, level, entities, particles, and bullets
    val dipped = monitor.recordSample(
      fps = 32.0,
      avgFrameTimeMs = 31.2,
      webGlHeapMb = 38.0,
      dungeonFloor = 5,
      playerLevel = 11,
      entityCount = 42,
      activeParticles = 310,
      activeBullets = 96,
      overrideUsedJvmMb = 220.0,
      overrideMaxJvmMb = 256.0
    )
    assertEquals(32, dipped.currentFps)
    assertTrue(dipped.isFrameRateDip)
    assertTrue(dipped.isMemoryPressure)
    assertEquals(1, dipped.frameDipCount)
    assertEquals(1, dipped.memoryWarningCount)
    assertEquals(1, dipped.dipSnapshots.size)

    val dipSnap = dipped.dipSnapshots.first()
    assertEquals(5, dipSnap.dungeonFloor)
    assertEquals(11, dipSnap.playerLevel)
    assertEquals(42, dipSnap.entityCount)
    assertEquals(310, dipSnap.activeParticles)
    assertEquals(96, dipSnap.activeBullets)
    assertTrue(dipSnap.formatLogLine().contains("Floor 5"))
    assertTrue(dipSnap.formatLogLine().contains("Lv 11"))
    assertTrue(dipSnap.formatLogLine().contains("Entities 42"))
    assertTrue(dipped.recentFindings.any { it.contains("Frame dip detected") })
    assertTrue(dipped.recentFindings.any { it.contains("Memory pressure") })

    // Daily 24h cycle monitoring during active gameplay
    val futureReset = 1_800_000_000_000L
    val beforeExpiry = monitor.monitorDailyResetCycle(
      dailyResetTimeMs = futureReset,
      currentTimeMs = futureReset - 10_000L,
      isActiveGameplay = true
    )
    assertEquals(null, beforeExpiry)
    assertFalse(monitor.snapshot.value.isDailyCycleExpired)
    assertEquals(0, monitor.snapshot.value.dailyCycleResetCount)

    // When paused/inactive gameplay, should not trigger toast yet
    val whilePaused = monitor.monitorDailyResetCycle(
      dailyResetTimeMs = futureReset,
      currentTimeMs = futureReset + 1_000L,
      isActiveGameplay = false
    )
    assertEquals(null, whilePaused)

    // When active gameplay and 24h cycle expires, returns non-intrusive toast message once
    val onExpiredActive = monitor.monitorDailyResetCycle(
      dailyResetTimeMs = futureReset,
      currentTimeMs = futureReset + 2_000L,
      isActiveGameplay = true
    )
    assertTrue(onExpiredActive != null && onExpiredActive.contains("24h"))
    assertTrue(monitor.snapshot.value.isDailyCycleExpired)
    assertEquals(1, monitor.snapshot.value.dailyCycleResetCount)
    assertTrue(monitor.snapshot.value.recentFindings.any { it.contains("Daily quest 24h cycle expired") })

    // Duplicate call for the same reset timestamp does not spam toast
    val duplicateCheck = monitor.monitorDailyResetCycle(
      dailyResetTimeMs = futureReset,
      currentTimeMs = futureReset + 5_000L,
      isActiveGameplay = true
    )
    assertEquals(null, duplicateCheck)
  }

  @Test
  fun `GameMonitor automatically prunes logs older than 7 days when View Logs is opened`() {
    val monitor = com.example.util.GameMonitor(
      fpsDipThreshold = 45.0,
      frameTimeSpikeMsThreshold = 28.0,
      memoryWarningRatioThreshold = 0.80,
      logToAndroidLogcat = false
    )

    val nowMs = 1_800_000_000_000L
    val eightDaysAgoMs = nowMs - (8L * 24L * 60L * 60L * 1000L)
    val tenDaysAgoMs = nowMs - (10L * 24L * 60L * 60L * 1000L)
    val twoDaysAgoMs = nowMs - (2L * 24L * 60L * 60L * 1000L)

    // Record two old dip snapshots (> 7 days old) and one recent dip snapshot (< 7 days old)
    monitor.recordSample(
      fps = 28.0,
      avgFrameTimeMs = 35.0,
      webGlHeapMb = 30.0,
      dungeonFloor = 2,
      playerLevel = 4,
      entityCount = 25,
      activeParticles = 150,
      activeBullets = 40,
      sampleTimeMs = tenDaysAgoMs
    )
    monitor.recordSample(
      fps = 30.0,
      avgFrameTimeMs = 33.0,
      webGlHeapMb = 32.0,
      dungeonFloor = 3,
      playerLevel = 6,
      entityCount = 28,
      activeParticles = 180,
      activeBullets = 45,
      sampleTimeMs = eightDaysAgoMs
    )
    monitor.recordSample(
      fps = 34.0,
      avgFrameTimeMs = 30.0,
      webGlHeapMb = 29.0,
      dungeonFloor = 7,
      playerLevel = 14,
      entityCount = 35,
      activeParticles = 210,
      activeBullets = 60,
      sampleTimeMs = twoDaysAgoMs
    )

    // Opening 'View Logs' triggers automatic pruning of logs older than 7 days
    val afterOpen = monitor.onOpenViewLogs(currentTimeMs = nowMs)
    assertEquals(1, afterOpen.dipSnapshots.size)
    assertEquals(7, afterOpen.dipSnapshots.first().dungeonFloor)
    assertEquals(14, afterOpen.dipSnapshots.first().playerLevel)
    assertTrue(afterOpen.prunedLogsCount >= 2)
  }

  @Test
  fun `Web asset index html includes 7-day log pruning, NPC Battle Stats, 3D weapon models, and mathematical drop curve`() {
    val htmlFile = java.io.File("src/main/assets/index.html").let {
      if (it.exists()) it else java.io.File("app/src/main/assets/index.html")
    }
    assertTrue("index.html must exist", htmlFile.exists())
    val content = htmlFile.readText()

    // 1. GameMonitor 7-day log pruning on View Logs open
    assertTrue(content.contains("LOG_RETENTION_DAYS"))
    assertTrue(content.contains("pruneOldLogs("))
    assertTrue(content.contains("onOpenViewLogs("))

    // 2. NPC Relations Battle Stats section with companion combat progression tier badge & progress bar
    assertTrue(content.contains("id=\"npcBattleStatsBox\""))
    assertTrue(content.contains("id=\"npcBattleStatsGrid\""))
    assertTrue(content.contains("enemiesDefeated"))
    assertTrue(content.contains("timesSavedPlayer"))
    assertTrue(content.contains("function getCompanionCombatTier("))
    assertTrue(content.contains("battle-tier-badge"))
    assertTrue(content.contains("battle-exp-bar-bg"))
    assertTrue(content.contains("battle-exp-bar-fill"))
    assertTrue(content.contains("data-combat-tier"))
    assertTrue(content.contains("factionModifiers"))

    // 3. Individual 3D weapon & gear models and weapon-type inventory icons
    assertTrue(content.contains("function buildWeapon3DModel("))
    assertTrue(content.contains("function buildGearVisual3D("))
    assertTrue(content.contains("function getItemIconHtml("))

    // 4. Mathematical item drop rate & rarity curve (50% Normal, 5.5% Common Green, 1.5% Rare, 0.5% Rarest)
    assertTrue(content.contains("function calcDropCurve("))
    assertTrue(content.contains("50.0 * Math.pow(k"))
    assertTrue(content.contains("5.5  * Math.pow(k"))
    assertTrue(content.contains("1.5  * Math.pow(k"))
    assertTrue(content.contains("0.40 * Math.pow(k"))
    assertTrue(content.contains("0.10 * Math.pow(k"))
    assertTrue(content.contains("function rollMonsterLootRarity("))
    assertTrue(content.contains("rollMonsterLootRarity(e, pLvl)"))
    // 5. Central QuestManager within GameMonitor decoupling quest tracking from UI rendering
    assertTrue(content.contains("class QuestManager"))
    assertTrue(content.contains("this.questManager=QUEST_MANAGER"))
    assertTrue(content.contains("QUEST_MANAGER.getVillageQuestsForRender()"))
    assertTrue(content.contains("QUEST_MANAGER.getDailyQuestsForRender()"))

    // 6. NPC Relations verification function & Warrior melee-only / short-range auto-attack
    assertTrue(content.contains("function verifyNpcRelationsData("))
    assertTrue(content.contains("function isWarriorMeleeAttack("))
    assertTrue(content.contains("function getAutoAttackMaxRange("))
    assertTrue(content.contains("startWeapon:'sword',meleeOnly:true"))

    // 7. verifyNpcRelationsData called in GameMonitor initialization & immediately before rendering NPC relations modal
    assertTrue(content.contains("verifyNpcRelationsData(typeof S!=='undefined'?S:null, typeof npcMemStore!=='undefined'?npcMemStore:null)"))
    assertTrue(content.contains("renderNpcRelationsModal"))
  }

  @Test
  fun `verifyNpcRelationsData derives trust and battle stats from actual gameplay data rather than default values`() {
    // 1. When no historyNodes or combat metrics have occurred, hardcoded default trust or unbacked counters are eliminated
    val emptyRecords = listOf(
      GameRuntimeValidator.CompanionGameplayRecord(
        id = "kaelen",
        savedByPlayer = 3, // Unbacked by historyNodes -> must be ignored
        tradedCount = 2, // Unbacked by historyNodes -> must be ignored
        rawTrust = 50
      ),
      GameRuntimeValidator.CompanionGameplayRecord(id = "lyra", rawTrust = 10)
    )
    val initialCheck = GameRuntimeValidator.verifyNpcRelationsData(
      companions = emptyRecords,
      earnedQuestTrust = 0,
      rawFactionTrust = 50
    )
    assertTrue(initialCheck.isVerified)
    assertEquals(0, initialCheck.factionTrust)
    assertEquals(0, initialCheck.totalEnemiesDefeated)
    assertEquals(0, initialCheck.totalTimesSavedPlayer)
    assertEquals(0, initialCheck.totalCombatScore)
    assertEquals(0, initialCheck.totalInteractions)
    assertEquals(0, initialCheck.factionModifiers.unlockedTierLevel)
    assertEquals(1.0, initialCheck.factionModifiers.shopPriceMultiplier, 0.001)
    assertEquals(0, initialCheck.factionModifiers.shopDiscountPct)
    assertEquals(25, initialCheck.factionModifiers.npcTradeGoldCost)
    assertFalse(initialCheck.factionModifiers.unlockedPotion)
    assertTrue(initialCheck.companions.all {
      it.trust == 0 &&
        it.stance == "new" &&
        it.combatTier.level == 1 &&
        it.combatTier.id == "recruit" &&
        it.combatTier.progressPct == 0
    })

    // 2. When actual gameplay events occur (historyNodes + combat metrics), trust, combat tiers, and faction reward modifiers are strictly derived
    val activeRecords = listOf(
      GameRuntimeValidator.CompanionGameplayRecord(
        id = "kaelen",
        savedByPlayer = 1, // +25 trust (backed by "Saved" historyNode)
        tradedCount = 2, // +20 companion trust, +10 faction trust (backed by 2 "Traded" historyNodes)
        enemiesDefeated = 7,
        timesSavedPlayer = 2, // +10 companion trust (7 + 2*5 = 17 combatScore -> Level 3 Elite)
        historyNodeTypes = listOf("Saved", "Traded", "Traded_Whetstone", "PlayerSaved", "PlayerSaved", "EnemyKill")
      ),
      GameRuntimeValidator.CompanionGameplayRecord(
        id = "draven",
        killedByPlayer = 1, // -40 companion trust, -30 faction trust (backed by "Conflict" historyNode)
        enemiesDefeated = 3, // 3 combatScore -> Level 1 Recruit (60% to Level 2)
        historyNodeTypes = listOf("Conflict", "EnemyKill", "EnemyKill", "EnemyKill")
      )
    )
    val verified = GameRuntimeValidator.verifyNpcRelationsData(
      companions = activeRecords,
      earnedQuestTrust = 15,
      rawFactionTrust = 0
    )
    assertTrue(verified.isVerified)
    assertTrue(verified.derivedFromGameplay)
    // Faction trust: Kaelen (+25 saved + 2*5 traded = +35) + Draven (-30 conflict) + 15 quest = 20
    assertEquals(20, verified.factionTrust)
    assertEquals(10, verified.totalEnemiesDefeated) // 7 + 3
    assertEquals(2, verified.totalTimesSavedPlayer)
    assertEquals(1, verified.totalSavedByPlayer)
    assertEquals(2, verified.totalTradedCount)
    assertEquals(1, verified.totalKilledByPlayer)
    assertEquals(20, verified.totalCombatScore) // 17 + 3

    // Verify strictly derived faction reward modifiers for factionTrust = 20 (Tier 1) + 2 player rescues + 1 whetstone node
    val mods = verified.factionModifiers
    assertEquals(1, mods.unlockedTierLevel)
    assertTrue(mods.unlockedPotion)
    assertFalse(mods.unlockedRation)
    assertEquals(0.90, mods.shopPriceMultiplier, 0.001)
    assertEquals(10, mods.shopDiscountPct)
    assertEquals(22, mods.npcTradeGoldCost) // 25 - (20/10) - (2/2) = 22
    assertEquals(12, mods.whetstoneDmgBonusPct) // 1 Traded_Whetstone node * 12%
    assertEquals(0.71, mods.potionHealRatio, 0.001) // 0.25 + 0.40 + 2*0.03 = 0.71
    assertEquals(31, mods.blessingDurationSec) // 25 + 2*3 = 31s

    val kaelen = verified.companions.first { it.id == "kaelen" }
    assertEquals("positive", kaelen.stance)
    assertEquals(55, kaelen.trust) // 25 + 20 + 10
    assertEquals(7, kaelen.enemiesDefeated)
    assertEquals(2, kaelen.timesSavedPlayer)
    assertEquals(3, kaelen.combatTier.level)
    assertEquals("elite", kaelen.combatTier.id)
    assertEquals("🛡️", kaelen.combatTier.icon)
    assertEquals(17, kaelen.combatTier.combatScore)
    assertEquals(13, kaelen.combatTier.progressPct) // (17 - 15) / (30 - 15) = 13%

    val draven = verified.companions.first { it.id == "draven" }
    assertEquals("negative", draven.stance)
    assertEquals(-40, draven.trust)
    assertEquals(3, draven.enemiesDefeated)
    assertEquals(1, draven.combatTier.level)
    assertEquals("recruit", draven.combatTier.id)
    assertEquals("🌱", draven.combatTier.icon)
    assertEquals(60, draven.combatTier.progressPct) // 3 / 5 = 60%
  }

  @Test
  fun `Warrior class is strictly melee and auto-attack only activates within a few meters in front of the player`() {
    // Warrior is always melee-only
    assertTrue(GameRuntimeValidator.isWarriorMeleeAttack("warrior", "sword"))
    assertTrue(GameRuntimeValidator.isWarriorMeleeAttack("warrior", "pistol"))
    assertFalse(GameRuntimeValidator.isWarriorMeleeAttack("mage", "staff"))
    assertFalse(GameRuntimeValidator.isWarriorMeleeAttack("rogue", "pistol"))

    // Auto-attack max range is short (2.65m for Warrior melee, 4.2m for ranged classes)
    assertEquals(2.65, GameRuntimeValidator.getAutoAttackMaxRange("warrior", "sword"), 0.001)
    assertEquals(4.20, GameRuntimeValidator.getAutoAttackMaxRange("mage", "staff"), 0.001)

    // Warrior auto-attacks an enemy 2.2m directly in front, but NOT 6m or 20m away across the map
    assertTrue(
      GameRuntimeValidator.canAutoAttackTarget(
        playerClass = "warrior",
        weaponKey = "sword",
        distanceToEnemy = 2.2,
        enemyRadius = 0.4,
        angleDiffRad = 0.2,
        hasLineOfSight = true
      )
    )
    assertFalse(
      GameRuntimeValidator.canAutoAttackTarget(
        playerClass = "warrior",
        weaponKey = "sword",
        distanceToEnemy = 6.0,
        enemyRadius = 0.4,
        angleDiffRad = 0.0,
        hasLineOfSight = true
      )
    )

    // Ranged class auto-attacks an enemy 3.5m in front with line of sight, but NOT across the map (9.5m) or behind the player or through walls
    assertTrue(
      GameRuntimeValidator.canAutoAttackTarget(
        playerClass = "mage",
        weaponKey = "staff",
        distanceToEnemy = 3.5,
        enemyRadius = 0.4,
        angleDiffRad = 0.3,
        hasLineOfSight = true
      )
    )
    assertFalse(
      GameRuntimeValidator.canAutoAttackTarget(
        playerClass = "mage",
        weaponKey = "staff",
        distanceToEnemy = 9.5,
        enemyRadius = 0.4,
        angleDiffRad = 0.0,
        hasLineOfSight = true
      )
    )
    assertFalse(
      GameRuntimeValidator.canAutoAttackTarget(
        playerClass = "mage",
        weaponKey = "staff",
        distanceToEnemy = 3.2,
        enemyRadius = 0.4,
        angleDiffRad = 2.5, // Behind the character
        hasLineOfSight = true
      )
    )
    assertFalse(
      GameRuntimeValidator.canAutoAttackTarget(
        playerClass = "mage",
        weaponKey = "staff",
        distanceToEnemy = 2.5,
        enemyRadius = 0.4,
        angleDiffRad = 0.0,
        hasLineOfSight = false // Blocked by wall
      )
    )
  }

  @Test
  fun `QuestManager within GameMonitor tracks and validates daily and village quests outside UI rendering`() {
    val monitor = com.example.util.GameMonitor(logToAndroidLogcat = false)
    val qm = monitor.questManager
    val nowMs = 1_800_000_000_000L

    // 1. Generate and validate floor-scaled Village Quests
    val villageQuests = qm.ensureVillageQuests(floor = 2)
    assertEquals(3, villageQuests.size)
    assertEquals("husk", villageQuests[0].target)
    assertEquals(7, villageQuests[0].amt) // 5 + floor(2)*1
    assertFalse(villageQuests[0].epic)
    assertTrue(villageQuests[2].epic) // 3rd quest is epic bounty
    assertEquals(0, villageQuests[0].pct)

    // 2. Generate and validate deterministic Daily Quests
    val dailyQuests = qm.ensureDailyQuests(
      floor = 2,
      currentTimeMs = nowMs,
      deterministicOrder = true
    )
    assertEquals(3, dailyQuests.size)
    assertEquals("daily_kill_all", dailyQuests[0].type)
    assertEquals(25, dailyQuests[0].amt)
    assertEquals("daily_boss", dailyQuests[1].type)
    assertEquals(2, dailyQuests[1].amt)
    assertEquals("legendary_item", dailyQuests[1].tierReward)

    // 3. Record enemy kills via central QuestManager (advances both village & daily quests)
    val killStep1 = qm.recordVillageKill("husk", amount = 3, floor = 2, timestampMs = nowMs)
    assertTrue(killStep1.changed)
    assertTrue(killStep1.completedVillageQuests.isEmpty())
    val renderSnap1 = qm.getVillageQuestsForRender()
    assertEquals(3, renderSnap1[0].killed)
    assertEquals(43, renderSnap1[0].pct)
    assertEquals(0, renderSnap1[0].startPct)

    // Complete the husk village quest
    val killStep2 = qm.recordVillageKill("husk", amount = 10, floor = 2, timestampMs = nowMs + 1000L)
    assertTrue(killStep2.changed)
    assertEquals(1, killStep2.completedVillageQuests.size)
    val completedHuskQuest = qm.getVillageQuestsForRender()[0]
    assertTrue(completedHuskQuest.done)
    assertEquals(7, completedHuskQuest.killed) // Clamped to amt
    assertEquals(100, completedHuskQuest.pct)
    assertEquals(43, completedHuskQuest.startPct) // Preserves previous pct for smooth UI bar transition
    assertEquals(1, qm.questState.value.totalCompletedVillageQuests)

    // 4. Unified recordEnemyDefeated advances boss daily quest and kill_all daily quest
    val bossRes1 = qm.recordEnemyDefeated(
      enemyType = "boss",
      isBoss = true,
      floor = 2,
      currentTimeMs = nowMs + 2000L
    )
    assertTrue(bossRes1.changed)
    assertEquals(1, qm.getDailyQuestsForRender()[0].progress) // daily_kill_all
    assertEquals(1, qm.getDailyQuestsForRender()[1].progress) // daily_boss (50%)
    assertEquals(50, qm.getDailyQuestsForRender()[1].pct)

    val bossRes2 = qm.recordEnemyDefeated(
      enemyType = "raidboss",
      isBoss = true,
      floor = 2,
      currentTimeMs = nowMs + 3000L
    )
    assertEquals(1, bossRes2.completedDailyQuests.size)
    assertTrue(qm.getDailyQuestsForRender()[1].done)
    assertEquals(100, qm.getDailyQuestsForRender()[1].pct)
    assertEquals(1, qm.questState.value.totalCompletedDailyQuests)

    // 5. Validate malformed/out-of-bounds quests via validateVillageQuest & syncFromJson
    val sanitizedInvalid = qm.validateVillageQuest(
      com.example.util.GameMonitor.VillageQuest(
        id = "",
        target = "INVALID_MONSTER",
        amt = -10,
        reward = -50,
        xp = 0,
        shards = -2,
        killed = 999,
        done = false
      ),
      indexHint = 0
    )
    assertEquals("husk", sanitizedInvalid.target)
    assertEquals(1, sanitizedInvalid.amt)
    assertEquals(1, sanitizedInvalid.killed)
    assertTrue(sanitizedInvalid.done)
    assertEquals(100, sanitizedInvalid.pct)
    assertEquals(1, sanitizedInvalid.reward)
    assertEquals(1, sanitizedInvalid.xp)
    assertEquals(1, sanitizedInvalid.shards)
  }

  @Test
  fun `Google Play Console policy declarations and AdMob Rewarded Ad SDK are explicitly configured`() {
    assertTrue(GameRuntimeValidator.verifyPlayPolicyDeclarations())
    assertEquals("13+", BuildConfig.PLAY_TARGET_AGE_GROUP)
    assertEquals("ca-app-pub-2526614647374921~8134608409", BuildConfig.ADMOB_APP_ID)
    assertEquals("ca-app-pub-2526614647374921/8246289044", BuildConfig.ADMOB_REWARDED_AD_UNIT_ID)
    assertEquals("Reward", BuildConfig.ADMOB_REWARD_NAME)
    assertEquals(1, BuildConfig.ADMOB_REWARD_AMOUNT)
    assertTrue(com.example.util.AdMobRewardedAdManager.verifyAdMobConfiguration())

    val grant = com.example.util.AdMobRewardedAdManager.calculateInGameReward("Reward", 1)
    assertEquals("Reward", grant.rewardName)
    assertEquals(1, grant.rewardAmount)
    assertEquals(75, grant.goldGranted)
    assertEquals(10, grant.shardsGranted)

    val manifestFile = java.io.File("src/main/AndroidManifest.xml").let {
      if (it.exists()) it else java.io.File("app/src/main/AndroidManifest.xml")
    }
    assertTrue("AndroidManifest.xml must exist", manifestFile.exists())
    val manifestXml = manifestFile.readText()
    assertTrue(manifestXml.contains("com.google.android.gms.ads.APPLICATION_ID"))
    assertTrue(manifestXml.contains("ca-app-pub-2526614647374921~8134608409"))
    assertTrue(manifestXml.contains("com.google.android.gms.permission.AD_ID"))
  }

  @Test
  fun `Paladin tank is strictly a melee fighter using warhammer with 2_65m auto attack range and no magic projectiles`() {
    // Paladin (tank) must be recognized as pure melee like Warrior
    assertTrue("Paladin tank class must perform melee-only attack", GameRuntimeValidator.isWarriorMeleeAttack("tank", "hammer"))
    assertTrue("Paladin with mace must perform melee-only attack", GameRuntimeValidator.isWarriorMeleeAttack("paladin", "mace"))
    assertTrue("Hammer is a registered melee weapon", "hammer" in GameRuntimeValidator.MELEE_WEAPON_KEYS)
    assertTrue("Sword is a registered melee weapon", "sword" in GameRuntimeValidator.MELEE_WEAPON_KEYS)

    // Paladin auto-attack range must be restricted to short melee reach (2.65m), never full map ranged (4.2m)
    assertEquals(2.65, GameRuntimeValidator.getAutoAttackMaxRange("tank", "hammer"), 0.001)
    assertEquals(2.65, GameRuntimeValidator.getAutoAttackMaxRange("warrior", "sword"), 0.001)
    assertEquals(4.20, GameRuntimeValidator.getAutoAttackMaxRange("mage", "staff"), 0.001)
    assertEquals(4.20, GameRuntimeValidator.getAutoAttackMaxRange("rogue", "pistol"), 0.001)

    // Verify auto-attack target candidate: Paladin can attack enemy at 1.8m in front
    assertTrue(GameRuntimeValidator.canAutoAttackTarget("tank", "hammer", distanceToEnemy = 1.8, angleDiffRad = 0.2))
    // Paladin cannot lock onto an enemy 10m away or across the map!
    assertFalse(GameRuntimeValidator.canAutoAttackTarget("tank", "hammer", distanceToEnemy = 5.0, angleDiffRad = 0.2))
  }

  @Test
  fun `Skill tree mutually exclusive branching locks the unselected branch and enforces sequential tiers`() {
    // Unallocated tree: no branch locked
    val initial = GameRuntimeValidator.validateSkillTreeSelection("tank", chosenBranch = null, chosenTier = 0)
    assertTrue(initial.isValid)
    assertFalse(initial.isOtherBranchLocked)

    // Choose Branch A Tier 1
    val branchASelected = GameRuntimeValidator.validateSkillTreeSelection(
      playerClass = "tank",
      chosenBranch = null,
      chosenTier = 0,
      attemptedBranch = "A",
      attemptedTier = 1,
      availableSkillPoints = 1
    )
    assertTrue(branchASelected.isValid)
    assertEquals("A", branchASelected.selectedBranch)
    assertEquals(1, branchASelected.selectedTier)
    assertEquals("B", branchASelected.lockedBranch)
    assertTrue(branchASelected.isOtherBranchLocked)

    // Attempting to skill Branch B when Branch A is chosen must fail (permanently locked / unskillable)
    val illegalBranchB = GameRuntimeValidator.validateSkillTreeSelection(
      playerClass = "tank",
      chosenBranch = "A",
      chosenTier = 1,
      attemptedBranch = "B",
      attemptedTier = 1,
      availableSkillPoints = 1
    )
    assertFalse("Branch B must be locked when Branch A is chosen", illegalBranchB.isValid)
    assertTrue(illegalBranchB.isOtherBranchLocked)
    assertEquals("B", illegalBranchB.lockedBranch)
    assertTrue(illegalBranchB.errorReason?.contains("permanently locked") == true)

    // Attempting to skip Tier 2 and unlock Tier 3 directly must fail
    val illegalSkip = GameRuntimeValidator.validateSkillTreeSelection(
      playerClass = "tank",
      chosenBranch = "A",
      chosenTier = 1,
      attemptedBranch = "A",
      attemptedTier = 3,
      availableSkillPoints = 1
    )
    assertFalse("Must unlock Tier 2 sequentially before Tier 3", illegalSkip.isValid)

    // Unlocking Tier 2 sequentially succeeds
    val tier2Success = GameRuntimeValidator.validateSkillTreeSelection(
      playerClass = "tank",
      chosenBranch = "A",
      chosenTier = 1,
      attemptedBranch = "A",
      attemptedTier = 2,
      availableSkillPoints = 1
    )
    assertTrue(tier2Success.isValid)
    assertEquals(2, tier2Success.selectedTier)
  }

  @Test
  fun `Enemy difficulty multiplier scales enemy HP and damage by 35 percent`() {
    assertEquals(1.35, GameRuntimeValidator.ENEMY_DIFFICULTY_MULTIPLIER, 0.001)

    // Verify scaled stats for husk and boss
    val baseHuskHp = 34.0
    val scaledHuskHp = Math.round(baseHuskHp * GameRuntimeValidator.ENEMY_DIFFICULTY_MULTIPLIER)
    assertEquals(46L, scaledHuskHp)

    val baseBossHp = 900.0
    val scaledBossHp = Math.round(baseBossHp * GameRuntimeValidator.ENEMY_DIFFICULTY_MULTIPLIER)
    assertEquals(1215L, scaledBossHp)
  }

  @Test
  fun `rollMonsterLootRarity weighted-probability curve conforms to 50 percent Common 5_5 percent Green 1_5 percent Rare 0_5 percent Rarest based on difficulty and player level`() {
    // 1. Base level 1, diffFactor 1.0 (Normal enemy)
    val baseCurve = GameRuntimeValidator.calculateDropCurve(playerLevel = 1, diffFactor = 1.0)
    assertEquals(50.0, baseCurve.normal, 0.001)
    assertEquals(5.5, baseCurve.green, 0.001)
    assertEquals(1.5, baseCurve.rare, 0.001)
    assertEquals(0.40, baseCurve.epic, 0.001)
    assertEquals(0.10, baseCurve.legendary, 0.001)
    assertEquals(0.50, baseCurve.rarestCombined, 0.001)
    assertEquals(57.5, baseCurve.totalDropChance, 0.001)

    // 2. High monster difficulty factor (e.g. boss 4.5) scales curve factor and boosts high rarities
    val bossCurve = GameRuntimeValidator.calculateDropCurve(playerLevel = 1, diffFactor = 4.5)
    assertTrue("Curve factor must scale with enemy difficulty", bossCurve.curveFactor >= 4.5)
    assertTrue("Boss Common chance scales", bossCurve.normal > baseCurve.normal)
    assertTrue("Boss Green chance scales", bossCurve.green > baseCurve.green)
    assertTrue("Boss Rare chance scales", bossCurve.rare > baseCurve.rare)
    assertTrue("Boss Epic chance scales", bossCurve.epic > baseCurve.epic)
    assertTrue("Boss Legendary chance scales", bossCurve.legendary > baseCurve.legendary)
    assertTrue("Boss Rarest combined chance scales", bossCurve.rarestCombined > baseCurve.rarestCombined)

    // 3. Higher player level scales curve factor and drop rates
    val highLvlCurve = GameRuntimeValidator.calculateDropCurve(playerLevel = 25, diffFactor = 1.0)
    assertTrue("Level factor must increase curve factor", highLvlCurve.curveFactor > 1.0)
    assertTrue("High level increases rare drop rate", highLvlCurve.rare > baseCurve.rare)
    assertTrue("High level increases rarest drop rate", highLvlCurve.rarestCombined > baseCurve.rarestCombined)

    // 4. rollMonsterLootRarity rolls
    // Rarest (Legendary / Epic)
    assertEquals(4, GameRuntimeValidator.rollMonsterLootRarity(diffFactor = 1.0, playerLevel = 1, rollPct = 0.05))
    assertEquals(3, GameRuntimeValidator.rollMonsterLootRarity(diffFactor = 1.0, playerLevel = 1, rollPct = 0.35))
    // Rare
    assertEquals(2, GameRuntimeValidator.rollMonsterLootRarity(diffFactor = 1.0, playerLevel = 1, rollPct = 1.0))
    // Green
    assertEquals(1, GameRuntimeValidator.rollMonsterLootRarity(diffFactor = 1.0, playerLevel = 1, rollPct = 3.0))
    // Common / Normal
    assertEquals(0, GameRuntimeValidator.rollMonsterLootRarity(diffFactor = 1.0, playerLevel = 1, rollPct = 25.0))
    // No drop (> 57.5% on level 1, diff 1.0)
    assertEquals(-1, GameRuntimeValidator.rollMonsterLootRarity(diffFactor = 1.0, playerLevel = 1, rollPct = 80.0))

    // Guaranteed drop selects proportionally among rarities
    val guaranteedRar = GameRuntimeValidator.rollMonsterLootRarity(diffFactor = 1.0, playerLevel = 1, rollPct = 50.0, guaranteeDrop = true)
    assertTrue("Guaranteed drop must return valid rarity 0..4", guaranteedRar in 0..4)
  }
}

