# Meshly

**Offline-first, local-network messenger for Android.**

Meshly is being built around a simple idea: people nearby should be able to communicate without depending on a central account or internet connection.

## Alpha 0.1.0

This first Android project foundation includes:
- Kotlin and Jetpack Compose app structure
- Local display-name onboarding
- Chats, Nearby, and Profile navigation
- Dark visual identity with a mint accent
- Android permissions foundation for nearby Wi-Fi and Bluetooth work
- GitHub Actions workflow to build a debug APK

## Planned milestones

1. **Alpha 1:** discover nearby devices on the same Wi-Fi/hotspot, establish a local connection, exchange text messages, and save conversations on-device.
2. **Alpha 2:** Wi-Fi Direct and Bluetooth discovery/transport fallback, reconnect handling, and stronger local identity.
3. **Alpha 3:** group conversations, attachments, and voice messages.
4. **Alpha 4:** local voice/video calling and additional privacy/security hardening.

## Build

The GitHub Actions workflow builds a debug APK. You can download it from the workflow run's Artifacts section after a successful run.

## Important

This is an early foundation, not a finished messenger yet. Local device discovery, message transport, persistence, encryption, media sharing, and calls are still planned implementation work.
