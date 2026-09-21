package app.meshpigeon.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.meshpigeon.domain.ChannelRepository
import app.meshpigeon.domain.ConversationRepository
import app.meshpigeon.domain.CreateIdentity
import app.meshpigeon.domain.IdentityRepository
import app.meshpigeon.domain.RadioPresets
import app.meshpigeon.domain.RegionPreset
import app.meshpigeon.protocol.MeshCrypto
import app.meshpigeon.ui.MeshPigeonSpacing
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * First-run onboarding (07 §2): welcome → name → region → connect radio →
 * Chats. "Skip for now" is always available — offline mode is first-class.
 * Finishing the region step creates the profile: identity keypair, the
 * Public channel, and its conversation (07 §6 hard requirement).
 */
class OnboardingViewModel(
    private val identities: IdentityRepository,
    private val channels: ChannelRepository,
    private val conversations: ConversationRepository,
    private val crypto: MeshCrypto,
) : ViewModel() {
    data class State(
        val step: Int = 0,
        val name: String = "",
        val region: RegionPreset = RadioPresets.REGIONS.first(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    fun setName(name: String) {
        _state.value = _state.value.copy(name = name)
    }

    fun setRegion(region: RegionPreset) {
        _state.value = _state.value.copy(region = region)
    }

    fun next() {
        val wasRegionStep = _state.value.step == 2
        _state.value = _state.value.copy(step = (_state.value.step + 1).coerceAtMost(3))
        if (wasRegionStep) viewModelScope.launch { createProfile() }
    }

    fun back() {
        _state.value = _state.value.copy(step = (_state.value.step - 1).coerceAtLeast(0))
    }

    private suspend fun createProfile() {
        if (identities.active().first() != null) return // already set up
        CreateIdentity(identities, channels, conversations, crypto).create(_state.value.name)
    }
}

@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    onScanForRadios: (() -> Unit)? = null,
    viewModel: OnboardingViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(MeshPigeonSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(MeshPigeonSpacing.md),
    ) {
        LinearProgressIndicator(
            progress = { (state.step + 1) / 4f },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Onboarding step ${state.step + 1} of 4" },
        )
        when (state.step) {
            0 -> WelcomeStep(onNext = viewModel::next)
            1 -> NameStep(
                name = state.name,
                onNameChange = viewModel::setName,
                onNext = viewModel::next,
                onBack = viewModel::back,
            )
            2 -> RegionStep(
                selected = state.region,
                onSelected = viewModel::setRegion,
                onNext = viewModel::next,
                onBack = viewModel::back,
            )
            else -> ConnectStep(
                onSkip = onFinished,
                onScan = onScanForRadios,
                onDone = onFinished,
                onBack = viewModel::back,
            )
        }
    }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(MeshPigeonSpacing.md)) {
        Text("🕊️", style = MaterialTheme.typography.displayLarge) // brand mark
        Text("Welcome to MeshPigeon", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Message anywhere without internet. Your messages hop across a " +
                "network of small radios — no towers, no accounts.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(MeshPigeonSpacing.xl))
        Button(
            onClick = onNext,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .semantics { contentDescription = "Get started with MeshPigeon" },
        ) { Text("Get started") }
    }
}

@Composable
private fun NameStep(name: String, onNameChange: (String) -> Unit, onNext: () -> Unit, onBack: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(MeshPigeonSpacing.md)) {
        Text("What should people call you?", style = MaterialTheme.typography.headlineSmall)
        Text("This is the name others will see.", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            singleLine = true,
            label = { Text("Your name") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(MeshPigeonSpacing.sm)) {
            TextButton(onClick = onBack) { Text("Back") }
            Button(onClick = onNext, enabled = name.isNotBlank()) { Text("Continue") }
        }
    }
}

@Composable
private fun RegionStep(
    selected: RegionPreset,
    onSelected: (RegionPreset) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MeshPigeonSpacing.md)) {
        Text("Where are you?", style = MaterialTheme.typography.headlineSmall)
        Text(
            "This tunes your radio. You can change it later in Settings.",
            style = MaterialTheme.typography.bodyMedium,
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(MeshPigeonSpacing.xs),
        ) {
            items(RadioPresets.REGIONS.filter { it.id != 0 }) { region ->
                Card(onClick = { onSelected(region) }) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MeshPigeonSpacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = region.id == selected.id, onClick = { onSelected(region) })
                        Column {
                            Text(region.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${region.freqHz / 1_000_000.0} MHz · SF${region.spreadingFactor} · ${region.bandwidthKhz} kHz",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MeshPigeonSpacing.sm)) {
            TextButton(onClick = onBack) { Text("Back") }
            Button(onClick = onNext) { Text("Continue") }
        }
    }
}

@Composable
private fun ConnectStep(onSkip: () -> Unit, onScan: (() -> Unit)?, onDone: () -> Unit, onBack: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(MeshPigeonSpacing.md)) {
        Text("Connect your radio", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Plug your radio in over USB or turn it on near you over Bluetooth. " +
                "You can also do this later.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(MeshPigeonSpacing.md))
        Button(onClick = { onScan?.invoke() }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("Scan for radios")
        }
        // The scan sheet lives with the app's connection UI; onboarding
        // keeps the flow short (07 §2).
        OutlinedButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
            Text("Skip for now — explore offline")
        }
        if (onScan != null) {
            TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text("Continue")
            }
        }
        TextButton(onClick = onBack) { Text("Back") }
    }
}
