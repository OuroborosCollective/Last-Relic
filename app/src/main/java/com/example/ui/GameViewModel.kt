package com.example.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.CloudSave
import com.example.data.GameRepository
import com.example.data.LeaderboardEntry
import com.example.util.GameRuntimeValidator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

sealed interface UiState<out T> {
  data object Loading : UiState<Nothing>
  data class Success<T>(val data: T) : UiState<T>
  data class Error(val message: String) : UiState<Nothing>
}

class GameViewModel(
  private val repository: GameRepository,
  val currentUserId: String,
  val defaultPlayerName: String
) : ViewModel() {

  private val isCloudAuthenticated: Boolean
    get() = currentUserId.isNotBlank() && currentUserId != "local_offline_hero"

  val cloudSaveState: StateFlow<UiState<CloudSave?>> = (
    if (isCloudAuthenticated) {
      repository.observeCloudSave(currentUserId)
        .map<CloudSave?, UiState<CloudSave?>> { UiState.Success(it) }
        .catch { error ->
          Log.w(TAG, "Error observing cloud save", error)
          emit(UiState.Error(error.message ?: "Fehler beim Laden des Cloud-Speicherstands"))
        }
    } else {
      kotlinx.coroutines.flow.flowOf(UiState.Success(null))
    }
    ).stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5000L),
      initialValue = UiState.Loading
    )

  val leaderboardState: StateFlow<UiState<List<LeaderboardEntry>>> = (
    if (isCloudAuthenticated) {
      repository.observeGlobalLeaderboard()
        .map<List<LeaderboardEntry>, UiState<List<LeaderboardEntry>>> { UiState.Success(it) }
        .catch { error ->
          Log.w(TAG, "Error observing global leaderboard", error)
          emit(UiState.Error(error.message ?: "Fehler beim Laden der Bestenliste"))
        }
    } else {
      kotlinx.coroutines.flow.flowOf(UiState.Success(emptyList()))
    }
    ).stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5000L),
      initialValue = UiState.Loading
    )

  private val _adsRemoved = MutableStateFlow(false)
  val adsRemoved: StateFlow<Boolean> = _adsRemoved.asStateFlow()

  private val _showInterstitialAd = MutableStateFlow(false)
  val showInterstitialAd: StateFlow<Boolean> = _showInterstitialAd.asStateFlow()

  private val _showRewardedAd = MutableStateFlow(false)
  val showRewardedAd: StateFlow<Boolean> = _showRewardedAd.asStateFlow()

  private val _showIapDialog = MutableStateFlow(false)
  val showIapDialog: StateFlow<Boolean> = _showIapDialog.asStateFlow()

  private val _showCloudPanel = MutableStateFlow(false)
  val showCloudPanel: StateFlow<Boolean> = _showCloudPanel.asStateFlow()

  private val _statusBanner = MutableStateFlow<String?>(null)
  val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

  init {
    viewModelScope.launch {
      cloudSaveState.collect { state ->
        if (state is UiState.Success && state.data != null) {
          if (state.data.adsRemoved) {
            _adsRemoved.value = true
          }
        }
      }
    }
  }

  fun syncCloudSaveFromJs(saveJson: String) {
    if (!isCloudAuthenticated) return
    viewModelScope.launch {
      try {
        val validated = GameRuntimeValidator.validateAndSanitizeSaveJson(saveJson)
        if (!validated.isValid) {
          Log.w(TAG, "Skipped syncing invalid JS save payload")
          return@launch
        }
        repository.saveCloudProgress(
          userId = currentUserId,
          playerName = defaultPlayerName,
          saveDataJson = validated.sanitizedJson,
          level = validated.level,
          floor = validated.floor,
          gold = validated.gold,
          metaShards = validated.metaShards,
          adsRemoved = _adsRemoved.value
        )
      } catch (e: Exception) {
        Log.e(TAG, "Failed to parse and sync JS save", e)
      }
    }
  }

  fun submitScoreFromJs(
    playerName: String,
    playerClass: String,
    score: Long,
    level: Long,
    floor: Long,
    kills: Long,
    itemsFound: Long
  ) {
    if (!isCloudAuthenticated) return
    viewModelScope.launch {
      val validated = GameRuntimeValidator.validateLeaderboardSubmission(
        playerName = playerName,
        playerClass = playerClass,
        score = score,
        level = level,
        floor = floor,
        kills = kills,
        itemsFound = itemsFound,
        fallbackName = defaultPlayerName
      )
      val res = repository.submitLeaderboardScore(
        userId = currentUserId,
        playerName = validated.playerName,
        playerClass = validated.playerClass,
        score = validated.score,
        level = validated.level,
        floor = validated.floor,
        kills = validated.kills,
        itemsFound = validated.itemsFound
      )
      if (res.isSuccess) {
        showStatus("Play Games Bestenliste aktualisiert: ${validated.score} Punkte!")
      }
    }
  }

  fun getGlobalLeaderboardJson(): String {
    val state = leaderboardState.value
    val arr = JSONArray()
    if (state is UiState.Success) {
      for (entry in state.data) {
        val obj = JSONObject().apply {
          put("name", entry.playerName)
          put("score", entry.score)
          put("level", entry.level)
          put("floor", entry.floor)
          put("kills", entry.kills)
          put("items", entry.itemsFound)
          put("playerClass", entry.playerClass)
          val dateStr = entry.updatedAt?.toDate()?.let {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(it)
          } ?: "Cloud"
          put("date", dateStr)
        }
        arr.put(obj)
      }
    }
    return arr.toString()
  }

  fun requestInterstitialAdOnRunEnd() {
    if (!_adsRemoved.value) {
      _showInterstitialAd.value = true
    }
  }

  fun dismissInterstitialAd() {
    _showInterstitialAd.value = false
  }

  fun requestRewardedAd() {
    _showRewardedAd.value = true
  }

  fun dismissRewardedAd() {
    _showRewardedAd.value = false
  }

  fun openIapDialog() {
    if (!IS_PLAY_BILLING_INTEGRATED) {
      _showIapDialog.value = false
      return
    }
    _showIapDialog.value = true
  }

  fun closeIapDialog() {
    _showIapDialog.value = false
  }

  fun toggleCloudPanel(show: Boolean? = null) {
    _showCloudPanel.value = show ?: !_showCloudPanel.value
  }

  fun getLatestCloudSaveJson(): String {
    val state = cloudSaveState.value
    return if (state is UiState.Success && state.data != null) {
      state.data.saveDataJson
    } else {
      ""
    }
  }

  fun fetchCloudSaveFromServer(onResult: (String?) -> Unit) {
    if (!isCloudAuthenticated) {
      showStatus("Lokaler Modus aktiv. Melde dich mit Google an für Cloud-Speicher.")
      onResult(null)
      return
    }
    viewModelScope.launch {
      val res = repository.getCloudSave(currentUserId)
      val save = res.getOrNull()
      if (save != null && save.saveDataJson.length > 2) {
        if (save.adsRemoved) _adsRemoved.value = true
        showStatus("Play Games Cloud-Spielstand geladen (Lv ${save.level}, Ebene ${save.floor})!")
        onResult(save.saveDataJson)
      } else {
        showStatus("Kein Cloud-Spielstand auf diesem Konto gefunden.")
        onResult(null)
      }
    }
  }

  fun completeRemoveAdsPurchase(onComplete: () -> Unit = {}) {
    if (!IS_PLAY_BILLING_INTEGRATED) {
      _showIapDialog.value = false
      return
    }
    viewModelScope.launch {
      _adsRemoved.value = true
      _showIapDialog.value = false
      _showInterstitialAd.value = false
      if (!isCloudAuthenticated) {
        showStatus("Werbefreiheit für diese Sitzung aktiviert!")
        onComplete()
        return@launch
      }
      val existing = (cloudSaveState.value as? UiState.Success)?.data
      val res = if (existing != null) {
        repository.saveCloudProgress(
          userId = currentUserId,
          playerName = existing.playerName.ifEmpty { defaultPlayerName },
          saveDataJson = existing.saveDataJson,
          level = existing.level,
          floor = existing.floor,
          gold = existing.gold,
          metaShards = existing.metaShards,
          adsRemoved = true
        )
      } else {
        repository.unlockRemoveAds(currentUserId, defaultPlayerName)
      }
      if (res.isSuccess) {
        showStatus("Werbefreiheit dauerhaft aktiviert & in der Cloud gespeichert!")
      } else {
        showStatus("Werbefreiheit für diese Sitzung aktiviert!")
      }
      onComplete()
    }
  }

  fun restorePurchases() {
    if (!IS_PLAY_BILLING_INTEGRATED) {
      _showIapDialog.value = false
      return
    }
    if (!isCloudAuthenticated) {
      showStatus("Melde dich mit Google an, um Cloud-Käufe wiederherzustellen.")
      return
    }
    viewModelScope.launch {
      val res = repository.getCloudSave(currentUserId)
      val save = res.getOrNull()
      if (save?.adsRemoved == true) {
        _adsRemoved.value = true
        showStatus("Einkauf wiederhergestellt: Werbefrei-Status aktiv!")
      } else {
        showStatus("Kein vorheriger Werbefrei-Kauf auf diesem Konto gefunden.")
      }
    }
  }

  fun showStatus(msg: String) {
    _statusBanner.value = msg
  }

  fun clearStatus() {
    _statusBanner.value = null
  }

  companion object {
    const val TAG = "GameViewModel"
    const val IS_PLAY_BILLING_INTEGRATED = false
  }
}
