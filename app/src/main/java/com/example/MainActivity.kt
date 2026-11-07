package com.example

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.data.GameRepository
import com.example.ui.AuthScreen
import com.example.ui.GameScreen
import com.example.ui.GameViewModel
import com.example.ui.theme.MyApplicationTheme
import com.example.util.AdMobRewardedAdManager
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch

fun ensureFirebaseInitialized(context: Context): Boolean {
  return try {
    val appContext = context.applicationContext ?: context
    if (FirebaseApp.getApps(appContext).isNotEmpty()) {
      true
    } else {
      FirebaseApp.initializeApp(appContext) != null
    }
  } catch (t: Throwable) {
    Log.w("MainActivity", "Firebase initialization unavailable, continuing in local mode", t)
    false
  }
}

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    runCatching { enableEdgeToEdge() }
    ensureFirebaseInitialized(this)
    runCatching { AdMobRewardedAdManager.initialize(this) }
    setContent {
      MyApplicationTheme(darkTheme = true, dynamicColor = false) {
        LastRelicApp()
      }
    }
  }
}

@Composable
fun LastRelicApp() {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val auth: FirebaseAuth? = remember(context) {
    if (ensureFirebaseInitialized(context)) {
      runCatching { Firebase.auth }.getOrNull()
    } else {
      null
    }
  }
  var currentUser by remember {
    mutableStateOf(runCatching { auth?.currentUser }.getOrNull())
  }

  DisposableEffect(auth) {
    if (auth == null) {
      onDispose { }
    } else {
      val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        currentUser = runCatching { firebaseAuth.currentUser }.getOrNull()
      }
      runCatching { auth.addAuthStateListener(listener) }
      onDispose {
        runCatching { auth.removeAuthStateListener(listener) }
      }
    }
  }

  val user = currentUser
  var isOfflineLocalMode by remember { mutableStateOf(true) }

  if (user == null && !isOfflineLocalMode) {
    AuthScreen(
      onAuthSuccess = {
        currentUser = runCatching { auth?.currentUser }.getOrNull()
        if (currentUser == null) {
          isOfflineLocalMode = true
        }
      },
      onPlayOffline = {
        isOfflineLocalMode = true
      }
    )
  } else {
    val activeUid = user?.uid ?: "local_offline_hero"
    val playerName = remember(user?.uid, user?.displayName, user?.email, isOfflineLocalMode) {
      user?.displayName?.takeIf { it.isNotBlank() }
        ?: user?.email?.substringBefore("@")?.takeIf { it.isNotBlank() }
        ?: "RelicHunter"
    }

    val gameViewModel: GameViewModel = viewModel(
      key = activeUid,
      factory = viewModelFactory {
        initializer {
          val firestore = if (ensureFirebaseInitialized(context)) {
            runCatching {
              val dbId = context.applicationContext.getString(R.string.firestore_database_id)
              FirebaseFirestore.getInstance(dbId)
            }.getOrNull()
          } else {
            null
          }
          val repository = GameRepository(firestore)
          GameViewModel(
            repository = repository,
            currentUserId = activeUid,
            defaultPlayerName = playerName
          )
        }
      }
    )

    GameScreen(
      viewModel = gameViewModel,
      onSignOut = {
        scope.launch {
          try {
            val credentialManager = CredentialManager.create(context)
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
          } catch (_: Throwable) {
          }
          runCatching { auth?.signOut() }
          currentUser = null
          isOfflineLocalMode = false
        }
      }
    )
  }
}
