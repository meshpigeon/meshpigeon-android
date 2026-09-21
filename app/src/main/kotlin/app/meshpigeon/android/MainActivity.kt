package app.meshpigeon.android

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.meshpigeon.domain.CreateIdentity
import app.meshpigeon.domain.Identity
import app.meshpigeon.feature.contacts.ContactsScreen
import app.meshpigeon.feature.contacts.ContactsViewModel
import app.meshpigeon.feature.messaging.ChatsScreen
import app.meshpigeon.feature.messaging.ChatsViewModel
import app.meshpigeon.feature.messaging.ConversationScreen
import app.meshpigeon.feature.messaging.ConversationViewModel
import app.meshpigeon.feature.messaging.StartChatSheet
import app.meshpigeon.feature.messaging.StartChatViewModel
import app.meshpigeon.feature.onboarding.OnboardingScreen
import app.meshpigeon.feature.onboarding.OnboardingViewModel
import app.meshpigeon.ui.InitialAvatar
import app.meshpigeon.ui.MeshPigeonSpacing
import app.meshpigeon.ui.MeshPigeonTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * MeshPigeon main activity: offline-first home is Chats (00 principle 1),
 * bottom navigation (Chats / Contacts / Map), drawer for identities.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = AppGraph.of(this)
        setContent {
            MeshPigeonTheme {
                MeshPigeonApp(graph)
            }
        }
    }
}

private sealed class Destination(val route: String) {
    data object Onboarding : Destination("onboarding")
    data object Chats : Destination("chats")
    data object Contacts : Destination("contacts")
    data object Map : Destination("map")
    data object Conversation : Destination("conversation/{conversationId}") {
        fun of(id: Long) = "conversation/$id"
    }
}

@Composable
fun MeshPigeonApp(graph: AppGraph) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    var showConnectSheet by remember { mutableStateOf(false) }
    var showStartChat by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val identities by graph.identities.all().collectAsStateWithLifecycle(initialValue = emptyList())
    val activeIdentity by graph.identities.active().collectAsStateWithLifecycle(initialValue = null)
    var showAddIdentity by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    // first-run gate: without a profile, start at onboarding (07 §2)
    var hasProfile by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        hasProfile = graph.identities.active().first() != null
    }
    // Android 13+ gates notifications behind a runtime permission (07 §8)
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(graph.appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    if (hasProfile == null) return // profile check is a fast local query

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            MeshPigeonDrawer(
                identities = identities,
                active = activeIdentity,
                onSelect = { id ->
                    scope.launch {
                        graph.identities.setActive(id)
                        drawerState.close()
                    }
                },
                onAdd = { showAddIdentity = true },
                onConnectRadio = {
                    scope.launch { drawerState.close() }
                    showConnectSheet = true
                },
                onSettings = { showSettings = true },
            )
        },
    ) {
        Scaffold(
        bottomBar = {
            if (currentRoute in setOf(Destination.Chats.route, Destination.Contacts.route, Destination.Map.route)) {
                NavigationBar(
                    modifier = Modifier.semantics { contentDescription = "Main navigation" },
                ) {
                    NavigationBarItem(
                        selected = currentRoute == Destination.Chats.route,
                        onClick = { navController.navigate(Destination.Chats.route) { popUpTo(Destination.Chats.route); launchSingleTop = true } },
                        icon = { Icon(Icons.Filled.Chat, contentDescription = null) },
                        label = { Text("Chats") },
                    )
                    NavigationBarItem(
                        selected = currentRoute == Destination.Contacts.route,
                        onClick = { navController.navigate(Destination.Contacts.route) { launchSingleTop = true } },
                        icon = { Icon(Icons.Filled.Groups, contentDescription = null) },
                        label = { Text("Contacts") },
                    )
                    NavigationBarItem(
                        selected = currentRoute == Destination.Map.route,
                        onClick = { navController.navigate(Destination.Map.route) { launchSingleTop = true } },
                        icon = { Icon(Icons.Filled.Place, contentDescription = null) },
                        label = { Text("Map") },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = if (hasProfile == false) Destination.Onboarding.route else Destination.Chats.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Destination.Onboarding.route) {
                val vm: OnboardingViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            OnboardingViewModel(
                                graph.identities, graph.channels, graph.conversations, graph.crypto,
                            )
                        }
                    },
                )
                OnboardingScreen(
                    onFinished = {
                        navController.navigate(Destination.Chats.route) {
                            popUpTo(Destination.Onboarding.route) { inclusive = true }
                        }
                    },
                    onScanForRadios = { showConnectSheet = true },
                    viewModel = vm,
                )
            }
            composable(Destination.Chats.route) {
                val vm: ChatsViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            ChatsViewModel(
                                graph.identities, graph.conversations, graph.messages,
                                graph.contacts, graph.channels,
                            )
                        }
                    },
                )
                ChatsScreen(
                    viewModel = vm,
                    onOpenConversation = { conv ->
                        navController.navigate(Destination.Conversation.of(conv.id))
                    },
                    onStartChat = { showStartChat = true },
                    onConnectRadio = { showConnectSheet = true },
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                )
            }
            composable(Destination.Contacts.route) {
                val vm: ContactsViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            ContactsViewModel(graph.identities, graph.contacts)
                        }
                    },
                )
                ContactsScreen(
                    viewModel = vm,
                    onSayHi = {
                        when {
                            graph.radioSession == null -> "Connect a radio first"
                            else -> {
                                val packet = graph.sendAdvert.build(zeroHop = true)
                                when {
                                    packet == null -> "Create a profile first"
                                    else -> runCatching {
                                        graph.repeater.observeOutgoing(packet) // TX echo must not repeat
                                        graph.radioSession!!.sendPacket(packet)
                                    }
                                        .fold(
                                            onSuccess = { "Hello sent — radios in range heard you" },
                                            onFailure = { "Could not send: ${it.message}" },
                                        )
                                }
                            }
                        }
                    },
                )
            }
            composable(Destination.Map.route) {
                val vm: MapViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            MapViewModel(graph.identities, graph.contacts, graph.conversations)
                        }
                    },
                )
                MapScreen(
                    viewModel = vm,
                    onOpenConversation = { id ->
                        navController.navigate(Destination.Conversation.of(id))
                    },
                )
            }
            composable(Destination.Conversation.route) { entry ->
                val conversationId = entry.arguments?.getString("conversationId")?.toLongOrNull() ?: return@composable
                val vm: ConversationViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            ConversationViewModel(
                                graph.identities, graph.conversations, graph.messages,
                                graph.contacts, graph.channels, graph.createChannel,
                                graph.sendMessage, conversationId,
                            )
                        }
                    },
                )
                ConversationScreen(
                    viewModel = vm,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
    }

    if (showStartChat) {
        StartChatSheet(
            viewModel = remember {
                StartChatViewModel(graph.identities, graph.contacts, graph.conversations, graph.createChannel)
            },
            onOpenConversation = { id -> navController.navigate(Destination.Conversation.of(id)) },
            onDismiss = { showStartChat = false },
        )
    }

    if (showConnectSheet) {
        RadioConnectSheet(graph = graph, onDismiss = { showConnectSheet = false })
    }

    if (showAddIdentity) {
        AddIdentityDialog(
            onCreate = { name ->
                showAddIdentity = false
                scope.launch { graph.createIdentity.create(name) }
            },
            onDismiss = { showAddIdentity = false },
        )
    }

    if (showSettings) {
        SettingsDialog(graph = graph, onDismiss = { showSettings = false })
    }
}

/** Left drawer (07 §1): identity switcher, connect radio, settings. */
@Composable
private fun MeshPigeonDrawer(
    identities: List<Identity>,
    active: Identity?,
    onSelect: (Long) -> Unit,
    onAdd: () -> Unit,
    onConnectRadio: () -> Unit,
    onSettings: () -> Unit,
) {
    ModalDrawerSheet {
        Column(modifier = Modifier.padding(MeshPigeonSpacing.md), verticalArrangement = Arrangement.spacedBy(MeshPigeonSpacing.xs)) {
            Text("Identities", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = MeshPigeonSpacing.sm))
            identities.forEach { identity ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(identity.id) }
                        .padding(vertical = MeshPigeonSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MeshPigeonSpacing.md),
                ) {
                    InitialAvatar(name = identity.name, key = identity.publicKey)
                    Text(
                        identity.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    if (identity.id == active?.id) {
                        Icon(Icons.Filled.Check, contentDescription = "Active identity")
                    }
                }
            }
            TextButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("Add identity")
            }
            TextButton(onClick = onConnectRadio) {
                Text("Connect radio…")
            }
            TextButton(onClick = onSettings) {
                Text("Settings")
            }
        }
    }
}

/** Settings (drawer): the in-app repeater on/off toggle (03 §4). */
@Composable
private fun SettingsDialog(graph: AppGraph, onDismiss: () -> Unit) {
    val repeaterEnabled by graph.repeaterEnabled.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MeshPigeonSpacing.md),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Repeat mesh traffic")
                    Text(
                        "While a radio is connected, pass along messages heading to pigeons out of range. " +
                            "Off by default — turn on where there's no repeater in reach, like a group hiking " +
                            "out of coverage.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = repeaterEnabled,
                    onCheckedChange = { graph.setRepeaterEnabled(it) },
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** Name prompt for a new identity (per-identity channels/conversations follow). */
@Composable
private fun AddIdentityDialog(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New identity") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("Name others will see") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank()) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Reconnect after phone restart (06 §6, opt-in via Settings toggle). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            context.startForegroundService(Intent(context, RadioConnectionService::class.java))
        }
    }
}
