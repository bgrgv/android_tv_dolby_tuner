# Dolby Tuner

Personal Android TV app for the Hisense Smart TV (`songshan`, Android TV 11, MediaTek/MStar m7632, TAS5751 amp, 2.1 speakers).
It drives the TV's Dolby MS12 engine through the vendor service `com.dolby.android.audio.service`,
which is exported without a permission.

- Build: `./build.sh` (aapt2 + javac + d8 + apksigner, no Gradle). Output `build/DolbyTuner.apk`.
- Install: `adb -s 192.168.0.10:5555 install -r build/DolbyTuner.apk`
- Logs: `adb shell setprop log.tag.DolbyTuner V`, then `adb logcat -s DolbyTuner` (the TV filters to errors otherwise).
- Undo everything: "Clear saved" in the app, then change sound mode or restart the TV. Or uninstall.

## Gotchas

- Bind intent MUST carry action `com.dolby.android.audio.service.IDolbyAudioService`.
  `DolbyAudioService.onBind()` does `intent.getAction().equals(...)` with no null check: a bind
  without an action crashes the Dolby service.
- The AIDL classes are not on the app classpath via `<uses-library>`; they load through a
  `PathClassLoader` on `/vendor/framework/com.dolby.android.audio.jar`.
- Only DRC strengths have getters. Dialogue enhancer, leveller, virtualizer values are write-only,
  so the app shows `?` until you set them, and stores what you set.
- Unsupported on this TV: virtualizer start frequency, headphone reverb (hidden in the UI).
- Ranges reported: dialogue enhancer 0-16, AC-4 DE 0-12, leveller 0-10, virtualizer angle 5-30,
  surround boost 0-96, DRC 0-100, AAC ref level 0-127, PCM gain 0.0-1.0.
- Shell `setParameters` on audio_flinger is refused (-1); reads work (`diaglogue_enhancement`, etc.).

## YouTube profiles (EqService)

- Music / Music wide / Podcasts & docs: Dolby values + a global DynamicsProcessing EQ + limiter on
  session 0 (curves in `Profiles.java`). The TV has a single MIXER output (AudioOut_D), so YouTube's
  track goes through it; verified with `dumpsys media.audio_flinger`.
- EqService is a foreground service: the EQ survives closing the app. Force-stopping the app kills it;
  boot reapplies the active profile.
- The AOSP bundle Virtualizer is headphone-only, so virtual surround = the Dolby speaker virtualizer.
