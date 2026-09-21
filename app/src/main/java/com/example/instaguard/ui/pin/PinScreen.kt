package com.example.instaguard.ui.pin

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.instaguard.PinManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PinDots(
    pinLength: Int,
    isError: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until 4) {
            val isFilled = i < pinLength
            val dotColor by animateColorAsState(
                targetValue = when {
                    isError -> MaterialTheme.colorScheme.error
                    isFilled -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
                label = "pinDotColor"
            )

            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(dotColor)
                    .border(
                        width = 1.5.dp,
                        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                        shape = CircleShape
                    )
            )
        }
    }
}

@Composable
fun PinKeypad(
    onDigitClick: (Char) -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rows = listOf(
        listOf('1', '2', '3'),
        listOf('4', '5', '6'),
        listOf('7', '8', '9'),
        listOf(' ', '0', '⌫')
    )

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        rows.forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                row.forEach { char ->
                    when (char) {
                        ' ' -> {
                            Spacer(modifier = Modifier.size(72.dp))
                        }
                        '⌫' -> {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(CircleShape)
                                    .clickable { onDeleteClick() },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "⌫",
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        else -> {
                            Surface(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(CircleShape)
                                    .clickable { onDigitClick(char) },
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                tonalElevation = 2.dp
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    Text(
                                        text = char.toString(),
                                        fontSize = 28.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
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

/**
 * Fullscreen PIN Lock Screen shown when the app is locked.
 */
@Composable
fun PinLockScreen(
    onUnlockSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("instaguard_prefs", Context.MODE_PRIVATE) }
    var enteredPin by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Header
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "AppLimiter",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Bitte gib deinen 4-stelligen PIN ein",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            PinDots(
                pinLength = enteredPin.length,
                isError = isError
            )

            Box(modifier = Modifier.height(24.dp), contentAlignment = Alignment.Center) {
                if (errorMessage.isNotEmpty()) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Keypad
        PinKeypad(
            onDigitClick = { digit ->
                if (enteredPin.length < 4 && !isError) {
                    val nextPin = enteredPin + digit
                    enteredPin = nextPin
                    errorMessage = ""

                    if (nextPin.length == 4) {
                        if (PinManager.verifyPin(prefs, nextPin)) {
                            onUnlockSuccess()
                        } else {
                            isError = true
                            errorMessage = "Falscher PIN. Bitte erneut versuchen."
                            coroutineScope.launch {
                                delay(500)
                                enteredPin = ""
                                isError = false
                            }
                        }
                    }
                }
            },
            onDeleteClick = {
                if (enteredPin.isNotEmpty() && !isError) {
                    enteredPin = enteredPin.dropLast(1)
                    errorMessage = ""
                }
            }
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

/**
 * Dialog to set up a new 4-digit PIN (with confirmation step).
 */
@Composable
fun PinSetupDialog(
    onDismiss: () -> Unit,
    onPinSet: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("instaguard_prefs", Context.MODE_PRIVATE) }
    var step by remember { mutableIntStateOf(1) } // 1: Enter PIN, 2: Confirm PIN
    var firstPin by remember { mutableStateOf("") }
    var currentPin by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = if (step == 1) "Neuen PIN festlegen" else "PIN bestätigen",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Text(
                    text = if (step == 1) "Wähle einen 4-stelligen PIN" else "Gib den PIN zur Bestätigung erneut ein",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                PinDots(
                    pinLength = currentPin.length,
                    isError = isError,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                if (errorMessage.isNotEmpty()) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                PinKeypad(
                    onDigitClick = { digit ->
                        if (currentPin.length < 4 && !isError) {
                            val nextPin = currentPin + digit
                            currentPin = nextPin
                            errorMessage = ""

                            if (nextPin.length == 4) {
                                if (step == 1) {
                                    firstPin = nextPin
                                    currentPin = ""
                                    step = 2
                                } else {
                                    // Step 2: Compare
                                    if (nextPin == firstPin) {
                                        PinManager.setPin(prefs, nextPin)
                                        onPinSet()
                                    } else {
                                        isError = true
                                        errorMessage = "PINs stimmen nicht überein!"
                                        coroutineScope.launch {
                                            delay(700)
                                            currentPin = ""
                                            firstPin = ""
                                            step = 1
                                            isError = false
                                            errorMessage = "Bitte von vorne beginnen."
                                        }
                                    }
                                }
                            }
                        }
                    },
                    onDeleteClick = {
                        if (currentPin.isNotEmpty() && !isError) {
                            currentPin = currentPin.dropLast(1)
                            errorMessage = ""
                        }
                    }
                )

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Abbrechen")
                }
            }
        }
    }
}

/**
 * Dialog to verify the current PIN before disabling or changing it.
 */
@Composable
fun PinVerifyDialog(
    title: String = "PIN bestätigen",
    subtitle: String = "Gib deinen aktuellen PIN ein",
    onDismiss: () -> Unit,
    onVerified: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("instaguard_prefs", Context.MODE_PRIVATE) }
    var enteredPin by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                PinDots(
                    pinLength = enteredPin.length,
                    isError = isError,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                if (errorMessage.isNotEmpty()) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                PinKeypad(
                    onDigitClick = { digit ->
                        if (enteredPin.length < 4 && !isError) {
                            val nextPin = enteredPin + digit
                            enteredPin = nextPin
                            errorMessage = ""

                            if (nextPin.length == 4) {
                                if (PinManager.verifyPin(prefs, nextPin)) {
                                    onVerified()
                                } else {
                                    isError = true
                                    errorMessage = "Falscher PIN."
                                    coroutineScope.launch {
                                        delay(500)
                                        enteredPin = ""
                                        isError = false
                                    }
                                }
                            }
                        }
                    },
                    onDeleteClick = {
                        if (enteredPin.isNotEmpty() && !isError) {
                            enteredPin = enteredPin.dropLast(1)
                            errorMessage = ""
                        }
                    }
                )

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Abbrechen")
                }
            }
        }
    }
}
