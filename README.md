# Second Space (Cloak)

An open-source, Secure Folder-style second space for Android. It uses Android's built-in
**work profile** to keep a separate, locked area on your phone where apps run with their own
data, accounts and lock.

You see one icon in your normal launcher. Open it, unlock the space, and you get a grid of the
apps that live there. No root, no app virtualization, no vendor framework.

> **Status: early.** Developed and tested on one device (OnePlus 13s, OxygenOS 15, Android 15).
> Other phones and Android versions are untested. See [Limitations](#limitations) and
> [Not a security product](#not-a-security-product) before relying on it.

<!-- Screenshots go here once release builds are ready. -->

## Features

- Create a second space with one tap (an Android managed profile, with Cloak as its profile owner).
- Separate lock for the space, enforced by the system.
- One visible icon, in the main space. The copy of the app inside the space is hidden.
- Grid of apps in the space with a lock action and a menu, modelled on Secure Folder.
- Add apps from your main space (the APK is copied across and installed in the space) or enable
  preinstalled apps such as the Play Store, which can use its own Google account.
- Freeze, unfreeze and remove apps from the space.
- Privacy settings: block screenshots, hide notification content on the lock screen, keep contacts
  private, block clipboard sharing (device support varies, see below).
- Delete the space and everything in it from inside the app.
- Debug tooling that exists only in debug builds.

## Requirements

- Android 12 or newer (min SDK 31).
- A device that allows managed profiles. Some phones and enterprise setups do not.
- Only one work profile can exist on a device. If another app already manages one, Second Space
  cannot be created until it is removed.

## Build and install

There is no published release yet. Build from source:

1. Install Android Studio (a recent stable version) and enable USB debugging on your phone.
2. Clone the repository and open it in Android Studio. Let Gradle sync.
3. Build the debug APK:
   ```
   gradlew.bat assembleDebug        (Windows)
   ./gradlew assembleDebug          (macOS / Linux)
   ```
4. Install the APK from `app/build/outputs/apk/debug/` with `adb install -r <apk>`.

Install the **debug** APK. The release build is unsigned unless you configure your own keystore,
and Android refuses unsigned APKs ("package appears to be invalid"). Never commit a keystore.

Run the unit tests with `gradlew.bat testDebugUnitTest`.

## Using it

1. Open **Cloak** and tap **Create Second Space**. Follow the system prompts.
2. Open **Settings** in the app and tap **Set Second Space lock** to choose the space's own PIN or
   pattern. Without a separate lock the space shares your phone lock.
3. Use the **Add apps** page to bring apps in. For a normal app, the system shows an Install dialog
   inside the space. Tap Install. The first time, Android may ask you to allow installs from Cloak
   in the space's settings.
4. Open apps from the grid. Use the lock icon to lock the space.

To keep cloned apps out of your main launcher, use a third-party launcher that can hide apps.

## How it works

One APK is installed twice: once in your main user and once inside the work profile.

- The **main copy** is the whole interface. It cannot call the privileged Android APIs that
  manage a work profile.
- The **work copy** is the profile owner. It has no visible screen. It runs the privileged calls
  (lock, freeze, privacy policy, delete) when the main copy asks.
- The main copy talks to the work copy through a cross-profile intent filter. Every command is
  signed with a secret created when the space is set up, using HMAC-SHA256 and a counter that
  prevents replay.

Details, command reference, and every dead end found along the way are in
[docs/DEVELOPER_GUIDE.md](docs/DEVELOPER_GUIDE.md).

## Limitations

These come from how Android work profiles behave. Most cannot be fixed by the app.

- **Cloned apps are not fully hidden.** They appear in the launcher and in system Settings with a
  work badge. A third-party launcher can hide them from the launcher only.
- **Notifications from the space show in the main notification shade.**
- **Updates:** cloned apps update through the space's own Play Store or by cloning them again. The
  copy in the space does not follow updates to the copy in your main space.
- **No backup.** Deleting the space deletes its data permanently. Cloud backup of the app is
  disabled on purpose.
- **No idle auto-lock yet.** The space locks when the phone locks or when you press Lock. Leaving a
  space app open with the screen on does not re-lock it after a timeout.
- **Privacy switches depend on the phone maker.** Screenshot blocking works on the test device.
  Clipboard blocking did not take effect on it. Notification redaction needs a freshly created
  space and is not yet confirmed. Contact privacy may not apply to every dialer.
- **Losing the app's data can orphan the space.** If Cloak's data in your main profile is cleared,
  the space can no longer be controlled from the app, and the space must be removed through system
  Settings. A proper recovery flow is planned.
- **Fingerprint is not offered** for the space lock on the test device.
- **One device tested.** Behaviour on other phones, Android versions, and phones with Private Space
  is unknown.

## Not a security product

Second Space is a convenience and privacy tool. It relies on Android's work profile isolation and
adds nothing to it. It does not protect against a rooted phone, forensic extraction, or someone who
knows your PIN. Read [docs/THREAT_MODEL.md](docs/THREAT_MODEL.md) for what it does and does not
defend against.

If you find a security problem, please report it privately through the repository's security
contact (or by email to the maintainer) before opening a public issue.

## Project layout

```
app/src/main/java/io/cloak/os/
  admin/     profile owner, policy calls, command receiver, installer
  bridge/    signed commands, authentication, profile and state detection, file transport
  apps/      listing and launching apps in the space
  ui/        Compose screens and theme
  common/    role detection and logging
app/src/debug/     debug-only panel
app/src/test/      unit tests
docs/              developer guide and threat model
```

## Contributing

Issues and pull requests are welcome. Please read the developer guide first, especially the
sections on the command channel and the list of known dead ends, since several obvious approaches
do not work for work profiles. Behaviour that has not been verified on a real device should be
described as such in pull requests.

Because testing has covered only one phone, reports of how Second Space behaves on other devices
(model, Android version, what worked and what did not) are especially useful.

## License

Apache License 2.0. See [LICENSE](LICENSE).