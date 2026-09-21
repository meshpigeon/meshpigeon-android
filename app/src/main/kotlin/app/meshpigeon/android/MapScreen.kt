package app.meshpigeon.android

import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.meshpigeon.domain.ContactRepository
import app.meshpigeon.domain.ConversationKind
import app.meshpigeon.domain.ConversationRepository
import app.meshpigeon.domain.IdentityRepository
import app.meshpigeon.ui.EmptyState
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.absoluteValue
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.tan
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Map tab (07 §10). Contacts (and repeaters) that shared a position in an
 * advert appear as pins on Google Maps; tapping a pin opens a card with the
 * last-seen time and a Message affordance. Requires Google Play services
 * and a Maps API key (local.properties `MAPS_API_KEY`, gitignored).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MapViewModel(
    private val identities: IdentityRepository,
    contacts: ContactRepository,
    private val conversations: ConversationRepository,
) : ViewModel() {

    data class Pin(
        val contactId: Long,
        val name: String,
        val latitude: Double,
        val longitude: Double,
        val isRepeater: Boolean,
        val lastSeenAt: Long?,
        /** Marker tint (0..360), same hash-hue convention as avatars (07 §3). */
        val hue: Float,
    )

    data class UiState(val pins: List<Pin> = emptyList())

    val state: StateFlow<UiState> = identities.active().flatMapLatest { identity ->
        if (identity == null) {
            flowOf(UiState())
        } else {
            contacts.observe(identity.id).map { list ->
                UiState(
                    list.filter { !it.isBlocked && it.lastLatitude != null && it.lastLongitude != null }
                        .map { c ->
                            Pin(
                                contactId = c.id,
                                name = c.name,
                                latitude = c.lastLatitude!!,
                                longitude = c.lastLongitude!!,
                                isRepeater = c.isRepeater,
                                lastSeenAt = c.lastSeenAt,
                                hue = avatarHue(c.publicKey),
                            )
                        }
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    /** Open (or create) the DM conversation for a pin and jump into it. */
    fun message(pin: Pin, onOpen: (Long) -> Unit) {
        viewModelScope.launch {
            val identity = identities.active().first() ?: return@launch
            onOpen(conversations.ensure(identity.id, ConversationKind.DM, pin.contactId))
        }
    }

    private companion object {
        /** Same fold-hash as avatarColor (07 §3), as a hue without Compose. */
        fun avatarHue(bytes: ByteArray): Float =
            (bytes.fold(0) { acc, b -> acc * 31 + (b.toInt() and 0xFF) }.absoluteValue % 360).toFloat()
    }
}

private val SHEET_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(viewModel: MapViewModel, onOpenConversation: (Long) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<MapViewModel.Pin?>(null) }
    val context = LocalContext.current

    val playServicesOk = remember {
        GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }
    val mapsKeyConfigured = remember {
        runCatching {
            context.packageManager.getApplicationInfo(
                context.packageName, PackageManager.GET_META_DATA,
            ).metaData?.getString("com.google.android.geo.API_KEY")
        }.getOrNull().orEmpty().isNotBlank()
    }

    when {
        !playServicesOk -> EmptyState(
            title = "Map unavailable",
            body = "This device has no Google Play services, so the map can't load. Radio features work without it.",
        )

        !mapsKeyConfigured -> EmptyState(
            title = "Map not set up",
            body = "Add a Google Maps API key to build the app (MAPS_API_KEY in local.properties).",
        )

        state.pins.isEmpty() -> EmptyState(
            title = "Nothing on the map yet",
            body = "People and radios that share their location in an advert appear here.",
        )

        else -> {
            val screenPx = LocalWindowInfo.current.containerSize
            val cameraPositionState = rememberCameraPositionState {
                position = initialCamera(state.pins, screenPx.width, screenPx.height)
            }
            GoogleMap(cameraPositionState = cameraPositionState, modifier = Modifier.fillMaxSize()) {
                state.pins.forEach { pin ->
                    Marker(
                        state = rememberMarkerState(
                            key = "pin-${pin.contactId}",
                            position = LatLng(pin.latitude, pin.longitude),
                        ),
                        title = pin.name,
                        icon = BitmapDescriptorFactory.defaultMarker(
                            if (pin.isRepeater) REPEATER_HUE else pin.hue,
                        ),
                        onClick = { selected = pin; true },
                    )
                }
            }
        }
    }

    selected?.let { pin ->
        ModalBottomSheet(
            onDismissRequest = { selected = null },
            sheetState = rememberModalBottomSheetState(),
        ) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
                Text(pin.name, style = MaterialTheme.typography.titleLarge)
                Text(
                    if (pin.isRepeater) "Repeater" else "Person",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                pin.lastSeenAt?.let {
                    Text(
                        "Last seen here " + SHEET_TIME_FORMAT.format(
                            Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "%.5f, %.5f".format(pin.latitude, pin.longitude),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!pin.isRepeater) {
                    Button(
                        onClick = {
                            selected = null
                            viewModel.message(pin) { onOpenConversation(it) }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    ) { Text("Message") }
                }
            }
        }
    }
}

/** One pin: centered; several: the world view or a fit-to-bounds zoom. */
private fun initialCamera(pins: List<MapViewModel.Pin>, widthPx: Int, heightPx: Int): CameraPosition {
    val center = LatLng(
        pins.map { it.latitude }.average(),
        pins.map { it.longitude }.average(),
    )
    if (pins.size == 1) return CameraPosition.fromLatLngZoom(center, 11f)

    // Zoom that fits the pin bounding box in both dimensions (Web-Mercator px).
    val lonSpan = pins.maxOf { it.longitude } - pins.minOf { it.longitude }
    val zoomLon = log2(widthPx * 360.0 / (lonSpan * TILE_PX))
    val yMax = mercatorY(pins.maxOf { it.latitude })
    val yMin = mercatorY(pins.minOf { it.latitude })
    val zoomLat = if (yMax > yMin) log2(heightPx / ((yMax - yMin) * TILE_PX)) else zoomLon
    val zoom = (minOf(zoomLon, zoomLat) - 0.25).coerceIn(2.0, 15.0)
    return CameraPosition(center, zoom.toFloat(), 0f, 0f)
}

/** Normalized Web-Mercator y (0..1). */
private fun mercatorY(lat: Double): Double {
    val clamped = lat.coerceIn(-85.05112878, 85.05112878)
    val r = Math.toRadians(clamped)
    return (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2
}

private const val TILE_PX = 256.0

/** Repeaters get a fixed brand-teal hue so they read as infrastructure. */
private const val REPEATER_HUE = 200f
