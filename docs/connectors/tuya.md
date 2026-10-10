# Tuya Wi-Fi devices

| | |
|---|---|
| Connector id | `tuya` |
| Module | [`connectors/tuya`](../../connectors/tuya) |
| Devices | Tuya Wi-Fi plugs, energy meters and switches, and the many brands built on Tuya (SmartLife / Tuya Smart apps) |
| Transport | JSON over TCP, port 6668, on the local network |
| Encryption | AES-128: ECB (protocol 3.3 and 3.4) or GCM (3.5); HMAC-SHA256 session frames on 3.4/3.5 |
| Cloud or account | Not used |

## Setting up a device

Tuya has no "ask the device for its key" step: each device has a secret 16-character **local key** that is minted in
the cloud when the device is first paired. Home Controller never talks to the cloud, so you supply four things per
device, read once from the Tuya/SmartLife account that paired it:

- **device id** (`gwId`), **local key**, **protocol version** (3.3, 3.4 or 3.5), and the device's **IP**.

Two ways to get them in:

1. **Import (recommended).** A companion PC tool (which does have the cloud credentials) renders all your devices as
   one QR code; the app scans it and you tick the ones to add. No key is ever typed. See [Importing](#importing).
2. **By hand.** Enter the id, key and version for one device. Discovery fills the IP.

The key stays on the phone, in the app's own storage, and is **never logged**.

## Protocol summary

Ported from the open-source [tinytuya](https://github.com/jasonacox/tinytuya). Every message is a framed packet on
TCP 6668; the payload is AES-encrypted JSON.

### Framing

**55AA (protocol 3.1–3.4):**

```
0x000055AA | seq(4) | command(4) | length(4) | [return code(4), replies only] | payload | CRC32(4) or HMAC-SHA256(32) | 0x0000AA55
```

All fields big-endian; `length` counts everything after it. Requests carry no return code; replies do. On 3.4 the
CRC32 is replaced by an HMAC-SHA256 keyed with the session key.

**6699 (protocol 3.5):**

```
0x00006699 | reserved(2) | seq(4) | command(4) | length(4) | IV(12) | AES-GCM(payload) | tag(16) | 0x00009966
```

The 14 header bytes after the prefix are the GCM associated data; `length` counts `IV + cipher text + tag`. Replies
prepend a 4-byte return code to the plaintext before encryption.

### The payload cipher by version

| | 3.3 | 3.4 | 3.5 |
|---|---|---|---|
| Frame | 55AA, CRC32 | 55AA, HMAC-SHA256 (session key) | 6699, AES-GCM |
| Payload | AES-128-ECB (local key) | AES-128-ECB (session key) | AES-128-GCM (session key) |
| Session key | — | negotiated | negotiated |
| Version header | `3.3`+12 nulls, prepended **after** encryption, on CONTROL only | `3.4`+12 nulls, **inside** the encryption, on CONTROL_NEW | `3.5`+12 nulls, inside the encryption, on CONTROL_NEW |

The query commands (DP_QUERY `0x0a`, DP_QUERY_NEW `0x10`) never carry the version header.

### Session-key negotiation (3.4 and 3.5)

Before any status or command, 3.4/3.5 negotiate a per-connection key:

1. the app sends a random 16-byte nonce (`SESS_KEY_NEG_START` `0x03`);
2. the device replies (`SESS_KEY_NEG_RESP` `0x04`) with its own 16-byte nonce and `HMAC-SHA256(local key, app nonce)`;
   the app checks that HMAC (a wrong local key fails here);
3. the app sends `HMAC-SHA256(local key, device nonce)` (`SESS_KEY_NEG_FINISH` `0x05`);
4. both sides set the **session key** to the local key encrypting the XOR of the two nonces — AES-ECB for 3.4,
   AES-GCM (IV = the app nonce's first 12 bytes) for 3.5. The session key encrypts everything afterwards.

### The requests

| Request | 3.3 | 3.4 / 3.5 |
|---|---|---|
| Read status | DP_QUERY `0x0a`, payload `{"gwId","devId","uid","t"}` | DP_QUERY_NEW `0x10`, payload `{}` |
| Set values | CONTROL `0x07`, payload `{"devId","uid","t","dps":{…}}` | CONTROL_NEW `0x0d`, payload `{"protocol":5,"t":…,"data":{"dps":{…}}}` |
| Keep the connection open | HEART_BEAT `0x09`, payload `{"gwId","devId"}` | the same |
| Re-measure readings | UPDATEDPS `0x12`, payload `{"dpId":[18,19,20]}` | the same |

The device answers status as `{"dps":{"1":true,"19":1033,…}}` (3.4/3.5 may wrap it as `{"data":{"dps":…}}`; both are
read). HEART_BEAT and UPDATEDPS carry no version header in any version; their frames are checked byte for byte
against tinytuya's in the tests.

## The live connection

A session keeps **one** TCP connection open to its device for as long as it lives. Many Tuya devices accept a single
local connection, so the connector never opens a second one while one is open: a reconnect closes the old socket
first, and a session closes its socket when it is closed.

A background reader takes every frame the device sends:

- **Replies.** The answer to the request in flight is matched by command, and below 3.4 also by sequence number,
  which those devices echo. 3.4 and 3.5 devices number their frames on their own: on real hardware a 3.4 plug
  answered our sequence number 3 with 21396, while a 3.3 alarm host echoed every one. Many devices first send an
  empty acknowledgement (a "null ack") and then the data; that is waited out.
- **Pushes.** A STATUS frame (`0x08`) is the device reporting a change on its own: a button pressed, a change made in
  another app, an alarm going off, or the change a command just made. It is never taken for a reply. The session
  hands it to the hub at once, as a whole state, through `DeviceSession.updates()`.
- **Heartbeats.** A HEART_BEAT goes out whenever the connection has been idle for 10 s (devices drop an idle socket;
  tinytuya's monitor uses 12 s, tuya-local 5 s). Its answer only proves the connection is alive.

Data points from every frame are merged in the order they arrive, so a reply that a push overtook never rolls the
state back.

When the connection drops (the device rebooted, Wi-Fi blinked) or goes silent (nothing heard for 30 s, heartbeats
included), it is reopened after 1 s, then 2, 4 … up to 60 s, and the status is read again (pushes sent meanwhile are
lost). The delay starts again from 1 s only after a connection has held for a minute, so a device that hangs up after
every answer is not hammered. A poll reconnects at once if it finds the connection down.

### Refreshing readings (UPDATEDPS)

Metering plugs and meters only re-measure some readings when asked. For a device that reports them, every other
read sends UPDATEDPS instead of the status query, alternating the two as tuya-local does; the device acknowledges and
pushes the fresh values. The dps refreshed:

| Device | dps | Source |
|---|---|---|
| Metering plug (`cz`): `cur_current`, `cur_power`, `cur_voltage` | 18, 19, 20 | tinytuya's default UPDATEDPS request |
| OWON PC321-TY: per-phase voltage, current, power; current and power totals | 101-103, 111-113, 121-123, 132, 133 | tuya-local's `pc321ty_energy_meter.yaml` (`force: true`) |

They are chosen by code, so an imported spec that names them the same way gets them too. A device that leaves
UPDATEDPS unanswered three times is read by status query alone from then on.

## Data points and the complete device view

A Tuya device reports a bag of numbered data points (dps). The numbers mean nothing on the wire; their code names
come from the device's **local strategy** (`dp id → code`). This connector resolves a dp to its meaning from, in
order of precedence: an **imported spec** (from the QR import), the device's **local-strategy override**, its
**category default** (below), and a **per-product decoder**. Anything still unmapped is shown as `dp_<id>`, so the
device view is always complete: every dp is decoded, named and marked read-only or writable.

Codes mean the same thing on every device, so each code's kind, unit, scale and options live once in the model.

### Smart plug (category `cz`)

| dp | Code | Capability | Notes |
|---|---|---|---|
| 1 | `switch_1` | Power | on/off |
| 9 | `countdown_1` | Number (`s`) | on-board auto-off countdown; writable |
| 17 | `add_ele` | Reading (`kWh`) | cumulative energy (raw ÷ 1000) |
| 18 | `cur_current` | Reading (`mA`) | |
| 19 | `cur_power` | Reading (`W`) | raw ÷ 10 |
| 20 | `cur_voltage` | Reading (`V`) | raw ÷ 10 |
| 26 | `fault` | Reading | `none` or a fault code; diagnostic |
| 38 | `relay_status` | Choice | power-on state: `off` / `on` / `memory` |
| 39 | `light_mode` | Choice | indicator: `none` / `relay` / `pos` / `on` |
| 40 | `child_lock` | Toggle | |
| 41 | `cycle_time` | on-board cycle timer | see [Native timers](#native-on-board-timers) |
| 42 | `random_time` | on-board random timer | |
| 43 | `switch_inching` | on-board inching timer | |
| 44 | `overcharge_switch` | Toggle | over-charge cutoff |
| 21–25 | `test_bit`, `*_coe` | Reading | self-test and calibration; diagnostic |

> **Scale is device-dependent.** The ÷10 / ÷1000 factors above are the common Tuya metering-plug values and should be
> confirmed against the real device; an import carries the exact scale per dp, which overrides these defaults.

Power monitoring (power, voltage, current, energy) is surfaced right under the switch, not in diagnostics.

### Three-phase energy meter (category `zndb`): OWON PC321-TY

The Tuya cloud returns no spec for this meter, so it has a built-in per-product decoder, keyed by product id
(`9rjjf6rdkca9woju`, `r64livttuufctble`, `gqmmtjclqb7reg5p`), protocol 3.5. All read-only. Phase A is `10x`, B `11x`,
C `12x`:

| dp (phase A / B / C) | Code | Unit | Divisor |
|---|---|---|---|
| 101 / 111 / 121 | `phase_x_voltage` | V | ÷10 |
| 102 / 112 / 122 | `phase_x_current` | A | ÷1000 |
| 103 / 113 / 123 | `phase_x_power` | W | raw |
| 104 / 114 / 124 | `phase_x_power_factor` | — | ÷100 |
| 106 / 116 / 126 | `phase_x_energy` | kWh | ÷100 |
| 107 / 117 / 127 | `phase_x_energy_produced` | kWh | ÷100 |

| dp | Code | Unit | Divisor |
|---|---|---|---|
| 131 | `energy_total` | kWh | ÷100 |
| 132 | `current_total` | A | ÷1000 |
| 133 | `power_total` | W | raw |
| 135 | `frequency` | Hz | raw |
| 136 | `temperature` | °C | ÷10 (diagnostic) |
| 139 | `energy_produced_total` | kWh | ÷100 |

dp 137 and 138 are not understood and are shown raw as `dp_137` / `dp_138`. Dp map from make-all / tuya-local's
`pc321ty_energy_meter.yaml`, confirmed against a live meter (≈127 V/phase, ≈3.6 kW total, 60 Hz).

## Scheduling with no cloud

Some Tuya devices refuse schedules set from their app unless the cloud is reachable. Home Controller does not depend
on the cloud, in two layers:

1. **Phone-side automations (primary).** The app's own automation engine runs schedules on the phone: a *time of day*
   trigger with an action that sets a Tuya device's `power` over the LAN. A Tuya plug is an ordinary automation
   target. This needs the app's background hub running, but no cloud.
2. **Native on-board timers.** The device's own timers persist on the device and run with the phone off and the
   internet down.

### Native on-board timers

Set with helpers on the session; they are one data point carrying a base64 string of fixed-width records, big-endian,
each record starting with a control byte `(channel << 1) | enabled` (channel is 0-based, `switch_1` = channel 0):

- **inching** (`switch_inching`): `[ctrl, seconds(2)]` — auto-off N seconds after the channel turns on;
- **random** (`random_time`): `[ctrl, week, start(2), end(2)]`, up to 16 — switch at a random minute in a window;
- **cycle** (`cycle_time`): `[ctrl, week, start(2), end(2), on(2), off(2)]`, up to 10 — repeat on/off in a window.

`week` is `0x00` for once, else a bitmask (bit 0 Sunday … bit 6 Saturday; Mon|Wed|Fri = `0x2A`). Formats confirmed
byte-for-byte against real Tuya-app writes in make-all / tuya-local (`wifi_m604_quad_switch.yaml`).

## Importing

The app imports a whole set of devices from one QR code so no 16-character key is ever typed. The code is
`HCTUYA1:` + base64url(gzip(UTF-8 JSON)), no padding; the JSON is
`{"devices":[{"id","key","ver","name","cat","pid","dps":[[dp_id,code,type,scale,unit,writable], …]}]}`. `ver` may be
missing for an offline device; discovery fills the IP and version by matching the device id. Keys are stored only in
the app's encrypted storage and never logged (the log says only "imported N devices").

## How the connector behaves

- **Discovery** is passive: Tuya devices broadcast on UDP 6666 (3.1 plaintext), 6667 (AES-ECB) and 6699 (AES-GCM),
  decrypted with the one published UDP key `md5("yGAdlopoPVldABfn")`. A broadcast gives a device's id, IP and
  protocol version — never its key.
- **Opening** connects over TCP, negotiates the session key on 3.4/3.5, then reads the status once to learn which
  dps the device has and declare their capabilities. The connection then stays open (see
  [The live connection](#the-live-connection)).
- **Pushes** reach the hub within a second of the device sending them; the hub keeps polling as the fallback.
- **Reads** never change the device. A device that stops answering reads as offline with its last known values; the
  connection is reopened once (renegotiating the session key) before a read is given up on.
- **Commands** are validated before anything is sent: an unknown key, a read-only one or a value out of range is
  refused with a message.
- **The key** (local key and the negotiated session key) lives only in memory for the session and is never logged.

## Quirks

- **device22.** Some devices with a 22-character id answer a plain DP_QUERY with "data unvalid". The connector then
  switches to the CONTROL_NEW query form, exactly as tinytuya does.
- **Scale.** The local protocol carries raw integers; the ÷10/÷100/÷1000 scaling is device metadata. The built-in
  defaults are the common values; an import carries the exact per-dp scale.
- **Odd answers.** If a device never answers a status query with the query's own command but sends its data in a
  STATUS frame instead, that data is taken (once, after the usual wait) and STATUS frames answer its queries from then
  on; the log says so. A device that ignores UPDATEDPS is read by status query alone.

## Security

The Tuya LAN protocol authenticates with the device's local key. Anyone who has the key and is on your Wi-Fi can
control the device. Keep your Wi-Fi password strong and your keys private. See [SECURITY.md](../../SECURITY.md).

## Credits

- [tinytuya](https://github.com/jasonacox/tinytuya) (Jason Cox and contributors) documented the Tuya LAN protocol —
  the framing, ciphers, session-key negotiation and command set here are ported from it, as are the HEART_BEAT and
  UPDATEDPS requests and the rule for matching a reply to its request.
- [make-all / tuya-local](https://github.com/make-all/tuya-local) device configs supplied the OWON PC321-TY meter dp
  map and the dps it refreshes (`pc321ty_energy_meter.yaml`) and the native-timer record formats
  (`wifi_m604_quad_switch.yaml`); its persistent-connection loop is the model for alternating UPDATEDPS with the
  status query.

Tuya and other brand names are trademarks of their owners. Home Controller is an independent project and is not
affiliated with or endorsed by any device maker.
