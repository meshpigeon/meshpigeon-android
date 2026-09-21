<p align="center"><img src="banner.png" width="480" alt="MeshPigeon — messages that find their way home"></p>

# MeshPigeon App

**Message anywhere. No towers, no internet.** MeshPigeon is a native Android
messaging app for mesh radio: install, connect a radio, start messaging. All
protocol, identity, storage, and UX intelligence lives here — the radio
([meshpigeon-firmware](https://github.com/jhuebert/meshpigeon-firmware)) is a dumb,
durable packet store.

```
┌──────────────────────────────────────────────────────────┐
│                    Android phone                         │
│  ┌──────────┐  ┌────────┐  ┌──────────────────────┐      │
│  │ UI (M3   │←→│ Domain │←→│ Protocol (codec,     │      │
│  │ Compose) │  │ (use   │  │ crypto, routing FSM) │      │
│  └────┬─────┘  │ cases) │  └──────────┬───────────┘      │
│       │        └───┬────┘             │                  │
│       │   ┌────────▼──────────────────▼───────────┐      │
│       │   │ Transport (RadioAdapter SPI:          │      │
│       │   │  BLE · USB-CDC · Wi-Fi/TCP)           │      │
│       │   └───────────────────┬───────────────────┘      │
│  ┌────▼───────────────────────▼───────────────────────┐  │
│  │ Room DB: messages, contacts, channels, identities  │  │
│  └────────────────────────────────────────────────────┘  │
└───────────────────────────┬──────────────────────────────┘
                            ▼  raw frames (BLE / USB / TCP)
              ┌──────────────────────────────┐
              │ MeshPigeon radio (dumb, durable)│
              └──────────────────────────────┘
                            ▼ on-air, MeshCore-compatible
```

## Status: v0.1.0 (M0/M1 foundations)

Working, tested core (68 unit tests + a live simulator interop test):

- **`:core-protocol`** — MeshCore-compatible v1 on-air codec, exact crypto
  (Ed25519 identities, AES-128-ECB + 2-byte HMAC per the MeshCore scheme,
  cross-verified against MeshCore's own C library), channel keys
  (public / hashtag / private), ACK/retry state machine with physics-based
  timeouts, uptime→wall-clock mapping.
- **`:core-transport`** — `RadioAdapter` SPI + framing byte-identical to the
  firmware's, session with nonces/timeouts/streamed fetch, TCP adapter
  (drives the desktop simulator), BLE (Nordic UART) and USB-CDC adapters in
  `:transport-android`.
- **`:core-domain`** — send/receive/history-sync/connect use cases,
  settings-epoch guard, region presets.
- **`:core-data`** — Room schema (identity-namespaced, unlimited history).
- **UI** — Material 3 Compose: onboarding, Chats, Conversation
  (delivery states: ⏳ → ✓ → ✓ heard → ✓✓), Contacts.

Module layout note: BLE/USB adapters live in `:transport-android` rather
than inside `:core-transport` so the SPI + session stay JVM-pure and
testable (a small deviation from plan 02 with the contracts unchanged).

## Build

```sh
./gradlew :app:assembleDebug     # debug APK
./gradlew :app:assembleRelease   # minified release
./gradlew test                   # all JVM unit tests
```

Run the app against the **desktop radio simulator** (no hardware needed):

```sh
# in a sibling checkout of meshpigeon-firmware:
pio run -e sim && .pio/build/sim/program --port 8765
# then the opt-in integration test:
./gradlew :core-transport:test -Dmeshpigeon.sim.port=8765 \
  --tests "*TransportSimInteropTest*"
```

## Guiding principles

See [GUIDING-PRINCIPLES.md](GUIDING-PRINCIPLES.md). The one rule that
surprises people: **the firmware has no protocol** — if a change would give
the radio opinions about repeating, message content, keys, or identity, it
belongs in this app.

## License

MIT — see [LICENSE](LICENSE).
