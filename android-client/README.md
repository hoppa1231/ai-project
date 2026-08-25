# SecureVPN Android Client

Production-oriented Jetpack Compose client for the SecureVPN control plane and sing-box tunnel runtime.

## Tech
- Kotlin
- Jetpack Compose (Material 3)
- Android Studio / Gradle

## What is implemented
- Main VPN screen in dark neon style.
- Two states: disconnected / connected.
- Swipe-up gesture to connect (`hold & swipe up`).
- Tap on lock to disconnect.
- Guest session bootstrap through the backend API.
- Server list and quota loaded from the API.
- VPN config issue/revoke calls wired to the API.
- Server card backed by API nodes.

## API endpoint

By default the app uses:

```bash
https://tech-supp-test.ru
```

Override it at build time when running against a local backend:

```bash
bash ./gradlew :app:assembleDebug -PSECUREVPN_API_BASE_URL=http://10.0.2.2:8080
```

## Open in Android Studio
1. Open folder: `android-client`
2. Let Gradle sync.
3. Run app module `app` on emulator/device.

## Notes

- The control-plane API covers guest auth, refresh, nodes, quota, notifications, issue, and revoke.
- The issued sing-box configuration is started by an Android VPN foreground service.
- A start intent is not treated as success: the UI waits for the tunnel runtime to report `STATE_ON` and surfaces bounded startup/configuration failures.
