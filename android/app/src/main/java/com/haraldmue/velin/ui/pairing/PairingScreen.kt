package com.haraldmue.velin.ui.pairing

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.haraldmue.velin.data.PairingPayloadParser

@Composable
fun PairingScreen(
    state: PairingUiState,
    onPair: (serverUrl: String, code: String) -> Unit,
    onDismissError: () -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var serverUrl by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            scanning = true
            localError = null
        } else {
            localError = "Camera permission was denied. You can still enter the details manually."
        }
    }
    val pairing = state is PairingUiState.Pairing
    val error = localError ?: (state as? PairingUiState.Error)?.message

    fun clearError() {
        localError = null
        onDismissError()
    }

    if (scanning) {
        QrScannerScreen(
            onScanned = { value ->
                try {
                    val payload = PairingPayloadParser.parse(value)
                    serverUrl = payload.serverUrl
                    code = payload.code
                    localError = null
                    onDismissError()
                    scanning = false
                } catch (exception: IllegalArgumentException) {
                    localError = exception.message ?: "This is not a Velin pairing QR code."
                    scanning = false
                }
            },
            onCancel = { scanning = false },
            onError = { message ->
                localError = message
                scanning = false
            },
        )
        return
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 48.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Connect to Velin",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Scan a pairing QR code from the server administration page, or enter its details manually.",
                modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = {
                    focusManager.clearFocus()
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        scanning = true
                        localError = null
                    } else {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !pairing,
            ) {
                Text("Scan pairing QR code")
            }
            Text(
                text = "or enter manually",
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 16.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = serverUrl,
                onValueChange = {
                    serverUrl = it
                    if (error != null) clearError()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !pairing,
                label = { Text("Server URL") },
                placeholder = { Text("http://192.168.1.10:8080") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) },
                ),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = code,
                onValueChange = {
                    code = it
                    if (error != null) clearError()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !pairing,
                label = { Text("Pairing code") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        focusManager.clearFocus()
                        onPair(serverUrl, code)
                    },
                ),
                isError = error != null,
                supportingText = error?.let { message -> { Text(message) } },
            )
            if (serverUrl.trim().startsWith("http://", ignoreCase = true)) {
                Text(
                    text = "HTTP is not encrypted. Use it only on a trusted local network; prefer HTTPS for remote access.",
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            Button(
                onClick = {
                    focusManager.clearFocus()
                    onPair(serverUrl, code)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
                enabled = !pairing && serverUrl.isNotBlank() && code.isNotBlank(),
            ) {
                if (pairing) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Pair device")
                }
            }
            Text(
                text = "The resulting device token is encrypted with Android Keystore and is never shown or placed in a URL.",
                modifier = Modifier.padding(top = 20.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        }
    }
}

@Composable
fun PairingLoadingScreen() {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Velin",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.SemiBold,
            )
            CircularProgressIndicator(modifier = Modifier.padding(top = 24.dp))
        }
    }
}
