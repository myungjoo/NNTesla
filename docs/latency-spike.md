# Latency spike: vehicle BLE (vehicle-command)

## Question

Can direct phone-to-car BLE using Tesla's vehicle-command protocol meet NNTesla's
display-latency targets?

| Path | Data | Target |
|---|---|---|
| Fast | Speed (`DriveState`) | P95 display latency <= 500 ms |
| Slow | Everything else (battery, temperatures, tires, ...) | P95 display latency <= 3000 ms |

If the fast-path target cannot be met, the BLE-only v1 plan has to be revisited.

## What the spike app does

The spike app (`:app`, package `io.github.myungjoo.nntesla.spike`) does the following:

1. Creates a P-256 key in Android Keystore. The private key never leaves the Keystore, and
   ECDH runs inside it.
2. Scans for the vehicle's BLE beacon. The beacon's local name is derived from the VIN.
3. Connects with `CONNECTION_PRIORITY_HIGH` and the largest MTU the car accepts.
4. Optionally sends an add-key request. The default role is `VEHICLE_MONITOR`, the least
   privilege a cluster needs. The owner confirms the request by tapping a keycard.
5. Performs the infotainment handshake once and keeps that session for the whole run.
6. Runs the polling scheduler (`PollingScheduler`):
   - **Fast path**: sends `GetVehicleData{drive}`. As soon as a request slot frees, the next
     request is sent (closed loop, no fixed interval).
   - **Slow path**: requests charge, climate and tire pressure, each every 2.5 s. Their due
     times are staggered so that at most one slow request interrupts the fast loop at a time.
     Navigation fields arrive inside `DriveState`, so they refresh on the fast path at no
     extra cost.
7. Publishes each decoded sample straight to the UI `StateFlow` from the BLE thread. There is
   no queue and no batching.
8. Records the timing of every request and shows live percentiles. The full log can be
   exported as CSV.

### Experiment matrix

The experiment is chosen in the UI:

| Run | In flight | Slow path | Batch slow | What it shows |
|---|---|---|---|---|
| A | 1 | off | - | Floor: drive-state round trip and vehicle refresh behavior |
| B | 1 | on | off | Cost of interleaving slow requests into the fast loop |
| C | 2 | on | off | Whether the vehicle processes overlapping requests (pipelining) |
| D | 1 | on | on | Whether one multi-category `GetVehicleData` is cheaper than several single-category requests |
| E | 2 | off | - | Whether two pipelined drive requests raise the sample rate |

Run each experiment for at least 5 minutes while driving with varying speed. Also run A once
while parked as a baseline.

## Metrics

All times use `System.nanoTime()`, which is monotonic.

- **rtt**: time from the start of request encryption until the response is reassembled.
- **write**: time from the start of request encryption until the vehicle acknowledges the last
  GATT write chunk.
- **latency bound**: a conservative upper bound on change-to-display latency, computed per
  displayed sample *i*:

  ```
  L_i = display_i - send(previous displayed sample of the same category)
  ```

  A value change that happens right after the previous sample was taken is first visible in
  sample *i*. That earlier sample was taken no earlier than its send time, so `L_i` bounds
  the true latency from above.

  How the display time is measured:
  - **Fast path**: the time the speed text was drawn, plus one frame for composition to reach
    the screen.
  - **Slow path**: the decode time plus two frames.

- **vehicle ts unchanged / change interval**: computed from `DriveState.timestamp`. These show
  whether the vehicle refreshes drive state on every request or serves a cached value. If the
  timestamp only advances every N ms, the fast path cannot do better than N ms, no matter how
  fast the BLE link is.

**Verdict:** `PASS` if the P95 of the latency bound is within the target. The latency bound
overestimates the true latency, so a `PASS` is reliable. A marginal `FAIL` needs a look at the
CSV.

## Running

1. Build with `./gradlew :app:assembleDebug` and install
   `app/build/outputs/apk/debug/app-debug.apk`. Android 12 (API 31) or later is required.
2. 근처 기기 권한과 위치 권한(정확한 위치)을 허용하고, 기기의 위치 서비스를 켠다. Tesla 차량은
   iBeacon 형식으로 광고하는데, Android는 위치 권한 없이 스캔하는 앱(`neverForLocation`)에는
   iBeacon 광고를 전달하지 않는다.
3. Enter the VIN and tap **Connect**. The car must be awake: during a drive, or with the
   touchscreen on.
4. If the status shows `NEEDS_PAIRING`:
   - Tap **Send pairing request**.
   - Tap an owner keycard on the console and confirm on the touchscreen.
   - The app re-tries the handshake every 3 s for 2 minutes and reaches `READY` on its own
     once the key is accepted. **Retry handshake** forces an immediate attempt.
   After the first successful connection, the app auto-connects on launch using the saved VIN.
   A lost link (car out of range, asleep, BLE error) is retried with backoff (1, 2, 5, 10,
   then every 30 s) until **Disconnect** is tapped.
5. Pick an experiment and tap **Start run**. When finished, tap **Stop**, then
   **Export CSV**.
6. Pull the CSV with
   `adb pull /sdcard/Android/data/io.github.myungjoo.nntesla/files/`.

### 차량 연결 시 자동 실행

**Set up auto-launch**를 누르고 시스템 창에서 차량의 블루투스 기기(예: `Tesla Model Y`)를 고르면,
이후 그 기기가 폰에 연결될 때(차에 타서 오디오가 붙을 때) 앱이 자동으로 뜬다. CompanionDeviceManager로
연결한 앱은 Android 12+의 백그라운드 액티비티 실행 제한에서 예외라 가능하다. 설정 앱에서 강제
종료하면 다음에 직접 열 때까지 동작하지 않으며, 배터리 설정에서 앱을 "제한 없음"으로 두는 것이 안전하다.

## Known limitations

- **Speed unit:** the unit of `DriveState.speed` / `speed_float` is not documented upstream.
  MPH is assumed and converted to km/h for display and for the CSV `speed_kmh` column. All
  displayed values use SI-based units (km/h, km, °C, kPa, kW). Confirm the speed unit during
  a drive by comparing with the car's own display: if the app reads about 1.6 times too high,
  the vehicle reports km/h.
- **Sleeping car:** waking infotainment through VCSEC is not implemented. If the car is
  asleep, the handshake fails.
- **No session reuse:** session state is not cached across app restarts, so every connect
  performs a handshake.
- **Pairing response:** the vehicle's reply to the pairing request is not parsed; pairing
  completion is detected by polling the handshake instead.
- **Link drops during a run:** polling pauses and resumes after reconnecting. Each drop is
  counted as `link drops` and is excluded from the latency bound (the first sample after a
  reconnect starts a new baseline), so judge outages from that counter.
