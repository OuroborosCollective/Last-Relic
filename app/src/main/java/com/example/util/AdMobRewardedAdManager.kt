package com.example.util

import android.app.Activity
import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.OnUserEarnedRewardListener
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages Google Mobile Ads SDK (AdMob) initialization, Rewarded Ad preloading,
 * FullScreenContentCallback lifecycle, and AdMob policy compliance for Last Relic ("Lost Relic").
 *
 * Configured Identifiers:
 * - AdMob App ID: ca-app-pub-2526614647374921~8134608409
 * - Rewarded Ad Unit ID: ca-app-pub-2526614647374921/8246289044
 * - Reward Item: Name = "Reward", Amount = 1
 */
object AdMobRewardedAdManager {

  private const val TAG = "AdMobRewardedAdMgr"

  const val ADMOB_APP_ID: String = BuildConfig.ADMOB_APP_ID
  const val REWARDED_AD_UNIT_ID: String = BuildConfig.ADMOB_REWARDED_AD_UNIT_ID
  const val REWARD_NAME: String = BuildConfig.ADMOB_REWARD_NAME
  const val REWARD_AMOUNT: Int = BuildConfig.ADMOB_REWARD_AMOUNT

  const val GOLD_PER_REWARD_UNIT: Int = 75
  const val SHARDS_PER_REWARD_UNIT: Int = 10

  data class InGameRewardGrant(
    val rewardName: String,
    val rewardAmount: Int,
    val goldGranted: Int,
    val shardsGranted: Int
  )

  private var rewardedAd: RewardedAd? = null
  private var isLoadingAd: Boolean = false
  private var isSdkInitialized: Boolean = false

  private val _isAdReady = MutableStateFlow(false)
  val isAdReady: StateFlow<Boolean> = _isAdReady.asStateFlow()

  /**
   * Validates that the configured AdMob App ID, Rewarded Ad Unit ID, and Reward item
   * match Google Mobile Ads SDK format and policy requirements.
   */
  fun verifyAdMobConfiguration(): Boolean {
    val validAppId = ADMOB_APP_ID == "ca-app-pub-2526614647374921~8134608409" &&
      ADMOB_APP_ID.matches(Regex("^ca-app-pub-\\d{16}~\\d{10}$"))
    val validAdUnitId = REWARDED_AD_UNIT_ID == "ca-app-pub-2526614647374921/8246289044" &&
      REWARDED_AD_UNIT_ID.matches(Regex("^ca-app-pub-\\d{16}/\\d{10}$"))
    val validRewardSpec = REWARD_NAME == "Reward" && REWARD_AMOUNT == 1
    return validAppId && validAdUnitId && validRewardSpec
  }

  /**
   * Computes the in-game reward grant for a completed AdMob Rewarded Ad.
   * Normalizes the reward name to "Reward" and ensures at least 1 unit is granted
   * per completed video view in accordance with the configured AdMob placement (Amount: 1, Name: Reward).
   */
  fun calculateInGameReward(
    rawRewardType: String?,
    rawRewardAmount: Int
  ): InGameRewardGrant {
    val normalizedType = rawRewardType?.trim()?.takeIf { it.isNotEmpty() } ?: REWARD_NAME
    val effectiveUnits = rawRewardAmount.coerceIn(1, 10)
    return InGameRewardGrant(
      rewardName = normalizedType,
      rewardAmount = effectiveUnits,
      goldGranted = GOLD_PER_REWARD_UNIT * effectiveUnits,
      shardsGranted = SHARDS_PER_REWARD_UNIT * effectiveUnits
    )
  }

  /**
   * Detects whether the app is running in an Android Emulator environment where
   * Privacy Sandbox AdServices (`android.adservices.MEASUREMENT_SERVICE`) is unavailable.
   */
  fun isEmulatorEnvironment(): Boolean {
    val fingerprint = android.os.Build.FINGERPRINT.lowercase()
    val model = android.os.Build.MODEL.lowercase()
    val product = android.os.Build.PRODUCT.lowercase()
    val hardware = android.os.Build.HARDWARE.lowercase()
    return fingerprint.contains("generic") ||
      fingerprint.contains("emulator") ||
      model.contains("sdk_gphone") ||
      model.contains("emulator") ||
      model.contains("android sdk built for") ||
      product.contains("sdk_gphone") ||
      product.contains("emulator") ||
      hardware.contains("goldfish") ||
      hardware.contains("ranchu") ||
      hardware.contains("cuttlefish")
  }

  /**
   * Initializes the Google Mobile Ads SDK with AdMob policy-compliant RequestConfiguration
   * (Target Age 13+ -> MAX_AD_CONTENT_RATING_T, non-child-directed) and preloads the first RewardedAd.
   * Skips eager measurement service binding on emulator images where `MEASUREMENT_SERVICE` is absent.
   */
  fun initialize(context: Context) {
    val appContext = context.applicationContext
    try {
      if (isEmulatorEnvironment()) {
        Log.d(TAG, "Emulator detected: skipping eager MobileAds measurement binding; using opt-in Rewarded Ad flow")
        return
      }

      val requestConfig = RequestConfiguration.Builder()
        .setTagForChildDirectedTreatment(RequestConfiguration.TAG_FOR_CHILD_DIRECTED_TREATMENT_FALSE)
        .setTagForUnderAgeOfConsent(RequestConfiguration.TAG_FOR_UNDER_AGE_OF_CONSENT_FALSE)
        .setMaxAdContentRating(RequestConfiguration.MAX_AD_CONTENT_RATING_T)
        .build()
      MobileAds.setRequestConfiguration(requestConfig)

      if (!isSdkInitialized) {
        MobileAds.initialize(appContext) { initStatus ->
          isSdkInitialized = true
          Log.d(TAG, "Google Mobile Ads SDK initialized: ${initStatus.adapterStatusMap.keys}")
          loadRewardedAd(appContext)
        }
      } else {
        loadRewardedAd(appContext)
      }
    } catch (e: Exception) {
      Log.w(TAG, "AdMob SDK initialization skipped or failed in current environment", e)
    }
  }

  /**
   * Loads a RewardedAd for placement `ca-app-pub-2526614647374921/8246289044`.
   */
  fun loadRewardedAd(context: Context) {
    if (isEmulatorEnvironment() || isLoadingAd || rewardedAd != null) return
    val appContext = context.applicationContext
    isLoadingAd = true

    try {
      val adRequest = AdRequest.Builder().build()
      RewardedAd.load(
        appContext,
        REWARDED_AD_UNIT_ID,
        adRequest,
        object : RewardedAdLoadCallback() {
          override fun onAdLoaded(ad: RewardedAd) {
            rewardedAd = ad
            isLoadingAd = false
            _isAdReady.value = true
            Log.d(TAG, "AdMob RewardedAd loaded for unit $REWARDED_AD_UNIT_ID")
          }

          override fun onAdFailedToLoad(loadAdError: LoadAdError) {
            rewardedAd = null
            isLoadingAd = false
            _isAdReady.value = false
            Log.d(TAG, "AdMob RewardedAd failed to load (${loadAdError.code}): ${loadAdError.message}")
          }
        }
      )
    } catch (e: Exception) {
      rewardedAd = null
      isLoadingAd = false
      _isAdReady.value = false
      Log.w(TAG, "Exception while requesting AdMob RewardedAd", e)
    }
  }

  /**
   * Shows the loaded AdMob RewardedAd if available.
   * Complies with AdMob policies:
   * - Only called after explicit user opt-in.
   * - Pauses/mutes game audio while the full-screen video ad is on screen.
   * - Preloads the next RewardedAd after dismissal.
   * - Falls back to the opt-in preview modal if no live ad fill is available in emulator/offline mode.
   */
  fun showRewardedAd(
    activity: Activity?,
    onUserEarnedReward: (InGameRewardGrant) -> Unit,
    onAudioPauseChanged: (Boolean) -> Unit = {},
    onAdUnavailableFallback: () -> Unit
  ) {
    val currentAd = rewardedAd
    if (currentAd == null || activity == null || activity.isFinishing) {
      if (activity != null) {
        loadRewardedAd(activity.applicationContext)
      }
      onAdUnavailableFallback()
      return
    }

    currentAd.fullScreenContentCallback = object : FullScreenContentCallback() {
      override fun onAdShowedFullScreenContent() {
        Log.d(TAG, "AdMob RewardedAd showed fullscreen content")
        onAudioPauseChanged(true)
      }

      override fun onAdDismissedFullScreenContent() {
        Log.d(TAG, "AdMob RewardedAd dismissed fullscreen content")
        rewardedAd = null
        _isAdReady.value = false
        onAudioPauseChanged(false)
        loadRewardedAd(activity.applicationContext)
      }

      override fun onAdFailedToShowFullScreenContent(adError: AdError) {
        Log.w(TAG, "AdMob RewardedAd failed to show: ${adError.message}")
        rewardedAd = null
        _isAdReady.value = false
        onAudioPauseChanged(false)
        loadRewardedAd(activity.applicationContext)
        onAdUnavailableFallback()
      }
    }

    rewardedAd = null
    _isAdReady.value = false
    currentAd.show(
      activity,
      OnUserEarnedRewardListener { rewardItem ->
        val grant = calculateInGameReward(
          rawRewardType = rewardItem.type,
          rawRewardAmount = rewardItem.amount
        )
        Log.d(
          TAG,
          "User earned AdMob reward: ${grant.rewardAmount}x ${grant.rewardName} -> +${grant.goldGranted} Gold, +${grant.shardsGranted} Shards"
        )
        onUserEarnedReward(grant)
      }
    )
  }
}
