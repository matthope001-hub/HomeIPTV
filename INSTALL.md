# Getting TV Dash from your computer onto the TV

For the RCA 55" 4K Google TV (RTGU5570), or any Google TV / Android TV.
You build the app on your computer once, then send it to the TV over Wi-Fi (option A), a USB stick (option B), or straight from Android Studio (option C).

## 1. Build the app on your computer

1. Install [Android Studio](https://developer.android.com/studio).
2. Download this repo: **Code ▸ Download ZIP** on GitHub and unzip it, or `git clone https://github.com/matthope001-hub/HomeIPTV`.
3. In Android Studio choose **Open** and pick the `HomeIPTV` folder. Wait for "Gradle sync" to finish.
4. Menu **Build ▸ Build App Bundle(s) / APK(s) ▸ Build APK(s)**.
5. When it finishes, click **locate** in the pop-up. The file is:
   `app/build/outputs/apk/debug/app-debug.apk`

### Goal horn
The Avalanche goal horn is included at `app/src/main/res/raw/goal_horn.mp3` and is built into the app. To use a different sound, replace that file (keep the name lowercase, `.mp3`, `.ogg` or `.wav`) and build again. Only the first 10 seconds play, fading out at the end. Delete the file to go back to the horn generated in code.

## 2. Get the TV ready (once)

1. **Turn on Developer options:** on the TV go to **Settings ▸ System ▸ About**, scroll to **Android TV OS build** and press OK on it 7 times until it says you're a developer.
2. **Turn on debugging:** **Settings ▸ System ▸ Developer options ▸ USB debugging** → On. (Needed for options A and C.)
3. **Find the TV's address:** **Settings ▸ Network & Internet ▸** your Wi-Fi network. Note the IP address, e.g. `192.168.1.20`. TV Dash also shows it in its header once it's running.

## Option A: Over Wi-Fi with ADB (recommended, also best for updates)

1. On your computer install **Android SDK Platform-Tools** (Android Studio: **Tools ▸ SDK Manager ▸ SDK Tools ▸ Android SDK Platform-Tools**), or download them from developer.android.com/tools/releases/platform-tools.
2. Open a terminal in the platform-tools folder (or anywhere, if `adb` is on your PATH) and connect:
   ```bash
   adb connect 192.168.1.20:5555
   ```
   The TV shows **Allow USB debugging?** Tick **Always allow from this computer** and choose **Allow**. Run the command again if it said "failed to authenticate".
3. Install (from the `HomeIPTV` folder):
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
   It prints `Success`.

**If `adb connect` is refused** (some newer Google TV updates): on the TV open **Developer options ▸ Wireless debugging** → On ▸ **Pair device with pairing code**. It shows an address, port and code:
```bash
adb pair 192.168.1.20:37000
```
Type the code, then `adb connect` to the address and port shown on the **Wireless debugging** screen (not the pairing one), and install as above.

## Option B: USB stick, no computer tools

1. Copy `app-debug.apk` onto a USB stick and plug it into the TV's USB port.
2. On the TV, install a file manager from the Play Store (for example **File Commander** or **X-plore File Manager**).
3. Allow it to install apps: **Settings ▸ Apps ▸ Security & restrictions ▸ Unknown sources** → turn on for that file manager.
4. Open the file manager, find the APK on the USB stick and choose **Install**.

## Option C: Run straight from Android Studio

1. Connect with `adb connect 192.168.1.20:5555` (or pair as in option A).
2. The TV appears in the device list at the top of Android Studio. Pick it and press the green **Run ▶**. It installs and opens TV Dash.

## Open it on the TV

**Apps ▸ See all** (or Your apps) ▸ **TV Dash**. To keep it handy, long-press it and choose **Add to favourites** so it shows on the Google TV home screen.

## Updating to a new version

Pull or download the latest code, build again (step 1), then repeat your install option. `adb install -r` keeps your favourites, tile order and phone-setup settings.

## Troubleshooting

- **"App not installed"** with option B: a version signed on a different computer is already on the TV. Uninstall TV Dash first (**Settings ▸ Apps ▸ TV Dash ▸ Uninstall**), then install again. This clears its settings.
- **`adb` can't find the TV:** the computer and TV must be on the same network; check the IP again (it can change after a router restart).
- **Phone setup page won't open:** the phone must be on the same Wi-Fi as the TV, and TV Dash must be open.
