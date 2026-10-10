# Changelog

All notable changes to Home Controller are recorded in this file. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

## Unreleased

### Added

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

### Changed

- A scheduled time the phone missed (Android asleep, or the app restarting) now still runs if the hub is back
  within 10 minutes, once; later than that it is skipped until the next time. When each automation last ran is
  saved, so a restart never runs a time twice, and Automations shows the day of a run that was not today.

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
