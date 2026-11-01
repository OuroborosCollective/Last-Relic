package com.example.data

import com.example.base.FirestoreEmulatorTestBase
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameRepositoryRuleTest : FirestoreEmulatorTestBase() {

  @Test
  fun saveAndGetCloudProgress_authenticatedOwner_succeeds() = runBlocking {
    val uid = signInTestUser(ALICE_EMAIL)
    val repository = GameRepository(firestore)

    val saveResult = withTimeout(DEFAULT_TIMEOUT_MS) {
      repository.saveCloudProgress(
        userId = uid,
        playerName = "AliceHero",
        saveDataJson = "{\"lvl\":7,\"floor\":4}",
        level = 7L,
        floor = 4L,
        gold = 250L,
        metaShards = 30L,
        adsRemoved = false
      )
    }
    assertTrue("Expected saveCloudProgress to succeed: ${saveResult.exceptionOrNull()}", saveResult.isSuccess)

    val loadResult = withTimeout(DEFAULT_TIMEOUT_MS) {
      repository.getCloudSave(uid)
    }
    assertTrue(loadResult.isSuccess)
    val saved = loadResult.getOrNull()
    assertNotNull(saved)
    assertEquals(7L, saved?.level)
    assertEquals(4L, saved?.floor)
    assertEquals("AliceHero", saved?.playerName)
  }

  @Test
  fun getCloudSave_crossUserAccess_failsWithPermissionDenied() = runBlocking {
    val aliceUid = signInTestUser(ALICE_EMAIL)
    val aliceRepo = GameRepository(firestore)
    withTimeout(DEFAULT_TIMEOUT_MS) {
      aliceRepo.saveCloudProgress(
        userId = aliceUid,
        playerName = "AliceHero",
        saveDataJson = "{\"lvl\":5}",
        level = 5L,
        floor = 2L,
        gold = 100L,
        metaShards = 10L,
        adsRemoved = false
      ).getOrThrow()
    }

    signInTestUser(BOB_EMAIL)
    val bobRepo = GameRepository(firestore)
    val crossReadResult = withTimeout(DEFAULT_TIMEOUT_MS) {
      bobRepo.getCloudSave(aliceUid)
    }
    assertTrue(crossReadResult.isFailure)
    val ex = crossReadResult.exceptionOrNull()?.unwrapFirestoreException()
    assertNotNull(ex)
    assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, ex?.code)
  }

  @Test
  fun submitAndObserveLeaderboard_authenticatedUser_emitsEntries() = runBlocking {
    val aliceUid = signInTestUser(ALICE_EMAIL)
    val repository = GameRepository(firestore)

    val submitResult = withTimeout(DEFAULT_TIMEOUT_MS) {
      repository.submitLeaderboardScore(
        userId = aliceUid,
        playerName = "NecroAlice",
        playerClass = "necromancer",
        score = 18500L,
        level = 12L,
        floor = 8L,
        kills = 210L,
        itemsFound = 34L
      )
    }
    assertTrue("Expected submitLeaderboardScore to succeed: ${submitResult.exceptionOrNull()}", submitResult.isSuccess)

    val entries = withTimeout(FLOW_TIMEOUT_MS) {
      repository.observeGlobalLeaderboard().first { list -> list.any { it.userId == aliceUid } }
    }
    assertTrue(entries.any { it.userId == aliceUid && it.score >= 18500L })
  }

  @Test
  fun observeGlobalLeaderboard_unauthenticatedUser_failsWithPermissionDenied() = runBlocking {
    auth.signOut()
    val repository = GameRepository(firestore)

    var caught: Throwable? = null
    try {
      withTimeout(FLOW_TIMEOUT_MS) {
        repository.observeGlobalLeaderboard().first()
      }
    } catch (e: Throwable) {
      caught = e
    }
    assertNotNull("Expected unauthenticated query to throw", caught)
    val firestoreEx = caught?.unwrapFirestoreException()
    assertNotNull("Expected nested FirebaseFirestoreException", firestoreEx)
    assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreEx?.code)
  }

  private companion object {
    const val ALICE_EMAIL = "alice_lastrelic@test.com"
    const val BOB_EMAIL = "bob_lastrelic@test.com"
    const val DEFAULT_TIMEOUT_MS = 5000L
    const val FLOW_TIMEOUT_MS = 4000L
  }
}
