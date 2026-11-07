package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.util.GameRuntimeValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `app title complies with Google Play metadata policy`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Last Relic", appName)
    assertTrue("App title must be <= 30 chars", appName.length <= GameRuntimeValidator.MAX_APP_TITLE_LENGTH)
    assertFalse("App title must not be ALL CAPS", appName == appName.uppercase())
  }

  @Test
  fun `bundled WebGL assets and HUD selectors pass runtime integrity check`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    assertTrue(
      "Bundled index.html and three.min.js must exist and contain all required HUD/runtime markers",
      GameRuntimeValidator.verifyBundledAssets(context)
    )

    val html = context.assets.open("index.html").bufferedReader().use { it.readText() }
    assertTrue(html.contains("#hud .minion-badge"))
    assertTrue(html.contains("#hud .minion-badge .minion-skull"))
    assertTrue(html.contains("@keyframes minionSpringPop"))
    assertTrue(html.contains("@keyframes necroOverloadPulse"))
    assertTrue(html.contains("@keyframes summonReadyFloat"))
    assertTrue(html.contains("quest_complete"))
    assertTrue(html.contains("btnViewLogs"))
    assertTrue(html.contains("NPCTalk2D"))
    assertTrue(html.contains("4.7.2+"))
    assertTrue(html.contains("targetLocked"))
    assertTrue(html.contains("btnRelations"))
    assertTrue(html.contains("npcRelationsOvl"))
    assertTrue(html.contains("FACTION_TIERS"))
    assertTrue(html.contains("getFactionTier"))
    assertTrue(html.contains("adv.state==='camp'"))
    assertTrue(html.contains("Lagerfeuer-Rast"))
    assertTrue(html.contains("P.campBuff"))
    assertTrue(html.contains("historyNodes"))
    assertTrue(html.contains("Traded"))
    assertTrue(html.contains("Conflict"))
    assertTrue(html.contains("qtabDaily"))
    assertTrue(html.contains("qtabVillage"))
    assertTrue(html.contains("DAILY_QUEST_TEMPLATES"))
    assertTrue(html.contains("ensureDailyQuests"))
    assertTrue(html.contains("dailyResetTime"))
    assertTrue(html.contains("daily_complete_toast"))
    assertTrue(html.contains("timerPopAlert"))
    assertTrue(html.contains("triggerDailyResetAlert"))
    assertTrue(html.contains("tickPauseDailyTimer"))
    assertTrue(html.contains("startPauseTimerInterval"))
    assertTrue(html.contains("daily-flash-anim"))
    assertTrue(html.contains("screen-flash-overlay"))
    assertTrue(html.contains("monitorDailyResetCycle"))
    assertTrue(html.contains("startDailyResetWatcher"))
    assertTrue(html.contains("#npcLayer2D .npc-speech-feed-wrap"))
    assertTrue(html.contains("npcSpeechFloat"))
    assertTrue(html.contains("npcSpeechFeedList"))
    assertTrue(html.contains("npcInteractionLogBox"))
    assertTrue(html.contains("npcSpeechHistoryList"))
    assertTrue(html.contains("recordNpcSpeech"))
    assertTrue(html.contains("guildShopOvl"))
    assertTrue(html.contains("GUILD_SHOP_CATALOG"))
    assertTrue(html.contains("openGuildShop"))
    assertTrue(html.contains("buyGuildShopItem"))
    assertTrue(html.contains("tryClickVillageNpc3D"))
    assertTrue(html.contains("groupNpcSpeechByNpc"))
    assertTrue(html.contains("markNpcSpeechAsRead"))
    assertTrue(html.contains("npc-speech-mark-read-btn"))
    assertTrue(html.contains("npc-speech-group"))
    assertTrue(html.contains("resolveNpcTrait"))
    assertTrue(html.contains("npc-speech-trait-badge"))
    assertTrue(html.contains("data-live-filter=\"name,trait,text\""))
    assertTrue(html.contains("#npcLayer2D .npc-speech-entry.quest-faction-glow"))
    assertTrue(html.contains("@keyframes npcSpeechQuestFactionPulseGlow"))
    assertTrue(html.contains("evaluateNpcSpeechQuestFactionGlow"))
    assertTrue(html.contains("getActiveQuestAndFactionKeywords"))
    assertTrue(html.contains("btnHeaderMarkAllSpeechRead"))
    assertTrue(html.contains("npc-speech-header-mark-all-btn"))
    assertTrue(html.contains("Mark All as Read"))
    assertTrue(html.contains("isMerchantNpcTrait"))
    assertTrue(html.contains("triggerNpcQuickTrade"))
    assertTrue(html.contains("npc-speech-quick-trade-btn"))
    assertTrue(html.contains("Quick Trade"))
    assertTrue(html.contains("@keyframes npcCardPopIn"))
    assertTrue(html.contains("@keyframes npcCardExit"))
    assertTrue(html.contains("#npcLayer2D .npc-card-2d.pop-in"))
    assertTrue(html.contains("#npcLayer2D .npc-card-2d.is-exiting"))
    assertTrue(html.contains("triggerNpcCardPopIn"))
    assertTrue(html.contains("triggerNpcCardExit"))
  }

  @Test
  fun `validateAndSanitizeSaveJson sanitizes valid and out-of-bounds payloads`() {
    val raw = """{"lvl":12,"floor":4,"gold":550,"metaShards":25,"playerClass":"necromancer","shakeMult":1.5,"npcFactionTrust":45}"""
    val result = GameRuntimeValidator.validateAndSanitizeSaveJson(raw)
    assertTrue(result.isValid)
    assertEquals(12L, result.level)
    assertEquals(4L, result.floor)
    assertEquals(550L, result.gold)
    assertEquals(25L, result.metaShards)
    assertEquals("necromancer", result.playerClass)
    assertEquals(45L, result.npcFactionTrust)

    val malformed = GameRuntimeValidator.validateAndSanitizeSaveJson("not-a-json")
    assertFalse(malformed.isValid)
    assertEquals(1L, malformed.level)
    assertEquals("warrior", malformed.playerClass)
    assertEquals(0L, malformed.npcFactionTrust)
  }

  @Test
  fun `AndroidManifest declares portrait and touchscreen features and Play policy metadata`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val pkgManager = context.packageManager
    val pkgInfo = pkgManager.getPackageInfo(
      context.packageName,
      android.content.pm.PackageManager.GET_CONFIGURATIONS or
        android.content.pm.PackageManager.GET_PERMISSIONS or
        android.content.pm.PackageManager.GET_META_DATA
    )

    val featureNames = pkgInfo.reqFeatures?.mapNotNull { it.name }?.toSet() ?: emptySet()
    assertTrue(
      "Manifest must declare android.hardware.screen.portrait",
      "android.hardware.screen.portrait" in featureNames
    )
    assertTrue(
      "Manifest must declare android.hardware.touchscreen",
      "android.hardware.touchscreen" in featureNames
    )

    val requestedPermissions = pkgInfo.requestedPermissions?.toSet() ?: emptySet()
    assertTrue("android.permission.INTERNET" in requestedPermissions)
    assertTrue("android.permission.ACCESS_NETWORK_STATE" in requestedPermissions)
    assertTrue("android.permission.VIBRATE" in requestedPermissions)

    val appInfo = pkgManager.getApplicationInfo(
      context.packageName,
      android.content.pm.PackageManager.GET_META_DATA
    )
    assertEquals("13+", appInfo.metaData?.getString("com.google.play.target_age_group"))
    assertEquals(true, appInfo.metaData?.getBoolean("com.google.play.data_safety_declared"))
  }

  @Test
  fun `MainActivity launches cleanly without crashing or finishing on startup`() {
    val controller = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).setup()
    val activity = controller.get()
    assertFalse("MainActivity must not finish immediately on startup", activity.isFinishing)
    assertFalse("MainActivity must not be destroyed on startup", activity.isDestroyed)
    controller.pause().stop().destroy()
  }

  @Test
  fun `MainActivity and GameRepository survive missing FirebaseApp initialization without crashing`() {
    com.google.firebase.FirebaseApp.clearInstancesForTest()
    val repository = com.example.data.GameRepository(db = null)
    val viewModel = com.example.ui.GameViewModel(
      repository = repository,
      currentUserId = "local_offline_hero",
      defaultPlayerName = "RelicHunter"
    )
    assertEquals("local_offline_hero", viewModel.currentUserId)
    assertEquals("RelicHunter", viewModel.defaultPlayerName)
    assertEquals("[]", viewModel.getGlobalLeaderboardJson())

    val controller = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).setup()
    val activity = controller.get()
    assertFalse("MainActivity must stay open even after FirebaseApp instances were cleared", activity.isFinishing)
    controller.pause().stop().destroy()
  }

  @Test
  fun `Robolectric SDK 36 environment and Android SDK JAR provider are properly configured`() {
    assertEquals("Robolectric must execute against Android SDK 36", 36, android.os.Build.VERSION.SDK_INT)
    val javaSpecVersion = System.getProperty("java.specification.version")?.substringBefore(".")?.toIntOrNull() ?: 0
    assertTrue(
      "Robolectric SDK 36 requires Java 21+ runtime to prevent DefaultSdkProvider UnsupportedOperationException",
      javaSpecVersion >= 21
    )
    val context = ApplicationProvider.getApplicationContext<Context>()
    assertEquals("com.aistudio.lastrelic.xkvpzr", context.packageName)
  }
}

