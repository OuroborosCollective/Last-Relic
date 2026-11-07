package com.example.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.example.R
import com.example.ensureFirebaseInitialized
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private const val AUTH_TAG = "LastRelicAuth"

private tailrec fun Context.findActivity(): Activity? = when (this) {
  is Activity -> this
  is ContextWrapper -> baseContext.findActivity()
  else -> null
}

fun attemptAutoSignIn(
  context: Context,
  credentialManager: CredentialManager?,
  onAuthSuccess: () -> Unit,
  onUnauthenticated: () -> Unit,
  scope: CoroutineScope
) {
  if (!ensureFirebaseInitialized(context) || credentialManager == null) {
    onUnauthenticated()
    return
  }
  val alreadySignedIn = runCatching { Firebase.auth.currentUser != null }.getOrDefault(false)
  if (alreadySignedIn) {
    onAuthSuccess()
    return
  }
  val activity = context.findActivity()
  if (activity == null || activity.isFinishing) {
    onUnauthenticated()
    return
  }
  val clientId = try {
    val resId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
    if (resId != 0) context.getString(resId).trim() else ""
  } catch (t: Throwable) {
    ""
  }
  if (clientId.isBlank()) {
    onUnauthenticated()
    return
  }

  scope.launch {
    try {
      val googleIdOption = GetGoogleIdOption.Builder()
        .setFilterByAuthorizedAccounts(true)
        .setServerClientId(clientId)
        .setAutoSelectEnabled(true)
        .build()

      val request = GetCredentialRequest.Builder().addCredentialOption(googleIdOption).build()
      val result = credentialManager.getCredential(activity, request)
      val credential = result.credential
      if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
        val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
        val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
        Firebase.auth.signInWithCredential(authCredential).await()
        onAuthSuccess()
      } else {
        onUnauthenticated()
      }
    } catch (t: Throwable) {
      onUnauthenticated()
    }
  }
}

fun onGoogleSignInClicked(
  context: Context,
  credentialManager: CredentialManager?,
  onAuthSuccess: () -> Unit,
  onAuthError: (String) -> Unit,
  scope: CoroutineScope,
  onAuthCancelled: () -> Unit = {}
) {
  if (!ensureFirebaseInitialized(context) || credentialManager == null) {
    onAuthError("Google Cloud-Dienste sind auf diesem Gerät nicht verfügbar. Bitte nutze den lokalen Modus.")
    return
  }
  val activity = context.findActivity()
  if (activity == null || activity.isFinishing) {
    onAuthError("Aktivität konnte für die Google-Anmeldung nicht initialisiert werden.")
    return
  }
  val clientId = try {
    val resId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
    if (resId != 0) context.getString(resId).trim() else ""
  } catch (t: Throwable) {
    ""
  }
  if (clientId.isBlank()) {
    onAuthError("Google Sign-In Konfiguration fehlt: default_web_client_id nicht gefunden")
    return
  }

  scope.launch {
    try {
      val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
      val request = GetCredentialRequest.Builder().addCredentialOption(signInOption).build()
      val result = credentialManager.getCredential(activity, request)
      val credential = result.credential
      if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
        val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
        val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
        Firebase.auth.signInWithCredential(authCredential).await()
        onAuthSuccess()
      } else {
        onAuthError("Unerwarteter Anmeldetyp")
      }
    } catch (e: GetCredentialCancellationException) {
      Log.w(AUTH_TAG, "Google Sign-In cancelled or dismissed: ${e.message}", e)
      onAuthCancelled()
    } catch (t: Throwable) {
      Log.e(AUTH_TAG, "Google Sign-In failed", t)
      onAuthError(t.localizedMessage ?: "Anmeldung fehlgeschlagen")
    }
  }
}

fun signOutUser(
  credentialManager: CredentialManager?,
  onSignOutComplete: () -> Unit,
  scope: CoroutineScope
) {
  runCatching { Firebase.auth.signOut() }
  scope.launch {
    try {
      credentialManager?.clearCredentialState(ClearCredentialStateRequest())
    } catch (t: Throwable) {
      Log.e(AUTH_TAG, "Failed to clear credential state", t)
    } finally {
      onSignOutComplete()
    }
  }
}

@Composable
fun AuthScreen(
  onAuthSuccess: () -> Unit,
  onPlayOffline: () -> Unit = {}
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val credentialManager = remember(context) {
    runCatching { CredentialManager.create(context) }.getOrNull()
  }
  var isLoading by remember { mutableStateOf(false) }
  var errorMessage by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(Unit) {
    attemptAutoSignIn(
      context = context,
      credentialManager = credentialManager,
      onAuthSuccess = onAuthSuccess,
      onUnauthenticated = {},
      scope = scope
    )
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(
        Brush.radialGradient(
          colors = listOf(Color(0xFF161C33), Color(0xFF06080E)),
          radius = 1200f
        )
      )
      .windowInsetsPadding(WindowInsets.safeDrawing),
    contentAlignment = Alignment.Center
  ) {
    // Decorative Arcane Background Canvas
    Canvas(modifier = Modifier.fillMaxSize()) {
      val cx = size.width * 0.5f
      val cy = size.height * 0.35f
      drawCircle(
        color = Color(0xFF4FD1FF).copy(alpha = 0.07f),
        radius = size.minDimension * 0.42f,
        center = Offset(cx, cy)
      )
      drawCircle(
        color = Color(0xFFC77DFF).copy(alpha = 0.06f),
        radius = size.minDimension * 0.28f,
        center = Offset(cx, cy)
      )
    }

    Card(
      modifier = Modifier
        .widthIn(max = 460.dp)
        .fillMaxWidth()
        .padding(20.dp)
        .border(1.5.dp, Color(0xFF2C3A57), RoundedCornerShape(20.dp)),
      shape = RoundedCornerShape(20.dp),
      colors = CardDefaults.cardColors(containerColor = Color(0xFF0D121F).copy(alpha = 0.95f)),
      elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Surface(
          shape = RoundedCornerShape(16.dp),
          color = Color(0xFF1A243B),
          modifier = Modifier.size(64.dp)
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              imageVector = Icons.Default.SportsEsports,
              contentDescription = "Last Relic Symbol",
              tint = Color(0xFF4FD1FF),
              modifier = Modifier.size(36.dp)
            )
          }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
          text = "LAST RELIC",
          fontSize = 30.sp,
          fontWeight = FontWeight.ExtraBold,
          letterSpacing = 3.sp,
          color = Color(0xFF4FD1FF),
          textAlign = TextAlign.Center
        )

        Text(
          text = "3D Roguelite Loot-Dungeon · Play Games Edition",
          fontSize = 13.sp,
          color = Color(0xFF9EC7FF),
          textAlign = TextAlign.Center,
          modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
        )

        Column(
          modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF141C2E), RoundedCornerShape(12.dp))
            .padding(14.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          FeatureRow(
            icon = Icons.Default.CloudDone,
            title = "Google Play Cloud-Speicher",
            subtitle = "Automatisches Synchronisieren von Helden, Gold & Relikt-Splittern"
          )
          FeatureRow(
            icon = Icons.Default.EmojiEvents,
            title = "Plattformübergreifende Bestenliste",
            subtitle = "Echtzeit Top-30 Rangliste weltweit über Firestore"
          )
          FeatureRow(
            icon = Icons.Default.Security,
            title = "Gilden-Laden & Fraktions-Vertrauen",
            subtitle = "Fortschritt bei Dorf-Händlern, Quests & Kanais Würfel sicher in der Cloud"
          )
        }

        if (errorMessage != null) {
          Spacer(modifier = Modifier.height(12.dp))
          Text(
            text = errorMessage!!,
            color = Color(0xFFFF6B6B),
            fontSize = 13.sp,
            textAlign = TextAlign.Center
          )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Button(
          onClick = {
            errorMessage = null
            isLoading = true
            onGoogleSignInClicked(
              context = context,
              credentialManager = credentialManager,
              onAuthSuccess = {
                isLoading = false
                onAuthSuccess()
              },
              onAuthError = { msg ->
                isLoading = false
                errorMessage = msg
              },
              scope = scope,
              onAuthCancelled = {
                isLoading = false
              }
            )
          },
          enabled = !isLoading,
          modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .testTag("google_sign_in_button"),
          shape = RoundedCornerShape(12.dp),
          colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF2563EB),
            contentColor = Color.White
          )
        ) {
          if (isLoading) {
            CircularProgressIndicator(
              modifier = Modifier.size(24.dp),
              color = Color.White,
              strokeWidth = 2.dp
            )
          } else {
            Text(
              text = "Sign in with Google",
              fontSize = 16.sp,
              fontWeight = FontWeight.Bold
            )
          }
        }

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedButton(
          onClick = onPlayOffline,
          enabled = !isLoading,
          modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .testTag("play_offline_button"),
          shape = RoundedCornerShape(12.dp),
          border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF4A5A7A)),
          colors = ButtonDefaults.outlinedButtonColors(
            contentColor = Color(0xFF9EC7FF)
          )
        ) {
          Text(
            text = "Direkt spielen (Lokaler Speicher)",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
          )
        }
      }
    }
  }
}

@Composable
private fun FeatureRow(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  title: String,
  subtitle: String
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    Icon(
      imageVector = icon,
      contentDescription = title,
      tint = Color(0xFFFFD54A),
      modifier = Modifier.size(24.dp)
    )
    Column {
      Text(
        text = title,
        color = Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold
      )
      Text(
        text = subtitle,
        color = Color(0xFFB0BCD0),
        fontSize = 11.sp
      )
    }
  }
}
