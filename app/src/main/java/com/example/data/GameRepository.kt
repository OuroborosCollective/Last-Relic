package com.example.data

import android.content.Context
import com.example.R
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class GameRepository(private val db: FirebaseFirestore?) {

  constructor(context: Context) : this(
    kotlin.runCatching {
      val dbId = context.applicationContext.getString(R.string.firestore_database_id)
      FirebaseFirestore.getInstance(dbId)
    }.getOrNull()
  )

  private fun requireUserId(explicitUserId: String? = null): String {
    if (!explicitUserId.isNullOrBlank()) return explicitUserId
    val currentUid = runCatching { Firebase.auth.currentUser?.uid }.getOrNull()
    return currentUid
      ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
  }

  fun observeCloudSave(userId: String): Flow<CloudSave?> {
    val firestore = db ?: return flowOf(null)
    val path = "cloud_saves/$userId"
    return firestore.collection("cloud_saves").document(userId)
      .snapshots()
      .map { snapshot ->
        if (snapshot.exists()) {
          snapshot.toObject(CloudSave::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        } else {
          null
        }
      }
      .catch { error ->
        if (error is Exception) handleFirestoreError(error, OperationType.GET, path)
        emit(null)
      }
  }

  suspend fun getCloudSave(userId: String): Result<CloudSave?> {
    val firestore = db ?: return Result.success(null)
    val docRef = firestore.collection("cloud_saves").document(userId)
    return try {
      val snapshot = docRef.get(com.google.firebase.firestore.Source.SERVER).await()
      if (snapshot.exists()) {
        Result.success(
          snapshot.toObject(CloudSave::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        )
      } else {
        Result.success(null)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.GET, docRef.path)
      Result.failure(e)
    }
  }

  suspend fun saveCloudProgress(
    userId: String? = null,
    playerName: String,
    saveDataJson: String,
    level: Long,
    floor: Long,
    gold: Long,
    metaShards: Long,
    adsRemoved: Boolean
  ): Result<Unit> {
    val firestore = db ?: return Result.failure(IllegalStateException("Cloud database not available in local mode."))
    return try {
      val uid = requireUserId(userId)
      val docRef = firestore.collection("cloud_saves").document(uid)
      val safeName = playerName.trim().ifEmpty { "Spieler" }.take(60)
      val safeJson = saveDataJson.ifBlank { "{}" }.take(190000)
      val safeLevel = level.coerceIn(1L, 9999L)
      val safeFloor = floor.coerceIn(1L, 9999L)
      val safeGold = gold.coerceIn(0L, 100000000L)
      val safeShards = metaShards.coerceIn(0L, 100000000L)

      val payload = mapOf(
        "userId" to uid,
        "playerName" to safeName,
        "saveDataJson" to safeJson,
        "level" to safeLevel,
        "floor" to safeFloor,
        "gold" to safeGold,
        "metaShards" to safeShards,
        "adsRemoved" to adsRemoved,
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
      )
      docRef.set(payload).await()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.WRITE, "cloud_saves")
      Result.failure(e)
    }
  }

  suspend fun unlockRemoveAds(userId: String? = null, playerName: String): Result<Unit> {
    val firestore = db ?: return Result.failure(IllegalStateException("Cloud database not available in local mode."))
    return try {
      val uid = requireUserId(userId)
      val docRef = firestore.collection("cloud_saves").document(uid)
      val safeName = playerName.trim().ifEmpty { "Spieler" }.take(60)
      val payload = mapOf(
        "userId" to uid,
        "playerName" to safeName,
        "saveDataJson" to "{}",
        "level" to 1L,
        "floor" to 1L,
        "gold" to 0L,
        "metaShards" to 0L,
        "adsRemoved" to true,
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
      )
      docRef.set(payload).await()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, "cloud_saves")
      Result.failure(e)
    }
  }

  fun observeGlobalLeaderboard(): Flow<List<LeaderboardEntry>> {
    val firestore = db ?: return flowOf(emptyList())
    val path = "leaderboard"
    return firestore.collection(path)
      .whereGreaterThanOrEqualTo("score", 0L)
      .orderBy("score", Query.Direction.DESCENDING)
      .limit(30)
      .snapshots()
      .map { snapshot ->
        snapshot.toObjects(LeaderboardEntry::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
      }
      .catch { error ->
        if (error is Exception) handleFirestoreError(error, OperationType.LIST, path)
        emit(emptyList())
      }
  }

  suspend fun submitLeaderboardScore(
    userId: String? = null,
    playerName: String,
    playerClass: String,
    score: Long,
    level: Long,
    floor: Long,
    kills: Long,
    itemsFound: Long
  ): Result<Unit> {
    val firestore = db ?: return Result.failure(IllegalStateException("Cloud database not available in local mode."))
    return try {
      val uid = requireUserId(userId)
      val docRef = firestore.collection("leaderboard").document(uid)
      val safeName = playerName.trim().ifEmpty { "Spieler" }.take(40)
      val validClasses = setOf("warrior", "rogue", "mage", "tank", "necromancer")
      val safeClass = if (playerClass in validClasses) playerClass else "warrior"
      val safeScore = score.coerceIn(0L, 1000000000L)
      val safeLevel = level.coerceIn(1L, 9999L)
      val safeFloor = floor.coerceIn(1L, 9999L)
      val safeKills = kills.coerceIn(0L, 10000000L)
      val safeItems = itemsFound.coerceIn(0L, 10000000L)

      val payload = mapOf(
        "userId" to uid,
        "playerName" to safeName,
        "playerClass" to safeClass,
        "score" to safeScore,
        "level" to safeLevel,
        "floor" to safeFloor,
        "kills" to safeKills,
        "itemsFound" to safeItems,
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
      )
      docRef.set(payload).await()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.WRITE, "leaderboard")
      Result.failure(e)
    }
  }
}
