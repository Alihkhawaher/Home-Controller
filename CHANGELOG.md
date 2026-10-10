# Changelog

All notable changes to Home Controller are recorded in this file. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

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
