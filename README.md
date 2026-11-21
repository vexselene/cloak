# Second Space (Cloak)

An open-source, Secure Folder-style second space for Android. It uses Android's built-in **work profile** to keep a separate, locked area on your phone where apps run with their own data, accounts and lock.

You see one icon in your normal launcher. Open it, unlock the space, and you get a grid of the apps that live there. No root, no app virtualization, no vendor framework.

> **Status: early, pre-release.** Built and tested mainly on Android 15 (OnePlus 13s, OxygenOS 15), with partial checks on Android 16 (Samsung Galaxy S21 FE, One UI 8.5). Android 12 to 14 are supported in the code but have not been tested on a real phone yet. Read [Limitations](#limitations) and [Not a security product](#not-a-security-product) before relying on it.

<!-- Screenshots go here. -->

## Features

- **One-tap setup.** Cloak creates the space (an Android managed profile with Cloak as its profile owner) and walks you through a short first run: give the space its own lock, set up its Play Store, add apps, and optionally turn on a recovery token and hide mode.
- **Separate lock.** The space has its own PIN, pattern or password, enforced by Android.
- **One visible icon.** The copy of the app inside the space is hidden. Cloak is the whole interface.
- **App grid.** A Secure Folder-style grid with rounded app icons, a lock button, and one-hand reach: the grid sits low on the screen and its title moves to the top as you scroll.
- **Add apps.** Copy an app from your main space into the space, or enable preinstalled apps such as the Play Store, which can use its own Google account. Freeze, unfreeze and remove apps from the grid.
- **Move files between your phone and the space.** Copy or move files and folders in both directions, with a progress notification, cancel, and a prompt when a file name already exists (replace, keep both or skip, with apply to all). A move deletes the originals only after the copy is verified.
- **Share sheet support.** Share from your personal apps into the space by default. Sharing out of the space to your personal apps is an optional setting.
- **Hide mode (experimental).** When the space locks, its apps are hidden and stopped, and the launcher's work tab disappears. Opening Cloak and entering the PIN brings them back.
- **Privacy settings.** Block screenshots, hide notification content on the lock screen, keep contacts private, block clipboard sharing. Which ones work depends on your phone.
- **Recovery token (optional).** A code you keep yourself. If Cloak's data is ever cleared, it lets you reconnect to the existing space instead of deleting it.
- **Colour presets.** Ocean, Forest, Dusk, Ember and Rose.
- **Delete the space** and everything in it from inside the app.
- **Clear messages** when another app already manages a work profile, or when Cloak has lost its link to the space.

## Requirements

- **Android 12 or newer** (API 31). It is developed on Android 15, and the older versions (12 to 14) have not been tested on a real phone. See [Limitations](#limitations).
- A phone that allows managed profiles. Some phones and enterprise setups do not.
- Only one work profile can exist on a device. If another app already manages one, Second Space cannot be created until it is removed. Cloak tells you when that is the case. On Android 12 to 14 it can only recognise another app's profile when Cloak is installed inside it, so setup may simply end with a message saying it did not finish.

## Install

> It is tested on very few phones and can change how the space is set up between versions. Read [Limitations](#limitations) first.

### Download

1. Open the [Releases page](../../releases).
2. Under **Assets**, download `Cloak-<version>.apk`. Also download `Cloak-<version>.apk.sha256` if you want to verify it. You can download on your phone, or on a computer and copy the file over.

### Verify (recommended)

Two checks, and they prove different things.

**The download is intact.** Compare the APK's SHA-256 with the value in the `.sha256` file:

```
certutil -hashfile Cloak-<version>.apk SHA256     (Windows)
sha256sum -c Cloak-<version>.apk.sha256           (Linux, macOS)
```

The checksum file sits next to the APK on the same page, so this only catches a damaged download.

**The APK came from the maintainer.** Check the signing certificate with `apksigner`, which is in the `build-tools` folder of the Android SDK:

```
apksigner verify --print-certs Cloak-<version>.apk
```

The `certificate SHA-256 digest` line must match this value, which is also published in [`docs/release-cert.sha256`](docs/release-cert.sha256):

```
b4df89f0679ccc781c3df58e1d0f58145da58f9ef55ebee7820fc47458caecd2
```

If the digest differs, do not install the file.

### Install the APK

1. Open the APK on your phone. Android asks you to allow installs from the app you opened it with (your browser or file manager). Allow it, go back, and tap **Install**. You can turn the permission off again afterwards. Play Protect may warn that the app comes from an unknown developer. That is expected for an APK installed outside the Play Store.
2. Or install it from a computer with USB debugging on:
   ```
   adb install -r Cloak-<version>.apk
   ```
3. Open **Cloak** and tap **Create Second Space**. A short first run guides you through the rest.

### Update

Install a newer pre-release over the old one. The space and its apps stay, because both versions are signed with the same key. Cloak inside the space updates along with it, so give it a few seconds before using it.

If something misbehaves after an update, open **Settings, Maintenance, Repair space** first. If that does not help, remove the space and create it again. Removing it deletes everything inside it.

### Switching from a build you made yourself

An APK signed with a different key cannot be installed over this one, and this one cannot replace a build you compiled yourself. Android refuses, in both directions. To switch:

1. Copy out anything you need from the space (menu, **Transfer files**).
2. In Cloak, **Settings, Remove, Delete Second Space**.
3. Uninstall Cloak.
4. Install the release APK and create a new space.

### Uninstall

Delete the space first (**Settings, Remove, Delete Second Space**), then uninstall Cloak. Deleting the space permanently removes its apps and data. Back up first, because there is no recovery for it.
If you have uninstalled the Cloak first, you can remove the work profile from the Android > Settings > User & Accounts > Work > Remove Work Profile. (Exact path may differ from OEM's implementation).

## Build from source

1. Install Android Studio (a recent stable version) and enable USB debugging on your phone.
2. Clone the repository and open it in Android Studio. Let Gradle sync.
3. Build and install the debug APK:
   ```
   gradlew.bat assembleDebug        (Windows)
   ./gradlew assembleDebug          (macOS / Linux)
   adb install -r -t app/build/outputs/apk/debug/app-debug.apk
   ```
4. Run the unit tests with `gradlew.bat testDebugUnitTest`.

Debug builds include developer tools (a Debug panel with probes and test buttons). Release builds do not. Never distribute a debug build.

### Release builds

A release build needs a signing key, and the build refuses to produce an unsigned APK.

- **For your own phone:** add `cloak.debugSign=true` to `~/.gradle/gradle.properties` and run `gradlew assembleRelease`. It signs with your debug key, so it installs over a debug build without deleting your space.

An APK signed with one key cannot be updated in place by an APK signed with another. Android refuses, and the only way forward is to remove the space and create it again. Never commit a keystore.

## Using it

1. Open **Cloak** and tap **Create Second Space**. Follow the system prompts.
2. The first run asks you to choose a lock for the space. Pick one that is different from your phone lock. Without a separate lock, the Lock button locks your whole phone.
3. Allow Cloak to install apps inside the space when the first run asks. Open the space's Play Store and sign in if you want to install apps there, then add apps from your main space with the **+** button. For a normal app, Android shows an Install dialog inside the space. Tap Install.
4. Open apps from the grid. The lock icon locks the space.
5. The ⋮ menu has **Transfer files** and **Settings**.

If you open Cloak from the launcher while the space is locked, Android asks for the space's PIN. After you enter it, you may land on the launcher and need to tap Cloak again (see Limitations).

## How it works

One APK is installed twice: once in your main user and once inside the work profile.

- The **main copy** is the whole interface. It cannot call the privileged Android APIs that manage a work profile.
- The **work copy** is the profile owner. It has no screen you can reach. It runs the privileged calls (lock, hide, freeze, privacy policy, delete) when the main copy asks.
- The main copy talks to the work copy through a cross-profile intent filter. Every command is signed with a secret created when the space is set up (HMAC-SHA256 with a counter that prevents replay).
- File transfers are always pulls: the side that receives picks its own destination folder and reads the source through a temporary grant, because Android does not let one profile create or delete files inside the other.

Details, the command reference, and every dead end found along the way are in [docs/DEVELOPER_GUIDE.md](docs/DEVELOPER_GUIDE.md).

## Limitations

Most of these come from how Android work profiles behave and cannot be fixed by the app.

**The space**
- **Cloned apps are not fully hidden** unless hide mode is on. While visible they appear in the launcher and in system Settings, and their notifications show in the main notification shade.
- **Updates:** apps in the space update through the space's own Play Store, or by adding them again. They do not follow updates to the copy in your main space.
- **No backup.** Deleting the space deletes its data permanently. Cloud backup of Cloak is disabled on purpose.
- **No idle auto-lock.** The space locks when you press Lock, or, with hide mode on, when the screen turns off. Without hide mode, locking the phone's screen does not re-lock Cloak's grid, and leaving a space app open does not lock it after a timeout.
- **After entering the work PIN from a cold start,** you may land on the launcher and need to tap Cloak again. This is partly improved but not fully fixed.
- **Fingerprint** is not always offered for the space lock. Android decides when to ask for the PIN instead, and on one test phone the unlock screen once stayed up after a fingerprint unlock. Turning the screen off and unlocking again clears it.

**Hide mode (experimental)**
- Hidden apps are stopped: no messages or notifications arrive while the space is locked, and they catch up when you open it.
- Unhiding takes a moment on slower phones. Cloak shows "Getting your apps ready" and refreshes the grid for up to about 20 seconds until every app is back.
- While the space is open, a small "Second Space is open" notification keeps the lock listener alive.
- After a restart, apps may show in the launcher for a few seconds before they are hidden again. They always need the space's PIN.
- If Cloak loses its link to the space while apps are hidden, they stay hidden until you reconnect with your recovery token. If the space's own data is cleared, Settings, Maintenance, Repair space shows them again.
- With hide mode on, the screen turning off locks the space, which interrupts a file transfer that is running against the space's storage.

**Privacy switches** depend on the phone maker. Screenshot blocking and clipboard blocking worked on the test phone. Contact privacy may not apply to every dialer.

**File transfer**
- Choosing a folder inside the space needs a system files app there. Cloak asks you to enable it (Settings, File transfer) because the space has none by default (some OEMs have it by default).
- **Share from the space to your personal apps** is off by default. Turning it on lets every app in the space share to every personal app, not only Cloak.
- Large transfers run in a foreground service with a notification. On Android 15 and newer the system limits how long data-sync services can run in a day (about six hours), which can stop a very large transfer. Cloak then cancels cleanly and keeps what it copied.
- A move removes originals only when Cloak is next opened, after checking file counts and sizes on both sides. A skipped, failed, cancelled or changed source keeps its originals.
- Replacing a file overwrites it in place, so cancelling mid-file leaves it partial, although preserving original.

**Recovery**
- The recovery token only covers the case where Cloak's data in your main profile is lost. If the space's own data is cleared, the token's key is gone with it.
- If you lose the token and Cloak's data, the only way out is to delete the space.
- Anyone with the token and your space PIN could take over the space. Keep the token private.

**Devices and Android versions**
- Tested mainly on one phone, running Android 15, with partial checks on a second phone running Android 16.
- **Android 12 to 14 are untested.** Hide mode after a restart, file transfer between the profiles, the share-sheet settings and the return to Cloak after the work PIN all depend on cross-profile behaviour that was only checked on Android 15 and 16. Tell us what you see.
- On Android 12 to 14 Cloak cannot identify another app's work profile unless Cloak is installed in it, so the "another app manages a work profile" screen may not appear. If setup ends without creating the space, check Settings, Accounts for an existing work profile.
- Behaviour on phones with Private Space or other cloned-app profiles is unknown.

## Not a security product

Second Space is a convenience and privacy tool. It relies on Android's work profile isolation and adds nothing to it. It does not protect against a rooted phone, forensic extraction, or someone who knows your PIN. Read [docs/THREAT_MODEL.md](docs/THREAT_MODEL.md) for what it does and does not defend against.

If you find a security problem, please report it privately through the repository's security contact (or by email to the maintainer) before opening a public issue.

## Project layout

```
app/src/main/java/io/cloak/os/
  admin/       profile owner, policy calls, command receiver, hide mode, installer
  bridge/      signed commands, authentication, profile and state detection, settings, recovery
  transfer/    file and folder copy and move between profiles
  apps/        listing and launching apps in the space
  ui/          Compose screens, onboarding and theme
  common/      role detection and logging
app/src/debug/     debug-only panel, probes and test tools
app/src/test/      unit tests
docs/              developer guide, threat model, release steps
```

## Contributing

Issues and pull requests are welcome. Please read the developer guide first, especially the sections on the command channel and the list of dead ends, since several obvious approaches do not work for work profiles. Behaviour that has not been verified on a real device should be described as such in pull requests.

Because testing has covered only a couple of phones, reports of how Second Space behaves on other devices (model, Android version, what worked and what did not) are especially useful, and reports from Android 12 to 14 most of all.

## License

Apache License 2.0. See [LICENSE](LICENSE).