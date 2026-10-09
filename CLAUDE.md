# Talking Clock (Android)

Package `com.example.timespeaker`. Kotlin, minSdk 26, targetSdk 34, Material 1.12.
No `INTERNET` permission, deliberately.

## Build and install

```sh
ANDROID_HOME=~/Library/Android/sdk ./gradlew -q :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

There is no `local.properties` here, so pass `ANDROID_HOME` as above. Builds are
debug-signed with this Mac's `~/.android/debug.keystore`; an install signed with a
different key must be uninstalled first (that wipes the app's settings).

## Releases

1. Commit the change(s), then a separate `Release X.Y` commit bumping `versionCode`
   and `versionName` in `app/build.gradle.kts`.
2. Tag `vX.Y`, push `main` and the tag.
3. Build, copy the APK to `apk/time-speaker-debug.apk` (ignored by git), and check it
   with `aapt dump badging`.
4. The GitHub release is created by hand in the browser (no `gh` CLI on this Mac):
   `https://github.com/sant527/talking-clock-android/releases/new?tag=vX.Y`. The
   asset must be named exactly `time-speaker-debug.apk` — the README's "latest"
   download link depends on it.

Before committing, check `git config user.email` is set (repo-local:
`sant527 <sant527@users.noreply.github.com>`). It has gone missing before, and a
failed commit followed by `git tag` + push published a tag on the wrong commit. Run
release steps under `set -e`.

## Where things live

- `TimeAnnouncerService` — schedules alarms, decides what is due (announcement,
  major, countdown), speaks and vibrates.
- `SpeechOutput` — the only audio path, shared by the service and the settings
  preview. Owns sound-output routing, per-device volume and the >100% boost.
- `Silencer` — lets a key press cut an announcement short (media session + screen
  on/off), and holds audio focus while speaking.
- `Vibration` — the only vibration path, shared with the settings preview.
- `Prefs` — every setting. Per-announcement settings take a `SpeechProfile`
  (`MAIN`, `COUNTDOWN`, `MAJOR`) and store under a key suffix.
- `SettingsActivity` — one screen; the profile selector swaps which profile the
  controls below edit.

## Decisions worth keeping

- **Each profile has its own style** (speak / vibrate / both), including the
  countdown. The service must read the profile's own style, never MAIN's.
- **Audio focus is `GAIN_TRANSIENT_MAY_DUCK`, and only for speech.** Plain transient
  focus paused players, and under Xiaomi's Ultra battery saver they never resumed.
  Vibration takes no focus at all: players such as MX Player pause on any duck
  request, and the countdown buzzes every minute. Speech's focus can be switched off
  ("Lower other audio while speaking"); without focus the volume keys no longer reach
  the `Silencer`, the power key still does.
- **A vibrate-only announcement must release focus when the buzz ends**
  (`endVibration`) — there is no utterance to do it.
- **Sound output:** the speech engine cannot target a device. Speaker-only, and
  Bluetooth + speaker at different volumes, render to a file and play it through
  `MediaPlayer.setPreferredDevice` (API 28+). Bluetooth + speaker at one volume uses
  `STREAM_ALARM`, which Android plays on both. Bluetooth-only stays silent with
  nothing connected.
- **Volume** runs 0–500%. Above 100% is `LoudnessEnhancer` gain on the
  announcement's own audio session. Overrides, outermost first: "Same volume for all
  announcements", then "Bluetooth uses the normal volume", then each profile's
  "Separate volume for Bluetooth".
- The Bluetooth volume control is styled apart (violet `?attr/bluetoothAccent`, boxed,
  round thumb) because it sits directly below the ordinary volume. Two palettes are
  already blue, hence violet.
- Clock text auto-sizes on one line: at large system font sizes a fixed size wrapped
  and the fixed-height block cut off the seconds.

## Testing on a phone

Nothing here is covered by tests; changes are checked on a real device over adb
(`uiautomator dump`, `screencap`, `dumpsys vibrator_manager`, `dumpsys audio`,
`run-as com.example.timespeaker cat shared_prefs/time_speaker.xml`). Settings preview
plays sound, so avoid tapping sliders blind — a stray tap once set a volume to 500%.
