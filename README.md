# btPhoneboard

Use your Android phone as a Bluetooth keyboard and trackpad for an Android TV,
tablet, or anything else that accepts a Bluetooth keyboard and mouse.

The phone shows a landscape keyboard with a small trackpad. To the TV it looks
exactly like a normal Bluetooth keyboard + mouse, so **nothing needs to be
installed on the TV or tablet**.

## Download

GitHub builds the app automatically on every push. On your phone, open
**https://github.com/ronmurphy/btPhoneboard/releases/latest/download/btPhoneboard.apk**
(always the newest build), then open the downloaded file to install it. The
first time, Android asks you to allow installing apps from your browser.

All builds are listed on the
[Releases page](https://github.com/ronmurphy/btPhoneboard/releases). The build
itself is defined in `.github/workflows/build.yml`. You can watch it run, or
start it by hand, from the repo's **Actions** tab.

## Requirements

- An Android phone running **Android 9 or newer**. The app has two modes:
  - **Classic** uses Android's built-in `BluetoothHidDevice` feature. Some
    phone makers (Motorola, for one) switch it off.
  - **BLE** builds a Bluetooth Low Energy keyboard ("HID over GATT") from
    general BLE features, so it works on phones where Classic doesn't.

  The app tries Classic first and switches to BLE by itself if Classic isn't
  available. You can also switch by hand in **Devices**.
- **Not iPhone**: iOS doesn't let apps act as a Bluetooth keyboard.
- The TV/tablet: Android TV / Google TV and Android tablets get both keyboard
  and mouse pointer. Windows, macOS, Linux, ChromeOS and iPad usually work too.
  Fire TV accepts the keyboard but doesn't show a mouse pointer on its own.

## Build it yourself (optional)

1. Install [Android Studio](https://developer.android.com/studio).
2. Get the code: **File → New → Project from Version Control**, paste
   `https://github.com/ronmurphy/btPhoneboard`, then switch to the branch with
   the code (bottom-right branch menu) if it isn't on `main` yet.
3. Let Gradle sync. Android Studio downloads the Android SDK pieces it needs.
   If it offers to upgrade the Android Gradle Plugin or library versions, that's
   fine to accept.
4. On the phone, turn on USB debugging:
   - Settings → About phone → tap **Build number** 7 times.
   - Settings → System → Developer options → **USB debugging** on.
5. Plug the phone in, pick it in the device menu at the top of Android Studio,
   and press **Run ▶**.

## Pairing

The tag next to the status dot says which mode the app is in.

### BLE mode

1. Open the app and allow the **Nearby devices** permission. The status says
   *Waiting: pair "your phone's name"*.
2. On the tablet: **Settings → Connected devices → Pair new device**.
   On a TV: **Settings → Remotes & Accessories → Pair accessory**.
3. Pick your phone's name and accept the pairing request on both screens.
   Android often puts the phone's request in a **notification** instead of a
   pop-up. Because the app is full screen, swipe down from the top twice to
   see it, then tap Pair/Accept. Be quick, since the request times out.
4. The status turns green with the tablet's name.

After that the tablet reconnects by itself whenever the app is open. If
pairing fails, "forget" the phone on the tablet and the tablet on the phone,
then try again.

If something goes wrong, **Devices → Show Bluetooth log** lists what
happened (connections, pairing, what the tablet read). A screenshot of it is
the best bug report.

### Classic mode

1. Open the app and allow the **Nearby devices** permission.
2. Tap **Devices → Make discoverable** (the phone is visible for 2 minutes).
3. On the TV: **Settings → Remotes & Accessories → Pair accessory**, and pick
   the phone. Accept the pairing code on both screens.
4. The status at the top turns green with the TV's name.

If the TV doesn't list the phone, pair from the phone instead (Devices →
Bluetooth settings → Pair new device, with the TV in its own Bluetooth pairing
screen), then come back to the app, open **Devices**, and tap the TV.

After the first time, the app reconnects to the last device automatically when
it starts.

## Controls

| Where | What it does |
|---|---|
| Keys | Held down as long as your finger is on them, so holding a key repeats on the TV |
| Shift / Ctrl / Alt / Meta | Tap once: applies to the next key. Tap twice: locked on. Tap again: off |
| Fn | Number row becomes F1–F12, arrows become Home / End / PgUp / PgDn, Del becomes Insert |
| Esc | Acts as **Back** on Android |
| Top row | Back, Home, previous / play-pause / next, volume down / mute / up |
| Trackpad, 1 finger | Move the pointer. Tap = left click |
| Trackpad, 2 fingers | Scroll. Tap = right click (3-finger tap = middle click) |
| Left / Right buttons | Hold Left and drag on the trackpad with another finger to select or drag |
| Speed | Cycles the pointer speed (0.5× to 3×) |
| Back button | Press twice to exit (exiting disconnects from the TV) |

## Project tour

Everything is under `app/src/main/java/com/ronmurphy/btphoneboard/`:

| File | What's in it |
|---|---|
| `hid/HidDescriptor.kt` | The HID report descriptor: the bytes that tell the TV "I'm a keyboard + mouse + media keys" |
| `hid/HidKeys.kt` | Key codes (HID usage IDs) for keys, modifiers, mouse buttons and media keys |
| `hid/InputModel.kt` | Turns "key down", "finger moved" etc. into HID reports. Plain Kotlin, no Android, unit tested |
| `hid/MouseReports.kt` | Merges queued mouse movements so BLE mode doesn't lag |
| `bluetooth/HidLink.kt` | The `HidLink` interface both modes implement, plus the status and mode types |
| `bluetooth/BluetoothKeyboard.kt` | Picks Classic or BLE, falls back to BLE automatically, handles Bluetooth on/off |
| `bluetooth/ClassicHidLink.kt` | Classic mode: registers with Android's `BluetoothHidDevice` API and sends reports |
| `bluetooth/BleHidLink.kt` | BLE mode: a GATT server with the HID service, advertising, and notifications |
| `PhoneboardViewModel.kt` | Connects the UI to the Bluetooth code, and survives screen rotation |
| `MainActivity.kt` | Permissions, full-screen mode, and opening system screens |
| `ui/KeyboardLayout.kt` | The keyboard layout as data (rows of keys) |
| `ui/Keyboard.kt` | Draws the keyboard (Jetpack Compose). Open it in Split view to see a preview |
| `ui/Trackpad.kt` | Trackpad gestures: move, scroll, tap to click |
| `ui/PhoneboardScreen.kt` | The full screen layout, status bar and Devices dialog |

## Tests

The HID logic has unit tests in `app/src/test/`. Run them from Android Studio
(right-click the `test` folder → Run Tests), or from a terminal:

```
./gradlew test
```
