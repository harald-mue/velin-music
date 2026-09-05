package com.haraldmue.velin.ui.pairing

import com.haraldmue.velin.data.CredentialStore
import com.haraldmue.velin.data.DeviceCredentials
import com.haraldmue.velin.data.PairingGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PairingViewModelTest {
    @Test
    fun successfulPairingPersistsCredentialsAndUpdatesState() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val expected = DeviceCredentials(
                serverUrl = "https://velin.example",
                deviceId = "device-1",
                token = "secret-token",
                serverName = "Velin",
                serverVersion = "test",
            )
            val store = FakeCredentialStore()
            var credentialChangeCount = 0
            var credentialsSeenByCallback: DeviceCredentials? = null
            val viewModel = PairingViewModel(
                pairingGateway = PairingGateway { _, _ -> expected },
                credentialStore = store,
                ioDispatcher = dispatcher,
                onCredentialsChanged = {
                    credentialChangeCount++
                    credentialsSeenByCallback = store.credentials
                },
            )

            advanceUntilIdle()
            assertEquals(PairingUiState.Unpaired, viewModel.state.value)
            assertNull(store.credentials)

            viewModel.pair("https://velin.example", "one-time-code")
            advanceUntilIdle()

            assertEquals(expected, store.credentials)
            assertEquals(expected, credentialsSeenByCallback)
            assertEquals(1, credentialChangeCount)
            assertEquals(PairingUiState.Paired(expected), viewModel.state.value)

            viewModel.disconnect()
            advanceUntilIdle()

            assertNull(store.credentials)
            assertNull(credentialsSeenByCallback)
            assertEquals(2, credentialChangeCount)
            assertEquals(PairingUiState.Unpaired, viewModel.state.value)
        } finally {
            Dispatchers.resetMain()
        }
    }
}

private class FakeCredentialStore : CredentialStore {
    var credentials: DeviceCredentials? = null

    override fun load(): DeviceCredentials? = credentials

    override fun save(credentials: DeviceCredentials) {
        this.credentials = credentials
    }

    override fun clear() {
        credentials = null
    }
}
