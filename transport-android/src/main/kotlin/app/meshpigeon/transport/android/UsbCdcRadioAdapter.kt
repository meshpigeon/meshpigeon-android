package app.meshpigeon.transport.android

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import app.meshpigeon.transport.FrameStream
import app.meshpigeon.transport.RadioAdapter
import app.meshpigeon.transport.RadioFrame
import app.meshpigeon.transport.RadioLink
import app.meshpigeon.transport.RadioLinkState
import app.meshpigeon.transport.RadioTarget
import app.meshpigeon.transport.TransportFrameCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * USB-CDC-ACM adapter (05 §1): talks to the firmware's virtual COM port
 * (ESP32-S3 native USB / nRF52 TinyUSB). One host, one radio — permission
 * is remembered per device (06 §2 radio_targets).
 */
class UsbCdcRadioAdapter(
    private val context: Context,
    private val device: UsbDevice,
) : RadioAdapter {

    override val state = MutableStateFlow(RadioLinkState(RadioLinkState.Phase.DISCONNECTED))
    override val frames = MutableSharedFlow<RadioFrame>(extraBufferCapacity = 256)

    private val running = AtomicBoolean(false)
    private var connection: UsbDeviceConnection? = null
    private var cdcInterface: UsbInterface? = null
    private var reader: Thread? = null
    private val stream = FrameStream()

    private val target = RadioTarget(
        persistentId = "usb:${device.vendorId}:${device.productId}",
        name = device.productName ?: "USB radio",
        link = RadioLink.Usb(device.vendorId, device.productId, device.deviceName),
    )

    fun hasPermission(): Boolean =
        (context.getSystemService(Context.USB_SERVICE) as UsbManager)
            .hasPermission(device)

    /** Opens the CDC-ACM interface (control lines + bulk endpoints). */
    fun connectBlocking(): Boolean {
        val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val conn = usb.openDevice(device) ?: return false
        val cdc = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_COMM || it.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA }
            ?: device.getInterface(0)
        if (!conn.claimInterface(cdc, true)) {
            conn.close()
            return false
        }
        connection = conn
        cdcInterface = cdc
        state.value = RadioLinkState(RadioLinkState.Phase.CONNECTED, target)

        var bulkIn: UsbEndpoint? = null
        var bulkOut: UsbEndpoint? = null
        for (i in 0 until cdc.endpointCount) {
            val ep = cdc.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN) bulkIn = ep else bulkOut = ep
            }
        }
        bulkOutEndpoint = bulkOut
        bulkInEndpoint = bulkIn
        state.value = RadioLinkState(RadioLinkState.Phase.RADIO_READY, target)

        running.set(true)
        reader = Thread {
            val buf = ByteArray(512)
            val inEp = bulkIn
            while (running.get() && inEp != null) {
                val n = conn.bulkTransfer(inEp, buf, buf.size, 200)
                if (n > 0) {
                    synchronized(stream) {
                        stream.feed(buf.copyOf(n)).forEach { frames.tryEmit(it) }
                    }
                }
            }
        }.also { it.isDaemon = true; it.start() }
        return true
    }

    private var bulkOutEndpoint: UsbEndpoint? = null
    private var bulkInEndpoint: UsbEndpoint? = null

    override suspend fun send(frame: RadioFrame) {
        val conn = connection ?: error("not connected")
        val out = bulkOutEndpoint ?: error("no bulk out endpoint")
        val wire = TransportFrameCodec.toWire(frame)
        conn.bulkTransfer(out, wire, wire.size, 2000)
    }

    override fun scan(): Flow<RadioTarget> = callbackFlow {
        val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val scope = kotlinx.coroutines.MainScope()
        scope.launch(Dispatchers.Main) {
            usb.deviceList.values
                .filter { isCdcDevice(it) }
                .forEach { d ->
                    trySend(
                        RadioTarget(
                            persistentId = "usb:${d.vendorId}:${d.productId}",
                            name = d.productName ?: "USB radio",
                            link = RadioLink.Usb(d.vendorId, d.productId, d.deviceName),
                        ),
                    )
                }
            close()
        }
        awaitClose { scope.cancel() }
    }

    fun close() {
        running.set(false)
        reader?.interrupt()
        cdcInterface?.let { connection?.releaseInterface(it) }
        connection?.close()
        connection = null
        state.value = RadioLinkState(RadioLinkState.Phase.DISCONNECTED)
    }

    companion object {
        /** Broadcast the permission prompt answers with (connect sheet). */
        const val ACTION_USB_PERMISSION = "app.meshpigeon.transport.android.USB_PERMISSION"

        /** CDC-ACM candidates: TinyUSB/Adafruit CDC (class 0x02 on the comm
         *  interface, 0x0A on the data interface) and the ESP32-S3 native
         *  USB-Serial-JTAG peripheral (vendor-specific class 0xFF — what
         *  ARDUINO_USB_MODE=1 boards enumerate as). */
        fun isCdcDevice(device: UsbDevice): Boolean =
            (0 until device.interfaceCount).any { i ->
                val c = device.getInterface(i).interfaceClass
                c == UsbConstants.USB_CLASS_COMM ||
                    c == UsbConstants.USB_CLASS_CDC_DATA ||
                    c == UsbConstants.USB_CLASS_VENDOR_SPEC
            }
    }
}
