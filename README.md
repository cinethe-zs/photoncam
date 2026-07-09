<img src="logo.svg" alt="PhotonCam" width="480"/>

An Android film camera emulator. Select a film stock, shoot through a vintage rangefinder viewfinder, and get processed images with authentic analog characteristics — grain, light leaks, date imprint, and per-film colour grading via 3D LUTs.

---

## Features

- **73 film stocks** across 8 brands — each with a unique 3D LUT, grain profile, and colour character
- **Rangefinder UI** — physical camera body aesthetic with D-pad EV control, lens selector ring, and shutter button
- **Live exposure readout** — optional viewfinder overlay showing real-time shutter speed, aperture, and ISO
- **Flash** — hardware flash on back camera; screen flash (full white, max brightness) on front camera
- **Grain & light leaks** — procedural per-shot analog artefacts
- **Date imprint** — configurable 1990s-style timestamp: format, colour, font (LED 7-segment), size, position, glow, blur, opacity, layer repeat
- **Level indicator** — gravity sensor overlay showing a rotating horizon bar; turns green when level at 0°/90°/180°/270°
- **Grid overlays** — 3×3, 4×4, 9×9, Golden Ratio (φ), and Diagonal guide grids; toggled from settings
- **Film canister button** — film selector shows brand/name in large text with an oval porthole showing the authentic canister body colour
- **Favorites** — star any film stock, filter to favorites in the selector
- **Shot counter** — cumulative shot count since install + per-roll frame counter (36 exp.)
- **Lens selector** — ultrawide / main / telephoto / front camera via D-pad or tap
- **Background processing** — WorkManager foreground service; photo develops even if app is closed
- **VIEW / SHARE** — always available if any PhotonCam photo exists in the gallery

---

## Film stocks

### Kodak

| Stock | ISO | Category | Character |
|---|---|---|---|
| Kodachrome 25 | 25 | Slide | The immortal slide film. Saturated reds that never fade. |
| Kodachrome 64 | 64 | Slide | The working photographer's Kodachrome. Rich, precise. |
| Kodachrome 200 | 200 | Slide | Kodachrome at speed. Vivid colour, iconic contrast. |
| Ektar 100 | 100 | Color | Ultra-fine grain, hyper-saturated landscapes. |
| Ektachrome 100 VS | 100 | Slide | Vivid Saturation slide. Maximum color punch. |
| Ektachrome E100 | 100 | Slide | Cool slide precision. Blues that cut like glass. |
| Elite Chrome 100 XPro | 100 | Cross Process | Slide film processed wrong. Teal, orange, electric. |
| Elite Color 200 | 200 | Color | Consumer slide quality at print film price. |
| ColorPlus 200 | 200 | Color | Affordable warmth. The corner-store roll. |
| Gold 200 | 200 | Color | Sunny, golden-hour warmth. The family holiday roll. |
| Portra 160 | 160 | Color | Portrait softness, half the grain of 400. |
| Portra 400 | 400 | Color | Warm, flattering skin tones. The portrait standard. |
| Portra 400 NC | 400 | Color | Natural Color — subtle palette, faithful skin tones. |
| Portra 400 UC | 400 | Color | Ultra Color — vibrant, punchy, push-processing style. |
| Portra 400 VC | 400 | Color | Vivid Color — richer tones with portrait softness. |
| Portra 800 | 800 | Color | Available-light portrait. Warm and a little gritty. |
| Ultramax 400 | 400 | Color | Warm grain, every situation. The tourist's choice. |
| T-MAX 100 | 100 | B&W | Clinical sharpness, near-invisible grain. |
| T-MAX 400 | 400 | B&W | The flexible B&W standard. Speed with precision. |
| T-MAX 3200 | 3200 | B&W | Nighttime B&W. Extreme grain, deep blacks. |
| Tri-X 400 | 400 | B&W | Gritty grain, deep blacks. Street photography icon. |

### Fujifilm

| Stock | ISO | Category | Character |
|---|---|---|---|
| Velvia 50 | 50 | Slide | Punchy greens, electric reds. Landscape obsession. |
| Provia 100F | 100 | Slide | Neutral, accurate slide film. Faithful reproduction. |
| Superia 100 | 100 | Color | Clean, cool everyday color. Fine grain consumer. |
| 160C | 160 | Color | Studio softness. Pastel, cool, perfect skin. |
| Superia 200 | 200 | Color | Cool, clean 200-speed everyday film. Reliable and versatile. |
| Superia 200 XPro | 200 | Cross Process | C-41 run wrong. Teal, yellow, unpredictable. |
| Pro 400H | 400 | Color | Airy pastel portraits. The fashion studio standard. |
| Superia 400 | 400 | Color | Cool shadows, vivid highlights. Everyday versatility. |
| 800Z | 800 | Color | High-speed warmth. Events and dim rooms. |
| Superia 800 | 800 | Color | Fast consumer film. Cool shadows, warm highlights. |
| Superia 1600 | 1600 | Color | Low light in cool tones. Grain tells the story. |
| Natura 1600 | 1600 | Color | Available-light grain. Warm, alive, imperfect. |
| Acros 100 | 100 | B&W | The finest grain in B&W. Shadow gradation perfection. |
| Neopan 400 | 400 | B&W | Warm grays, smooth gradation. Japanese street soul. |
| Neopan 1600 | 1600 | B&W | Pushed-to-the-limit B&W. Grain and shadow. |

### Ilford

| Stock | ISO | Category | Character |
|---|---|---|---|
| Pan F Plus 50 | 50 | B&W | Near grain-free. Maximum tonal range for slow light. |
| Delta 100 | 100 | B&W | Delta perfection at 100. Silky grain, wide latitude. |
| FP4 Plus | 125 | B&W | Fine grain, wide latitude. The studio workhorse. |
| Delta 400 | 400 | B&W | The versatile Delta. Reportage with latitude. |
| HP5 Plus | 400 | B&W | Classic reportage grain. Push to 3200 in your mind. |
| XP2 Super | 400 | B&W | C-41 B&W. Lab-friendly, smooth grain at any exposure. |
| HPS 800 | 800 | B&W | Discontinued speed legend. High grain, strong character. |
| Delta 3200 | 3200 | B&W | Extreme grain, dramatic shadows. Low light legend. |
| SFX 200 | 200 | Infrared | Near-infrared: glowing foliage, black skies. |

### CineStill

| Stock | ISO | Category | Character |
|---|---|---|---|
| 50D | 50 | Color | Cinema precision in daylight. Ultra-fine, vivid. |
| 800T | 800 | Color | Cinema grain, halation glow. Neon nights. |

### Lomography

| Stock | ISO | Category | Character |
|---|---|---|---|
| LomoChrome Purple | 400 | Color | Greens become purple. Reality optional. |
| LomoChrome Metropolis | 400 | Color | Urban decay. Muted tones, olive shadows. |
| Cross Process | 200 | Cross Process | C-41 soup in E-6 chemistry. Unpredictable art. |
| Redscale XR | 200 | Cross Process | Exposed through the base. All red, no rules. |
| X-Pro Slide 200 | 200 | Cross Process | Slide film cross-processed. Electric cyan and yellow. |

### Agfa

| Stock | ISO | Category | Character |
|---|---|---|---|
| APX 100 | 100 | B&W | European B&W precision. Clean, neutral, accurate. |
| Precisa CT 100 | 100 | Slide | Cross-process teal & orange. Slide film gone wild. |
| Ultra Color 100 | 100 | Color | Ultra-vivid European color. Bold, saturated, punchy. |
| Vista 200 | 200 | Color | Warm, vivid, affordable. European 90s color. |

### Rollei

| Stock | ISO | Category | Character |
|---|---|---|---|
| Ortho 25 | 25 | B&W | Orthochromatic sensitivity. Blue sky burns, skin glows. |
| Retro 80S | 80 | B&W | 80s archive aesthetics. Warm B&W, soft tonality. |
| Retro 100 Tonal | 100 | B&W | Retro tonal gradation. Smooth B&W for any subject. |
| Infrared 400 | 400 | Infrared | Infrared B&W. White foliage, black skies, drama. |

### Polaroid

| Stock | ISO | Category | Character |
|---|---|---|---|
| 665 | 75 | Instant | Positive/negative pack film. Archival B&W prints. |
| 669 | 80 | Instant | Classic pack film. Warm brown, soft edges, instant magic. |
| 690 | 100 | Instant | Last generation pack film. Warm, dreamy, classic. |
| PX-100 UV+ Cold | 100 | Instant | Cold B&W integral film. Icy shadows, ethereal tone. |
| Time Zero Expired | 100 | Instant | Expired dreams. Unpredictable shifts, ghostly glow. |
| 672 | 160 | Instant | Pro pack film, warm and rich. Reliable instant magic. |
| PX-680 | 160 | Instant | 600 film reborn. Dreamy fade, unexpected colour shifts. |
| PX-70 | 160 | Instant | SX-70 at its warmest. Vintage instant nostalgia. |
| 667 | 800 | Instant | Coaterless high-speed B&W pack film. Raw and immediate. |

---

## Date imprint

Burns a configurable 1990s-style timestamp into each photo — applied to raw pixels before colour grading so the LUT ages the stamp alongside the image.

| Parameter | Options | Default |
|---|---|---|
| Style | `CLASSIC` (DEC 24 '95) · `NUMERIC_US` · `NUMERIC_EU` · `YEAR_FIRST` · `WITH_TIME` · `YY_MM_DD` · `YYYY_MM_DD` | CLASSIC |
| Colour | AMBER · RED · WHITE · GOLD · GREEN · CYAN | AMBER |
| Font | LED (7-segment) · MONO · BOLD · SERIF · COND | LED |
| Size | SMALL · MED · LARGE | MED |
| Position | BOT-R · BOT-L · BOT-C · TOP-R · TOP-L | BOT-R |
| Glow | 0–100 % — bloom halo radius | 100 % |
| Blur | 0–100 % — soft-focus diffusion radius | 50 % |
| Opacity | 0–100 % | 50 % |
| Blur layers | 0–20 — repeat count to intensify blur | 3 |

---

## Build

```bash
./gradlew assembleDebug    # → app/build/outputs/apk/debug/photoncam-v1.4.0.apk
./gradlew installDebug     # install on device / emulator
```

Requires Android SDK, min API 26 (Android 8.0).

---

## Third-party credits

- **Hald CLUT film simulations** — 3D colour lookup tables used for per-film colour grading.
  © cedeber — [github.com/cedeber/hald-clut](https://github.com/cedeber/hald-clut) — GNU General Public License v3.0.
  See [`LICENSES/GPL-3.0.txt`](LICENSES/GPL-3.0.txt) for the full license text.

- **DSEG7 font** — 7-segment LED display typeface used for the date imprint LED style.
  © 2020 Keshikan — [github.com/keshikan/DSEG](https://github.com/keshikan/DSEG) — SIL Open Font License 1.1.

---

## Disclaimer

The trademarked names which may appear in the filenames of the HaldCLUT/FilmSimulation images are there for informational purposes only. They serve only to inform the user which film stock the given HaldCLUT/FilmSimulation image is designed to approximate. As there is no way to convey this information other than by using the trademarked name, we believe this constitutes fair use. Neither the publisher nor the authors are affiliated with or endorsed by the companies that own the trademarks.
