# FoldLink

Two phones → one foldable. Install the same APK on both phones (Actions tab → latest run → FoldLink-apk).

## Setup (both phones)
1. Pair the phones in normal Bluetooth settings.
2. Open FoldLink. Pick **Main phone** on phone 1 and **Second phone** on phone 2. Then pick the other phone in the list.
3. Do setup steps 1–7.
4. Tap **Start / restart link** on phone 2 first, then on phone 1. Status should say **Connected ✓**.

## What it does
- **Home screen:** One UI–style. When linked, the grid is 8 columns wide across both screens and swiping moves both. The clock is on the left and the date on the right. The dock has 6 slots, 3 per phone (long-press an app). Hold the clock to open settings.
- **Keyboard:** Set FoldLink Keyboard as default on both phones. Typing on either phone splits the keyboard: left half on the left phone, right half on the right phone. Has English and Arabic (ع).
- **Power button:** Turning either screen off turns the other one off. Turning one on wakes the other. Shutting one phone down powers off the other **if rooted**, otherwise it locks it.
- **Volume:** Volume buttons on either phone change both.
- **Span video:** Pick a video. It's sent over Bluetooth (slow for big files) and plays stretched across both screens. Tap to play or pause on both.

## Limits
Android doesn't let an app stretch *other* apps (like TikTok) across two separate phones. That needs a custom ROM. The span video player is the in-app version.

## Stretch any app across both phones (phone 1 must be rooted)
Long-press an app → **⧉ Stretch across both screens**, or use the button in FoldLink settings.
Phone 1 opens the app on a hidden screen as wide as both phones. Phone 1 shows the left half, phone 2 shows the right half, and you can touch both.
- The video goes over **Wi-Fi**: same Wi-Fi network, or phone 2 joins phone 1's hotspot. Bluetooth still handles everything else.
- Sound plays from phone 1. ◀ = back, ✕ = stop.
- Uses the [scrcpy](https://github.com/Genymobile/scrcpy) server (Apache License 2.0), downloaded at build time.
