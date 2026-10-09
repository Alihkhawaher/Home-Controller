# Changelog

All notable changes to Home Controller are recorded in this file. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

## 0.1.0 — unreleased

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
