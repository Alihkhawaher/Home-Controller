# Branding

The Home Controller mark is a house with a thermostat-style dial inside it: the house is the home, the dial is
control, and the amber knob is the set point, the one thing you change. It is flat, uses one line weight, and reads
at launcher size.

![Home Controller icon](icon-512.png)

## Palette

| Role | Colour | Hex |
|---|---|---|
| Background | Deep navy | `#0E3A4F` |
| House, hub | White | `#FFFFFF` |
| Dial, active part | Cyan | `#5CE1E6` |
| Dial, rest of the track | Muted teal | `#2C6E80` |
| Knob, accents | Amber | `#FFB547` |

Graphics set their type in [Poppins](https://fonts.google.com/specimen/Poppins) (SemiBold for the name, Regular and
Medium for the tagline).

## Files

| File | What it is |
|---|---|
| `icon.svg` | Master icon, 512 x 512, full-bleed square: exactly what a launcher shows. |
| `icon-rounded.svg` | The icon in a rounded square, for the README and web pages. |
| `icon-512.png` | Store icon (Google Play, F-Droid). |
| `feature-graphic.png` | Store feature graphic, 1024 x 500. |
| `social-preview.png` | GitHub social preview, 1280 x 640. |
| `src/` | The HTML pages and the hub illustration the PNGs are rendered from. |
| `build.py` | Generates all of the above, and the app's launcher icon. |

The app's adaptive icon (`app/src/main/res/drawable/ic_launcher_foreground.xml`), its Android 13 themed icon
(`ic_launcher_monochrome.xml`) and the SVGs are written by `build.py` from one set of shapes, so they never drift
apart. The monochrome layer gives the knob real space on the track, because a themed icon has no background colour
to cut with. `build.py` stops with an error if the artwork leaves the adaptive icon's 66 dp safe zone.

## Regenerating

```sh
python branding/build.py            # SVGs and the launcher icon XML
python branding/build.py --render   # also the PNGs, copied into fastlane/
```

`--render` needs a Chromium-based browser (Microsoft Edge or Google Chrome; set `BROWSER` to its path if it is not
found) and the Poppins font installed. Check every PNG by eye after rendering.
