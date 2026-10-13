# notification-pinger

Android app that watches for Slack and Teams notifications on the phone and
pings (loud sound, vibration, undismissable notification) every minute until
you tap **Acknowledge**. It does not read message content and needs no
Slack or Teams tokens.

## Build

Needs Android Studio (it bundles the JDK and SDK).

1. Open this folder in Android Studio. It will create the Gradle wrapper and sync.
2. Build > Build APK(s). The APK lands in `app/build/outputs/apk/debug/`.
3. Copy the APK to the phone and install it. Allow "install unknown apps" for
   the file manager when prompted.

## Setup on the phone

1. Open **Notification Pinger** and tap **Grant notification access**. Enable
   it for Notification Pinger in the list, then go back.
2. Allow notifications when asked (Android 13+).
3. Turn off battery optimization for the app (Settings > Apps > Notification
   Pinger > Battery > Unrestricted). Some phones kill the listener otherwise.

## Pebble

No watch app needed. The ping is a normal notification with an **Acknowledge**
action, so the Pebble phone app can mirror it to the watch:

1. In the Pebble phone app, enable notification access and turn on mirroring for
   **Notification Pinger** (it may be off by default for new apps).
2. Make sure the Pebble app is not set to skip notifications while the phone
   screen is on.
3. When the ping arrives, the watch buzzes. Open it and pick **Acknowledge**
   (or dismiss it) to stop the ring on the phone.

## Behavior

- A notification from Slack (`com.Slack`) or Teams (`com.microsoft.teams`)
  starts the ping.
- The ping respects Do Not Disturb. While DND is on, it stays silent (it still
  shows in the shade). There is no call exception.
- It rings as soon as a notification arrives, if none arrived in the previous
  X minutes (set in the app, default 2, decimals like 0.5 allowed). Every new
  notification restarts that wait, so a busy conversation stays silent and the
  next ring comes only after X minutes without any notification.
- It rings continuously until you tap **Acknowledge**, swipe the ping or the
  Slack/Teams notification away, or 10 minutes pass (`RING_TIMEOUT_MS`).
- Further Slack/Teams notifications while pinging do not add extra pings.

## Config

All in `app/src/main/java/local/pinger/WatchService.kt`:

- `WATCHED`: package names. If Teams or Slack notifications are not caught,
  check the real package name on the phone (a notification-logger app shows it,
  or `adb shell dumpsys notification`).
- `RING_TIMEOUT_MS`: auto-stop after this long. The cooldown is set in the app (`DEFAULT_COOLDOWN_MIN` = 2).

## Status

Builds clean. The APK is at the project root: `notification-pinger/notification-pinger.apk`
(debug build, unsigned for release). `build-docker.sh` copies it there every time. Built in a throwaway Docker container, not on the host. The
on-device test (permissions, ping timing, call override) is still to do.
