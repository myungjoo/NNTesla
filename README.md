# NNTesla

NNTesla (New Neon Tesla Cluster) is an Android app that acts as an instrument cluster for
Tesla vehicles.

- **Data source (v1):** direct BLE connection to the car, using Tesla's
  [vehicle-command](https://github.com/teslamotors/vehicle-command) protocol. A Telemetry API
  source may be added later.
- **Primary quality attribute:** display latency.
  - Speed: P95 <= 0.5 s.
  - All other data: P95 <= 3 s.

## Modules

| Module | Kind | Purpose |
|---|---|---|
| `tesla-protocol` | JVM library | vehicle-command protocol: handshake, AES-GCM request authorization, response decryption, BLE framing. Verified against the upstream `protocol.md` test vectors. |
| `tesla-ble` | Android library | GATT link, Android Keystore key, vehicle client, and the fast/slow polling scheduler. |
| `vehicle-data` | JVM library | Source-agnostic cluster data model (`VehicleDataSource`). |
| `app` | Android app | Currently the latency spike app. See [docs/latency-spike.md](docs/latency-spike.md). |

## Docs

- [BLE data inventory](docs/ble-data-inventory.md): everything readable over BLE.
- [Latency spike](docs/latency-spike.md): how latency is measured and judged.

## Build

```sh
./gradlew :tesla-protocol:test :app:assembleDebug
```

Requires JDK 17 or later and the Android SDK (compileSdk 34). Devices must run Android 12
(API 31) or later.

## License

AGPL-3.0; see [LICENSE](LICENSE). The protobuf definitions in
`tesla-protocol/src/main/proto` are © Tesla, Inc., licensed under Apache-2.0.
