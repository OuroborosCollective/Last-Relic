package com.example.ui

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.ui.platform.LocalContext
import com.example.BuildConfig
import com.example.data.CloudSave
import com.example.data.LeaderboardEntry
import com.example.util.AdMobRewardedAdManager
import com.example.util.GameMonitor
import kotlinx.coroutines.delay
import org.json.JSONObject

class AndroidWebBridge(
  private val viewModel: GameViewModel,
  private val gameMonitor: GameMonitor,
  private val mainHandler: Handler,
  private val onWebViewEvaluate: (String) -> Unit
) {
  @JavascriptInterface
  fun syncCloudSave(saveJson: String) {
    viewModel.syncCloudSaveFromJs(saveJson)
  }

  @JavascriptInterface
  fun getCloudSaveJson(): String {
    return viewModel.getLatestCloudSaveJson()
  }

  @JavascriptInterface
  fun submitScore(
    name: String,
    playerClass: String,
    score: Long,
    level: Long,
    floor: Long,
    kills: Long,
    items: Long
  ) {
    viewModel.submitScoreFromJs(
      playerName = name,
      playerClass = playerClass,
      score = score,
      level = level,
      floor = floor,
      kills = kills,
      itemsFound = items
    )
  }

  @JavascriptInterface
  fun getGlobalLeaderboardJson(): String {
    return viewModel.getGlobalLeaderboardJson()
  }

  @JavascriptInterface
  fun showGlobalLeaderboard() {
    mainHandler.post {
      viewModel.toggleCloudPanel(true)
    }
  }

  @JavascriptInterface
  fun showLeaderboard() {
    mainHandler.post {
      viewModel.toggleCloudPanel(true)
    }
  }

  @JavascriptInterface
  fun onRunEnded(score: Long, floor: Long, level: Long) {
    mainHandler.post {
      viewModel.requestInterstitialAdOnRunEnd()
    }
  }

  @JavascriptInterface
  fun showRewardedAd() {
    mainHandler.post {
      viewModel.requestRewardedAd()
    }
  }

  @JavascriptInterface
  fun getAdMobConfigJson(): String {
    return JSONObject().apply {
      put("appId", AdMobRewardedAdManager.ADMOB_APP_ID)
      put("rewardedAdUnitId", AdMobRewardedAdManager.REWARDED_AD_UNIT_ID)
      put("rewardName", AdMobRewardedAdManager.REWARD_NAME)
      put("rewardAmount", AdMobRewardedAdManager.REWARD_AMOUNT)
      put("isAdReady", AdMobRewardedAdManager.isAdReady.value)
    }.toString()
  }

  @JavascriptInterface
  fun openRemoveAds() {
    mainHandler.post {
      viewModel.openIapDialog()
    }
  }

  @JavascriptInterface
  fun getPlayerName(): String {
    return viewModel.defaultPlayerName
  }

  @JavascriptInterface
  fun reportPerformanceMetrics(
    fps: Double,
    avgFrameTimeMs: Double,
    webGlHeapMb: Double,
    dungeonFloor: Int,
    playerLevel: Int,
    entityCount: Int,
    activeParticles: Int,
    activeBullets: Int
  ) {
    gameMonitor.recordSample(
      fps = fps,
      avgFrameTimeMs = avgFrameTimeMs,
      webGlHeapMb = webGlHeapMb,
      dungeonFloor = dungeonFloor,
      playerLevel = playerLevel,
      entityCount = entityCount,
      activeParticles = activeParticles,
      activeBullets = activeBullets
    )
  }

  @JavascriptInterface
  fun reportDailyResetCycle(
    dailyResetTimeMs: Long,
    currentTimeMs: Long,
    isActiveGameplay: Boolean
  ) {
    val toastMsg = gameMonitor.monitorDailyResetCycle(
      dailyResetTimeMs = dailyResetTimeMs,
      currentTimeMs = currentTimeMs,
      isActiveGameplay = isActiveGameplay
    )
    if (toastMsg != null) {
      mainHandler.post {
        viewModel.showStatus(toastMsg)
      }
    }
  }

  @JavascriptInterface
  fun onViewLogsOpened(): Int {
    return gameMonitor.pruneOldLogs()
  }

  @JavascriptInterface
  fun syncQuestState(
    villageQuestsJson: String,
    dailyQuestsJson: String,
    dailyResetTimeMs: Long,
    floor: Int
  ) {
    gameMonitor.questManager.syncFromJson(
      villageQuestsJson = villageQuestsJson,
      dailyQuestsJson = dailyQuestsJson,
      dailyResetTimeMs = dailyResetTimeMs,
      floor = floor
    )
  }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GameScreen(
  viewModel: GameViewModel,
  onSignOut: () -> Unit
) {
  val cloudSaveState by viewModel.cloudSaveState.collectAsStateWithLifecycle()
  val leaderboardState by viewModel.leaderboardState.collectAsStateWithLifecycle()
  val adsRemoved by viewModel.adsRemoved.collectAsStateWithLifecycle()
  val showInterstitialAd by viewModel.showInterstitialAd.collectAsStateWithLifecycle()
  val showRewardedAd by viewModel.showRewardedAd.collectAsStateWithLifecycle()
  val showIapDialog by viewModel.showIapDialog.collectAsStateWithLifecycle()
  val showCloudPanel by viewModel.showCloudPanel.collectAsStateWithLifecycle()
  val statusBanner by viewModel.statusBanner.collectAsStateWithLifecycle()

  val context = LocalContext.current
  var webViewRef by remember { mutableStateOf<WebView?>(null) }
  val mainHandler = remember { Handler(Looper.getMainLooper()) }
  val gameMonitor = remember { GameMonitor() }
  val perfSnapshot by gameMonitor.snapshot.collectAsStateWithLifecycle()
  val isAdMobReady by AdMobRewardedAdManager.isAdReady.collectAsStateWithLifecycle()
  var showPerfOverlay by remember { mutableStateOf(false) }
  var showFallbackRewardedModal by remember { mutableStateOf(false) }

  LaunchedEffect(context) {
    AdMobRewardedAdManager.initialize(context)
  }

  // Periodic JVM, frame rate health, and 24h dailyResetTime background check every 3 seconds
  LaunchedEffect(gameMonitor) {
    while (true) {
      gameMonitor.checkJvmPerformance()
      val toastMsg = gameMonitor.monitorDailyResetCycle(
        dailyResetTimeMs = gameMonitor.snapshot.value.dailyResetTime,
        currentTimeMs = System.currentTimeMillis(),
        isActiveGameplay = true
      )
      if (toastMsg != null) {
        viewModel.showStatus(toastMsg)
      }
      delay(3000L)
    }
  }

  // Auto-dismiss status banner after 3.5 seconds
  LaunchedEffect(statusBanner) {
    if (statusBanner != null) {
      delay(3500L)
      viewModel.clearStatus()
    }
  }

  // Notify WebView when adsRemoved becomes true
  LaunchedEffect(adsRemoved) {
    if (adsRemoved) {
      webViewRef?.evaluateJavascript("if(window.onAdsRemoved) window.onAdsRemoved();", null)
    }
  }

  // Also inject initial cloud save into WebView once loaded if local save is empty
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_PAUSE -> webViewRef?.onPause()
        Lifecycle.Event.ON_RESUME -> webViewRef?.onResume()
        else -> {}
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
    }
  }

  BackHandler(enabled = showCloudPanel || showIapDialog) {
    if (showIapDialog) viewModel.closeIapDialog()
    else if (showCloudPanel) viewModel.toggleCloudPanel(false)
  }

  Scaffold(
    contentWindowInsets = WindowInsets(0, 0, 0, 0),
    containerColor = Color(0xFF05060A)
  ) { innerPadding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
        .background(Color(0xFF05060A))
    ) {
      // Main 3D WebGL Game Viewport + Native Play Games Overlay
      Box(
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth()
      ) {
        AndroidView(
          modifier = Modifier
            .fillMaxSize()
            .testTag("game_webview"),
          factory = { ctx ->
            try {
              WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                  ViewGroup.LayoutParams.MATCH_PARENT,
                  ViewGroup.LayoutParams.MATCH_PARENT
                )
                runCatching { setLayerType(View.LAYER_TYPE_HARDWARE, null) }
                setBackgroundColor(android.graphics.Color.parseColor("#05060A"))
                isFocusable = true
                isFocusableInTouchMode = true

                runCatching {
                  settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    allowFileAccess = true
                    allowContentAccess = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    setSupportZoom(false)
                    builtInZoomControls = false
                    displayZoomControls = false
                  }
                }

                val bridge = AndroidWebBridge(
                  viewModel = viewModel,
                  gameMonitor = gameMonitor,
                  mainHandler = mainHandler,
                  onWebViewEvaluate = { script ->
                    runCatching { evaluateJavascript(script, null) }
                  }
                )
                runCatching { addJavascriptInterface(bridge, "AndroidBridge") }

                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                  override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (adsRemoved) {
                      runCatching {
                        view?.evaluateJavascript("if(window.onAdsRemoved) window.onAdsRemoved();", null)
                      }
                    }
                  }

                  override fun onRenderProcessGone(
                    view: WebView?,
                    detail: android.webkit.RenderProcessGoneDetail?
                  ): Boolean {
                    runCatching {
                      (view?.parent as? ViewGroup)?.removeView(view)
                      view?.destroy()
                    }
                    if (webViewRef === view) {
                      webViewRef = null
                    }
                    viewModel.showStatus("Grafik-Engine wird neu initialisiert...")
                    return true
                  }
                }

                runCatching { loadUrl("file:///android_asset/index.html") }
                webViewRef = this
              }
            } catch (t: Throwable) {
              View(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                  ViewGroup.LayoutParams.MATCH_PARENT,
                  ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(android.graphics.Color.parseColor("#05060A"))
              }
            }
          },
          update = { view ->
            if (view is WebView) {
              webViewRef = view
            }
          }
        )

        // Compact Top-Center Play Games & Cloud Pill Bar
        Row(
          modifier = Modifier
            .align(Alignment.TopCenter)
            .statusBarsPadding()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(0xCC0D1322))
            .border(1.dp, Color(0x554FD1FF), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          // Play Games Cloud & Leaderboard Hub Button
          Row(
            modifier = Modifier
              .clip(RoundedCornerShape(50))
              .clickable { viewModel.toggleCloudPanel(true) }
              .padding(horizontal = 6.dp, vertical = 4.dp)
              .testTag("play_games_cloud_button"),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Icon(
              imageVector = Icons.Default.SportsEsports,
              contentDescription = "Google Play Games Cloud & Rangliste",
              tint = Color(0xFF6DFF9B),
              modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
              text = "Play Games Cloud",
              color = Color(0xFFE8ECF4),
              fontSize = 11.sp,
              fontWeight = FontWeight.Bold
            )
          }

          // Quick Cloud Sync Indicator
          IconButton(
            onClick = {
              webViewRef?.evaluateJavascript(
                "(function(){ if(window.saveGame){ window.saveGame(); return localStorage.getItem('lr_save') || ''; } return ''; })()"
              ) { rawResult ->
                viewModel.showStatus("Cloud-Spielstand mit Google Play synchronisiert ✓")
              }
            },
            modifier = Modifier
              .size(28.dp)
              .testTag("quick_cloud_sync_button")
          ) {
            Icon(
              imageVector = Icons.Default.CloudDone,
              contentDescription = "Cloud Sync",
              tint = Color(0xFF4FD1FF),
              modifier = Modifier.size(16.dp)
            )
          }

          // IAP Remove Ads / VIP Badge (hidden until Google Play Billing integration is active)
          if (GameViewModel.IS_PLAY_BILLING_INTEGRATED) {
            if (!adsRemoved) {
              Row(
                modifier = Modifier
                  .clip(RoundedCornerShape(50))
                  .background(Color(0x33FFD54A))
                  .border(1.dp, Color(0x88FFD54A), RoundedCornerShape(50))
                  .clickable { viewModel.openIapDialog() }
                  .padding(horizontal = 8.dp, vertical = 3.dp)
                  .testTag("open_iap_button"),
                verticalAlignment = Alignment.CenterVertically
              ) {
                Icon(
                  imageVector = Icons.Default.RemoveCircleOutline,
                  contentDescription = "Werbung entfernen",
                  tint = Color(0xFFFFD54A),
                  modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                  text = "No-Ads",
                  color = Color(0xFFFFD54A),
                  fontSize = 10.sp,
                  fontWeight = FontWeight.Bold
                )
              }
            } else {
              Row(
                modifier = Modifier
                  .clip(RoundedCornerShape(50))
                  .background(Color(0x266DFF9B))
                  .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
              ) {
                Icon(
                  imageVector = Icons.Default.Verified,
                  contentDescription = "VIP Werbefrei",
                  tint = Color(0xFF6DFF9B),
                  modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                  text = "VIP",
                  color = Color(0xFF6DFF9B),
                  fontSize = 10.sp,
                  fontWeight = FontWeight.Bold
                )
              }
            }
          }
          // Toggleable GameMonitor Performance Pill
          Row(
            modifier = Modifier
              .clip(RoundedCornerShape(50))
              .background(
                if (perfSnapshot.isFrameRateDip || perfSnapshot.isMemoryPressure) {
                  Color(0x33FF6A3D)
                } else {
                  Color(0x264FD1FF)
                }
              )
              .clickable {
                showPerfOverlay = !showPerfOverlay
                if (showPerfOverlay) {
                  gameMonitor.onOpenViewLogs()
                }
                webViewRef?.evaluateJavascript(
                  "if(window.GAME_MONITOR) window.GAME_MONITOR.toggleOverlay($showPerfOverlay);",
                  null
                )
              }
              .padding(horizontal = 7.dp, vertical = 3.dp)
              .testTag("game_monitor_toggle_button"),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = "${perfSnapshot.currentFps} FPS",
              color = if (perfSnapshot.isFrameRateDip) Color(0xFFFFB347) else Color(0xFF9EC7FF),
              fontSize = 10.sp,
              fontWeight = FontWeight.Bold
            )
          }
        }

        // GameMonitor Debug Overlay for Frame Rate Dips, Memory Usage & Play Policy Diagnostics
        androidx.compose.animation.AnimatedVisibility(
          visible = showPerfOverlay,
          enter = fadeIn(),
          exit = fadeOut(),
          modifier = Modifier
            .align(Alignment.TopEnd)
            .statusBarsPadding()
            .padding(top = 48.dp, end = 10.dp)
        ) {
          Surface(
            modifier = Modifier
              .widthIn(max = 280.dp)
              .testTag("game_monitor_overlay"),
            color = Color(0xE60B101D),
            shape = RoundedCornerShape(10.dp),
            border = androidx.compose.foundation.BorderStroke(
              1.dp,
              if (perfSnapshot.isFrameRateDip || perfSnapshot.isMemoryPressure) {
                Color(0xFFFF6A3D)
              } else {
                Color(0x664FD1FF)
              }
            )
          ) {
            Column(
              modifier = Modifier.padding(10.dp),
              verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
              Text(
                text = "GameMonitor · Android Vitals",
                color = Color(0xFF4FD1FF),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
              )
              Text(
                text = "FPS: ${perfSnapshot.currentFps} (${perfSnapshot.avgFrameTimeMs} ms) · Dips: ${perfSnapshot.frameDipCount}",
                color = if (perfSnapshot.isFrameRateDip) Color(0xFFFFB347) else Color(0xFFE8ECF4),
                fontSize = 10.sp
              )
              Text(
                text = "Floor: ${perfSnapshot.dungeonFloor} · Lv: ${perfSnapshot.playerLevel} · Entities: ${perfSnapshot.entityCount}",
                color = Color(0xFFE8ECF4),
                fontSize = 10.sp
              )
              Text(
                text = "Particles: ${perfSnapshot.activeParticles} · Bullets: ${perfSnapshot.activeBullets}",
                color = Color(0xFF6DFF9B),
                fontSize = 10.sp
              )
              Text(
                text = "JVM Heap: ${perfSnapshot.usedMemoryMb}/${perfSnapshot.maxMemoryMb} MB (${perfSnapshot.memoryUsagePercent}%)",
                color = if (perfSnapshot.isMemoryPressure) Color(0xFFFF7A7A) else Color(0xFFE8ECF4),
                fontSize = 10.sp
              )
              Text(
                text = "WebGL Heap: ~${perfSnapshot.webGlMemoryMb} MB · Play Age: ${BuildConfig.PLAY_TARGET_AGE_GROUP}",
                color = Color(0xFF9EC7FF),
                fontSize = 10.sp
              )
              val latestFinding = perfSnapshot.recentFindings.lastOrNull()
              if (latestFinding != null) {
                Text(
                  text = latestFinding,
                  color = Color(0xFF6DFF9B),
                  fontSize = 9.sp,
                  maxLines = 2,
                  overflow = TextOverflow.Ellipsis
                )
              }
            }
          }
        }

        // Floating Status Notification Pill
        androidx.compose.animation.AnimatedVisibility(
          visible = statusBanner != null,
          enter = fadeIn(),
          exit = fadeOut(),
          modifier = Modifier
            .align(Alignment.TopCenter)
            .statusBarsPadding()
            .padding(top = 48.dp)
        ) {
          Surface(
            color = Color(0xEE101828),
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF6DFF9B))
          ) {
            Text(
              text = statusBanner.orEmpty(),
              color = Color(0xFF6DFF9B),
              fontSize = 12.sp,
              fontWeight = FontWeight.SemiBold,
              modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
            )
          }
        }
      }

      // AdMob Banner Ad Bar at bottom (hidden when Remove Ads IAP is unlocked)
      if (!adsRemoved) {
        AdMobBannerBar(
          onWatchRewarded = { viewModel.requestRewardedAd() },
          onRemoveAdsClick = { viewModel.openIapDialog() }
        )
      }
    }
  }

  // Native Play Games Cloud Save & Global Leaderboard Dialog
  if (showCloudPanel) {
    PlayGamesCloudDialog(
      playerName = viewModel.defaultPlayerName,
      cloudSaveState = cloudSaveState,
      leaderboardState = leaderboardState,
      adsRemoved = adsRemoved,
      onDismiss = { viewModel.toggleCloudPanel(false) },
      onSaveToCloudNow = {
        webViewRef?.evaluateJavascript(
          "(function(){ if(window.saveGame) window.saveGame(); return localStorage.getItem('lr_save') || ''; })()"
        ) { result ->
          val unescaped = unquoteJsString(result)
          if (unescaped.length > 2) {
            viewModel.syncCloudSaveFromJs(unescaped)
            viewModel.showStatus("Aktueller Spielstand in Google Play Cloud gespeichert!")
          } else {
            viewModel.showStatus("Spielstand wird beim nächsten Run-Start synchronisiert.")
          }
        }
      },
      onLoadFromCloudNow = {
        viewModel.fetchCloudSaveFromServer { cloudJson ->
          if (!cloudJson.isNullOrBlank()) {
            val escapedForJs = JSONObject.quote(cloudJson)
            webViewRef?.evaluateJavascript(
              "if(window.applyCloudSave) window.applyCloudSave($escapedForJs);",
              null
            )
            viewModel.toggleCloudPanel(false)
          }
        }
      },
      onOpenIap = {
        viewModel.toggleCloudPanel(false)
        viewModel.openIapDialog()
      },
      onSignOut = {
        viewModel.toggleCloudPanel(false)
        onSignOut()
      }
    )
  }

  // In-App Purchase (Remove Ads) Modal (hidden until Google Play Billing integration is available)
  if (GameViewModel.IS_PLAY_BILLING_INTEGRATED && showIapDialog) {
    RemoveAdsIapDialog(
      adsRemoved = adsRemoved,
      onDismiss = { viewModel.closeIapDialog() },
      onPurchaseConfirm = {
        viewModel.completeRemoveAdsPurchase {
          webViewRef?.evaluateJavascript("if(window.onAdsRemoved) window.onAdsRemoved();", null)
        }
      },
      onRestorePurchases = {
        viewModel.restorePurchases()
      }
    )
  }

  // AdMob Interstitial Ad on Run Over (if Ads not removed)
  if (showInterstitialAd && !adsRemoved) {
    AdMobInterstitialDialog(
      onDismiss = { viewModel.dismissInterstitialAd() },
      onRemoveAds = {
        viewModel.dismissInterstitialAd()
        viewModel.openIapDialog()
      }
    )
  }

  // Trigger Google Mobile Ads SDK RewardedAd when requested by explicit user opt-in
  LaunchedEffect(showRewardedAd) {
    if (showRewardedAd) {
      val activity = context.findActivity()
      AdMobRewardedAdManager.showRewardedAd(
        activity = activity,
        onUserEarnedReward = { grant ->
          viewModel.dismissRewardedAd()
          showFallbackRewardedModal = false
          val safeRewardName = JSONObject.quote(grant.rewardName)
          webViewRef?.evaluateJavascript(
            "if(window.grantRewardedGold) window.grantRewardedGold(${grant.goldGranted}, ${grant.shardsGranted}, $safeRewardName, ${grant.rewardAmount});",
            null
          )
          viewModel.showStatus(
            "AdMob Prämie (${grant.rewardAmount}x ${grant.rewardName}): +${grant.goldGranted} Gold & +${grant.shardsGranted} Relikt-Splitter!"
          )
        },
        onAudioPauseChanged = { paused ->
          webViewRef?.evaluateJavascript(
            "if(window.onAdAudioPause) window.onAdAudioPause($paused);",
            null
          )
        },
        onAdUnavailableFallback = {
          showFallbackRewardedModal = true
        }
      )
    } else {
      showFallbackRewardedModal = false
    }
  }

  // AdMob Rewarded Ad Opt-In & Fallback Modal (Placement: ca-app-pub-2526614647374921/8246289044, 1x Reward)
  if (showRewardedAd && showFallbackRewardedModal) {
    DisposableEffect(Unit) {
      webViewRef?.evaluateJavascript("if(window.onAdAudioPause) window.onAdAudioPause(true);", null)
      onDispose {
        webViewRef?.evaluateJavascript("if(window.onAdAudioPause) window.onAdAudioPause(false);", null)
      }
    }
    AdMobRewardedDialog(
      adUnitId = AdMobRewardedAdManager.REWARDED_AD_UNIT_ID,
      rewardName = AdMobRewardedAdManager.REWARD_NAME,
      rewardAmount = AdMobRewardedAdManager.REWARD_AMOUNT,
      onDismiss = {
        showFallbackRewardedModal = false
        viewModel.dismissRewardedAd()
      },
      onRewardEarned = {
        val grant = AdMobRewardedAdManager.calculateInGameReward(
          rawRewardType = AdMobRewardedAdManager.REWARD_NAME,
          rawRewardAmount = AdMobRewardedAdManager.REWARD_AMOUNT
        )
        showFallbackRewardedModal = false
        viewModel.dismissRewardedAd()
        val safeRewardName = JSONObject.quote(grant.rewardName)
        webViewRef?.evaluateJavascript(
          "if(window.grantRewardedGold) window.grantRewardedGold(${grant.goldGranted}, ${grant.shardsGranted}, $safeRewardName, ${grant.rewardAmount});",
          null
        )
        viewModel.showStatus(
          "AdMob Prämie (${grant.rewardAmount}x ${grant.rewardName}): +${grant.goldGranted} Gold & +${grant.shardsGranted} Relikt-Splitter!"
        )
      }
    )
  }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
  is Activity -> this
  is ContextWrapper -> baseContext.findActivity()
  else -> null
}

private fun unquoteJsString(raw: String?): String {
  if (raw.isNullOrBlank() || raw == "null") return ""
  return try {
    org.json.JSONTokener(raw).nextValue().toString()
  } catch (e: Exception) {
    raw.trim('"')
  }
}

@Composable
private fun AdMobBannerBar(
  onWatchRewarded: () -> Unit,
  onRemoveAdsClick: () -> Unit
) {
  Surface(
    color = Color(0xFF0E131F),
    modifier = Modifier
      .fillMaxWidth()
      .navigationBarsPadding()
      .testTag("admob_banner_bar")
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .height(50.dp)
        .border(width = 1.dp, color = Color(0xFF232D42))
        .padding(horizontal = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.weight(1f)
      ) {
        Box(
          modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFFFFB347))
            .padding(horizontal = 5.dp, vertical = 2.dp)
        ) {
          Text(
            text = "AdMob",
            color = Color(0xFF080B12),
            fontSize = 9.sp,
            fontWeight = FontWeight.ExtraBold
          )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column {
          Text(
            text = "Gratis Beute-Boost: +75 Gold & +10 Splitter",
            color = Color(0xFFE8ECF4),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
          Text(
            text = "Google AdMob · Belohnungs-Spot verfügbar",
            color = Color(0xFF9AA4B8),
            fontSize = 9.sp,
            maxLines = 1
          )
        }
      }

      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        OutlinedButton(
          onClick = onWatchRewarded,
          contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
          modifier = Modifier
            .height(34.dp)
            .testTag("watch_rewarded_ad_button")
        ) {
          Icon(
            imageVector = Icons.Default.PlayCircle,
            contentDescription = "Rewarded Video starten",
            tint = Color(0xFF6DFF9B),
            modifier = Modifier.size(15.dp)
          )
          Spacer(modifier = Modifier.width(4.dp))
          Text(
            text = "+75 Gold",
            fontSize = 11.sp,
            color = Color(0xFF6DFF9B),
            fontWeight = FontWeight.Bold
          )
        }

        if (GameViewModel.IS_PLAY_BILLING_INTEGRATED) {
          TextButton(
            onClick = onRemoveAdsClick,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            modifier = Modifier
              .height(34.dp)
              .testTag("banner_remove_ads_button")
          ) {
            Text(
              text = "✕ Werbefrei",
              fontSize = 10.sp,
              color = Color(0xFFFFD54A),
              fontWeight = FontWeight.Bold
            )
          }
        }
      }
    }
  }
}

@Composable
private fun PlayGamesCloudDialog(
  playerName: String,
  cloudSaveState: UiState<CloudSave?>,
  leaderboardState: UiState<List<LeaderboardEntry>>,
  adsRemoved: Boolean,
  onDismiss: () -> Unit,
  onSaveToCloudNow: () -> Unit,
  onLoadFromCloudNow: () -> Unit,
  onOpenIap: () -> Unit,
  onSignOut: () -> Unit
) {
  var selectedTab by remember { mutableIntStateOf(0) }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false)
  ) {
    Card(
      modifier = Modifier
        .fillMaxWidth(0.94f)
        .widthIn(max = 540.dp)
        .heightIn(max = 640.dp)
        .border(1.dp, Color(0xFF344563), RoundedCornerShape(20.dp)),
      shape = RoundedCornerShape(20.dp),
      colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1320))
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(18.dp)
      ) {
        // Header
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              imageVector = Icons.Default.SportsEsports,
              contentDescription = null,
              tint = Color(0xFF6DFF9B),
              modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
              Text(
                text = "Google Play Games Services",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.ExtraBold
              )
              Text(
                text = "Angemeldet als $playerName",
                color = Color(0xFF9EC7FF),
                fontSize = 12.sp
              )
            }
          }
          IconButton(
            onClick = onDismiss,
            modifier = Modifier.testTag("close_cloud_dialog_button")
          ) {
            Icon(
              imageVector = Icons.Default.Close,
              contentDescription = "Schließen",
              tint = Color(0xFF9AA4B8)
            )
          }
        }

        Spacer(modifier = Modifier.height(10.dp))

        TabRow(
          selectedTabIndex = selectedTab,
          containerColor = Color(0xFF141C2E),
          contentColor = Color(0xFF4FD1FF),
          modifier = Modifier.clip(RoundedCornerShape(10.dp))
        ) {
          Tab(
            selected = selectedTab == 0,
            onClick = { selectedTab = 0 },
            text = { Text("Cloud-Speicher", fontWeight = FontWeight.Bold, fontSize = 13.sp) },
            modifier = Modifier.testTag("tab_cloud_save")
          )
          Tab(
            selected = selectedTab == 1,
            onClick = { selectedTab = 1 },
            text = { Text("Globale Top 30", fontWeight = FontWeight.Bold, fontSize = 13.sp) },
            modifier = Modifier.testTag("tab_global_leaderboard")
          )
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (selectedTab == 0) {
          // Cloud Save Tab
          Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            Card(
              colors = CardDefaults.cardColors(containerColor = Color(0xFF141D2F)),
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier.fillMaxWidth()
            ) {
              Column(modifier = Modifier.padding(14.dp)) {
                Text(
                  text = "Synchronisierter Cloud-Spielstand",
                  color = Color(0xFF4FD1FF),
                  fontWeight = FontWeight.Bold,
                  fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                when (val state = cloudSaveState) {
                  is UiState.Loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                      CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                      Spacer(modifier = Modifier.width(8.dp))
                      Text("Lade Cloud-Daten...", color = Color(0xFFB8C4D9), fontSize = 13.sp)
                    }
                  }
                  is UiState.Error -> {
                    Text(
                      text = state.message,
                      color = Color(0xFFFF7A7A),
                      fontSize = 12.sp
                    )
                  }
                  is UiState.Success -> {
                    val save = state.data
                    if (save == null) {
                      Text(
                        text = "Noch kein Cloud-Spielstand gespeichert. Klicke unten auf 'In Cloud speichern', um deinen Fortschritt geräteübergreifend zu sichern.",
                        color = Color(0xFFB8C4D9),
                        fontSize = 12.sp
                      )
                    } else {
                      Text(
                        text = "Held: ${save.playerName} · Level ${save.level} · Ebene ${save.floor}",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                      )
                      Text(
                        text = "Gold: ${save.gold} · Relikt-Splitter: ${save.metaShards}",
                        color = Color(0xFFFFD54A),
                        fontSize = 12.sp
                      )
                      val dateText = save.updatedAt?.toDate()?.let {
                        java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.GERMANY).format(it)
                      } ?: "Gerade eben"
                      Text(
                        text = "Zuletzt synchronisiert: $dateText",
                        color = Color(0xFF9AA4B8),
                        fontSize = 11.sp
                      )
                    }
                  }
                }
              }
            }

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
              Button(
                onClick = onSaveToCloudNow,
                modifier = Modifier
                  .weight(1f)
                  .height(48.dp)
                  .testTag("btn_cloud_save_now"),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
              ) {
                Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Cloud Speichern", fontSize = 12.sp, fontWeight = FontWeight.Bold)
              }

              Button(
                onClick = onLoadFromCloudNow,
                modifier = Modifier
                  .weight(1f)
                  .height(48.dp)
                  .testTag("btn_cloud_load_now"),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
              ) {
                Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Cloud Laden", fontSize = 12.sp, fontWeight = FontWeight.Bold)
              }
            }

            if (GameViewModel.IS_PLAY_BILLING_INTEGRATED && !adsRemoved) {
              OutlinedButton(
                onClick = onOpenIap,
                modifier = Modifier
                  .fillMaxWidth()
                  .height(48.dp)
                  .testTag("cloud_modal_iap_button")
              ) {
                Icon(
                  imageVector = Icons.Default.ShoppingCart,
                  contentDescription = null,
                  tint = Color(0xFFFFD54A),
                  modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                  text = "Werbung dauerhaft entfernen (In-App-Kauf)",
                  color = Color(0xFFFFD54A),
                  fontWeight = FontWeight.Bold,
                  fontSize = 13.sp
                )
              }
            }

            HorizontalDivider(color = Color(0xFF232F46))

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              TextButton(
                onClick = onSignOut,
                modifier = Modifier.testTag("sign_out_button")
              ) {
                Icon(
                  imageVector = Icons.AutoMirrored.Filled.Logout,
                  contentDescription = "Abmelden",
                  tint = Color(0xFFFF7A7A),
                  modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Konto wechseln / Abmelden", color = Color(0xFFFF7A7A), fontSize = 12.sp)
              }

              TextButton(onClick = onDismiss) {
                Text("Zurück zum Spiel", color = Color(0xFF9EC7FF), fontSize = 13.sp)
              }
            }
          }
        } else {
          // Global Leaderboard Tab
          Column(modifier = Modifier.fillMaxWidth()) {
            when (val state = leaderboardState) {
              is UiState.Loading -> {
                Box(
                  modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                  contentAlignment = Alignment.Center
                ) {
                  CircularProgressIndicator()
                }
              }
              is UiState.Error -> {
                Text(
                  text = state.message,
                  color = Color(0xFFFF7A7A),
                  fontSize = 13.sp,
                  modifier = Modifier.padding(16.dp)
                )
              }
              is UiState.Success -> {
                val list = state.data
                if (list.isEmpty()) {
                  Box(
                    modifier = Modifier
                      .fillMaxWidth()
                      .height(180.dp),
                    contentAlignment = Alignment.Center
                  ) {
                    Text(
                      text = "Noch keine globalen Einträge vorhanden.\nSchließe einen Run ab, um Platz #1 zu erobern!",
                      color = Color(0xFF9AA4B8),
                      textAlign = TextAlign.Center,
                      fontSize = 13.sp
                    )
                  }
                } else {
                  LazyColumn(
                    modifier = Modifier
                      .fillMaxWidth()
                      .heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                  ) {
                    itemsIndexed(list, key = { _, item -> item.id.ifEmpty { item.userId } }) { idx, item ->
                      Row(
                        modifier = Modifier
                          .fillMaxWidth()
                          .clip(RoundedCornerShape(8.dp))
                          .background(
                            if (idx == 0) Color(0x33FFD54A)
                            else Color(0xFF141D2F)
                          )
                          .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                      ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                          Text(
                            text = "#${idx + 1}",
                            color = if (idx < 3) Color(0xFFFFD54A) else Color(0xFF9EC7FF),
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 14.sp,
                            modifier = Modifier.width(34.dp)
                          )
                          Column {
                            Text(
                              text = item.playerName,
                              color = Color.White,
                              fontWeight = FontWeight.Bold,
                              fontSize = 13.sp
                            )
                            Text(
                              text = "Klasse: ${item.playerClass} · Lv ${item.level} · Ebene ${item.floor} · ${item.kills} Kills",
                              color = Color(0xFF9AA4B8),
                              fontSize = 11.sp
                            )
                          }
                        }
                        Text(
                          text = "${item.score}",
                          color = Color(0xFF6DFF9B),
                          fontWeight = FontWeight.ExtraBold,
                          fontSize = 14.sp
                        )
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun RemoveAdsIapDialog(
  adsRemoved: Boolean,
  onDismiss: () -> Unit,
  onPurchaseConfirm: () -> Unit,
  onRestorePurchases: () -> Unit
) {
  Dialog(onDismissRequest = onDismiss) {
    Card(
      shape = RoundedCornerShape(20.dp),
      colors = CardDefaults.cardColors(containerColor = Color(0xFF101726)),
      modifier = Modifier
        .fillMaxWidth()
        .border(1.dp, Color(0xFFFFD54A), RoundedCornerShape(20.dp))
    ) {
      Column(
        modifier = Modifier.padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Box(
          modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(Color(0x26FFD54A)),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = Icons.Default.EmojiEvents,
            contentDescription = null,
            tint = Color(0xFFFFD54A),
            modifier = Modifier.size(32.dp)
          )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
          text = "Last Relic · Werbefrei-Edition",
          color = Color.White,
          fontSize = 19.sp,
          fontWeight = FontWeight.ExtraBold,
          textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
          text = "Entferne dauerhaft alle Banner- und Interstitial-Werbeanzeigen. Dein Kauf wird über Google Play & Cloud-Save auf allen deinen Geräten synchronisiert.",
          color = Color(0xFFB8C4D9),
          fontSize = 13.sp,
          textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Card(
          colors = CardDefaults.cardColors(containerColor = Color(0xFF182238)),
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
          ) {
            Text("✓ Keine Banner-Werbung am unteren Bildschirmrand", color = Color(0xFF6DFF9B), fontSize = 12.sp)
            Text("✓ Keine Zwangspausen nach Run-Ende", color = Color(0xFF6DFF9B), fontSize = 12.sp)
            Text("✓ Freiwillige Belohnungs-Videos (+75 Gold) bleiben nutzbar", color = Color(0xFF9EC7FF), fontSize = 12.sp)
          }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (!adsRemoved) {
          Button(
            onClick = onPurchaseConfirm,
            modifier = Modifier
              .fillMaxWidth()
              .height(50.dp)
              .testTag("confirm_iap_purchase_button"),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
          ) {
            Text(
              text = "Werbung entfernen · 2,99 € (Einmalig)",
              fontWeight = FontWeight.ExtraBold,
              fontSize = 14.sp,
              color = Color.White
            )
          }

          Spacer(modifier = Modifier.height(8.dp))

          OutlinedButton(
            onClick = onRestorePurchases,
            modifier = Modifier
              .fillMaxWidth()
              .height(48.dp)
              .testTag("restore_iap_button")
          ) {
            Text("Kauf wiederherstellen (Cloud-Sync)", color = Color(0xFF9EC7FF), fontSize = 13.sp)
          }
        } else {
          Text(
            text = "✓ Werbefrei-Upgrade ist auf deinem Konto aktiv!",
            color = Color(0xFF6DFF9B),
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
          )
        }

        Spacer(modifier = Modifier.height(6.dp))

        TextButton(onClick = onDismiss) {
          Text("Schließen", color = Color(0xFF9AA4B8))
        }
      }
    }
  }
}

@Composable
private fun AdMobInterstitialDialog(
  onDismiss: () -> Unit,
  onRemoveAds: () -> Unit
) {
  var secondsLeft by remember { mutableIntStateOf(3) }
  LaunchedEffect(Unit) {
    while (secondsLeft > 0) {
      delay(1000L)
      secondsLeft--
    }
  }

  Dialog(
    onDismissRequest = { if (secondsLeft <= 0) onDismiss() },
    properties = DialogProperties(usePlatformDefaultWidth = false)
  ) {
    Card(
      modifier = Modifier
        .fillMaxWidth(0.9f)
        .widthIn(max = 420.dp)
        .border(1.dp, Color(0xFF3B82F6), RoundedCornerShape(18.dp)),
      shape = RoundedCornerShape(18.dp),
      colors = CardDefaults.cardColors(containerColor = Color(0xFF0B101B))
    ) {
      Column(
        modifier = Modifier.padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = "Google AdMob · Interstitial",
            color = Color(0xFFFFB347),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
          )
          if (secondsLeft > 0) {
            Text(
              text = "Überspringen in ${secondsLeft}s",
              color = Color(0xFF9AA4B8),
              fontSize = 12.sp
            )
          } else {
            TextButton(
              onClick = onDismiss,
              modifier = Modifier.testTag("close_interstitial_button")
            ) {
              Text("Schließen ✕", color = Color.White, fontWeight = FontWeight.Bold)
            }
          }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Box(
          modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
              Brush.linearGradient(
                listOf(Color(0xFF1D2844), Color(0xFF2D1B4E))
              )
            ),
          contentAlignment = Alignment.Center
        ) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
              text = "LAST RELIC · DUNGEON CHRONIK",
              color = Color(0xFF4FD1FF),
              fontWeight = FontWeight.ExtraBold,
              fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
              text = "Sammle Relikt-Splitter & verhandle mit der Gilde im Dorf für deinen nächsten Run!",
              color = Color(0xFFE8ECF4),
              fontSize = 12.sp,
              textAlign = TextAlign.Center,
              modifier = Modifier.padding(horizontal = 16.dp)
            )
          }
        }

        if (GameViewModel.IS_PLAY_BILLING_INTEGRATED) {
          Spacer(modifier = Modifier.height(16.dp))

          Button(
            onClick = onRemoveAds,
            modifier = Modifier
              .fillMaxWidth()
              .height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB347))
          ) {
            Text(
              text = "Werbung dauerhaft entfernen",
              color = Color(0xFF0A0E17),
              fontWeight = FontWeight.ExtraBold
            )
          }
        }
      }
    }
  }
}

@Composable
private fun AdMobRewardedDialog(
  adUnitId: String = AdMobRewardedAdManager.REWARDED_AD_UNIT_ID,
  rewardName: String = AdMobRewardedAdManager.REWARD_NAME,
  rewardAmount: Int = AdMobRewardedAdManager.REWARD_AMOUNT,
  onDismiss: () -> Unit,
  onRewardEarned: () -> Unit
) {
  var secondsLeft by remember { mutableIntStateOf(4) }
  LaunchedEffect(Unit) {
    while (secondsLeft > 0) {
      delay(1000L)
      secondsLeft--
    }
  }

  Dialog(onDismissRequest = { if (secondsLeft <= 0) onRewardEarned() else onDismiss() }) {
    Card(
      shape = RoundedCornerShape(18.dp),
      colors = CardDefaults.cardColors(containerColor = Color(0xFF0E1525)),
      modifier = Modifier
        .fillMaxWidth()
        .border(1.dp, Color(0xFF6DFF9B), RoundedCornerShape(18.dp))
    ) {
      Column(
        modifier = Modifier.padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Text(
          text = "AdMob Anzeige mit Prämie · $rewardAmount× $rewardName",
          color = Color(0xFF6DFF9B),
          fontWeight = FontWeight.ExtraBold,
          fontSize = 15.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = "Block-ID: $adUnitId",
          color = Color(0xFF9AA4B8),
          fontSize = 10.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
          text = if (secondsLeft > 0) {
            "Prämie ($rewardAmount× $rewardName) wird freigeschaltet in ${secondsLeft}s..."
          } else {
            "Prämie bereit ($rewardAmount× $rewardName): +75 Gold & +10 Relikt-Splitter!"
          },
          color = Color.White,
          fontSize = 13.sp,
          textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        if (secondsLeft > 0) {
          CircularProgressIndicator(color = Color(0xFF6DFF9B))
          Spacer(modifier = Modifier.height(12.dp))
          TextButton(onClick = onDismiss) {
            Text("Abbrechen (Keine Prämie)", color = Color(0xFF9AA4B8))
          }
        } else {
          Button(
            onClick = onRewardEarned,
            modifier = Modifier
              .fillMaxWidth()
              .height(48.dp)
              .testTag("claim_rewarded_ad_button"),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
          ) {
            Text(
              text = "Prämie einfordern ($rewardAmount× $rewardName · +75G / +10💎)",
              fontWeight = FontWeight.Bold
            )
          }
        }
      }
    }
  }
}
