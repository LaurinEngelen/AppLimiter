package com.example.instaguard

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import com.example.instaguard.theme.InstaGuardTheme
import com.example.instaguard.ui.pin.PinLockScreen

class MainActivity : ComponentActivity() {

  private val isAppUnlocked = mutableStateOf(false)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
      requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
    }

    val prefs = getSharedPreferences("instaguard_prefs", Context.MODE_PRIVATE)
    if (!PinManager.isPinEnabled(prefs)) {
      isAppUnlocked.value = true
    }

    enableEdgeToEdge()
    setContent {
      InstaGuardTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          if (!isAppUnlocked.value && PinManager.isPinEnabled(prefs)) {
            PinLockScreen(onUnlockSuccess = { isAppUnlocked.value = true })
          } else {
            MainNavigation()
          }
        }
      }
    }
  }

  override fun onStop() {
    super.onStop()
    val prefs = getSharedPreferences("instaguard_prefs", Context.MODE_PRIVATE)
    if (PinManager.isPinEnabled(prefs)) {
      isAppUnlocked.value = false
    }
  }
}
