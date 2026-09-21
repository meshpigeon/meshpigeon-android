package app.meshpigeon.android

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import android.annotation.SuppressLint
import app.meshpigeon.domain.RadioLinkKind
import app.meshpigeon.domain.RadioTarget
import app.meshpigeon.protocol.Messages
import app.meshpigeon.protocol.PacketCodec
import app.meshpigeon.protocol.PacketSpec
import app.meshpigeon.transport.RadioAdapter
import app.meshpigeon.transport.RadioFrame
import app.meshpigeon.transport.RadioLinkState
import app.meshpigeon.transport.RadioSession
import app.meshpigeon.transport.TcpRadioAdapter
import app.meshpigeon.transport.android.BleRadioAdapter
import app.meshpigeon.transport.android.UsbCdcRadioAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch

/**
 * Foreground service holding the radio connection (06 §6): keeps the
 * session alive while backgrounded, syncs history on connect, re-anchors
 * uptime every 15 min, feeds live packets through the receive pipeline
 * (fast-path ACKs included), and flushes the outbox with ACK/retry
 * tracking via [FlushOutbox]. The retry/ACK state machine lives in
 * :core-domain — this service only moves bytes.
 */
class RadioConnectionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requested = intent?.let {
            RadioTarget(
                id = 0,
                persistentId = it.getStringExtra(EXTRA_PERSISTENT_ID) ?: "",
                name = it.getStringExtra(EXTRA_NAME) ?: "Radio",
                linkKind = runCatching { RadioLinkKind.valueOf(it.getStringExtra(EXTRA_KIND) ?: "") }
                    .getOrDefault(RadioLinkKind.WIFI),
                linkAddr = it.getStringExtra(EXTRA_ADDR) ?: "",
                prefOrder = 0,
                preferred = false,
            )
        }
        startInForeground()
        scope.launch { run(requested) }
        return START_STICKY
    }

    private fun startInForeground() {
        val notification = RadioNotifications.connection(this, "Connecting…")
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(
                this, RadioNotifications.ID_CONNECTION, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(RadioNotifications.ID_CONNECTION, notification)
        }
    }

    private suspend fun run(requested: RadioTarget?) {
        val graph = AppGraph.of(this)
        val target = requested
            ?: graph.radioTargets.observeAll().first().firstOrNull()
        if (target == null) {
            graph.connection.value = AppGraph.ConnectionState(detail = "No radio configured")
            updateNotification("No radio yet — tap MeshPigeon to connect")
            return
        }

        graph.radioSession?.stop()
        graph.radioSession = null
        graph.connection.value = AppGraph.ConnectionState(RadioLinkState.Phase.CONNECTED, target.name)

        val adapter = adapterFor(target)
        if (adapter == null) {
            graph.connection.value = AppGraph.ConnectionState(detail = "${target.name}: could not open the link")
            updateNotification("${target.name} — could not open the link")
            return
        }

        try {
            val session = graph.newRadioSession(adapter)
            // the repeater's outgoing path is the current session; errors are
            // handled inside onPacket's runCatching
            graph.repeater.transmit = { raw -> session.sendPacket(raw) }
            val info = session.getInfo()
            anchorUptime(session)
            graph.connection.value = AppGraph.ConnectionState(
                RadioLinkState.Phase.RADIO_READY, target.name, "Connected · ${info.boardName}",
            )
            updateNotification("Connected · ${info.boardName}")

            // history catch-up from the radio's retained store (05 §6)
            val cursor = graph.historyCursors[target.persistentId] ?: 0
            graph.syncRadioHistory.sync(session, cursor)
            graph.historyCursors[target.persistentId] = info.oldestSeq
            // replayed history is not re-ACKed on the air
            graph.receivePipeline.pendingAcks.clear()

            graph.flushOutbox.resume()
            val rxJob = scope.launch { collectLivePackets(session) }
            try {
                mainLoop(session, adapter)
            } finally {
                rxJob.cancel()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            graph.connection.value = AppGraph.ConnectionState(detail = "Radio unreachable")
            updateNotification("Radio unreachable — retrying…")
        } finally {
            graph.radioSession?.stop()
            graph.radioSession = null
            graph.connection.value = AppGraph.ConnectionState()
            stopSelf()
        }
    }

    private suspend fun mainLoop(session: RadioSession, adapter: RadioAdapter) {
        val graph = AppGraph.of(this)
        var lastAnchor = System.currentTimeMillis()
        val current = currentCoroutineContext()
        while (current.isActive) {
            delay(250)
            if (adapter.state.value.phase == RadioLinkState.Phase.DISCONNECTED) return
            if (System.currentTimeMillis() - lastAnchor > ClockAnchor.INTERVAL_MS) {
                anchorUptime(session)
                lastAnchor = System.currentTimeMillis()
            }
            // outbox: per-conversation FIFO + tracker-driven retries (11 §2.2)
            for (tx in graph.flushOutbox.tick()) {
                graph.repeater.observeOutgoing(tx.entry.packet) // never repeat our own TX
                runCatching { session.sendPacket(tx.entry.packet) }
            }
        }
    }

    /** Live pushes + async events (nonce 0): RX packets, ACKs. */
    private suspend fun collectLivePackets(session: RadioSession) {
        val graph = AppGraph.of(this)
        session.events.collect { frame ->
            if (frame.cmd != RadioFrame.CMD_RX_PACKET || frame.nonce != 0) return@collect
            val entry = session.parsePacketEntry(frame.payload) ?: return@collect
            graph.receivePipeline.onPacket(
                raw = entry.raw,
                rssi = entry.rssi,
                snr = entry.snr,
                radioUptimeMs = entry.uptimeMs,
            )
            // the in-app repeater judges the packet after the pipeline —
            // flood traffic we merely heard is re-sent verbatim (03 §4);
            // send failures (BUSY, TX failed) are swallowed — repeat mode
            // is best-effort, never a retry queue
            runCatching { graph.repeater.onPacket(entry.raw) }
            // ACK every received DM (fast path, 06 §4)
            var ack = graph.receivePipeline.pendingAcks.removeFirstOrNull()
            while (ack != null) {
                val ackPacket = Messages.buildAck(ack)
                graph.repeater.observeOutgoing(ackPacket)
                runCatching { session.sendPacket(ackPacket) }
                ack = graph.receivePipeline.pendingAcks.removeFirstOrNull()
            }
            // an ACK for one of our outgoing messages confirms it
            if (PacketCodec.decode(entry.raw)?.payloadType == PacketSpec.PAYLOAD_ACK) {
                PacketCodec.decode(entry.raw)?.let { graph.flushOutbox.onConfirmed(it.payload) }
            }
        }
    }

    private suspend fun anchorUptime(session: RadioSession) {
        val graph = AppGraph.of(this)
        val now = System.currentTimeMillis()
        graph.clockMapper.anchor(
            appNowMs = now,
            appUptimeMs = now,
            radioUptimeMs = session.getInfo().uptimeMs,
        )
    }

    /** Adapter per saved link kind. USB requires a granted device permission —
     *  the connect sheet's "Allow access" prompt obtains it. BLE runtime
     *  permissions are granted by the connect sheet before this runs.
     */
    @SuppressLint("MissingPermission")
    private fun adapterFor(target: RadioTarget): RadioAdapter? {
        val (host, port) = target.linkAddr.split(":", limit = 2).let {
            if (it.size == 2) it[0] to (it[1].toIntOrNull() ?: 8765) else target.linkAddr to 8765
        }
        return when (target.linkKind) {
            RadioLinkKind.WIFI -> TcpRadioAdapter(host, port).also { it.connectBlocking() }
            RadioLinkKind.BLE -> BleRadioAdapter(applicationContext, target.linkAddr)
                .also { if (!it.connectBlocking()) return null }
            RadioLinkKind.USB -> {
                val usb = getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
                val device = usb.deviceList[target.linkAddr] ?: return null
                if (!usb.hasPermission(device)) return null
                UsbCdcRadioAdapter(applicationContext, device)
                    .also { if (!it.connectBlocking()) return null }
            }
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(RadioNotifications.ID_CONNECTION, RadioNotifications.connection(this, text))
    }

    override fun onDestroy() {
        AppGraph.of(this).radioSession?.stop()
        scope.cancel()
        super.onDestroy()
    }

    object ClockAnchor {
        const val INTERVAL_MS = 15 * 60 * 1000L // 05 §6 re-anchor cadence
    }

    companion object {
        const val EXTRA_PERSISTENT_ID = "persistentId"
        const val EXTRA_NAME = "name"
        const val EXTRA_KIND = "kind"
        const val EXTRA_ADDR = "addr"
    }
}
