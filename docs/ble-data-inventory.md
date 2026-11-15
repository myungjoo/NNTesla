# BLE Data Inventory: What NNTesla Can Read from a Tesla over BLE

This document lists every piece of vehicle information that a client can **read** over the
car's own Bluetooth Low Energy (BLE) link using Tesla's vehicle-command protocol. It covers
only direct phone-to-car BLE: no Fleet API, no cloud, no CAN adapter.

It is meant to decide which requests NNTesla's two polling paths use:

- **Fast path**: speed, with a 0.5 s display-latency target.
- **Slow path**: everything else, with a 3 s target.

## Source and conventions

- **Source of truth:** `github.com/teslamotors/vehicle-command` at commit `0a582da`
  (2026-11-03). The main files are:
  - `pkg/protocol/protobuf/{vehicle,car_server,vcsec,common,universal_message,signatures,keys,managed_charging}.proto`
  - `pkg/vehicle/{state,vcsec,infotainment,security,vehicle}.go`
  - `internal/dispatcher/{dispatcher,session}.go`
  - `pkg/connector/ble/ble.go`
  - `cmd/tesla-control/commands.go`
  - `pkg/cli/config.go`
  - `pkg/protocol/protocol.md`
- **Units:** A unit is given only when the proto states it in a comment or in the field name
  (for example `_celsius`, `_ms`, `_mph`, or "in bar"). Any other unit is marked
  **(inferred)**, meaning it is not stated in the source.
- **Optional fields:** Most scalar fields are wrapped as `oneof optional_x { T x = N; }`.
  These fields have presence semantics: if the car does not send a field, it is absent, not
  zero. The tables list such fields by their inner name and type.
- **IC column** (relevance to an instrument cluster):
  - **F**: fast-path candidate.
  - **S**: useful on the cluster, slow path.
  - **-**: not normally shown on a cluster.

---

## 1. The two BLE domains

Every request is a `UniversalMessage.RoutableMessage`. Its `to_destination.domain` field
names the domain that should handle it:

```
enum Domain { DOMAIN_BROADCAST = 0; DOMAIN_VEHICLE_SECURITY = 2; DOMAIN_INFOTAINMENT = 3; }
```

| | **VCSEC** (Vehicle Security Controller, `DOMAIN_VEHICLE_SECURITY`) | **Infotainment** (CarServer, `DOMAIN_INFOTAINMENT`) |
|---|---|---|
| Read request | `VCSEC.UnsignedMessage { InformationRequest { informationRequestType } }` | `CarServer.Action { vehicleAction { getVehicleData { get<Category>State {} ... } } }` |
| Request types | `INFORMATION_REQUEST_TYPE_GET_STATUS` (0), `..._GET_WHITELIST_INFO` (5), `..._GET_WHITELIST_ENTRY_INFO` (6) | `GetVehicleData` with 12 category flags (§2). Also `Ping` and `GetNearbyChargingSites` (§4). |
| Response | `VCSEC.FromVCSECMessage` { `vehicleStatus` \| `whitelistInfo` \| `whitelistEntryInfo` \| `commandStatus` \| `nominalError` } | `CarServer.Response` { `actionStatus`, oneof `vehicleData` \| `getSessionInfoResponse` \| `getNearbyChargingSites` \| `ping` } |
| Works while infotainment is asleep? | **Yes.** `protocol.md`: "VCSEC can be reached over BLE even when infotainment is asleep." `state.go` (`BodyControllerState`): "This method works over BLE even when infotainment is asleep." | **No.** Infotainment must be running (see "Infotainment must be awake" below). |
| Authentication or session | **None for reads.** `getVCSECInfo()` sends with `connector.AuthMethodNone`. In tesla-control, `body-controller-state` and `list-keys` have `requiresAuth: false`, and `body-controller-state` is pinned to `domain: protocol.DomainVCSEC`. No handshake and no private key are needed; only a BLE connection is. | **Required.** `getCarServerResponse()` sends with `v.authMethod`, which is `AuthMethodGCM` over BLE (`ble.Connection.PreferredAuthMethod`). tesla-control `state` has `requiresAuth: true`. The client's public key must be on the car's whitelist, and an infotainment session handshake (`SessionInfoRequest`) must be completed first (§5.3). |
| Ordering and concurrency | `protocol.md`: "clients should avoid making simultaneous requests to that domain" (VCSEC memory constraints). "VCSEC requires that messages arrive in counter order." | "Infotainment tracks a sliding window of valid counters to allow for out-of-order message arrival." |

### Infotainment must be awake

What the source shows:

- `protocol.md` says that VCSEC, and only VCSEC, is reachable while infotainment is asleep.
- `ErrBusy` is described as "vehicle busy or finishing wake-up". `error.go` adds: "it's not
  unusual for the car to return Busy errors if it's in the process of waking from sleep and
  the services responsible for executing the command are not yet running."
- `MESSAGEFAULT_ERROR_INTERNAL` is described as "Most commonly encountered when the vehicle
  has not finished booting."
- `pkg/cli/config.go` says that limiting domains "can reduce connection latency and avoid
  waking up the infotainment system unnecessarily". `StartSession` says "a client that only
  interacts with VCSEC can avoid waking infotainment."
- To wake the car over BLE, `Vehicle.Wakeup` sends `RKEAction_E.RKE_ACTION_WAKE_VEHICLE` to
  VCSEC. This is an authenticated VCSEC command, so it needs a key and a VCSEC session.
  tesla-control's BLE `wake` command handshakes with VCSEC only.

**Inferred:** Opening an infotainment session, or sending any infotainment request, may
itself wake infotainment or keep it awake. The source only implies this through the "avoid
waking" comments. Do not rely on either behaviour without testing on a real car.

**Practical rule:** Every `GetVehicleData` category needs the car awake. Only the VCSEC
`VehicleStatus` and whitelist reads work while infotainment sleeps.

---

## 2. Infotainment `GetVehicleData`

### 2.1 Request flags

`car_server.proto` defines the request like this:

```
message GetVehicleData {
  GetChargeState getChargeState = 2;               GetClimateState getClimateState = 3;
  GetDriveState getDriveState = 4;                 reserved 5, 6;
  GetLocationState getLocationState = 7;           GetClosuresState getClosuresState = 8;
  GetChargeScheduleState getChargeScheduleState = 10;
  GetPreconditioningScheduleState getPreconditioningScheduleState = 11;  reserved 12, 13;
  GetTirePressureState getTirePressureState = 14;  GetMediaState getMediaState = 15;
  GetMediaDetailState getMediaDetailState = 16;    GetSoftwareUpdateState getSoftwareUpdateState = 17;
  GetParentalControlsState getParentalControlsState = 19;
}
```

Every `Get*State` sub-message is **empty**; setting it is the flag. The response is
`Response.vehicleData` (`CarServer.VehicleData`).

| Request flag | tesla-control `state` name | `vehicle.StateCategory` | Response field in `VehicleData` | Message |
|---|---|---|---|---|
| `getChargeState` (2) | `charge` | `StateCategoryCharge` | `charge_state` (3) | `ChargeState` |
| `getClimateState` (3) | `climate` | `StateCategoryClimate` | `climate_state` (4) | `ClimateState` |
| `getDriveState` (4) | `drive` | `StateCategoryDrive` | `drive_state` (5) | `DriveState` |
| `getLocationState` (7) | `location` | `StateCategoryLocation` | `location_state` (8) | `LocationState` |
| `getClosuresState` (8) | `closures` | `StateCategoryClosures` | `closures_state` (9) | `ClosuresState` |
| `getChargeScheduleState` (10) | `charge-schedule` | `StateCategoryChargeSchedule` | `charge_schedule_state` (15) | `ChargeScheduleState` |
| `getPreconditioningScheduleState` (11) | `precondition-schedule` | `StateCategoryPreconditioningSchedule` | `preconditioning_schedule_state` (16) | `PreconditioningScheduleState` |
| `getTirePressureState` (14) | `tire-pressure` | `StateCategoryTirePressure` | `tire_pressure_state` (19) | `TirePressureState` |
| `getMediaState` (15) | `media` | `StateCategoryMedia` | `media_state` (20) | `MediaState` |
| `getMediaDetailState` (16) | `media-detail` | `StateCategoryMediaDetail` | `media_detail_state` (21) | `MediaDetailState` |
| `getSoftwareUpdateState` (17) | `software-update` | `StateCategorySoftwareUpdate` | `software_update_state` (23) | `SoftwareUpdateState` |
| `getParentalControlsState` (19) | `parental-controls` | `StateCategoryParentalControls` | `parental_controls_state` (24) | `ParentalControlsState` |

All 12 categories that the public proto defines are exposed through tesla-control `state`.
Request fields 5, 6, 12 and 13 and several `VehicleData` field numbers (1, 2, 6, 7, 10–14,
17, 18, 22) are absent or reserved in the public proto. Whatever they carried on the car is
not documented here.

### 2.2 Where speed lives

> **`DriveState` (request flag `getDriveState`, tesla-control `state drive`)**
>
> | Field | Proto type | Units / precision |
> |---|---|---|
> | `speed` (102) | `uint32` (optional) | Integer, so its resolution is 1 unit. The proto does not state the unit; **mph (inferred)**, because the protocol uses `_mph` and "hundredths of a mile" elsewhere and has no metric speed field. It is unsigned, so it carries no direction. |
> | `speed_float` (106) | `float` (optional) | Same quantity at fractional precision (IEEE-754 single). The unit is not stated; **mph (inferred)**. Prefer this field when present. |
> | `shift_state` (1) | `ShiftState` (oneof of `Void` markers) | `Invalid`, `P`, `R`, `N`, `D`, `SNA` |
> | `power` (103) | `int32` (optional) | Signed (regen is presumably negative, **inferred**). The unit is not stated; **kW (inferred)**. |
> | `timestamp` (4) | `google.protobuf.Timestamp` | The car-side time of the sample. Use it to measure the age of the data. |
>
> The source has no proto comment and no code that converts or documents these units.
> Validate them against the car's own display before shipping.

### 2.3 `DriveState`

| # | Field | Type | Unit / meaning | IC |
|---|---|---|---|---|
| 1 | `shift_state` | `ShiftState` | Gear: `Invalid` / `P` / `R` / `N` / `D` / `SNA` | **F** |
| 102 | `speed` | uint32 | Vehicle speed, integer; mph (inferred) | **F** |
| 103 | `power` | int32 | Drive power; kW (inferred), signed | **F** |
| 4 | `timestamp` | Timestamp | Sample time | **F** |
| 105 | `odometer_in_hundredths_of_a_mile` | int32 | Odometer, 0.01 mi (stated in the name) | S |
| 106 | `speed_float` | float | Vehicle speed, fractional; mph (inferred) | **F** |
| 7 | `active_route_destination` | string | Name of the navigation destination | S |
| 8 | `active_route_minutes_to_arrival` | float | Navigation ETA, minutes | S |
| 9 | `active_route_miles_to_arrival` | float | Remaining route distance, miles | S |
| 10 | `active_route_traffic_minutes_delay` | float | Traffic delay, minutes | S |
| 11 | `active_route_energy_at_arrival` | float | Predicted energy at arrival; unit not stated (% SoC or kWh?) | S |
| 14 | `last_route_update` | uint32 | Time of the last route update; epoch seconds (inferred) | - |
| 15 | `last_traffic_update` | Timestamp | Time of the last traffic update | - |
| 12 | `active_route_coordinates` | `LatLong` {float latitude, float longitude} | Destination coordinates | - |

### 2.4 `ChargeState`

Enums used in this message:

- `CableType`: SNA, IEC, SAE, GB_AC, GB_DC
- `ChargerType`: SNA, Supercharger, Chademo, Gb, ACSingleWireCAN, Combo, MCSingleWireCAN,
  Other, Tesla
- `ChargingState`: Unknown, Disconnected, NoPower, Starting, Charging, Complete, Stopped,
  Calibrating
- `ChargerBrand`: Tesla, SNA
- `ScheduledChargingMode`: Off, StartAt, DepartBy
- `ChargePortColor_E`: Off, Red, Green, Blue, White, FlashingGreen, FlashingAmber, Amber,
  Rave, Debug, FlashingBlue
- `ChargeLimitReason`: Unknown, None, Evse, BattTempLow, HighSoc, Cabin
- `OutletState` and `PowerFeedState`: Off, CabinAndBed, Cabin
- `PowershareStatus`: Inactive, Init, Active, Stopped, Handshaking, ActiveReconnectingSoon
- `PowershareType`: None, Load, Home
- `PowershareStopReason`: None, SOCTooLow, Retry, Fault, User, Reconnecting, Authentication
- `ChargePortLatchState`: SNA, Disengaged, Engaged, Blocking

Fields 8, 42 and 43 are reserved.

| # | Field | Type | Unit / meaning | IC |
|---|---|---|---|---|
| 1 | `charging_state` | `ChargingState` | Charging status | S |
| 2 | `fast_charger_type` | `ChargerType` | DC/AC charger type | - |
| 3 | `fast_charger_brand` | `ChargerBrand` | Charger brand | - |
| 104 | `charge_limit_soc` | int32 | Charge limit, % (inferred) | - |
| 105 | `charge_limit_soc_std` | int32 | "Standard" charge limit, % (inferred) | - |
| 106 | `charge_limit_soc_min` | int32 | Lowest settable limit, % (inferred) | - |
| 107 | `charge_limit_soc_max` | int32 | Highest settable limit, % (inferred) | - |
| 109 | `max_range_charge_counter` | int32 | Count of max-range charges | - |
| 110 | `fast_charger_present` | bool | DC fast charger connected | - |
| 111 | `battery_range` | float | Rated range; proto comment "2 decimals"; miles (inferred) | **S** |
| 112 | `est_battery_range` | float | Estimated range; "2 decimals"; miles (inferred) | **S** |
| 113 | `ideal_battery_range` | float | Ideal range; "2 decimals"; miles (inferred) | S |
| 114 | `battery_level` | int32 | State of charge, % (inferred) | **S** |
| 115 | `usable_battery_level` | int32 | Usable state of charge, % (inferred) | **S** |
| 116 | `charge_energy_added` | float | Energy added in the session; "1 decimal"; kWh (inferred) | - |
| 117 | `charge_miles_added_rated` | float | Rated miles added; "1 decimal" | - |
| 118 | `charge_miles_added_ideal` | float | Ideal miles added; "1 decimal" | - |
| 119 | `charger_voltage` | int32 | Charger voltage; V (inferred) | - |
| 120 | `charger_pilot_current` | int32 | Pilot current; A (inferred) | - |
| 121 | `charger_actual_current` | int32 | Actual current; A (inferred) | - |
| 122 | `charger_power` | int32 | Charge power; kW (inferred) | - |
| 123 | `minutes_to_full_charge` | int32 | Minutes | - |
| 142 | `minutes_to_charge_limit` | int32 | Minutes | - |
| 125 | `trip_charging` | bool | Trip charging active | - |
| 126 | `charge_rate_mph` | int32 | Charge rate, mph of range | - |
| 127 | `charge_port_door_open` | bool | Charge port door open | S |
| 28 | `conn_charge_cable` | `CableType` | Connected cable type | - |
| 129 | `scheduled_charging_start_time` | uint64 | Proto comment "seconds / datetime" (epoch seconds, inferred) | - |
| 130 | `scheduled_charging_pending` | bool | Scheduled charging pending | - |
| 31 | `scheduled_departure_time` | Timestamp | Scheduled departure | - |
| 132 | `user_charge_enable_request` | bool | User charge-enable request | - |
| 133 | `charge_enable_request` | bool | Charge-enable request | - |
| 134 | `charger_phases` | int32 | Number of AC phases | - |
| 35 | `charge_port_latch` | `ChargePortLatchState` | Latch state | - |
| 136 | `charge_port_cold_weather_mode` | bool | Cold-weather mode | - |
| 137 | `charge_current_request` | int32 | Requested current; A (inferred) | - |
| 138 | `charge_current_request_max` | int32 | Maximum requestable current; A (inferred) | - |
| 139 | `managed_charging_active` | bool | Managed charging active | - |
| 140 | `managed_charging_user_canceled` | bool | User cancelled managed charging | - |
| 141 | `managed_charging_start_time` | uint64 | Start time; epoch seconds (inferred) | - |
| 44 | `timestamp` | Timestamp | Sample time | S |
| 45 | `preconditioning_times` | `PreconditioningTimes` | `all_week` / `weekdays` | - |
| 46 | `off_peak_charging_times` | `OffPeakChargingTimes` | `all_week` / `weekdays` | - |
| 147 | `off_peak_hours_end_time` | uint32 | Off-peak end; minutes after midnight (inferred) | - |
| 148 | `scheduled_charging_mode` | `ScheduledChargingMode` | Off / StartAt / DepartBy | - |
| 149 | `charging_amps` | int32 | Charging amps setting | - |
| 150 | `scheduled_charging_start_time_minutes` | uint32 | Minutes (stated in the name) | - |
| 151 | `scheduled_departure_time_minutes` | uint32 | Minutes (stated in the name) | - |
| 152 | `preconditioning_enabled` | bool | Departure preconditioning on | - |
| 153 | `scheduled_charging_start_time_app` | sint32 | App-facing start time | - |
| 154 | `supercharger_session_trip_planner` | bool | Supercharger session from the trip planner | - |
| 155 | `charge_port_color` | `ChargePortColor_E` | Charge-port LED colour | - |
| 156 | `charge_rate_mph_float` | float | Charge rate, mph of range (float) | - |
| 157 | `charge_limit_reason` | `ChargeLimitReason` | Why charging is limited | - |
| 158 | `managed_charging_state` | `ManagedChargingState` | See below | - |
| 159 | `charge_cable_unlatched` | bool | Cable unlatched | - |
| 160 | `outlet_state` | `OutletState` | AC outlet state | - |
| 161 | `power_feed_state` | `PowerFeedState` | Power-feed state | - |
| 162 | `outlet_soc_limit` | int32 | Outlet SoC cutoff, % (inferred) | - |
| 163 | `power_feed_soc_limit` | int32 | Power-feed SoC cutoff, % (inferred) | - |
| 164 | `outlet_time_remaining` | int64 | Outlet time remaining; unit not stated | - |
| 165 | `power_feed_time_remaining` | int64 | Power-feed time remaining; unit not stated | - |
| 166 | `powershare_feature_allowed` | bool | Powershare allowed | - |
| 167 | `powershare_feature_enabled` | bool | Powershare enabled | - |
| 168 | `powershare_request` | bool | Powershare requested | - |
| 169 | `powershare_type` | `PowershareType` | None / Load / Home | - |
| 170 | `powershare_status` | `PowershareStatus` | See the enum list above | - |
| 171 | `powershare_stop_reason` | `PowershareStopReason` | See the enum list above | - |
| 172 | `powershare_instantaneous_load_kw` | float | kW (stated in the name) | - |
| 173 | `powershare_vehicle_energy_left_hr` | int32 | Hours (stated in the name) | - |
| 174 | `powershare_soc_limit` | int32 | Powershare SoC limit, % (inferred) | - |
| 175 | `one_time_soc_limit` | int32 | One-time SoC limit, % (inferred) | - |
| 176 | `home_location` | `LatLong` | Home location | - |
| 177 | `work_location` | `LatLong` | Work location | - |
| 178 | `outlet_max_timer_minutes` | int32 | Minutes | - |

`ManagedChargingState` contains:

- `charge_on_solar_state` (`ChargeOnSolarState`): a oneof of `not_allowed`,
  `no_charge_recommended` (which carries a `ManagedCharging.ChargeOnSolarNoChargeReason`:
  POWERWALL_CHARGE_PRIORITY, INSUFFICIENT_SOLAR, GRID_EXPORT_PRIORITY or
  ALTERNATE_VEHICLE_CHARGE_PRIORITY), `charging_on_excess_solar`, `charging_on_anything`,
  `user_disabled`, `waiting_for_server`, `error` and `user_stopped`.
- `charge_on_solar_gateway_din` (string).
- `tesla_electric_asset_id` (string).
- `minutes_to_lower_limit` (int32).

### 2.5 `ClimateState`

Enums used in this message:

- `ClimateKeeperMode`: Unknown, Off, On, Dog, Party
- `DefrostMode`: Off, Normal, Max
- `HvacAutoRequest`: On, Override
- `CabinOverheatProtection_E`: Off, On, FanOnly
- `SeatHeaterLevel_E` and `SeatCoolingLevel_E`: Off, Low, Med, High
- `CopActivationTemp`: Unspecified, Low, Medium, High
- `COPNotRunningReason`: NoReason, UserInteraction, EnergyConsumptionReached, Timeout,
  LowSolarLoad, Fault, CabinBelowThreshold
- `StwHeatLevel`: Unknown, Off, Low, High

Field 145 is reserved.

| # | Field | Type | Unit / meaning | IC |
|---|---|---|---|---|
| 101 | `inside_temp_celsius` | float | Cabin temperature, °C | S |
| 102 | `outside_temp_celsius` | float | Outside temperature, °C | **S** |
| 103 | `driver_temp_setting` | float | Driver setpoint; °C (inferred) | S |
| 104 | `passenger_temp_setting` | float | Passenger setpoint; °C (inferred) | S |
| 105 | `left_temp_direction` | int32 | Left vent temperature direction (semantics not stated) | - |
| 106 | `right_temp_direction` | int32 | Right vent temperature direction (semantics not stated) | - |
| 107 | `is_front_defroster_on` | bool | Front defroster on | S |
| 108 | `is_rear_defroster_on` | bool | Rear defroster on | S |
| 109 | `fan_status` | int32 | Fan speed level | S |
| 110 | `is_climate_on` | bool | HVAC on | S |
| 111 | `min_avail_temp_celsius` | float | Minimum settable temperature, °C | - |
| 112 | `max_avail_temp_celsius` | float | Maximum settable temperature, °C | - |
| 113–121 | `seat_heater_left`, `_right`, `_rear_left`, `_rear_right`, `_rear_center`, `_rear_right_back`, `_rear_left_back`, `_third_row_right`, `_third_row_left` | int32 | `SeatHeaterLevel_E` values. Not set when the seat heater is absent (per the proto comments). | S |
| 122 | `battery_heater` | bool | Battery heater on | - |
| 123 | `battery_heater_no_power` | bool | Battery heater lacks power | - |
| 125 | `steering_wheel_heater` | bool | Steering wheel heater; not set if not installed | S |
| 126 | `wiper_blade_heater` | bool | Wiper heater | - |
| 127 | `side_mirror_heaters` | bool | Mirror heaters | - |
| 128 | `is_preconditioning` | bool | Preconditioning active | - |
| 129 | `remote_heater_control_enabled` | bool | Remote heater control | - |
| 30 | `climate_keeper_mode` | `ClimateKeeperMode` | Keep / Dog / Party mode | - |
| 33 | `timestamp` | Timestamp | Sample time | S |
| 134 | `bioweapon_mode_on` | bool | Bioweapon defense mode; only on cars with a HEPA filter | - |
| 35 | `defrost_mode` | `DefrostMode` | Off / Normal / Max | S |
| 136 | `is_auto_conditioning_on` | bool | Auto conditioning | S |
| 137 | `auto_seat_climate_left` | bool | Auto seat climate, left | - |
| 138 | `auto_seat_climate_right` | bool | Auto seat climate, right | - |
| 139 | `seat_fan_front_left` | int32 | `SeatCoolingLevel_E` | - |
| 140 | `seat_fan_front_right` | int32 | `SeatCoolingLevel_E` | - |
| 141 | `allow_cabin_overheat_protection` | bool | COP allowed | - |
| 142 | `supports_fan_only_cabin_overheat_protection` | bool | Fan-only COP supported | - |
| 143 | `cabin_overheat_protection` | `CabinOverheatProtection_E` | COP mode | - |
| 144 | `cabin_overheat_protection_actively_cooling` | bool | COP cooling now | - |
| 146 | `cop_activation_temperature` | `CopActivationTemp` | COP threshold | - |
| 147 | `auto_steering_wheel_heat` | bool | Auto steering wheel heat | - |
| 148 | `steering_wheel_heat_level` | `StwHeatLevel` | Steering wheel heat level | - |
| 150 | `hvac_auto_request` | `HvacAutoRequest` | On / Override | - |
| 151 | `cop_not_running_reason` | `COPNotRunningReason` | Why COP is not running | - |

### 2.6 `ClosuresState` (infotainment's view)

Enums used in this message:

- `SunRoofState`: Unknown, Calibrating, Closed, Open, Moving, Vent
- `DisplayState`: Off, Dim, Accessory, On, Driving, Charging, Lock, Sentry, Dog,
  Entertainment
- `SentryModeState`: Off, Idle, Armed, Aware, Panic, Quiet
- `VCSEC.ClosureState_E`: see §3

| # | Field | Type | Unit / meaning | IC |
|---|---|---|---|---|
| 101–104 | `door_open_driver_front`, `door_open_driver_rear`, `door_open_passenger_front`, `door_open_passenger_rear` | bool | Door open | **S** |
| 105 | `door_open_trunk_front` | bool | Frunk open | **S** |
| 106 | `door_open_trunk_rear` | bool | Trunk open | **S** |
| 107–110 | `window_open_driver_front`, `window_open_passenger_front`, `window_open_driver_rear`, `window_open_passenger_rear` | bool | Window open | S |
| 11 | `sun_roof_state` | `SunRoofState` | Only set when a sunroof is installed | - |
| 112 | `sun_roof_percent_open` | int32 | % (stated in the name); only with a sunroof | - |
| 113 | `locked` | bool | Vehicle locked | S |
| 114 | `is_user_present` | bool | User present | - |
| 15 | `center_display_state` | `DisplayState` | Centre screen state; `Driving` is one value | S |
| 116 | `remote_start` | bool | "`true` when remote start is active" | - |
| 117 | `valet_mode` | bool | Valet mode | - |
| 118 | `valet_pin_needed` | bool | Valet PIN needed | - |
| 19 | `sentry_mode_state` | `SentryModeState` | Only when sentry is supported | - |
| 120 | `sentry_mode_available` | bool | Only when sentry is supported | - |
| 22 | `speed_limit_mode` | `SpeedLimitMode` | See below; only when supported | S |
| 23 | `tonneau_state` | `VCSEC.ClosureState_E` | Cybertruck tonneau | - |
| 24 | `tonneau_percent_open` | uint32 | % | - |
| 25 | `tonneau_in_motion` | bool | Tonneau moving | - |
| 2000 | `timestamp` | Timestamp | Sample time | S |

`SpeedLimitMode` contains:

- `active` (bool)
- `pin_code_set` (bool)
- `max_limit_mph`, `min_limit_mph` and `current_limit_mph` (all float, mph)

### 2.7 `LocationState`

The proto comment says:

- Use the `native_*` fields, which are WGS or GCJ depending on the car's region.
- If they are missing, fall back to plain `latitude`/`longitude` (WGS clients) or
  `corrected_*` (GCJ clients).
- The `geo_*` fields are raw WGS GPS. Prefer them when `estimated_to_raw_distance` is large,
  for example after the car has been towed.

Field 12 is reserved.

| # | Field | Type | Unit / meaning | IC |
|---|---|---|---|---|
| 101 | `latitude` | float | Degrees | S |
| 102 | `longitude` | float | Degrees | S |
| 103 | `heading` | uint32 | Heading; degrees (inferred) | S |
| 104 | `gps_as_of` | uint64 | Fix time; epoch (unit not stated) | - |
| 105 | `native_location_supported` | bool | Native coordinates available | - |
| 106 | `native_latitude` | float | Native latitude | S |
| 107 | `native_longitude` | float | Native longitude | S |
| 8 | `native_type` | `GPSCoordinateType` | GCJ / WGS | - |
| 109 | `corrected_latitude` | float | China cars only | - |
| 110 | `corrected_longitude` | float | China cars only | - |
| 11 | `timestamp` | Timestamp | Sample time | - |
| 112 | `homelink_nearby` | bool | Only when HomeLink is supported | - |
| 113 | `location_name` | string | "non-precise location name" | - |
| 114 | `geo_latitude` | float | Raw GPS | - |
| 115 | `geo_longitude` | float | Raw GPS | - |
| 116 | `geo_heading` | float | Raw heading | - |
| 117 | `geo_elevation` | float | Elevation; m (inferred) | - |
| 118 | `geo_accuracy` | float | Accuracy; m (inferred) | - |
| 119 | `estimated_gps_valid` | bool | Estimated fix valid | - |
| 120 | `estimated_to_raw_distance` | float | Metres (stated in a comment) | - |

### 2.8 `TirePressureState`

The proto comment says "tpms pressure values in bar" and "rcp values in bar".

| # | Field | Type | Unit / meaning | IC |
|---|---|---|---|---|
| 1 | `timestamp` | Timestamp | Sample time | S |
| 2–5 | `tpms_pressure_fl`, `_fr`, `_rl`, `_rr` | float | Tyre pressure, bar | **S** |
| 6–9 | `tpms_last_seen_pressure_time_fl`, `_fr`, `_rl`, `_rr` | Timestamp | When each sensor last reported | S |
| 10–13 | `tpms_hard_warning_fl`, `_fr`, `_rl`, `_rr` | bool | Hard warning | **S** |
| 14–17 | `tpms_soft_warning_fl`, `_fr`, `_rl`, `_rr` | bool | Soft warning | **S** |
| 18 | `tpms_rcp_front_value` | float | Recommended cold pressure, front, bar | S |
| 19 | `tpms_rcp_rear_value` | float | Recommended cold pressure, rear, bar | S |

### 2.9 `MediaState`

| # | Field | Type | Unit / meaning | IC |
|---|---|---|---|---|
| 1 | `timestamp` | Timestamp | Sample time | S |
| 2 | `remote_control_enabled` | bool | Media remote control allowed | - |
| 3 | `now_playing_artist` | string | Artist | S |
| 4 | `now_playing_title` | string | Title | S |
| 5 | `audio_volume` | float | Volume (scale not stated) | S |
| 6 | `audio_volume_increment` | float | Volume step | - |
| 7 | `audio_volume_max` | float | Maximum volume | - |
| 8 | `now_playing_source` | `MediaSourceType` | AM, FM, XM, Slacker, LocalFiles, iPod, Bluetooth, AuxIn, DAB, Rdio, Spotify, USRadio, EURadio, MediaFile, TuneIn, Stingray, SiriusXM, Tidal, QQMusic, QQMusic2, Ximalaya, OnlineRadio, OnlineRadio2, NetEaseMusic, Browser, Theater, Game, Tutorial, Toybox, RecentsFavorites, HomeApps, Search, None | S |
| 9 | `media_playback_status` | `MediaPlaybackStatus` | Stopped / Playing / Paused | S |

### 2.10 `MediaDetailState`

| # | Field | Type | Unit / meaning | IC |
|---|---|---|---|---|
| 1 | `timestamp` | Timestamp | Sample time | - |
| 2 | `now_playing_duration` | int32 | Track length; unit not stated | S |
| 3 | `now_playing_elapsed` | int32 | Elapsed time; unit not stated | S |
| 4 | `now_playing_source_string` | string | Source display name | S |
| 5 | `now_playing_album` | string | Album | S |
| 6 | `now_playing_station` | string | Station | S |
| 7 | `a2dp_source_name` | string | Bluetooth A2DP source device name | - |

### 2.11 `SoftwareUpdateState`

| # | Field | Type | Unit / meaning | IC |
|---|---|---|---|---|
| 1 | `status` | `SoftwareUpdateStatus` | Unknown / Installing / Scheduled / Available / DownloadingWifiWait / Downloading | - |
| 102 | `scheduled_time_ms` | uint64 | ms (stated in the name); epoch (inferred) | - |
| 103 | `warning_time_remaining_ms` | uint64 | ms | - |
| 104 | `expected_duration_sec` | uint32 | s | - |
| 105 | `download_perc` | uint32 | % | - |
| 106 | `install_perc` | uint32 | % | - |
| 107 | `version` | string | Version of the pending update. Whether this is the installed or the pending version is not stated. | - |
| 108 | `timestamp` | Timestamp | Sample time | - |

### 2.12 `ChargeScheduleState` and `PreconditioningScheduleState`

**`ChargeScheduleState`:**

| # | Field | Type | Meaning |
|---|---|---|---|
| 1 | `charge_schedules` | repeated `ChargeSchedule` | List of schedules |
| 2 | `charge_schedule_window` | `ChargeSchedule` | Current or next window |
| 3 | `charge_buffer` | int32 | Buffer; unit not stated |
| 4 | `max_num_charge_schedules` | uint32 | Capacity |
| 5 | `next_schedule` | bool | |
| 6 | `show_schedule_complete_state` | bool | |
| 2000 | `timestamp` | Timestamp | Sample time |

**`PreconditioningScheduleState`:**

| # | Field | Type | Meaning |
|---|---|---|---|
| 1 | `precondition_schedules` | repeated `PreconditionSchedule` | List of schedules |
| 2 | `preconditioning_schedule_window` | `PreconditionSchedule` | Current or next window |
| 3 | `max_num_precondition_schedules` | uint32 | Capacity |
| 4 | `next_schedule` | bool | |
| 2000 | `timestamp` | Timestamp | Sample time |

The element types come from `common.proto`:

- **`ChargeSchedule`:** `id` (uint64, "datetime in epoch time"), `name`, `days_of_week`
  (int32 bitmask; commands.go maps ALL to 127 and WEEKDAYS to 62), `start_enabled`,
  `start_time` ("24h in minutes"), `end_enabled`, `end_time` ("24h in minutes"),
  `one_time`, `enabled`, `latitude`, `longitude`.
- **`PreconditionSchedule`:** `id`, `name`, `days_of_week`, `precondition_time`
  ("24h in minutes"), `one_time`, `enabled`, `latitude`, `longitude`.

None of these are cluster-relevant. The repeated lists can make these responses large
(see §5.4).

### 2.13 `ParentalControlsState`

| # | Field | Type | Meaning |
|---|---|---|---|
| 1 | `timestamp` | Timestamp | Sample time |
| 2 | `parental_controls_active` | bool | |
| 3 | `parental_controls_pin_set` | bool | |
| 4 | `parental_controls_settings` | `ParentalControlsSettings` | See below |

`ParentalControlsSettings` contains:

- `speed_limit_enabled` (bool)
- `max_limit_mph`, `min_limit_mph` and `current_limit_mph` (float, mph)
- `chill_acceleration_enabled` (bool)
- `require_safety_settings_enabled` (bool)
- `curfew_enabled` (bool)
- `curfew_start_time` and `curfew_end_time` (int32; unit not stated, minutes inferred)

---

## 3. VCSEC reads (no wake, no authentication)

### 3.1 `INFORMATION_REQUEST_TYPE_GET_STATUS` → `VehicleStatus`

This is exposed as `Vehicle.BodyControllerState()` and tesla-control `body-controller-state`
("Fetch limited vehicle state information. Works over BLE when infotainment is asleep.").

| # | Field | Type | Values / meaning | IC |
|---|---|---|---|---|
| 1 | `closureStatuses` | `ClosureStatuses` | One `ClosureState_E` per closure (see below) | **S** |
| 2 | `vehicleLockState` | `VehicleLockState_E` | UNLOCKED, LOCKED, INTERNAL_LOCKED, SELECTIVE_UNLOCKED | S |
| 3 | `vehicleSleepStatus` | `VehicleSleepStatus_E` | UNKNOWN, AWAKE, ASLEEP. `state.go` calls this the "infotainment sleep status". | **S** (gating) |
| 4 | `userPresence` | `UserPresence_E` | UNKNOWN, NOT_PRESENT, PRESENT | S |
| 5 | `detailedClosureStatus` | `DetailedClosureStatus` | `tonneauPercentOpen` (uint32) | - |

`ClosureStatuses` has one `ClosureState_E` for each of these closures:

- `frontDriverDoor`
- `frontPassengerDoor`
- `rearDriverDoor`
- `rearPassengerDoor`
- `rearTrunk`
- `frontTrunk`
- `chargePort`
- `tonneau`

`ClosureState_E` values are CLOSED, OPEN, AJAR, UNKNOWN, FAILED_UNLATCH, OPENING and
CLOSING.

VCSEC reports no windows, speed, gear, SoC or temperatures.

### 3.2 Key whitelist (`list-keys`)

| Request | Response | Fields |
|---|---|---|
| `INFORMATION_REQUEST_TYPE_GET_WHITELIST_INFO` (`Vehicle.KeySummary`) | `WhitelistInfo` | `numberOfEntries` (uint32), `whitelistEntries` (repeated `KeyIdentifier{publicKeySHA1}`), `slotMask` (uint32 bitmask of occupied slots) |
| `..._GET_WHITELIST_ENTRY_INFO` with `InformationRequest.key.slot` (`Vehicle.KeyInfoBySlot`) | `WhitelistEntryInfo` | `keyId`, `publicKey.PublicKeyRaw`, `metadataForKey.keyFormFactor` (NFC_CARD, IOS_DEVICE, ANDROID_DEVICE, CLOUD_KEY, UNKNOWN), `slot`, `keyRole` (`Keys.Role`: NONE, SERVICE, OWNER, DRIVER, FM, VEHICLE_MONITOR, CHARGING_MANAGER, GUEST) |

The proto's `InformationRequest.key` oneof also allows `keyId` or `publicKey` as the
selector. The library only uses `slot`.

### 3.3 VCSEC status messages

Command replies can carry these status messages. They are not vehicle state.

- `CommandStatus`: `operationStatus` (OK, WAIT or ERROR), `signedMessageStatus` (`counter`
  and a `SignedMessage_information_E` fault), or `whitelistOperationStatus`.
- `Errors.NominalError`.

---

## 4. Other readable items

| Item | Domain | Proto | Exposed by tesla-control over BLE? | Notes |
|---|---|---|---|---|
| **Session info** | either | `Signatures.SessionInfo`: `counter`, `publicKey`, `epoch` (16 B, random per boot), `clock_time` (fixed32, seconds since epoch start), `status` (OK / KEY_NOT_ON_WHITELIST), `handle`, `flags` | Yes: `session-info PUBLIC_KEY DOMAIN` (`requiresAuth: false`) | Unauthenticated. Tells you whether the key is enrolled and gives the domain clock. **Inferred:** an infotainment request probably fails or is busy while infotainment sleeps. |
| **Ping** | Infotainment | `Ping`: `ping_id`, `local_timestamp`, `last_remote_timestamp`; returned in `Response.ping` | `ping` (`requiresAuth: true`) | `Vehicle.Ping` discards the response body. The Ping message has timestamp fields; using it for RTT measurement is **inferred**, not shown. |
| **Nearby charging sites** | Infotainment | `GetNearbyChargingSites{include_meta_data, radius, count}` → `NearbyChargingSites{timestamp, superchargers[], congestion_sync_time_utc_secs}`. Each `Superchargers` entry has `id`, `amenities`, `available_stalls`, `billing_info`, `billing_time`, `city`, `country`, `distance_miles`, `district`, `location`, `name`, `postal_code`, `site_closed`, `state`, `street_address`, `total_stalls`, `within_range`, `max_power_kw`, `out_of_order_stalls_number`, `out_of_order_stalls_names` | **No.** No tesla-control command. `Vehicle.GetNearbyCharging()` exists (radius 200, count 10) but **returns only `error`**; the response data is discarded. | Defined in the proto only. Data such as stall availability presumably needs the car's own connectivity (inferred). |
| **Guest mode** | Infotainment | `VehicleState.GuestMode{GuestModeActive}` | No | Used only as a *command* (`guestModeAction`). It is not part of `VehicleData`, so it is **not readable**. |
| **Vehicle name, software version, VIN, odometer (other than DriveState)** | - | - | - | No read message exists in the public proto. `SetVehicleNameAction` is write-only. The VIN is supplied by the client, and the BLE local name `S<sha1(VIN)[:8] hex>C` is derived from it. |

---

## 5. Latency-relevant notes

### 5.1 Request granularity

- **Proto:** `GetVehicleData` has 12 independent sub-message fields, not a `oneof`, so one
  request can set any combination of them.
- **Library and CLI:** `Vehicle.GetState(ctx, category)` sets **exactly one** category per
  request (`StateCategory.submessage()`), so tesla-control `state` costs one round trip per
  category.
- **Single-category requests work.** Requesting only `getDriveState` is how the library
  always operates, so this path is supported.
- **Multi-category requests are untested.** Whether the car honours several flags in one BLE
  request, and returns several `*_state` fields in one `VehicleData`, is **not shown** in
  the source. Treat it as untested.
- The `GetState` doc comment says: "The vehicle data Fleet API endpoint is much more
  efficient for clients that connect over the Internet because it combines data into a
  single query and can serve cached data when the vehicle is offline." **Inferred:** over
  BLE the library combines nothing and caches nothing.

### 5.2 Rate limits and caching

- The source shows **no rate limit, polling interval or caching hint** for BLE reads.
- Each `*State` carries a `timestamp`. These are the only freshness indicators. **Inferred:**
  compare successive timestamps to learn how often the car refreshes a category.
- The proto states no refresh rate for `DriveState`. Whether the car samples speed faster
  than a given poll rate is unknown, so measure it.

### 5.3 Session and transport overheads (from code)

- **Handshake before infotainment reads.** `StartSession` performs a
  `SessionInfoRequest` / `SessionInfo` handshake for each domain. tesla-control normally
  handshakes VCSEC and infotainment in parallel "to reduce latency" (comment in
  `commands.go`).
- **Session cache.** `cache.SessionCache` / `CacheEntry{CreatedAt, Domain, SessionInfo}` can
  resume a session without a new handshake. `dispatcher.LoadCache`,
  `Vehicle.UpdateCachedSessions` and `LoadCachedSessions` implement this. Keep the session
  alive across polls.
- **Command expiry.** By default an authenticated command expires 5 s after it is sent
  (`defaultExpiration = 5 * time.Second` in `session.go`). If the request context has a
  deadline, the time until that deadline is used instead.
- **Retries.** On a retriable error the library retries after `RetryInterval()`, which is
  **1 s on BLE**. Retriable errors include BUSY, TIMEOUT, INTERNAL ("not finished booting"),
  INVALID_SIGNATURE, INVALID_TOKEN_OR_COUNTER, INCORRECT_EPOCH, TIME_EXPIRED and
  TIME_TO_LIVE_TOO_LONG. A single retry already exceeds the 0.5 s fast-path budget, so the
  fast path should use its own short timeout instead of the library's retry loop
  (recommendation).
- **Clock-sync tolerance.** `maxLatency = 4 s` (`ble.go`). Session-info replies that arrive
  more than 4 s after the request are discarded.
- **Response encryption.** `DefaultFlags` sets `FLAG_ENCRYPT_RESPONSE`. Per `protocol.md`,
  cars on firmware 2024.38+ then encrypt responses. Fault
  `MESSAGEFAULT_ERROR_REQUIRES_RESPONSE_ENCRYPTION` (28) exists. Each response therefore has
  to be AES-GCM decrypted and checked against the anti-replay counter (`session.decrypt`).
- **BLE framing.** Each message is prefixed with a 2-byte big-endian length. Messages are
  written in chunks of `MTU - 3` bytes. If the MTU exchange fails, the chunk size falls back
  to `ble.DefaultMTU - 3`. The library negotiates up to `ble.MaxMTU`.
- **Write type.** `protocol.md` says to use "write with response". `ble.go` calls
  `WriteCharacteristic(..., false)`, meaning noRsp = false. **Inferred:** each chunk costs a
  GATT round trip, so a larger MTU directly cuts request latency.
- **Reassembly timeout.** `rxTimeout = 1 s`. If more than 1 s passes between inbound chunks,
  the partial buffer is discarded.
- **Connection limit.** "VCSEC can only reliably maintain up to three simultaneous BLE
  connections. These are shared by keyfobs and phone keys." A non-connectable advertisement
  yields `ErrMaxConnectionsExceeded`.
- **VCSEC concurrency.** Avoid simultaneous VCSEC requests, because VCSEC needs in-order
  counters and has limited memory. Infotainment accepts out-of-order counters within a
  window. **Inferred:** pipelining several infotainment requests may be possible, but it is
  not shown in the library.

### 5.4 Response size

- **Library cap.** `maxBLEMessageSize = 1024` in `ble.go`. In `flush()`, an inbound message
  whose length prefix exceeds 1024 bytes is **dropped**: the buffer is cleared and the
  message is not delivered.
- **Car-side cap.** The vehicle can return `MESSAGEFAULT_ERROR_RESPONSE_MTU_EXCEEDED` (25):
  "Client's request was received, but response size exceeded MTU". It also defines
  `REQUEST_MTU_EXCEEDED` (24).
- **Large categories (inferred).** `ChargeState` has about 75 fields, and the schedule
  states hold repeated lists. Requesting several categories in one `GetVehicleData` is
  therefore the most likely way to hit these limits.
- **Small category (inferred).** `DriveState` (5 core fields plus optional navigation
  fields) is small, which makes it a good fit for the fast path.

### 5.5 Suggested mapping (recommendation, not from the source)

| Path | Request | Fields |
|---|---|---|
| Fast (0.5 s) | Infotainment `GetVehicleData{getDriveState}` only, over a cached session | `speed_float` / `speed`, `shift_state`, `power`, `timestamp` |
| Slow (3 s) | Round-robin single-category requests: `getChargeState`, `getClosuresState`, `getClimateState`, `getTirePressureState`, `getMediaState`; occasionally `getLocationState` and `getSoftwareUpdateState` | Fields marked S |
| Sleep / gating | VCSEC `GET_STATUS` (no auth, no wake) | `vehicleSleepStatus`, `vehicleLockState`, `closureStatuses`, `userPresence`. Use it to decide when infotainment polling is possible. |

Measure the actual round-trip time of `getDriveState` on a real car before committing to
the 0.5 s budget. The source gives no latency figures.
