# SecureVPN Android Client

Jetpack Compose prototype based on your Figma reference.

## Tech
- Kotlin
- Jetpack Compose (Material 3)
- Android Studio / Gradle

## What is implemented
- Main VPN screen in dark neon style.
- Two states: disconnected / connected.
- Swipe-up gesture to connect (`hold & swipe up`).
- Tap on lock to disconnect.
- Traffic card and stat cards in connected state.
- Server card (country / city / ping).

## Open in Android Studio
1. Open folder: `android-client`
2. Let Gradle sync.
3. Run app module `app` on emulator/device.

## Notes
- This is UI + interaction prototype; real VPN tunnel is not wired yet.
- Current data on screen is demo/mock.
