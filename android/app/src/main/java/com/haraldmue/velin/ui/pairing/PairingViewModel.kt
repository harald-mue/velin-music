package com.haraldmue.velin.ui.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.haraldmue.velin.data.CredentialStore
import com.haraldmue.velin.data.DeviceCredentials
import com.haraldmue.velin.data.PairingException
import com.haraldmue.velin.data.PairingGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface PairingUiState {
    data object Loading : PairingUiState
    data object Unpaired : PairingUiState
    data class Pairing(val serverUrl: String) : PairingUiState
    data class Paired(val credentials: DeviceCredentials) : PairingUiState
    data class Error(val message: String) : PairingUiState
}

class PairingViewModel(
    private val pairingGateway: PairingGateway,
    private val credentialStore: CredentialStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutableState = MutableStateFlow<PairingUiState>(PairingUiState.Loading)
    val state: StateFlow<PairingUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            val credentials = withContext(ioDispatcher) { credentialStore.load() }
            mutableState.value = credentials?.let(PairingUiState::Paired) ?: PairingUiState.Unpaired
        }
    }

    fun pair(serverUrl: String, code: String) {
        if (mutableState.value is PairingUiState.Pairing) return
        viewModelScope.launch {
            mutableState.value = PairingUiState.Pairing(serverUrl.trim())
            try {
                val credentials = pairingGateway.pair(serverUrl, code)
                withContext(ioDispatcher) { credentialStore.save(credentials) }
                mutableState.value = PairingUiState.Paired(credentials)
            } catch (error: IllegalArgumentException) {
                mutableState.value = PairingUiState.Error(error.message ?: "Invalid pairing details.")
            } catch (error: PairingException) {
                mutableState.value = PairingUiState.Error(error.message ?: "Pairing failed.")
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.value = PairingUiState.Error("Pairing could not be completed.")
            }
        }
    }

    fun dismissError() {
        if (mutableState.value is PairingUiState.Error) {
            mutableState.value = PairingUiState.Unpaired
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            withContext(ioDispatcher) { credentialStore.clear() }
            mutableState.value = PairingUiState.Unpaired
        }
    }
}

class PairingViewModelFactory(
    private val pairingGateway: PairingGateway,
    private val credentialStore: CredentialStore,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(PairingViewModel::class.java))
        return PairingViewModel(pairingGateway, credentialStore) as T
    }
}
