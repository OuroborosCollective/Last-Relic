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
    assertEquals(10L, malformed.npcFactionTrust)
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
}

