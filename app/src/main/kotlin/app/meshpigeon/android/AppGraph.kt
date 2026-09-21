package app.meshpigeon.android

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.room.Room
import app.meshpigeon.data.KeystoreSecretSealer
import app.meshpigeon.data.MeshPigeonDatabase
import app.meshpigeon.data.RoomChannelRepository
import app.meshpigeon.data.RoomContactRepository
import app.meshpigeon.data.RoomConversationRepository
import app.meshpigeon.data.RoomIdentityRepository
import app.meshpigeon.data.RoomMessageRepository
import app.meshpigeon.data.RoomOutboxRepository
import app.meshpigeon.data.RoomRadioTargetRepository
import app.meshpigeon.domain.InMemoryPathCache
import app.meshpigeon.domain.CreateIdentity
import app.meshpigeon.domain.FlushOutbox
import app.meshpigeon.domain.PacketRepeater
import app.meshpigeon.domain.ReceivePipeline
import app.meshpigeon.domain.SendMessage
import app.meshpigeon.domain.SyncRadioHistory
import app.meshpigeon.protocol.AckTracker
import app.meshpigeon.protocol.BouncyMeshCrypto
import app.meshpigeon.protocol.ClockMapper
import app.meshpigeon.protocol.MeshCrypto
import app.meshpigeon.transport.FakeRadioAdapter
import app.meshpigeon.transport.RadioAdapter
import app.meshpigeon.transport.RadioLinkState
import app.meshpigeon.transport.RadioSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * Manual dependency graph (M0). Hilt lands when the feature surface grows
 * past what one graph can hold (plan 02 §app; noted as a pragmatic
 * deviation for a smaller reviewable foundation).
 */
class MeshPigeonApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

class AppGraph(private val context: Context) {
    private val prefs = context.getSharedPreferences("meshpigeon", Context.MODE_PRIVATE)

    val appContext: Context = context.applicationContext
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val crypto: MeshCrypto = BouncyMeshCrypto()

    val db: MeshPigeonDatabase = Room.databaseBuilder(context, MeshPigeonDatabase::class.java, MeshPigeonDatabase.NAME)
        // v2→v3 added contact.accepted + conversation.is_request; pre-release
        // installs rebuild rather than migrate.
        .fallbackToDestructiveMigration(dropAllTables = false)
        .build()

    val secretSealer = KeystoreSecretSealer(context)
    val identities = RoomIdentityRepository(db, secretSealer)
    val contacts = RoomContactRepository(db)
    val channels = RoomChannelRepository(db, secretSealer)
    val conversations = RoomConversationRepository(db)
    val messages = RoomMessageRepository(db)
    val outbox = RoomOutboxRepository(db)
    val radioTargets = RoomRadioTargetRepository(db)

    val clockMapper = ClockMapper()
    val ackTracker = AckTracker({ System.currentTimeMillis() })
    val pathCache = InMemoryPathCache { System.currentTimeMillis() }
    val tagCache = app.meshpigeon.domain.PacketTagCache()

    val receivePipeline = ReceivePipeline(
        identities, contacts, channels, conversations, messages,
        tagCache, ackTracker, pathCache, clockMapper, crypto,
        object : ReceivePipeline.Notifier {
            override suspend fun notify(notification: ReceivePipeline.Notification) {
                RadioNotifications.post(context, notification)
            }
        },
    )

    val syncRadioHistory = SyncRadioHistory(identities, tagCache, receivePipeline)

    /**
     * In-app repeater (03 §4): while a radio is connected, every eligible
     * flood packet we hear is re-sent verbatim (dedup by tag). Off by
     * default — enable it deliberately where there is no repeater
     * infrastructure in range (e.g. a hiking group out of coverage);
     * direct-routed traffic and packets addressed to us are never
     * repeated. The drawer's Settings switch flips [setRepeaterEnabled].
     */
    val repeater = PacketRepeater(
        crypto,
        myHash = {
            identities.active().first()?.publicKey?.getOrNull(0)?.toInt()?.and(0xFF)
        },
    ).also { it.enabled = prefs.getBoolean(PREF_REPEATER_ENABLED, false) }

    /** Persisted repeater on/off (the Settings switch reads this). */
    val repeaterEnabled = MutableStateFlow(repeater.enabled)

    fun setRepeaterEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(PREF_REPEATER_ENABLED, enabled) }
        repeater.enabled = enabled
        repeaterEnabled.value = enabled
    }

    val sendMessage = SendMessage(
        identities, contacts, conversations, messages, outbox, channels,
        ackTracker, pathCache, tagCache, crypto,
        airtimeEstimator = object : app.meshpigeon.domain.AirtimeEstimator {
            override fun estimate(packetLen: Int): Double =
                app.meshpigeon.protocol.Airtime.estimateMs(packetLen, sf = 9, bandwidthKhz = 125.0, codingRateDenominator = 5)
        },
        wallClockSec = { System.currentTimeMillis() / 1000 },
        wallClockMs = { System.currentTimeMillis() },
    )

    val connectToRadio = app.meshpigeon.domain.ConnectToRadio(radioTargets)
    val createIdentity = CreateIdentity(identities, channels, conversations, crypto)
    val createChannel = app.meshpigeon.domain.CreateChannel(channels, conversations)
    val sendAdvert = app.meshpigeon.domain.SendAdvert(identities, crypto, { System.currentTimeMillis() / 1000 })
    val settingsGuard = app.meshpigeon.domain.RadioSettingsGuard()
    val flushOutbox = FlushOutbox(outbox, messages, ackTracker, { System.currentTimeMillis() })

    /**
     * The connected radio session. v0.1 ships the TCP/sim adapter so the
     * whole pipeline is exercisable on the bench without BLE hardware;
     * :transport-android's BLE/USB adapters slot in behind the same SPI.
     */
    @Volatile
    var radioSession: RadioSession? = null

    /** Live connection state for the connect sheet and status banners. */
    data class ConnectionState(
        val phase: RadioLinkState.Phase = RadioLinkState.Phase.DISCONNECTED,
        val name: String? = null,
        val detail: String? = null,
    )

    val connection = MutableStateFlow(ConnectionState())

    /** Per-radio sync cursors (05 §6). v1: in-memory, resumes within a process. */
    val historyCursors = HashMap<String, Long>()

    fun newRadioSession(adapter: RadioAdapter): RadioSession =
        RadioSession(adapter, appScope).also { session ->
            radioSession = session
            session.start()
        }

    companion object {
        private const val PREF_REPEATER_ENABLED = "repeater_enabled"

        fun of(context: Context): AppGraph = (context.applicationContext as MeshPigeonApp).graph
    }
}
