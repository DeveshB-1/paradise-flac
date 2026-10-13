# Paradise FLAC

A one-station Android player for **[Radio Paradise](https://radioparadise.com)**'s lossless main mix
(`stream.radioparadise.com/flac`, 16-bit / 44.1 kHz FLAC).

> Unofficial. Not affiliated with Radio Paradise. They're listener-supported:
> [radioparadise.com/support](https://radioparadise.com/support).

## Features

- **Lossless stream** decoded on-device with Media3 / ExoPlayer (Ogg FLAC → PCM, no transcoding).
- **Now playing**: title, artist, album, year and cover art from the Radio Paradise API, synced to song changes.
- **Background playback** with a media notification, lock-screen and Bluetooth/headset controls.
- **Signal path card** showing exactly what happens to the audio: stream format, decoder output,
  and the output device (Bluetooth / USB / wired / speaker) with an honest lossless verdict.
- **Bit-perfect USB** (Android 14+): toggle to hand 44.1 kHz/16-bit PCM straight to a USB DAC,
  bypassing Android's mixer and resampler.
- Live-radio behaviour: resuming after a pause jumps back to "now", and dropped connections retry with backoff.

## About "lossless" on Android

| Output | What you get |
| --- | --- |
| USB DAC + bit-perfect on | Bit-perfect, untouched samples |
| USB DAC / wired jack | Lossless, but Android's mixer usually resamples to 48 kHz |
| Bluetooth | Re-encoded by the Bluetooth codec (lossy) — **except aptX Lossless**, which needs a Snapdragon Sound phone and compatible earbuds (e.g. Sennheiser MOMENTUM True Wireless 4) |

## Install

Grab `ParadiseFLAC.apk` from [Releases](../../releases) (or the latest CI run's artifact) and sideload it.
Requires Android 10+.

## Build

```sh
./gradlew assembleRelease   # JDK 17–21, Android SDK 36
```

Release signing reads `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`)
or the `KEYSTORE_FILE` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` env vars; without either it
falls back to the debug key. CI builds every push and attaches the APK to `v*` tag releases.
