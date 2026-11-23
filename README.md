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

| | Music | Music wide | Podcasts & docs |
|---|---|---|---|
| EQ | +7 / +5.5 dB at 40-80 / 80-160 Hz (subwoofer), mud cut at 315-630 Hz, +2.5 to +3 dB at 2.5-20 kHz | same | Rumble cut below 160 Hz, +2 to +3.5 dB at 1.25-5 kHz (speech presence) |
| Dolby virtual surround | On, angle 25, boost 72 | On, maximum (angle 30, boost 96) | Off (voice stays centred) |
| Dialogue enhancer | 0 | 0 | 10 / 16 |
| Volume leveller | Off | Off | On, 5 / 10 |

Every profile has -3 to -7 dB of input headroom and a limiter, so bass boosts do not clip.

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

## Undo

- **EQ off** stops the EQ.
- **Clear saved** forgets saved Dolby values; then change the TV's Sound Mode or restart the TV to
  return to stock Dolby settings.
- Or uninstall: `adb uninstall ai.brainback.dolbytuner`.

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
  (10-band pre-EQ + limiter) to the global output mix, session 0. Session 0 effects are deprecated
  but still honoured on Android 11. On the development TV the only output is one mixer thread, so
  every app, YouTube included, goes through it.
- **Virtual surround** is the Dolby speaker virtualizer. Android's own bundle `Virtualizer` is
  headphone-only and switches itself off on TV speakers.

## Gotchas found the hard way

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
src/ai/brainback/dolbytuner/
  MainActivity.java           UI: profiles, presets, manual controls, engine info
  Dolby.java                  reflection wrapper over IDolbyAudioService
  Profiles.java               listening profiles: Dolby values + EQ curves
  EqService.java              foreground service holding the global EQ
  BootReceiver.java           reapplies the active profile and saved values after boot
```

To change a profile, edit the numbers in `Profiles.java` (EQ gains are in dB per band; band edges
are in `EDGES`), rebuild and reinstall, then press the profile again.
