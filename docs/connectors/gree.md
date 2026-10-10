# Gree Wi-Fi air conditioners

| | |
|---|---|
| Connector id | `gree` |
| Module | [`connectors/gree`](../../connectors/gree) |
| Devices | Gree air conditioners with a Wi-Fi module, and Gree-made units sold under other brands, usually set up with the Gree+ or EWPE Smart app |
| Transport | JSON over UDP, port 7000, on the local network |
| Encryption | AES-128: ECB (v1) or GCM (v2) |
| Cloud or account | Not used |

## Setting up a unit

The unit must already be on your Wi-Fi. Home Controller does not join new units to a network yet: do that once with
the vendor's app (or the unit's own setup hotspot). After that, the vendor app and its account are not needed;
Home Controller finds the unit with **Search for devices** as long as the phone is on the same Wi-Fi.

## Protocol summary

Gree Wi-Fi modules speak JSON over UDP port 7000. Apart from the first scan request, every message is wrapped in a
`pack` envelope whose payload is AES-encrypted JSON.

```json
{"cid":"app","i":0,"t":"pack","uid":0,"tcid":"<unit mac>","pack":"<base64 cipher text>"}
```

- `i` is `1` for bind, which uses the generic key, and `0` for everything else, which uses the unit's own key.
- `tcid` is the unit's MAC as the unit itself spells it in its scan reply.
- v2 (GCM) messages carry the authentication tag next to the pack as `"tag":"<base64>"`.

### The two ciphers

| | v1 | v2 |
|---|---|---|
| Algorithm | AES-128-ECB, PKCS#7 padding | AES-128-GCM |
| Generic key | `a3K8Bx%2r8Y7#xDh` | `{yxAHAY_Lm6pbC/<` |
| Nonce | none | `5440784449675a516c5e6313` (hex, fixed) |
| Associated data | none | `qualcomm-test` |
| Tag | none | 16 bytes, base64, in the `tag` field |

The generic keys are the same in every Gree app and every open-source implementation; they are not secrets. A unit
answers scan and bind with its cipher's generic key, and bind hands out the unit's own key, which encrypts
everything after that.

### The four requests

**1. Scan** finds units. The request is plain, unencrypted JSON, sent to the broadcast address or to one host:

```json
{"t":"scan"}
```

Each unit answers with a pack encrypted with the generic key. A reply with a `tag` field is v2, otherwise v1. Inside:

```json
{"t":"dev","mac":"<unit mac>","name":"<name or empty>","ver":"<module firmware>","brand":"<brand>"}
```

**2. Bind** asks the unit for its own key. It changes nothing on the unit.

```json
{"t":"bind","mac":"<unit mac>","uid":0}
```

```json
{"t":"bindok","mac":"<unit mac>","key":"<the unit's 16-character key>"}
```

**3. Status** reads named columns, encrypted with the unit's key:

```json
{"t":"status","mac":"<unit mac>","cols":["Pow","Mod","SetTem","WdSpd"]}
```

```json
{"t":"dat","cols":["Pow","Mod","SetTem","WdSpd"],"dat":[1,1,24,0]}
```

**4. Command** sets one or more columns in one message:

```json
{"t":"cmd","opt":["Pow","SetTem"],"p":[1,24]}
```

```json
{"t":"res","opt":["Pow","SetTem"],"p":[1,24],"r":200}
```

Some modules confirm in `val` instead of `p`. An `r` other than 200 means the unit refused the command.

## Value tables

How the connector maps Gree columns to Home Controller capabilities.

### Controls

| Column | Capability key | Kind | Values |
|---|---|---|---|
| `Pow` | `power` | Power | `0` off, `1` on |
| `Mod` | `mode` | Choice | `0` auto, `1` cool, `2` dry, `3` fan, `4` heat |
| `SetTem` | `target_temperature` | Number | 16 to 30, step 1, °C |
| `WdSpd` | `fan_speed` | Choice | `0` auto, `1` low, `2` medium-low, `3` medium, `4` medium-high, `5` high |
| `SwUpDn` | `swing_vertical` | Choice | `0` default, `1` full swing, `2` fixed upper, `3` fixed upper-middle, `4` fixed middle, `5` fixed lower-middle, `6` fixed lower, `7` swing lower, `8` swing lower-middle, `9` swing middle, `10` swing upper-middle, `11` swing upper (the order of `7`-`11` needs a check on a real unit, see [Open questions](#open-questions)) |
| `SwingLfRig` | `swing_horizontal` | Choice | `0` default, `1` full swing, `2` left, `3` left-centre, `4` centre, `5` right-centre, `6` right |
| `Lig` | `display_light` | Toggle | `0` off, `1` on |
| `Tur` | `turbo` | Toggle | `0` off, `1` on |
| `Quiet` | `quiet` | Toggle | `0` off; written as `2` for on, as greeclimate does; any non-zero value reads as on |
| `SwhSlp` + `SlpMod` | `sleep` | Toggle | both `0` off, both `1` on |
| `Health` | `health` (ioniser) | Toggle | `0` off, `1` on |
| `Blo` | `x_fan` | Toggle | `0` off, `1` on. X-Fan keeps the fan running after cooling or dry mode to dry the coil. |
| `Air` | `fresh_air` | Toggle | `0` off, `1` on. Fresh-air intake. |
| `StHt` | `steady_heat` | Toggle | `0` off, `1` on. 8 °C frost protection: holds the room at 8 °C in heat mode. |
| `SvSt` | `energy_saving` | Toggle | `0` off, `1` on. Energy saving, a cool-mode feature. |
| (none) | `beep` | Toggle | "Beep on commands", the app's own setting; see [Beep on commands](#beep-on-commands). |

All of these are in the status request; a unit that does not return a column does not show its control (many units
have no `Air`, `StHt` or `SvSt`). When the connector sets `SetTem` it also writes `TemUn = 0` (degrees Celsius) and
`TemRec = 0` (no half-degree bit, which only matters for Fahrenheit).

### Readings and diagnostics

| Column | Capability key | Meaning |
|---|---|---|
| `TemSen` | `current_temperature` | Room temperature. Many modules report degrees C plus 40 (`68` means 28 °C): values of 40 and above have 40 taken off, lower values are used as they are, and `0` means no reading. |
| `AllErr` | `fault` | `0` none; anything else is shown as the unit's fault code. Diagnostic. |
| `Dfltr` | `clean_filter` | `1` when the unit asks for its filter to be cleaned. Diagnostic. |
| `hid` | `firmware` | Wi-Fi module firmware. Diagnostic. |
| `time` | `clock` | The unit's clock. Diagnostic. |

Diagnostic columns are asked in a separate, smaller request, at most every five minutes. A unit that does not answer
them still works normally.

### Optional columns

Columns that modules differ on are asked in two small requests of their own, never inside the status request. A
control or reading appears only when the unit answers its column when the device is opened.

| Group | Columns | When they are asked |
|---|---|---|
| Extras | `ChildLock`, `TmrOn`, `TmrOff`, `Defrost` | At open, up to two sends. Then every five minutes with the diagnostics, if the unit answered any. |
| Probe | `AntiDirectBlow`, `LigSen`, `OutEnvTem`, `DwatSen` | At open, one send, and only when the unit replied to the extras request. Then every five minutes with the diagnostics, if the unit answered any. |

| Column | Capability key | Kind | Values |
|---|---|---|---|
| `ChildLock` | `child_lock` | Toggle | `0` off, `1` on |
| `AntiDirectBlow` | `anti_direct_blow` | Toggle | `0` off, `1` on. Keeps the air stream from blowing straight at people. |
| `LigSen` | `auto_display_light` | Toggle | Inverted: `0` means the light sensor runs the display light, `1` that it does not. Turning it on writes `Lig = 1` and `LigSen = 0`; turning it off writes `LigSen = 1` and leaves the display light as it is. |
| `OutEnvTem` | `outdoor_temperature` | Reading | Outdoor temperature, °C. See [Outdoor temperature](#outdoor-temperature). |
| `DwatSen` | `humidity` | Reading | Room humidity, %. `0` (no sensor) and values above 100 show no value. |
| `Defrost` | `defrost` | Reading, diagnostic | "yes" while the unit defrosts. |
| `TmrOn`, `TmrOff` | `timer_on`, `timer_off` | Reading, diagnostic | "yes" when the unit's own on or off timer is set. |

Why two requests: a V1.2.1 module answered nothing at all to a request naming many columns it did not know. With
the newer names added to the extras request, such a module would lose the child lock, timers and defrost it shows
today; in a request of their own, only the newer ones go missing. The probe is skipped for a unit that did not reply
to the extras (it would be silent again), and gets a single send at open because every silent send makes the open
wait 2.5 seconds while the app waits only a few seconds for a unit to open. A lost probe hides those controls until
the next open. After a command that changes an optional column, its group is read back at once.

#### Outdoor temperature

Modules that report `TemSen` as degrees C plus 40 do the same with `OutEnvTem`. For the room, the value alone
usually tells which kind of module it is, because a room is rarely 40 °C or warmer. Outdoor temperatures run from
below 0 to above 40 °C, so the value alone cannot tell: `45` is 5 °C with the offset and 45 °C without it. The connector takes the answer from
the same unit's room sensor: a `TemSen` of 40 or more means the module adds 40 to both, a lower one means it adds
nothing. Without a room reading, the `TemSen` rule is used on the outdoor value itself. `0` means no reading.

### Beep on commands

Every Gree device has a **Beep on commands** switch (`beep`). It is a setting of the commands Home Controller sends,
not a column of the unit, and it is kept with the device in the `DeviceRef` extra `beep`: `"0"` off, `"1"` on, and
no extra means on, the unit's normal behaviour.

- **On** (the default): commands go out exactly as they always have, without buzzer flags. A command without them
  beeps.
- **Off**: every command also carries `Buzzer_ON_OFF = 1` (read by older module firmware; `1` is silent) and
  `BuzzerCtrl = 0` (read by newer firmware, 1.44 and up; `0` is silent), so the unit stays silent whichever flag its
  firmware reads.

The flags act on the command that carries them; the unit does not store them. So they go with every command and are
never read back (units report them inconsistently). Changing the switch sends nothing to the unit. When the switch
changes in the same batch as other settings, the new value already applies to that batch's command, and it is kept
only if the unit confirms the command.

The modules reported so far take a command that names columns they do not know: they answer `r: 200` and echo the
names back. Firmware differs in what it honours: some modules follow `Buzzer_ON_OFF`, some `BuzzerCtrl`, some neither (they always beep),
and one module was reported to silence only every second command. Other projects send a "beep" value when beeping is
on (`BuzzerCtrl = 1`, some also `Buzzer_ON_OFF = 0`); Home Controller sends nothing then, which beeps the same and
leaves the command unchanged for modules that know neither flag.

A change to the switch updates the session's `device` extras, which the app saves with the device. While it runs,
the connector also remembers the choice per unit, so a session that is opened again (after the unit went silent,
say) keeps it.

### Features and modes

Other Gree projects report that these features only work in some modes. The connector does not block any of them
by mode: models differ, and the unit decides. None of this is confirmed on a real unit yet.

| Feature | Column | Works in |
|---|---|---|
| Turbo | `Tur` | Cool and dry |
| Quiet | `Quiet` | Every mode except dry and fan |
| X-Fan | `Blo` | Cool and dry |
| 8 °C frost protection | `StHt` | Heat |
| Energy saving | `SvSt` | Cool |

### Asked but not shown yet

`HeatCoolType` and `TemUn`/`TemRec` are read with the status request but have no control yet.

### Open questions

These need a check on a real unit:

- **Swing positions 7 to 11.** gree-remote and RobHofmann's Home Assistant component number the swing ranges from
  the lowest (`7`) to the highest (`11`), as this connector does; greeclimate and Home Assistant's own integration
  number them the other way round.
- **Outdoor temperature and humidity.** No unit tested so far answers `OutEnvTem`, so the offset rule above follows
  the room sensor's rule and other projects' notes, not a real reading. `DwatSen` has not been checked on a real
  unit either.
- **Beep flags.** Which flag a given module honours, if any.

## How the connector behaves

- **Discovery** sends the scan to the broadcast address, to every host the app already knows, and by default to
  every address of the /24 one by one, because many routers, mesh systems and access points do not forward
  broadcasts. It sends twice within the window and returns every unit that answered, once each.
- **Opening a unit** scans its saved address first and checks the MAC. If a different unit answers there (after a
  DHCP change, for example), it stops and asks you to search again rather than control the wrong unit.
- **The key** is fetched with bind every time a unit is opened and lives only in memory. It is never logged, shown
  or saved. The cipher version and the MAC as the unit spells it are saved; neither is secret.
- **Capabilities** are declared from what the unit answers on the first read: a column the unit does not return is
  left out, so its control does not appear. The optional columns are asked separately (see
  [Optional columns](#optional-columns)); Beep on commands is always there.
- **Reads** never change the unit. A unit that stops answering reads as offline with its last known values.
- **Commands** are validated before anything is sent: an unknown setting, a setting this unit did not declare or a
  temperature outside 16-30 °C is refused with a message. With Beep on commands off, every command also carries the
  buzzer flags.

## Quirks

- **Packet loss.** UDP has no retransmission and units on weak Wi-Fi drop packets. A read is sent up to four times
  while the unit stays silent. A command is sent at most twice, the second time only when no reply arrived at all,
  so a command the unit answered is never repeated.
- **Unknown column names.** Some older modules answer *nothing* to a status request that contains a column they do
  not know. The connector asks only for known columns in the status request, and keeps the columns that modules
  differ on in small requests of their own. Commands seem to be different: the modules reported so far take a
  command with names they do not know and echo them back.
- **New key after re-pairing.** Pairing a unit again with the vendor app gives it a new key. When a reply stops
  decrypting, the connector binds again once and carries on.
- **Cipher detection.** The scan reply tells v1 from v2. Bind tries that cipher first, then the other.
- **Padding.** Some modules pad the decrypted JSON with stray bytes. The connector cuts the text after its last `}`.
- **Room temperature offset.** See `TemSen` above.

## Troubleshooting

**A unit is not found.**

- The phone and the unit must be on the same network and subnet. Guest networks and "client isolation" or "AP
  isolation" settings block local traffic.
- Most Gree modules use 2.4 GHz Wi-Fi only. They must have been joined to your Wi-Fi with the vendor app first.
- A VPN on the phone can capture local traffic. Pause it, or allow LAN access in the VPN app, and search again.
- **Very new module firmware** may close the local UDP port and talk to the cloud only. If one unit never answers
  while others on the same Wi-Fi do, its module is probably one of these. There is no local workaround; a module
  with older firmware works.

**"A different Gree unit answers at this address".** The unit has a new IP address and another unit took its old one.
Search for devices again; saved names, rooms and automations are kept because they follow the unit's MAC, not its
address.

**A unit goes offline now and then.** The Wi-Fi signal at the unit is weak. The hub keeps retrying and the unit comes
back on its own. A better access point position or a mesh node near the unit fixes it for good.

**The unit still beeps with Beep on commands off.** Its module firmware honours neither buzzer flag, or not for every
command; see [Beep on commands](#beep-on-commands). There is no local workaround.

**A control such as Auto display light or Outdoor temperature is missing.** The unit did not answer that column when
the app connected to it. Either the unit does not have the feature, or the single probe was lost on a weak link; then
it shows the next time the app connects to the unit (after the app restarts, for example).

## Security

The Gree LAN protocol has **no authentication**. Anyone on your Wi-Fi can scan, bind and control every unit, with or
without Home Controller. Protect your Wi-Fi with a strong password and keep guests on a separate network. See
[SECURITY.md](../../SECURITY.md).

## Credits

The protocol was documented by the open-source projects
[greeclimate](https://github.com/cmroche/greeclimate) (cmroche) and
[gree-remote](https://github.com/tomikaa87/gree-remote) (tomikaa87). The column names, codes and cipher parameters
above come from their work, confirmed against real units. The optional columns (`AntiDirectBlow`, `LigSen`,
`OutEnvTem`, `DwatSen`) and the buzzer flags come from
[RobHofmann's Home Assistant component](https://github.com/RobHofmann/HomeAssistant-GreeClimateComponent) and the
issue trackers of that component and greeclimate. These projects are GPL-3.0: Home Controller uses the protocol
facts they document, not their code.
