package com.haraldmue.velin.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.viewmodel.compose.viewModel
import com.haraldmue.velin.data.AndroidKeyStoreCredentialStore
import com.haraldmue.velin.data.DeviceCredentials
import com.haraldmue.velin.data.PairingClient
import com.haraldmue.velin.data.VelinApiClient
import com.haraldmue.velin.ui.library.HomeScreen
import com.haraldmue.velin.ui.library.LibraryScreen
import com.haraldmue.velin.ui.library.LibraryViewModel
import com.haraldmue.velin.ui.library.LibraryViewModelFactory
import com.haraldmue.velin.ui.library.SearchScreen
import com.haraldmue.velin.ui.pairing.PairingLoadingScreen
import com.haraldmue.velin.ui.pairing.PairingScreen
import com.haraldmue.velin.ui.pairing.PairingUiState
import com.haraldmue.velin.ui.pairing.PairingViewModel
import com.haraldmue.velin.ui.pairing.PairingViewModelFactory

internal enum class Destination(val label: String, val symbol: String) {
    Home("Home", "H"),
    Search("Search", "S"),
    Library("Library", "L"),
}

@Composable
fun VelinApp() {
    val applicationContext = LocalContext.current.applicationContext
    val pairingFactory = remember(applicationContext) {
        PairingViewModelFactory(
            pairingGateway = PairingClient(),
            credentialStore = AndroidKeyStoreCredentialStore(applicationContext),
        )
    }
    val pairingViewModel: PairingViewModel = viewModel(factory = pairingFactory)
    val pairingState by pairingViewModel.state.collectAsState()

    when (val state = pairingState) {
        PairingUiState.Loading -> PairingLoadingScreen()
        PairingUiState.Unpaired,
        is PairingUiState.Pairing,
        is PairingUiState.Error,
        -> PairingScreen(
            state = state,
            onPair = pairingViewModel::pair,
            onDismissError = pairingViewModel::dismissError,
        )
        is PairingUiState.Paired -> ConnectedApp(
            credentials = state.credentials,
            onDisconnect = pairingViewModel::disconnect,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectedApp(
    credentials: DeviceCredentials,
    onDisconnect: () -> Unit,
) {
    var destination by remember { mutableStateOf(Destination.Home) }
    val libraryFactory = remember(credentials) {
        LibraryViewModelFactory(VelinApiClient(credentials))
    }
    val libraryViewModel: LibraryViewModel = viewModel(
        key = "library-${credentials.deviceId}",
        factory = libraryFactory,
    )
    val libraryState by libraryViewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Velin", fontWeight = FontWeight.SemiBold) },
                actions = {
                    TextButton(onClick = libraryViewModel::refresh, enabled = !libraryState.loading) {
                        Text("Refresh")
                    }
                    TextButton(onClick = onDisconnect) {
                        Text("Disconnect")
                    }
                },
            )
        },
        bottomBar = {
            Column {
                HorizontalDivider()
                NavigationBar {
                    Destination.entries.forEach { item ->
                        NavigationBarItem(
                            selected = destination == item,
                            onClick = { destination = item },
                            icon = { Text(item.symbol, fontWeight = FontWeight.Bold) },
                            label = { Text(item.label) },
                        )
                    }
                }
            }
        },
    ) { contentPadding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
        Column(modifier = contentModifier) {
            when (destination) {
                Destination.Home -> HomeScreen(
                    credentials = credentials,
                    state = libraryState,
                    onRetry = libraryViewModel::refresh,
                    onPairAgain = onDisconnect,
                )
                Destination.Search -> SearchScreen(
                    state = libraryState,
                    onSearch = libraryViewModel::search,
                    onPairAgain = onDisconnect,
                )
                Destination.Library -> LibraryScreen(
                    state = libraryState,
                    onRetry = libraryViewModel::refresh,
                    onPairAgain = onDisconnect,
                )
            }
        }
    }
}
