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
  }

  @Test
  fun `Google Play Console policy declarations are explicitly configured`() {
    assertTrue(GameRuntimeValidator.verifyPlayPolicyDeclarations())
    assertEquals("13+", BuildConfig.PLAY_TARGET_AGE_GROUP)
  }
}

