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
| `SwUpDn` | `swing_vertical` | Choice | `0` default, `1` full swing, `2` fixed upper, `3` fixed upper-middle, `4` fixed middle, `5` fixed lower-middle, `6` fixed lower, `7` swing lower, `8` swing lower-middle, `9` swing middle, `10` swing upper-middle, `11` swing upper |
| `SwingLfRig` | `swing_horizontal` | Choice | `0` default, `1` full swing, `2` left, `3` left-centre, `4` centre, `5` right-centre, `6` right |
| `Lig` | `display_light` | Toggle | `0` off, `1` on |
| `Tur` | `turbo` | Toggle | `0` off, `1` on |
| `Quiet` | `quiet` | Toggle | `0` off; written as `2` for on, as greeclimate does; any non-zero value reads as on |
| `SwhSlp` + `SlpMod` | `sleep` | Toggle | both `0` off, both `1` on |
| `Health` | `health` (ioniser) | Toggle | `0` off, `1` on |
| `Blo` | `x_fan` | Toggle | `0` off, `1` on. X-Fan keeps the fan running after cooling or dry mode to dry the coil. |

When the connector sets `SetTem` it also writes `TemUn = 0` (degrees Celsius) and `TemRec = 0` (no half-degree bit,
which only matters for Fahrenheit).

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

### Asked but not shown yet

`Air` (fresh air), `StHt` (8 °C heating), `HeatCoolType`, `SvSt` (energy saving) and `TemUn`/`TemRec` are read with
the status request but have no control yet.

## How the connector behaves

- **Discovery** sends the scan to the broadcast address, to every host the app already knows, and by default to
  every address of the /24 one by one, because many routers, mesh systems and access points do not forward
  broadcasts. It sends twice within the window and returns every unit that answered, once each.
- **Opening a unit** scans its saved address first and checks the MAC. If a different unit answers there (after a
  DHCP change, for example), it stops and asks you to search again rather than control the wrong unit.
- **The key** is fetched with bind every time a unit is opened and lives only in memory. It is never logged, shown
  or saved. The cipher version and the MAC as the unit spells it are saved; neither is secret.
- **Capabilities** are declared from what the unit answers on the first read: a column the unit does not return is
  left out, so its control does not appear.
- **Reads** never change the unit. A unit that stops answering reads as offline with its last known values.
- **Commands** are validated before anything is sent: an unknown setting or a temperature outside 16-30 °C is
  refused with a message.

## Quirks

- **Packet loss.** UDP has no retransmission and units on weak Wi-Fi drop packets. A read is sent up to four times
  while the unit stays silent. A command is sent at most twice, the second time only when no reply arrived at all,
  so a command the unit answered is never repeated.
- **Unknown column names.** Some older modules answer *nothing* to a status request that contains a column they do
  not know. The connector asks only for known columns.
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

## Security

The Gree LAN protocol has **no authentication**. Anyone on your Wi-Fi can scan, bind and control every unit, with or
without Home Controller. Protect your Wi-Fi with a strong password and keep guests on a separate network. See
[SECURITY.md](../../SECURITY.md).

## Credits

The protocol was documented by the open-source projects
[greeclimate](https://github.com/cmroche/greeclimate) (cmroche) and
[gree-remote](https://github.com/tomikaa87/gree-remote) (tomikaa87). The column names, codes and cipher parameters
above come from their work, confirmed against real units.
