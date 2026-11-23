# Android TV Dolby Tuner

Unlock the hidden Dolby audio controls on an Android TV, without root.

Many MediaTek-based Android TVs (developed on a Hisense Smart TV, Android TV 11) run the
**Dolby MS12** audio engine. The TV's own Sound menu only shows a Dolby Atmos on/off switch, but the
engine underneath has a full set of controls: dialogue enhancer strength, volume leveller,
speaker virtualizer (virtual surround) angle and boost, DRC and more. The vendor service that drives
it, `com.dolby.android.audio.service`, is exported without a permission, so an ordinary app can
use it.

Dolby Tuner is a small, D-pad friendly app that does exactly that, plus a system-wide EQ, with
one-press listening profiles for YouTube.

> Not affiliated with Dolby or Hisense. Dolby, Dolby Atmos and MS12 are trademarks of Dolby
> Laboratories. This repo contains no Dolby code: the app loads the TV's own vendor library at runtime.

## Features

**Listening profiles** (top of the screen, one press, persist after you close the app and after a restart):

| Profile | Sound | Dolby | EQ frame |
|---|---|---|---|
| **Music** | Punchy 50-80 Hz, the TV's 100-250 Hz boom cut up to 15 dB, 2-2.5 kHz presence dip filled | Surround (angle 25, boost 72) | 80 ms |
| **Music wide** | Same EQ as Music | Maximum surround (angle 30, boost 96) | 80 ms |
| **Melodies** | Warm, smooth: rounded bass, low-mid body, softened 3-5 kHz, silky air | Gentle surround (20 / 48) | 80 ms |
| **Thunder** | +12 dB at 45-100 Hz, **no limiter, no headroom**: clips at high volume by design | Maximum surround | 80 ms |
| **Movies** | Big 50-80 Hz impact + dialogue presence at 2-4 kHz | Surround (25 / 80), dialogue enhancer 8 | 80 ms |
| **Podcasts & docs** | Bass boom cut up to 20 dB, speech presence +2..3 dB | Centred, dialogue enhancer 10, leveller 5 | 80 ms |
| **Night** | Little bass, voices forward | Centred, dialogue enhancer 10, leveller 8 | 80 ms |
| **Pure** | Neutral studio-style in-room curve, a reference for judging the others | No Dolby effects | 80 ms |

All profiles share one 80 ms EQ frame so a switch can update the running EQ in place (see
Gotchas). Longer frames give finer bass control but add audio delay; 80 ms is below the point
where most people notice lip-sync drift.

The EQ curves are **measured room corrections**, not guesses: the TV played sweeps, a MacBook
microphone recorded them at the listening seat, and the EQ was fitted to a target over three
closed-loop passes. Result on the development TV: within 1.7 dB (Music) and 2.6 dB (Voice) RMS of
target from 50 Hz to 12.5 kHz, down from 10.9 and 9.0 dB for hand-written curves. Full table in
[`tuning/results.md`](tuning/results.md). The curves fit one TV, room and seat; see
[Measure your own](#measure-your-own-room) to fit yours.

**Manual Dolby controls**, shown only when the TV reports the feature as supported:

- Speaker virtualizer mode (`DISABLE` / `ENABLE` / `ENABLE_FOR_ATMOS`), angle, surround boost
- Dialogue enhancer strength, and the separate AC-4 dialogue enhancer
- Volume leveller mode and strength
- DRC mode, boost and cut, for stereo and multichannel content
- Downmix mode, AAC reference level, PCM stream gain
- An engine info panel listing every range and supported feature your TV reports

Anything you set is saved and reapplied about 20 s after the TV boots.

## Requirements

- An Android TV (Android 11+, API 30) that has the package `com.dolby.android.audio.service`.
  Check with:
  ```sh
  adb shell pm list packages | grep com.dolby.android.audio.service
  ```
  If that prints nothing, the Dolby controls will not work on your TV. The EQ profiles still will.
- ADB access to the TV. On the TV: Settings > Device Preferences > About > press **Build** 7 times,
  then Developer options > **USB debugging** (and network debugging, if your TV has it). Then:
  ```sh
  adb connect <TV_IP>:5555      # accept the prompt on the TV
  adb devices                   # should list the TV as "device"
  ```
- To build: an Android SDK (build-tools + any platform from API 30 up) and JDK 11+. No Gradle.

## Build and install

```sh
git clone https://github.com/bgrgv/android_tv_dolby_tuner.git
cd android_tv_dolby_tuner
./build.sh                                   # writes build/DolbyTuner.apk
adb -s <TV_IP>:5555 install -r build/DolbyTuner.apk
adb -s <TV_IP>:5555 shell am start -n ai.brainback.dolbytuner/.MainActivity
```

`build.sh` finds the SDK from `ANDROID_HOME` / `ANDROID_SDK_ROOT`, Android Studio's default location,
or Homebrew's `android-commandlinetools`. It uses the newest build-tools and platform installed;
override with `BUILD_TOOLS=36.0.0 PLATFORM=android-34 ./build.sh`. The first build creates a
local `debug.keystore` (git-ignored) to sign the APK.

The app then appears in the TV's Apps list as a blue tile called **Dolby Tuner**.

## Use

1. Open Dolby Tuner with the remote (arrows to move, OK to press).
2. Press **Music**, **Music wide** or **Podcasts & docs**.
3. Go back to YouTube (or any app). The profile stays on. A small "Dolby Tuner" notification keeps
   the EQ alive.

Tips:

- Keep the TV's own Sound Mode on **Standard**, so the profiles start from a neutral base.
  Changing the TV's sound mode may reset the Dolby values; if so, press your profile again.
- If bass hits make the volume dip, lower the TV's own **Bass Boost** a step or two: that is the
  TV amp's limiter reacting.
- The EQ lowers the level a little to make room for the boosts, so expect to use a couple of
  volume steps more.

## Remote button (optional)

Turn a spare remote button into a sound switch: **short press** steps through the profiles in the
order above (a note pops up with the name), **long press** (about 1 s) opens Dolby Tuner.
It is an accessibility service that filters key events, so no root is needed.

The development remote's spare button sends Linux `KEY_MEDIA` (scan code 226; its stock job is
opening the TV's Media Center). To find yours, run `adb shell getevent -lt`, press the button, and
change `SCAN_CODE` in `KeyService.java`. Then turn the service on:

```sh
adb shell settings put secure enabled_accessibility_services ai.brainback.dolbytuner/ai.brainback.dolbytuner.KeyService
adb shell settings put secure accessibility_enabled 1
```

(If other accessibility services are already on, append ours to the existing value with a `:`.)
That remote sends no key repeats while a button is held, so long press is timed from the release.

## Undo

- **EQ off** stops the EQ.
- **Clear saved** forgets saved Dolby values; then change the TV's Sound Mode or restart the TV to
  return to stock Dolby settings.
- Remote button: `adb shell settings put secure enabled_accessibility_services ""`.
- Or uninstall: `adb uninstall ai.brainback.dolbytuner`.

## Measure your own room

The shipped curves suit one Hisense TV in one room. To fit yours you need a Mac at your seat
(any Mac with a microphone), Python 3 with `numpy` + `scipy`, and `swiftc` (Xcode command line tools).

```sh
export TV=<TV_IP>:5555 OUT=./out
M(){ python3 tools/measure.py "$@"; }; E(){ python3 tools/design_eq.py "$@"; }

# 1. Baseline: Dolby neutral, EQ off. Pause anything playing on the TV first.
M adb --es cmd dolby --es pairs "VirtualizerMode=e:DISABLE;VolumeLevelerMode=e:DISABLE;DialogueEnhancerStrength=i:0"
M adb --es cmd eq --es id off
M sweep neutral --level -18 && M analyze neutral      # check SNR in brackets is > 20 dB

# 2. Fit, push, measure, refit (repeat 2-3 times). Frame: 80 ms music, 60 ms voice.
E fit music neutral && E push music 80 && M sweep music_p1 --level -18 && E check music music_p1
cp out/music.json out/music_p1.json
E fit music music_p1 --prev out/music_p1.json && E push music 80 && M sweep music_p2 --level -18 && E check music music_p2
```

Then copy the `gains` from `out/music.json` (and `voice.json`) into `MUSIC_EQ` / `VOICE_EQ` in
`Profiles.java`, rebuild and reinstall. Targets live in `TARGETS` in `tools/design_eq.py`.

The TV side of this is the adb-only `CtrlReceiver` (protected by the `DUMP` permission, which only
the shell holds): it plays test sweeps through the real audio chain, sets Dolby values, and loads
custom EQ curves without rebuilding.

## How it works

```
YouTube / any app
      |  stereo PCM
      v
AudioFlinger mixer (AudioOut_D)  <-- EqService: DynamicsProcessing EQ + limiter on session 0
      |
      v
Audio HAL -> Dolby MS12 (DAP)    <-- Dolby service calls: virtualizer, dialogue enhancer, leveller...
      |
      v
TV amp -> speakers + woofer
```

- **Dolby side.** The app binds `com.dolby.android.audio.service/.DolbyAudioService` and loads the
  AIDL classes from `/vendor/framework/com.dolby.android.audio.jar` with a `PathClassLoader`, then
  calls `IDolbyAudioService` by reflection (`Dolby.java`).
- **EQ side.** A foreground service (`EqService.java`) attaches an AOSP `DynamicsProcessing` effect
  (31-band 1/3-octave pre-EQ + limiter) to the global output mix, session 0. Session 0 effects are deprecated
  but still honoured on Android 11. On the development TV the only output is one mixer thread, so
  every app, YouTube included, goes through it.
- **Virtual surround** is the Dolby speaker virtualizer. Android's own bundle `Virtualizer` is
  headphone-only and switches itself off on TV speakers.

## Gotchas found the hard way

- **DynamicsProcessing's default 10 ms frame cannot shape bass.** It is FFT-based; 10 ms gives
  ~100 Hz bins, so a -15 dB cut at 80-160 Hz measured as almost nothing. 40 ms worked for broad
  cuts, 80 ms for narrow ones (verified by cutting one band alone). Longer frames add audio delay.
- **ffmpeg's macOS (avfoundation) capture dropped about half the microphone buffers** and
  mislabelled the rate (96 kHz header, broken timeline), which wrecks sweep deconvolution. The
  measurement tool records with a small AVAudioEngine program instead (`tools/micrec.swift`).
- On the development TV the speakers + woofer + room already put **+29 dB at 125 Hz** over the
  midrange with the TV's Bass Boost at 5: "more bass" by ear was mostly boom. Measure before boosting.

- **Never delete and recreate the EQ to switch profiles.** Releasing the old DynamicsProcessing
  and creating a new one let the raw TV sound through for a moment: recorded on real YouTube
  music as a 10-15 dB bass burst at every button press, then a "drop" as the correction engaged.
  `EqService` now updates the live effect in place (same frame and band count), and when it must
  rebuild it enables the new effect before releasing the old one.
- **Register with the Dolby service once per process, never per action.**
  `DolbyAudioServiceClientManager.register()` calls `restoreDefaultState()` (asynchronously)
  whenever a new client token registers, and again on deregister and client death. Binding and
  registering per button press made every profile land and then revert a moment later.
  `DolbyClient.java` holds one never-unbound connection and waits 1.5 s after its own first
  registration for that reset to pass. Telltale in logcat: `MS12Server:
  setSpeakerVirtualizerStartFrequency() frequency 0 out of valid range` marks each reset.
- **The bind intent must carry the action `com.dolby.android.audio.service.IDolbyAudioService`.**
  `DolbyAudioService.onBind()` does `intent.getAction().equals(...)` with no null check, so binding
  with no action **crashes the Dolby service** (it restarts by itself). With any other action it
  returns no binder.
- `<uses-library android:name="com.dolby.android.audio">` did not put the classes on the app
  classpath on the development TV; hence the `PathClassLoader` fallback.
- Only the DRC strengths have getters. Dialogue enhancer, leveller and virtualizer values are
  write-only, so the manual controls show `?` until you set them.
- Ranges reported on the development TV: dialogue enhancer 0-16, AC-4 dialogue enhancer 0-12,
  leveller 0-10, virtualizer angle 5-30, surround boost 0-96, DRC 0-100, AAC reference level 0-127,
  PCM gain 0.0-1.0. Virtualizer start frequency and headphone reverb were unsupported.
- Many Android TVs only log errors. To see the app's log:
  `adb shell setprop log.tag.DolbyTuner V`, then `adb logcat -s DolbyTuner`.

## Project layout

```
AndroidManifest.xml
build.sh                      aapt2 / javac / d8 / apksigner build, no Gradle
res/drawable/banner.xml       launcher tile
res/xml/keyservice.xml        accessibility config for the remote button
tools/measure.py              sweep playback + recording + response analysis
tools/design_eq.py            target curves, room-correction fit, push to TV, check
tools/micrec.swift            reliable macOS mic recorder (AVAudioEngine)
tuning/                       fitted curves and before/after measurements
src/ai/brainback/dolbytuner/
  MainActivity.java           UI: profiles, presets, manual controls, engine info
  Dolby.java                  reflection wrapper over IDolbyAudioService
  DolbyClient.java            the single shared, never-unbound Dolby connection
  Profiles.java               listening profiles: Dolby values + EQ curves
  EqService.java              foreground service holding the global EQ
  BootReceiver.java           reapplies the active profile and saved values after boot
  CtrlReceiver.java           adb-only control: test signals, Dolby values, custom EQ, restore
  KeyService.java             remote button: short press = next profile, long press = open app
```

To change a profile, edit the numbers in `Profiles.java` (EQ gains are in dB per band; band edges
are in `EDGES`), rebuild and reinstall, then press the profile again.
