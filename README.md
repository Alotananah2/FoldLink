# FoldLink

Two phones, one foldable. Install the same APK on both.

## Get the APK
Push this folder to a GitHub repo → **Actions** tab → "Build APK" → download `FoldLink-apk`.

## Setup
1. Pair the two phones in normal Bluetooth settings.
2. On both: open FoldLink → tap the 3 setup buttons → pick the other phone in the list.
3. Phone 1: choose **Main phone**. Phone 2: choose **Second phone**.
4. Tap **Start link** on phone 2 first, then on phone 1. Status should say "Connected".
5. On phone 2, tap **Open keyboard**. Phone 1 sees it as a real Bluetooth keyboard and types into any app.

## Power off
- Phone 1 sends "OFF" over Bluetooth the moment it starts shutting down.
- Phone 2 really powers off **only if rooted** (`su -c reboot -p`). Android doesn't let normal apps shut the phone down.
- Without root it turns the screen off and locks instead (step 2 of setup).
- Optional: "Lock 2nd phone when main screen turns off". This makes the power button control both screens.

## FoldLink Home (launcher)
Liquid-glass home screen: big clock, link status, app search, app grid and a dock.
- Setup button 4 → choose **FoldLink Home** as your home app.
- Long-press an app → opens app info. Tap ⧉ in the dock for FoldLink settings.
- On the second phone the dock has a ⌨ button that opens the Bluetooth keyboard.

## FoldLink Keyboard (system keyboard)
A normal Android keyboard with the same glass look, English + Arabic (ع button).
Setup button 5 → turn on **FoldLink Keyboard**, then pick it from the keyboard switcher.
