# btPhoneboard

Use your Android phone as a Bluetooth keyboard and trackpad for an Android TV,
tablet, or anything else that accepts a Bluetooth keyboard and mouse.

The phone shows a landscape keyboard with a small trackpad. To the TV it looks
exactly like a normal Bluetooth keyboard + mouse, so **nothing needs to be
installed on the TV or tablet**.

## Requirements

- An Android phone running **Android 9 or newer** (it uses the
  `BluetoothHidDevice` API added in Android 9). A few phone makers switch this
  feature off; the app tells you if yours did.
- **Not iPhone**: iOS doesn't let apps act as a Bluetooth keyboard.
- The TV/tablet: Android TV / Google TV and Android tablets get both keyboard
  and mouse pointer. Windows, macOS, Linux, ChromeOS and iPad usually work too.
  Fire TV accepts the keyboard but doesn't show a mouse pointer on its own.

## Build and install

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

## Pairing with a TV

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
| `bluetooth/HidController.kt` | Registers with Android's `BluetoothHidDevice` API, connects, and sends the reports |
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
