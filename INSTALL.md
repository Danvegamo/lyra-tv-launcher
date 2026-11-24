# Installing Lyra

Plain-language guide for people who are not developers. Español: [INSTALL.es.md](INSTALL.es.md).

Lyra is a custom home screen for TV devices. It is not in any app store: you install ("sideload") an APK file yourself. It is free, open source (GPL-3.0) and has no ads or accounts.

> Honest summary: Lyra is built and tested only on a **Fire TV Stick 4K Max running Fire OS 7**. Other Fire TV models and Android TV / Google TV devices should be able to install and open it, but nobody has tested them yet. Amazon does not let you replace the Fire TV home screen, so on Fire TV Lyra opens *on top of* the Amazon home (see [step 4](#4-make-lyra-your-home-screen)).

## 1. What you need

- **A TV device:** a Fire TV (Fire TV Stick 4K, 4K Max, Fire TV Cube, or any Fire TV with Fire OS 7 or later) or an Android TV / Google TV device. The minimum is **Android 9 (API 28)**, which is Fire OS 7. Older devices cannot install it.
- **A remote.** The normal one is fine: the app is made for D-pad navigation and does not need a touchscreen.
- **Wi-Fi / internet** on the TV.
- **A PC or phone** is needed only for routes B and C below and for a few optional features. Route A works with just the TV.
- **A free TMDB key** (recommended, see [step 5](#5-add-your-api-keys)). Without it Lyra shows only sample rows.
- Optional: an AniList token, your own events server, and an IPTV app. **Lyra does not play IPTV and does not read playlists.** It can only open an IPTV app you already have and type a title into its search box (you set that app's package name in the code, see the README).

## 2. Choose how to install

### Route A: no PC, with the Downloader app (easiest)

1. On the TV, install **Downloader** (by AFTVnews) from the Amazon Appstore (Fire TV) or Google Play (Android TV).
2. Allow it to install apps:
   - **Fire TV:** *Settings > My Fire TV > Developer options > Install unknown apps > Downloader: ON*. If you do not see *Developer options*, open *Settings > My Fire TV > About*, select the device name and press OK 7 times.
   - **Android TV / Google TV:** *Settings > Apps > Security & restrictions > Unknown sources > Downloader* (the path varies by device).
3. Open Downloader, select the address box and type exactly:

   `https://github.com/Danvegamo/lyra-tv-launcher/releases/latest/download/lyra-tv-launcher.apk`

4. Press *Go*, wait for the download, choose **Install**, then **Open** (or find **Lyra** later in your apps).
5. Delete the downloaded APK when Downloader offers to; you do not need it any more.

The release APK has no API keys inside, so it shows sample rows until you add a key (step 5). It is signed with a debug key: Android accepts it, but an APK you build yourself is signed with a different key and will not install over it (uninstall first).

### Route B: from a PC with adb

1. On the TV: *Settings > My Fire TV > Developer options > ADB debugging: ON*. Write down the TV's IP: *Settings > My Fire TV > About > Network*.
2. On the PC install **adb** (Android platform-tools: <https://developer.android.com/tools/releases/platform-tools>). The PC and the TV must be on the same network.
3. Download `lyra-tv-launcher-<version>.apk` from the [latest release](https://github.com/Danvegamo/lyra-tv-launcher/releases/latest), then run:

   ```sh
   adb connect <tv-ip>:5555
   adb install -r lyra-tv-launcher-<version>.apk
   ```

   The TV asks you to allow the PC the first time: choose *Always allow*.
4. On Windows you can use the helper script instead. It also turns on the optional Home-button service: `.\instalar.ps1 -Tele <tv-ip> -Apk lyra-tv-launcher-<version>.apk`.

### Route C: build it yourself

You need **JDK 17 or newer** (21 recommended), the **Android SDK** (Android Studio, or only the command-line tools with platform 34) and **git**.

```sh
git clone https://github.com/Danvegamo/lyra-tv-launcher.git
cd lyra-tv-launcher
cp config.example.properties config.local.properties     # Windows: Copy-Item
# edit config.local.properties and add your keys (step 5)
./gradlew assembleDebug          # Windows: .\gradlew.bat assembleDebug
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. `./gradlew assembleRelease` gives a smaller, shrunk APK (`app/build/outputs/apk/release/app-release.apk`); it is also signed with the debug key, so it is meant for your own TV. Install it with route A (put it somewhere you can download it from) or route B. If Gradle cannot find the SDK, set `ANDROID_HOME` or create `local.properties` with `sdk.dir=<path to the Android SDK>`. A build made with your keys contains them: do not share that APK.

## 3. Check that it opens

Open **Lyra** from your app list. You should see the Lyra intro and then the home screen with rows. Move with the D-pad, press OK to open a title, and press the menu key (the three-lines button) for the per-title menu: trailer, mark as seen, don't recommend, My List. Inside Lyra, *Ajustes > Estado del sistema* (Settings > System status) shows what works and how to fix what does not. The in-app texts are in Spanish.

## 4. Make Lyra your home screen

This depends on your device and is the least tested part.

**Android TV / Google TV.** Lyra declares itself as a possible home app. Whether you can pick it depends on the device maker: some Android TV devices let you choose a default home app (in *Settings > Apps > Default apps*, or by asking which home app to use the first time you press Home), and many Google TV devices do not allow it at all. If your device offers the choice, pick Lyra. If it does not, Lyra still works as a normal app you open from the list. The Fire-specific features below do not apply.

**Fire TV (Fire OS).** Amazon does not allow changing the default home screen, and Lyra uses no exploit and no root. Instead Lyra offers an **optional accessibility service** ("Lyra: botón Home"): when the Amazon home screen appears, the service opens Lyra on top of it. It only watches window changes of a short list of apps (the Amazon launcher, your IPTV app, Crunchyroll, HBO Max and the Amazon keyboard) and reads screen contents only while a guided search is running. Honest limits:

- You may see the Amazon home for a moment before Lyra appears (a few seconds on the tested device; Lyra plays its intro to cover that gap).
- A Fire OS update can break it.
- "Force stop" on Lyra makes Fire OS turn the service off.

To turn the service on:

- **With the helper script (the tested way, Windows):** `.\instalar.ps1 -Tele <tv-ip>`. It installs the APK and enables the service.
- **By hand with adb (any OS):**

  ```sh
  adb shell settings put secure enabled_accessibility_services co.davidvega.launcher/co.davidvega.launcher.sistema.HomeAccessibilityService
  adb shell settings put secure accessibility_enabled 1
  adb shell pm grant co.davidvega.launcher android.permission.WRITE_SECURE_SETTINGS
  ```

  The first two lines change the TV's secure settings (the uninstall step below undoes them). The last line lets Lyra switch the service, and the "now playing" listener, back on by itself after a restart or an update.
- **Without a PC:** the service should be listed in the TV's *Settings > Accessibility* as "Lyra: botón Home"; turn it on there. This has not been tested on a real device, and Fire OS may hide it or reset it. If it does not work, use adb.

Optional extras (each needs adb once):

- Order your apps by how much you use them: `adb shell appops set co.davidvega.launcher GET_USAGE_STATS allow`.
- "Continue watching" and what is playing come from a notification-listener service ("Lyra: lo que se reproduce"). With the `WRITE_SECURE_SETTINGS` grant above, Lyra enables it by itself at the next restart or update.
- Nightly maintenance (off unless you authorize it): *Ajustes > Autorizar mantenimiento*, then accept "Always allow" on the adb prompt. At 4:30, if nobody is watching, it trims caches and reboots the TV over local adb. Turn it off in *Ajustes > Mantenimiento nocturno*. It needs network ADB debugging to stay on.

## 5. Add your API keys

Lyra needs data from the internet. All keys are **yours**; none ship with the app. The template is [`config.example.properties`](config.example.properties), with a comment on every line.

| Key | Needed? | How to get it |
| --- | --- | --- |
| `TMDB_API_KEY` or `TMDB_READ_TOKEN` | Recommended. Without one you only see sample rows. | Free account at <https://www.themoviedb.org>, then *Settings > API*, request a developer key. Use either the "API Key" (v3) or the "Read Access Token" (v4, starts with `eyJ`). |
| `ANILIST_TOKEN` | No. Marks finished Crunchyroll anime episodes as watched on your AniList account. | <https://anilist.co/settings/developer>. |
| `EVENTOS_URL`, `EVENTOS_TOKEN`, `EVENTOS_DISPOSITIVO` | No. Sends launcher events (views, searches, My List) to **your own** server. Empty means nothing is sent. | Only if you run your own HTTP endpoint. |

**If you built Lyra yourself** (route C), put the keys in `config.local.properties` before building.

**If you installed the release APK** (route A or B), give the keys to the app after installing. Create a text file named `config.local.properties`, for example:

```
TMDB_READ_TOKEN=eyJ...your-token...
```

and copy it to `/sdcard/Android/data/co.davidvega.launcher/files/` on the TV. With adb: `adb push config.local.properties /sdcard/Android/data/co.davidvega.launcher/files/`. Or use a file-manager app on the TV that can write there (on Android 11 and later, file managers cannot write to that folder; use adb). Lyra prefers that file over anything built in. Close Lyra completely and open it again.

Your taste profile (region, language, your streaming apps, titles you love) lives in `datos/gustos.json`. The README, section *Your taste profile*, explains how to change it, including without rebuilding.

## 6. Updating

- **Route A:** run Downloader again with the same address. Installing over the old version keeps your settings. If Android refuses because the signature differs, uninstall first.
- **Route B/C:** `adb install -r <new-apk>`. On Fire TV, `adb install -r` turns the accessibility service off: enable it again (step 4) or just use `instalar.ps1`.

## 7. Uninstalling and going back to stock

```sh
adb shell settings put secure enabled_accessibility_services ""
adb uninstall co.davidvega.launcher
```

The first line turns off all accessibility services (it clears the whole list, so turn back on any others you use). Without a PC: *Settings > Applications > Manage Installed Applications > Lyra > Uninstall*. The Amazon home comes back immediately; Lyra changes nothing else on the TV. If you set Lyra as the default home on Android TV, choose another home app first, or simply uninstall it.

## 8. Troubleshooting

- **The highlight gets lost or a screen cannot be navigated.** Press Back and enter the screen again. If it keeps happening, open an issue with the screen and the device. Lyra is operated with the D-pad only.
- **Black screen or the intro seems stuck.** Wait about 10 seconds, then press Back and open Lyra again. In *Ajustes > Intro* choose "ninguna" (none) to skip the video. Report your device model if it persists.
- **Only sample rows, no real recommendations.** The TMDB key is missing or wrong. Add it ([step 5](#5-add-your-api-keys)) and look at *Ajustes > Estado del sistema*.
- **Trailers do not play.** They are fetched from YouTube with NewPipeExtractor and need internet. YouTube changes often, so trailers can stop working until the library is updated. Check your connection first.
- **The Amazon home appears and Lyra does not open.** The accessibility service is off: turn it on again (step 4). It also turns off after "Force stop" or after `adb install -r`.
- **"App not installed".** Another build of Lyra with a different signature is installed (uninstall it first), or the device is older than Android 9.
- **Usage ordering, now playing or maintenance do nothing.** They need the one-time adb permissions from step 4. *Ajustes > Estado del sistema* lists what is missing.
- **adb cannot connect.** Check that ADB debugging is on, that the TV and the PC are on the same network, and that you accepted the prompt on the TV.

Found a bug? Open an issue at <https://github.com/Danvegamo/lyra-tv-launcher/issues>. Please do not include screenshots of your viewing history, IP addresses or account names.

*Lyra is not affiliated with Amazon, Google or any streaming service. Fire TV, Android TV and other names belong to their owners.*
