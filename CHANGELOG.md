# Changelog

All notable changes to Home Controller are recorded in this file. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

## 0.6.0 — 2026-10-10

### Added

- Live Tuya connections: each Tuya device keeps one connection open while the hub runs and reports its changes the
  moment they happen (a plug switched by hand, an alarm host sounding). The hub shows them at once, keeps the readings
  in the power history, and runs state-triggered automations within a second instead of at the next poll. Polling
  carries on as the fallback. Still direct to the device on your Wi-Fi: no relay, no server, no cloud.
- The connection is kept open with a heartbeat every 10 s when idle, and reopened by itself (after 1 s, then up to a
  minute apart) when it drops or goes silent.
- Fresh meter readings: metering plugs (power, current, voltage) and the OWON PC321-TY meter are asked to re-measure
  on every other read (UPDATEDPS), as tuya-local does; a device that ignores the request is read as before.
- Plugin contract: `DeviceSession.updates()`, an optional push channel. Connectors that only poll need no change.
- Sunrise and sunset schedules: "at sunset", "15 min before sunset", "1 h after sunrise" (up to three hours either
  way), on chosen days, for any automation and on a device's Schedules card. The times are worked out on the phone
  (commons-suncalc) from the home's location, set once in Settings → Home → Home location: typed or pasted, or
  "Use this phone's location", which asks for approximate location only then and reads it once. Until a location is
  set, sun schedules say so and do not run.
- Run once: an automation can turn itself off after it has run. If a device did not take the change, it stays on
  and tries again next time.
- Every day / Workdays / Weekend shortcuts for a schedule's days. Which days are the weekend is a setting
  (Settings → Home → Weekend: Friday–Saturday, Saturday–Sunday, Sunday or Friday); until it is chosen it follows the
  phone's region: Friday–Saturday in Saudi Arabia and the other Friday–Saturday countries, Saturday–Sunday
  elsewhere. Summaries say "Workdays" and "Weekend" when the days match.
- Gree: "Beep on commands" (turn the unit's beep off for the app's changes), anti direct blow, auto display light,
  and outdoor temperature and humidity readings, on units that report them.

### Changed

- A scheduled time the phone missed (Android asleep, or the app restarting) now still runs if the hub is back
  within 10 minutes, once; later than that it is skipped until the next time. When each automation last ran is
  saved, so a restart never runs a time twice, and Automations shows the day of a run that was not today.

### Fixed

- Tuya: a device's own STATUS report could be taken for the reply to a status query, after which replies came one
  request behind. Replies are now matched to their request by command (and by sequence number on protocol 3.3, whose
  devices echo it; 3.4 and 3.5 devices number their replies themselves, as a real 3.4 plug showed).
- Tuya OWON PC321-TY: an imported meter now gets its per-phase voltage, current and power refreshed too, not just
  the totals. The import names them `voltage_a` where the built-in map says `phase_a_voltage`.
- Gree: turning the beep off now survives an app restart. The setting is kept with the device, and the hub saved
  those details only when it opened a device, not after a change.
- Tuya: closing a device's session now closes its socket. Before, a reconnect left the old socket open, and devices
  that take one local connection could refuse the new one.

## 0.5.0 — 2026-10-10

### Added

- Schedules on every device's page: "at this time, on these days, set this" (power, a mode, a temperature, an
  alarm mode...). Each runs in the hub on the phone, so the device needs no internet; they also show in
  Automations. "Add schedule" opens the editor ready for that device.
- Tuya alarm host: arm (away / home), disarm and SOS from the app, with the siren, keypad, arm beep, delays and
  "silence" -- after Home Assistant's mapping. Smoke alarm: smoke state and battery.
- Imports carry each device's own options and ranges (a plug's power-on state as the device spells it, an alarm
  host's delay limits); older imports fall back to built-in options.
- Gree: fresh air, 8 °C frost protection and energy saving, on units that report them.

### Fixed

- A Tuya plug's "Energy" reading was a 0.1 kWh pulse the plug resets, not a total. It is now a diagnostic; energy
  comes from the power history.

## 0.4.0 — 2026-10-10

### Added

- Power history on the phone: every device with a power reading gets a "Power history" card -- 24 hours,
  7 days or 30 days; the power line (gaps shown as gaps, not joined), energy per hour or per day, and now / peak /
  today / this month. The hub keeps power, voltage, current and energy readings once a minute: minute detail for
  2 days, hourly summaries for 400 days, in a private SQLite file. No server, no cloud.
- Energy is exact for devices with their own counter (a 3-phase meter's total -- right even for hours the phone
  was away); for plugs it is worked out from power readings, and times the phone was away are left out rather
  than guessed.
- Removing a device also removes its history.

## 0.3.1 — 2026-10-10

### Added

- Per-device power button setting (device menu → Power button): **Show** (one tap switches it), **Ask first**
  (every tap asks before switching, on the home screen and on the device's page) or **Hide from the home screen**
  (the tile has no power button; switch it on the device's page). For a plug that powers a computer, say.
  Automations are never affected.

## 0.3.0 — 2026-10-10

### Added

- Tuya connector: Tuya Wi-Fi plugs, switches and energy meters over their local LAN protocol (3.3, 3.4 and 3.5,
  ported from tinytuya), no cloud. Every data point is decoded: on/off, live power, current, voltage and energy,
  power-on state, LED mode, child lock, over-charge cutoff, fault, and the device's own countdown, cycle, random
  and inching timers, which are stored on the device and keep running with no internet.
- OWON PC321-TY 3-phase clamp meter: per-phase voltage, current, power, power factor and energy, totals,
  frequency and meter temperature.
- "Import Tuya devices": scan one QR code shown by your PC to bring in every device with its key and data-point
  map. The key is sealed with an Android Keystore key before it is saved; the plaintext is never stored.
- A device whose address changed (DHCP), or an imported device that was switched off, is found again by its own
  broadcast when it is next opened, and the new address is kept.

## 0.2.0 — 2026-10-10

### Added

- Pairing: adopt a Gree unit through its setup hotspot.
- One-scan discovery (paced sweep, two passes, known hosts).
- Diagnostics log with Share; Gree child lock, defrost and on/off timer status.

## 0.1.0 — 2026-10-10

The first version: a smart-home hub that runs on the phone.

### Added

- Android app that controls devices directly on the local Wi-Fi, with no server, no cloud and no account.
- Plugin contract in `core`: connectors, device sessions and a capabilities model (power, choice, number, toggle,
  reading) from which the app draws every device's controls.
- Gree Wi-Fi air conditioner connector: discovery by broadcast and /24 sweep, v1 (AES-ECB) and v2 (AES-GCM)
  ciphers, power, mode, target temperature, fan speed, vertical and horizontal swing, display light, turbo, quiet,
  sleep, ioniser and X-Fan, plus room temperature and diagnostics (fault code, filter reminder, module firmware,
  unit clock).
- Hub service that polls devices and runs automations on the phone, optionally in the background as a foreground
  service.
- Automations engine: time-of-day and device-state triggers, state and time-window conditions, device and
  notification actions.
- Rooms, light and dark themes, and dynamic colour on Android 12 and later.
- App icon with an Android 13 themed (monochrome) layer, store graphics, documentation and continuous integration.
