package app.meshpigeon.android

import app.meshpigeon.domain.AdvertPolicy
import app.meshpigeon.domain.Contact
import app.meshpigeon.domain.ContactRepository
import app.meshpigeon.domain.Conversation
import app.meshpigeon.domain.ConversationKind
import app.meshpigeon.domain.ConversationRepository
import app.meshpigeon.domain.Identity
import app.meshpigeon.domain.IdentityRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Map pin selection + the pin→DM-conversation jump (07 §10). Pure JVM:
 * in-memory repositories, no Room, no Android.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MapViewModelTest {

    private val identities = TestIdentityRepository()
    private val contacts = TestContactRepository()
    private val conversations = TestConversationRepository()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        runBlocking { identities.upsert(identity(1L, isActive = true)) }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun identity(id: Long, isActive: Boolean) = Identity(
        id = id,
        name = "Me",
        publicKey = ByteArray(32) { it.toByte() },
        privateKeyEnc = ByteArray(0),
        flags = 0,
        createdAt = 0L,
        isActive = isActive,
        advertPolicy = AdvertPolicy.MANUAL,
    )

    private fun contact(id: Long, name: String, lat: Double? = null, lon: Double? = null) = Contact(
        id = id,
        identityId = 1L,
        publicKey = ByteArray(32) { (it + id).toByte() },
        name = name,
        firstSeenAt = 0L,
        lastSeenAt = if (lat != null) 1_000L else null,
        lastLatitude = lat,
        lastLongitude = lon,
        isRepeater = false,
    )

    @Test
    fun `pins include only located, unblocked contacts`() = runTest {
        contacts.store.value = listOf(
            contact(10, "Alice", lat = 47.6, lon = -122.3),
            contact(11, "NoPosition"),
            contact(12, "Bob", lat = 40.0, lon = -105.0).let { it.copy(blockedAt = 1L) },
            contact(13, "Repeater Rose", lat = 47.0, lon = -122.0).copy(isRepeater = true),
        )
        val vm = MapViewModel(identities, contacts, conversations)

        val pins = vm.state.first { it.pins.isNotEmpty() }.pins
        assertEquals(listOf("Alice", "Repeater Rose"), pins.map { it.name })
        assertEquals(47.6, pins[0].latitude, 1e-9)
        assertEquals(-122.3, pins[0].longitude, 1e-9)
        assertTrue(pins[1].isRepeater)
    }

    @Test
    fun `message opens or creates the DM conversation`() = runTest {
        contacts.store.value = listOf(contact(10, "Alice", lat = 47.6, lon = -122.3))
        val vm = MapViewModel(identities, contacts, conversations)
        val pin = vm.state.first { it.pins.isNotEmpty() }.pins.single()

        var opened: Long? = null
        vm.message(pin) { opened = it }

        val conversationId = requireNotNull(opened)
        val conversation = conversations.byId(conversationId)
        assertEquals(ConversationKind.DM, conversation?.kind)
        assertEquals(10L, conversation?.refId)
    }
}

/** In-memory fakes covering the three repository surfaces MapViewModel uses. */
private class TestIdentityRepository : IdentityRepository {
    val store = MutableStateFlow<List<Identity>>(emptyList())
    override fun active(): Flow<Identity?> = store.map { list -> list.firstOrNull { it.isActive } }
    override fun all(): Flow<List<Identity>> = store
    override suspend fun upsert(identity: Identity): Long {
        store.value = store.value.filter { it.id != identity.id } + identity
        return identity.id
    }
    override suspend fun setActive(id: Long) {
        store.value = store.value.map { it.copy(isActive = it.id == id) }
    }
    override suspend fun delete(id: Long) {
        store.value = store.value.filterNot { it.id == id }
    }
}

private class TestContactRepository : ContactRepository {
    val store = MutableStateFlow<List<Contact>>(emptyList())
    override fun observe(identityId: Long): Flow<List<Contact>> =
        store.map { list -> list.filter { it.identityId == identityId } }
    override fun observeBlocked(identityId: Long): Flow<List<Contact>> =
        store.map { list -> list.filter { it.identityId == identityId && it.isBlocked } }
    override fun observePending(identityId: Long): Flow<List<Contact>> =
        store.map { list -> list.filter { it.identityId == identityId && it.isPending && !it.isBlocked } }
    override suspend fun byId(id: Long): Contact? = store.value.firstOrNull { it.id == id }
    override suspend fun byPublicKey(identityId: Long, publicKey: ByteArray): Contact? =
        store.value.firstOrNull { it.identityId == identityId && it.publicKey.contentEquals(publicKey) }
    override suspend fun upsert(contact: Contact): Long {
        store.value = store.value.filter { it.id != contact.id } + contact
        return contact.id
    }
    override suspend fun block(id: Long) {
        store.value = store.value.map { if (it.id == id) it.copy(blockedAt = 1L) else it }
    }
    override suspend fun unblock(id: Long) {
        store.value = store.value.map { if (it.id == id) it.copy(blockedAt = null) else it }
    }
    override suspend fun rename(id: Long, name: String) {
        store.value = store.value.map { if (it.id == id) it.copy(name = name) else it }
    }
    override suspend fun clearPending(identityId: Long) {
        store.value = store.value.filterNot { it.identityId == identityId && it.isPending && !it.isBlocked }
    }
    override suspend fun setAccepted(id: Long, accepted: Boolean) {
        store.value = store.value.map { if (it.id == id) it.copy(accepted = accepted) else it }
    }
    override suspend fun delete(id: Long) {
        store.value = store.value.filterNot { it.id == id }
    }
}

private class TestConversationRepository : ConversationRepository {
    val store = MutableStateFlow<List<Conversation>>(emptyList())
    private var nextId = 1L
    override fun observeAll(identityId: Long): Flow<List<Conversation>> =
        store.map { list -> list.filter { it.identityId == identityId } }
    override fun observe(conversationId: Long): Flow<Conversation?> =
        store.map { list -> list.firstOrNull { it.id == conversationId } }
    override suspend fun byId(conversationId: Long): Conversation? =
        store.value.firstOrNull { it.id == conversationId }
    override suspend fun byKind(identityId: Long, kind: ConversationKind): Conversation? =
        store.value.firstOrNull { it.identityId == identityId && it.kind == kind }
    override suspend fun ensure(identityId: Long, kind: ConversationKind, refId: Long?): Long {
        val existing = store.value.firstOrNull {
            it.identityId == identityId && it.kind == kind && it.refId == refId
        }
        if (existing != null) return existing.id
        val id = nextId++
        store.value += Conversation(id, identityId, kind, refId)
        return id
    }
    override suspend fun update(conversation: Conversation) {
        store.value = store.value.map { if (it.id == conversation.id) conversation else it }
    }
    override suspend fun delete(conversationId: Long) {
        store.value = store.value.filterNot { it.id == conversationId }
    }
    override suspend fun bumpUnread(conversationId: Long, delta: Int) {
        store.value = store.value.map { if (it.id == conversationId) it.copy(unreadCount = it.unreadCount + delta) else it }
    }
    override suspend fun markRead(conversationId: Long) {
        store.value = store.value.map { if (it.id == conversationId) it.copy(unreadCount = 0) else it }
    }
    override suspend fun markAllRead(identityId: Long) {
        store.value = store.value.map { if (it.identityId == identityId) it.copy(unreadCount = 0) else it }
    }
}
