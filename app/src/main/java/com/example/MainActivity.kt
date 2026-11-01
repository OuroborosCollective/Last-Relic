package com.example

import android.os.Bundle
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
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
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
  val auth = remember { Firebase.auth }
  var currentUser by remember { mutableStateOf(auth.currentUser) }

  DisposableEffect(auth) {
    val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
      currentUser = firebaseAuth.currentUser
    }
    auth.addAuthStateListener(listener)
    onDispose {
      auth.removeAuthStateListener(listener)
    }
  }

  val user = currentUser
  if (user == null) {
    AuthScreen(
      onAuthSuccess = {
        currentUser = auth.currentUser
      }
    )
  } else {
    val playerName = remember(user.uid, user.displayName, user.email) {
      user.displayName?.takeIf { it.isNotBlank() }
        ?: user.email?.substringBefore("@")?.takeIf { it.isNotBlank() }
        ?: "RelicHunter"
    }

    val gameViewModel: GameViewModel = viewModel(
      key = user.uid,
      factory = viewModelFactory {
        initializer {
          val dbId = context.applicationContext.getString(R.string.firestore_database_id)
          val firestore = FirebaseFirestore.getInstance(dbId)
          val repository = GameRepository(firestore)
          GameViewModel(
            repository = repository,
            currentUserId = user.uid,
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
          } catch (_: Exception) {
          }
          auth.signOut()
          currentUser = null
        }
      }
    )
  }
}
