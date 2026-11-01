# Second Space (Cloak): Developer Guide

Package `io.cloak.os`, app label "Cloak", working product name "Second Space".
Verified device: OnePlus 13s, OxygenOS 15 (Android 15), no root. Min SDK 31, target 36.

This document records what the app does, how it is built, every non-obvious thing learned while
building it, what has been verified on a device and what has not, and what is still open.
Read sections 3 to 9 before changing anything in `admin/` or `bridge/`.

Verification labels used throughout:
- **Verified**: tested on the OnePlus 13s by the maintainer.
- **Written, not verified**: code exists and compiles in review, but has not been confirmed on a device.
- **Hypothesis**: believed from API documentation or memory. Treat it as unproven until tested.

---

## 1. What this app is

A Secure Folder-style second space for Android, built on the managed work profile. The user sees
one icon, in the main space (user 0). Opening it shows a grid of apps that live in a hidden,
separately locked space. The work profile is the isolation boundary. The app is both its Device
Policy Controller (profile owner) and its user interface.

Design goals:
- One visible Cloak icon, in the main space. The work-profile copy of the app is hidden and is
  never used directly by the user.
- The main-space app is the entire interface. The work-profile copy is a background agent that
  performs privileged actions on command.
- The gate is the system-enforced work lock (a separate PIN or pattern), not an app-level
  password, because work apps can be launched from the launcher without passing through our UI.
- No root, no app virtualization, no vendor framework.

Non-goals (also stated in the README): not a hardened security product, not Knox, cannot hide
apps from system surfaces (Settings, launcher), one work profile per device.

Both copies are the same APK. They depend on each other: the main copy cannot call profile-owner
APIs, and the work copy has no UI the user can reach. If either side is out of sync (stale setup,
lost secret), the app stops working. Section 13 describes the recovery work this implies.

---

## 2. Current status

| Capability | State                              | Notes |
|---|------------------------------------|---|
| Create the space (provision work profile) | Verified                           | Android 12+ flow with our own provisioning activities |
| Separate work lock | Verified                           | Set via Settings > Set lock. Does not auto-prompt at creation |
| Space state (none / paused / locked / unlocked) | Verified                           | Profile broadcasts plus a 3 s safety poll while visible |
| Lock now | Verified                           | Locks only the space if the work lock is separate |
| Command channel, main to work copy | Verified                           | Cross-profile intent filter, HMAC-signed with a counter |
| Clone a normal app into the space | Verified earlier; re-verify        | Re-check after the URI `host` fix and command hardening |
| Enable a preinstalled app (Play Store, Chrome) | Verified                           | `enableSystemApp` |
| App grid and launching | Verified                           | LauncherApps with live callbacks |
| Freeze / unfreeze, remove from space | Verified                           | Long-press menu in the grid |
| Hide the work copy's launcher icon | Verified                           | Re-applied by the work copy on update and on profile start |
| Privacy: block screenshots | Verified                           | `setScreenCaptureDisabled` |
| Privacy: hide lock-screen notification content | Written, not verified              | Needs a freshly created space, see 6.11 |
| Privacy: contacts kept private | Verified, OEM dialers vary         | `setCrossProfileContactsSearchDisabled`, `setCrossProfileCallerIdDisabled` |
| Privacy: block clipboard sharing | **Not working** on the test device | No error thrown, restriction not effective. Cause unknown, see 6.11 |
| Delete space | Verified                           | `wipeData` from the profile owner, behind a confirmation dialog |
| Secure Folder-style UI | Verified                           | Dark theme, top bar, grid, add-apps list, settings page |
| Maintenance: check connection, repair space | Written, not verified              | Repair sends a signed `repair` command |
| Self-repair of work copy setup | Verified                           | Runs on update, boot and profile start |
| Unit tests (Auth, Commands, PrivacySettings) | All 32 tests pass                  | Run `gradlew testDebugUnitTest` |
| Debug-only tools | Verified                           | `src/debug` source set, stripped from release |
| Auto-lock beyond native screen-lock behaviour | Not built                          | Section 12 |
| User-facing "copy file to other space" | Not built                          | Transport proven (6.6) |
| Onboarding | Not built                          | Section 13 |
| Recovery when the pairing secret is lost | Not built                          | Section 13, planned in detail |

---

## 3. Architecture

One APK installed in two Android users:

```
 user 0 (main space)                      managed profile (work profile, id varies: 13, 14, 15 ...)
 ------------------------------           ------------------------------------------------------
 MainActivity  (Role.MAIN)                 MainActivity (Role.AGENT)  - closes immediately, no icon
   HomeScreen, grid, add apps, settings    CommandActivity            - invisible command receiver
   SpaceStateReader / SpaceProfile         SpaceAdminReceiver         - profile owner (DPC)
   Commands.send()  ---signed intent--->   PolicyManager              - all DevicePolicyManager calls
   ApkExporter  -----FileProvider URI--->  ApkInstaller / InstallResultReceiver
                                           PackageReplacedReceiver    - re-runs setup (update, boot)
```

`Role.detect(context)` (`UserManager.isManagedProfile`) decides which behaviour a running copy has.
The main copy never calls profile-owner APIs. It asks the work copy to.

Why this shape: `DevicePolicyManager` calls such as suspending, locking, wiping and enabling
system apps only work from the profile owner process, which lives inside the profile.

Key consequence: the work copy has no visible screen, so nothing opens it by hand. Anything that
must be true in the work copy (enabled `CommandActivity`, registered intent filter, hidden
launcher alias, applied privacy policy) has to be set by code that runs there without user
action. Section 6.9 explains how.

---

## 4. Repository map

```
app/build.gradle.kts              buildConfig on, R8 off, material-icons-core dependency
gradle/libs.versions.toml         version catalog
docs/
  DEVELOPER_GUIDE.md              this file
  THREAT_MODEL.md                 what is and is not protected
  HANDOFF.md                      old architecture note, outdated, to be replaced by this guide
app/src/main/
  AndroidManifest.xml
  res/xml/device_admin.xml        declared policies: force-lock, wipe-data, disable-keyguard-features
  res/xml/data_extraction_rules.xml   excludes everything from cloud backup and device transfer
  res/xml/file_paths.xml          FileProvider: cache/bridge/ is shareable
  res/values/strings.xml
  java/io/cloak/os/
    MainActivity.kt               thin shell: finishes if AGENT, else shows HomeScreen
    common/
      Role.kt                     MAIN vs AGENT
      Logx.kt                     logging wrapper, quiet in release
    admin/                        runs mostly in the work copy
      SpaceAdminReceiver.kt       profile owner receiver; stores secret; enables profile
      GetProvisioningModeActivity.kt   answers the Android 12+ mode question
      PolicyComplianceActivity.kt      final provisioning step
      PolicyManager.kt            every DevicePolicyManager call (see 4.1)
      CommandActivity.kt          verifies signature, validates arguments, dispatches commands
      ApkInstaller.kt             PackageInstaller session writer + InstallCoordinator latch
      InstallResultReceiver.kt    install status; launches the system confirm dialog
      PackageUpdateReceiver.kt    declares PackageReplacedReceiver: re-runs setup (work copy only)
    bridge/                       shared by both copies
      Commands.kt                 command names, argument validation, signed send()
      Auth.kt                     secret storage, signing, verification
      PrivacySettings.kt          the four privacy switches, encode/decode, storage
      SpaceProfile.kt             picks the space's profile (managed only)
      SpaceState.kt               SpaceState enum + SpaceStateReader
      SpaceMaintenance.kt         connection check and repair request
      ApkExporter.kt              main copy: copies APKs to cache/bridge, returns URIs
    apps/SpaceApp.kt              SpaceApps: list and launch apps in the space
    ui/
      HomeScreen.kt               state machine: welcome, locked, grid, add apps, settings
      StateScreens.kt             WelcomeScreen, LockedScreen (centered layout)
      SpaceGrid.kt                icon grid, long-press menu
      AddAppsScreen.kt            searchable list of main-space apps, Play Store row
      SettingsScreen.kt           privacy switches, set lock, maintenance, delete space
      RememberSpaceApps.kt        live app list via LauncherApps callbacks
      RememberSpaceState.kt       space state via profile broadcasts + safety poll
      theme/SpaceTheme.kt         fixed dark colour scheme and background gradient
app/src/debug/.../debug/DebugPanel.kt     real debug panel (debug builds only)
app/src/release/.../debug/DebugPanel.kt   empty stand-in (release builds)
app/src/test/java/io/cloak/os/bridge/
  AuthTest.kt, CommandsTest.kt, PrivacySettingsTest.kt
```

Removed during cleanup: `ui/CloneList.kt` (replaced by `AddAppsScreen`), `res/xml/backup_rules.xml`
(ignored on API 31+), the work-copy `AgentScreen`, `PolicyManager.cloneApp`,
`PolicyManager.allowCrossProfile`, the `INTERACT_ACROSS_PROFILES` permission, and the placeholder
`ExampleUnitTest`.

### 4.1 PolicyManager functions

| Function | What it does |
|---|---|
| `setupChannel` | Runs each step independently (one failure does not skip the rest): enable `CommandActivity`; clear and re-add the cross-profile intent filter; `requireWorkLock`; `allowInstalls`; hide the launcher alias (work copy only); `applyPrivacy` with saved settings (work copy only) |
| `requireWorkLock` | `setRequiredPasswordComplexity(LOW)`: the profile must have a PIN or pattern |
| `lockNow` | `lockNow(FLAG_EVICT_CREDENTIAL_ENCRYPTION_KEY)`: re-locks the profile |
| `allowInstalls` | Clears `DISALLOW_INSTALL_APPS` and both unknown-sources restrictions |
| `applyPrivacy` | Screenshots, keyguard notification redaction, cross-profile clipboard restriction, contact search and caller ID. Each runs separately, failures are collected |
| `deleteSpace` | Checks profile owner, then `wipeData(0)` |
| `enableSystemApp` | Turns on a preinstalled app inside the profile |
| `setFrozen` | `setPackagesSuspended` for one package |

`StepRunner` (private) runs labelled steps inside `runCatching`, logs each failure and returns a
single `Result` listing what failed.

### 4.2 Manifest summary

- `MainActivity`: exported, no intent filter (Android Studio's Run button needs it exported).
  The launcher entry is `LauncherAlias`, an `activity-alias` the work copy disables independently.
- `CommandActivity`: exported, `enabled="false"` by default and enabled only in the work copy,
  `directBootAware="true"`, translucent, `excludeFromRecents="true"`, guarded by the signature
  permission `io.cloak.os.permission.COMMAND`. Filter action: `io.cloak.os.action.COMMAND`.
- `PackageReplacedReceiver`: not exported, direct-boot-aware, listens for `MY_PACKAGE_REPLACED`,
  `LOCKED_BOOT_COMPLETED` and `BOOT_COMPLETED`.
- Permissions: `REQUEST_PASSWORD_COMPLEXITY` (required, see section 8), `REQUEST_INSTALL_PACKAGES`,
  `REQUEST_DELETE_PACKAGES`, `RECEIVE_BOOT_COMPLETED`, and our own signature permission.
- `allowBackup="false"`. `data_extraction_rules.xml` excludes all domains from cloud backup and
  device transfer.
- `<queries>` for the launcher intent so the main copy can list apps without `QUERY_ALL_PACKAGES`.
- `FileProvider`, authority `io.cloak.os.fileprovider`, not exported, grants URI permissions.
- `device_admin.xml` declares `force-lock`, `wipe-data` and `disable-keyguard-features`.

---

## 5. Build, run, test

- Windows, Android Studio, USB debugging, ADB. Kotlin, Jetpack Compose (Material 3).
- `buildFeatures { compose = true; buildConfig = true }`. `buildConfig` is needed by `Logx` and
  `CommandActivity` for `BuildConfig.DEBUG`.
- Dependency note: newer Material 3 no longer brings icons in. `material-icons-core` is declared
  explicitly in the version catalog and in `app/build.gradle.kts`.
- Run from Studio or `adb install -r <debug apk>`. Because install restrictions are cleared in the
  space, an update replaces the app in both copies together. Both copies must update together,
  because the signing format must match.
- Install the **debug** APK from `app\build\outputs\apk\debug\`. The release folder holds an
  unsigned APK, which Android rejects ("package appears to be invalid").
- Release signing needs a keystore. Generate one with Build > Generate Signed App Bundle / APK,
  keep it outside the repo, back it up, never commit `*.jks` or `*.keystore`. A release-signed
  build cannot be installed over a debug-signed one.
- Debug-only code lives in `app/src/debug/`. Check a release build with `gradlew assembleRelease`
  then Build > Analyze APK and search for `DebugPanel`.
- Unit tests: `gradlew.bat testDebugUnitTest`. Report at
  `app/build/reports/tests/testDebugUnitTest/index.html`. They run on the plain JVM.
  `Auth.mac` and `Auth.isValid` are `internal` so tests can reach them; `Auth` uses
  `java.util.Base64` (not `android.util.Base64`) for the same reason.
- Code shrinking (R8) is off in release on purpose, see section 9.

### ADB cheat sheet (Windows `findstr`)

```
adb shell pm list users                          users: 0 owner, 999 MultiApp (OxygenOS), work profile
adb shell dpm list-owners                        confirms io.cloak.os is profile owner
adb logcat -d | findstr /i "SecondSpace"         all our log lines (debug builds)
adb logcat -c                                    clear the log before a test
adb logcat -s SecondSpace/PolicyManager SecondSpace/Receiver     setup and trigger lines
adb shell pm remove-user <id>                    remove the work profile (may be blocked)
adb shell am start --user 0 -a io.cloak.os.action.COMMAND --es cmd lock_now
                                                 security test: must do nothing
adb shell dumpsys user | findstr /i "no_install copy_paste"      restrictions per user
adb shell "dumpsys package io.cloak.os | grep -i pkgFlags"       ALLOW_BACKUP must be absent
adb shell run-as io.cloak.os ls cache/bridge     must be empty or missing after a clone
```

The shell cannot address the work user (`--user <work id>` is denied), so work-profile behaviour is
tested through the app and its logs.

Log tags: `SecondSpace/PolicyManager`, `/Command`, `/Installer`, `/Auth`, `/Profile`, `/Home`,
`/Settings`, `/AddApps`, `/Grid`, `/Receiver`, `/AdminReceiver`, `/Maintenance`, `/Debug`.

### Logging policy (`Logx`)

All production code logs through `common/Logx.kt`, never `android.util.Log` directly.
- Debug builds: normal logging with stack traces.
- Release builds: `d` is silent, `w` logs the message only, `e` logs the message plus the exception
  class name only (no stack trace, no messages that could include data).
- No package names, file contents or URIs are logged in release builds. Earlier code logged the
  apps added to the hidden space and the contents of the file test.
- The `src/debug` DebugPanel is allowed to use plain `Log` because it never ships.
- When adding code, import `io.cloak.os.common.Logx`. If you bulk-replace `Log.` with `Logx.`,
  do it before `Logx.kt` exists, or the replace rewrites `Logx` into infinite recursion.

---

## 6. How the pieces work

### 6.1 Provisioning

1. Main copy: "Create Second Space" generates a random secret (6.3), stores it, and launches
   `ACTION_PROVISION_MANAGED_PROFILE` with `SpaceAdminReceiver` and the secret in
   `EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE`.
2. The system runs the Android 12+ flow. `GetProvisioningModeActivity` answers
   `PROVISIONING_MODE_MANAGED_PROFILE`. `PolicyComplianceActivity` finishes with `RESULT_OK`.
3. The work copy stores the secret via `Auth.storeFromProvisioning` (from
   `onProfileProvisioningComplete` and the compliance activity, both idempotent) and calls
   `enableProfile`: `setProfileName`, `setProfileEnabled` (each guarded), then `setupChannel`.
4. After provisioning the main copy retries `SET_LOCK` for up to 30 s. This does not reliably open
   the lock screen. Settings > Set lock always works. Deferred, see section 12.
5. Provisioning is checked with `isProvisioningAllowed`. If it is false, the welcome screen shows a
   generic "not allowed" message. A clearer message for "another app already owns a work profile"
   is planned (section 13).

### 6.2 Command channel

Goal: the main copy asks the work copy to run a privileged action.

- `CrossProfileApps` interaction was tested and is unavailable on this device: `canInteract` and
  `canRequest` stay false even after `setCrossProfilePackages` succeeds. That code was removed.
- What works: the profile owner registers a **cross-profile intent filter**
  (`addCrossProfileIntentFilter`) for the action `io.cloak.os.action.COMMAND`, with both
  `FLAG_MANAGED_CAN_ACCESS_PARENT` and `FLAG_PARENT_CAN_ACCESS_MANAGED`. The main copy calls
  `startActivity`. Android's intent forwarder delivers it to `CommandActivity` in the work profile.
- A hardening attempt narrowed the filter to main-to-work only. After that change the channel broke
  on the test device (cause not isolated), so both flags were restored. The main copy's
  `CommandActivity` is disabled, so the space still cannot send commands to the main copy.
- `CommandActivity` is disabled in the main copy on purpose. Both copies are the same APK, so an
  enabled activity would match locally and the intent would never cross.
- `CommandActivity` must be `directBootAware="true"`. Without it the work profile in a locked state
  gives "No Activity found". As a result commands also run while the work lock is locked.
- `ActivityNotFoundException` from `Commands.send` means the system could not resolve the intent.
  Android starts the work copy's process on demand, so a killed process is not the cause. The real
  causes are system state: the intent filter is missing, `CommandActivity` is disabled in the
  work profile, or the profile is stopped. Only code running in the work copy can fix the first
  two, which is why 6.9 exists.
- Clone commands keep `CommandActivity` alive on a background thread until the system answers
  (`InstallCoordinator`, up to 60 s). Other commands finish the activity immediately.
- `CommandActivity.handle()` runs inside `runCatching`. A thrown exception is logged and the
  activity finishes, so a bad command cannot leave a translucent screen stuck.

### 6.3 Command authentication

The signature permission on `CommandActivity` does **not** protect forwarded intents: the forwarder
re-launches the activity with the system uid, which passes any permission check. A shell command to
user 0 reached the work copy and triggered the toast. So commands are authenticated in code.

Current scheme (replaced the original timestamp scheme):
- **Secret**: 32 random bytes (Base64), generated by the main copy at "Create Second Space",
  delivered via the provisioning extras, stored in device-protected shared preferences (`auth`) in
  both copies. Regenerated whenever the space is recreated.
- **Counter, not clock.** The main copy keeps a persistent `send_seq`, incremented per command.
  The work copy keeps `last_seq` and accepts only a strictly greater value. The earlier timestamp
  scheme had two real bugs: a phone clock that jumped backwards rejected every command until the
  clock caught up, and a command waiting behind the work PIN prompt for more than 60 s was rejected.
- **Signature**: `HMAC-SHA256(secret, "<len(cmd)>:<cmd>|<len(arg)>:<arg>|<seq>")`. Length prefixes
  make the message unambiguous even if a field contains `|`.
- **Verification** (`Auth.verify`, implemented via the pure `Auth.isValid`): secret exists,
  signature present, `seq > last_seq`, signature matches (`MessageDigest.isEqual`, constant time).
  `last_seq` is committed before the command executes, so a crash cannot allow a replay. If the
  `commit()` fails, `verify` returns false and `sign` returns null.
- All of `sign` and `verify` is wrapped in `synchronized(lock)`. The lock is per process, which is
  correct because the two copies have separate preferences files.
- URIs attached to a command are not covered by the signature. A forger would need the secret to
  send any command at all, so this is acceptable, but the receiving side still restricts them
  (6.5).
- Unsigned or invalid commands are logged as `rejected unsigned or invalid command` and ignored.
- A space created before the secret existed, or whose main copy lost its secret, rejects every
  command. See section 13 for the planned recovery.

Argument validation in `CommandActivity` (after the signature check):
- Commands in `Commands.TAKES_PACKAGE` (`clone`, `enable_system`, `freeze`, `unfreeze`,
  `uninstall`) require `Commands.isValidPackage(arg)`: at most 255 chars, matching
  `[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+` (every segment starts with a letter).
- A package argument equal to our own package is rejected, so a command can never freeze or
  uninstall Cloak itself and kill the space.
- `set_privacy` is decoded strictly (exactly four `0`/`1` characters) or ignored.
- `ping` and `file_test` only act in debug builds.

### 6.4 Lock model and state

- The work profile has its own lock (work challenge). `requireWorkLock` sets the required
  complexity. `ACTION_SET_NEW_PASSWORD` with `EXTRA_PASSWORD_COMPLEXITY` opens the lock screen. The
  extra needs `REQUEST_PASSWORD_COMPLEXITY`, or the app crashes asynchronously with
  `MissingRequestPasswordComplexityPermissionException`.
- If the user never sets a separate work PIN, the profile uses the phone lock. Then `lockNow`
  locks the whole phone and pausing asks for nothing. Set the work lock first.
- `lockNow(FLAG_EVICT_CREDENTIAL_ENCRYPTION_KEY)` needs `<force-lock />` in `device_admin.xml`.
- Four states, read by `SpaceStateReader` for the profile chosen by `SpaceProfile`:
    - **NONE**: no managed profile found. Welcome screen.
    - **PAUSED** (`isQuietModeEnabled`): any command triggers the system's "turn on work apps"
      prompt, then the work PIN.
    - **LOCKED** (running, `!isUserUnlocked`): commands run silently (direct-boot-aware). The locked
      screen shows an Unlock button, which sends `unlock`. The system presents the PIN prompt.
    - **UNLOCKED**: the grid.
- When Cloak opens while the space is paused or locked, `HomeScreen` sends `unlock` automatically
  so the PIN prompt appears without a tap. The Unlock button remains as a fallback.
- Cloak cannot pause or resume the profile. `UserManager.requestQuietModeEnabled` is restricted to
  the default launcher or an app holding the system `MODIFY_QUIET_MODE` permission. This is a
  standard Android restriction, not an OxygenOS quirk. "Lock" means re-lock the PIN. The user
  pauses via the system toggle. A "Restart space" button was tried and removed for this reason.
- Tested: after the phone's screen lock, work apps ask for the work PIN again. Leaving a work app
  and waiting 2 minutes with the screen on does not re-lock.

### 6.5 Cloning apps

- `installExistingPackage` is not usable: it needs a device owner or an affiliated profile and
  throws `SecurityException`. Do not retry it.
- **Normal apps:** `ApkExporter.export` copies the app's `sourceDir` and all `splitSourceDirs` to
  `cache/bridge/<package>/` (it clears earlier exports first) and returns FileProvider URIs.
  `Commands.send(CLONE, pkg, uris)` puts them in `ClipData` with `FLAG_GRANT_READ_URI_PERMISSION`.
  The forwarder keeps the grant and rewrites the URIs for the other user.
  `ApkInstaller.install` streams each file into a `PackageInstaller` session
  (`MODE_FULL_INSTALL`, `setAppPackageName`) and commits. If anything fails after the session is
  created, the session is abandoned (earlier code leaked it).
  `InstallResultReceiver` launches the system Install dialog on `STATUS_PENDING_USER_ACTION`; the
  user taps Install once. The latch is released as soon as the confirm screen opens, so a
  translucent screen cannot block Cloak for up to 60 s.
- **Forwarded URI format.** Android rewrites a forwarded `content://` URI to include the sender's
  user id: `content://0@io.cloak.os.fileprovider/...`. `Uri.authority` returns `0@io.cloak.os...`,
  `Uri.host` returns the bare provider name. `CommandActivity.bridgeUris()` therefore compares
  `host`, together with `scheme == "content"`. Comparing `authority` rejected every forwarded file
  and broke cloning and the file test. Only our own provider's URIs are accepted.
- **Preinstalled system apps** (`FLAG_SYSTEM`): `enableSystemApp`. This is how the Play Store
  appears in the space, where the user signs in with a separate Google account.
- First clone only: the dialog may say unknown apps are not allowed from this source. The user
  allows Cloak in the work profile's settings and taps the app again.
- **Temporary exports.** Copies live in the main copy's cache. They are deleted when the cloned app
  appears in the space (`AddAppsScreen`) and swept when older than 10 minutes at each app start
  (`MainActivity`). `ApkExporter.cleanup(context)` with no age deletes the whole `bridge/` folder.
- Cloning is one app at a time. The UI disables other rows while one is pending (90 s timeout),
  because the command counter rejects out-of-order commands and the installer has a single latch.

### 6.6 File bridge

`FILE_TEST` proved that a file written to `cache/bridge/` in the main copy can be read inside the
work copy through a granted FileProvider URI sent over the command channel. This is the basis for
cloning and for a future "Copy to other space" action. The work-to-main direction is untested.
The file test is debug-only and only accepts our own provider (same rule as 6.5).

### 6.7 Install restrictions

Installing inside the profile was blocked ("blocked by work policy"). `allowInstalls` clears
`DISALLOW_INSTALL_APPS`, `DISALLOW_INSTALL_UNKNOWN_SOURCES` and
`DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY`. It runs on every `setupChannel`. Side effect: any
app that can install packages inside the space is not blocked from doing so (see the threat model).

### 6.8 Profile selection and the app grid

- `SpaceProfile.find` takes the cross-profile target profiles and keeps the first one whose
  `LauncherApps.getLauncherUserInfo(user).userType` is `USER_TYPE_PROFILE_MANAGED`. It is used by
  `SpaceStateReader` and `SpaceApps`, so state and grid always agree on which profile is the space.
- `getLauncherUserInfo` is an API 35 call. On API 31 to 34 it fails inside `runCatching`, the type is
  unknown, and the code assumes the profile is managed. On a phone with more than one profile that
  could select the wrong one. Known gap, section 12.
- `SpaceApps.list` uses `LauncherApps.getActivityList(null, profile)`. `rememberSpaceApps` observes
  `LauncherApps.Callback`, so the grid and the Add apps screen refresh on add, remove, change,
  suspend and unsuspend.
- Tap launches via `startMainActivity`. Long-press opens Freeze/Unfreeze (`setPackagesSuspended`,
  shown from `FLAG_SUSPENDED`) and Remove from space (`ACTION_DELETE`, system confirmation).

### 6.9 Keeping the work copy configured (self-repair)

The work copy has no screen, so it repairs itself. `setupChannel` runs in the work copy:
- from `SpaceAdminReceiver.enableProfile` when the profile is provisioned,
- from `PackageReplacedReceiver` on `MY_PACKAGE_REPLACED` (updates),
- from `PackageReplacedReceiver` on `LOCKED_BOOT_COMPLETED` and `BOOT_COMPLETED` (profile start),
- on demand, from the signed `repair` command that Settings > Repair space sends.

An earlier version let the work copy's own `MainActivity` run `setupChannel` when opened. That
screen was removed in the cleanup, and from then on a lost filter or disabled `CommandActivity` was
never repaired. Lock, freeze, privacy and ping all failed with `ActivityNotFoundException`. The
receivers above fixed it. If setup state is lost while the profile keeps running and no broadcast
fires, the fallback is: Settings > Maintenance > Repair space, then pausing and resuming Work apps
from Quick Settings, then reinstalling over the app (fires the update receiver).

If `HIDE_WORK_LAUNCHER` in `PolicyManager` is set to `false` and the app is updated, the work icon
returns. The launcher caches icons, so a stale work icon may show and fail with "Failed to read the
app data, cannot start app" until the launcher restarts.

### 6.10 Debug panel

Collapsed "Debug" section (build time, role, space state, Ping, File test, work-copy profile-owner
status). Defined in `src/debug`; `src/release` holds an empty `DebugPanel` with the same signature.
The panel uses `rememberSpaceState()` like the real UI. `ping` and `file_test` commands only do
anything in debug builds.

### 6.11 Privacy settings

`PrivacySettings` holds four switches. It travels as a four-character command argument and is
stored in device-protected preferences (`privacy`) in both copies.

| Switch | Default | Policy call | Device status |
|---|---|---|---|
| Block screenshots | off | `setScreenCaptureDisabled` | Verified |
| Hide lock-screen notification content | on | `setKeyguardDisabledFeatures(KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS)` | Not verified, see below |
| Block clipboard sharing | off | `DISALLOW_CROSS_PROFILE_COPY_PASTE` user restriction | Not working on test device |
| Keep contacts private | on | `setCrossProfileContactsSearchDisabled` + `setCrossProfileCallerIdDisabled` | Verified, OEM dialers vary |

Flow: the toggle in Settings saves locally, sends `set_privacy`, and the work copy saves and applies
it. If the send fails, the toggle and saved value are rolled back and a toast says the setting did
not change. `setupChannel` re-applies the saved settings on every update, boot and repair, and a
new space gets the defaults automatically. `DISALLOW_SHARE_INTO_MANAGED_PROFILE` is deliberately
not used, because it would block the file bridge.

Known caveats:
- **Declared policies are read when the profile owner is set.** `device_admin.xml` was extended with
  `wipe-data` and then `disable-keyguard-features` after the test space existed. Updating the app and
  rebooting did not refresh the declaration, so on that space `setKeyguardDisabledFeatures` throws a
  `SecurityException` ("did not specify uses-policy") and every privacy toggle shows the "some
  settings could not be applied" toast. A freshly created space reads the current declaration. Any
  space created by an older build has the same limitation. Delete and recreate it to verify.
- **Clipboard:** no exception is thrown, yet blocking had no effect on the test device. Not
  diagnosed. To investigate, with the switch on run
  `adb shell "dumpsys user | grep -i -E 'copy_paste|User #|UserInfo'"` and check whether
  `no_cross_profile_copy_paste` is listed for the work user. If it is not listed the restriction is
  not being applied. If it is listed, the OEM or the keyboard (clipboard history, suggestion strip)
  is bypassing it. Also test with the long-press Paste menu rather than the keyboard suggestion.
  Retest on a fresh space.
- The main copy's saved choices cannot be read back from the space. If Cloak's data in the main
  profile is cleared, Settings shows defaults until the user changes something.
- Each switch change briefly flashes a transparent screen, because it goes through
  `CommandActivity`.

### 6.12 Delete space

Settings > Delete Second Space shows a confirmation dialog ("every app and all data will be
permanently deleted, no backup"), then sends `delete_space`. The work copy checks it is the profile
owner and calls `wipeData(0)`, which removes the whole profile. The main app returns to the welcome
screen. Needs `<wipe-data />` in the declared policies (same fresh-space caveat as 6.11). Before
this existed, the profile had to be removed through system Settings or adb.

### 6.13 User interface

- **Theme** (`SpaceTheme`): one fixed dark colour scheme with a blue-tinted background gradient. A
  fixed dark theme keeps the look consistent. Status and navigation bars are transparent with light
  icons (`enableEdgeToEdge`).
- **HomeScreen** is the state machine. NONE shows `WelcomeScreen`. PAUSED or LOCKED shows
  `LockedScreen`. UNLOCKED shows the grid or a sub-page (Add apps, Settings). The page resets to
  the grid whenever the state is not UNLOCKED. System back returns from a sub-page to the grid.
- **Home grid**: top bar with title, a Lock icon and a overflow menu (Add apps, Settings), then a
  launcher-style grid of icons. An empty space shows a hint pointing to the menu. The Debug button
  is the last grid item in debug builds. There is no Add tile (removed on request). Secure
  Folder's layout was reproduced from memory.
- **Add apps**: search field, a "Get apps from the Play Store" row that opens the space's Play
  Store (or enables it first), then every launchable main-space app. Apps already in the space show
  a check mark. One add at a time with a spinner.
- **Settings**: privacy switches, Set lock, Maintenance (Check connection, Repair space), Delete.
- **State updates** (`rememberSpaceState`): refresh on `MANAGED_PROFILE_ADDED/REMOVED/AVAILABLE/
  UNAVAILABLE/UNLOCKED` broadcasts and on `ON_START`, plus a 3 s safety poll that runs only while
  the activity is started. The poll stays because it is unconfirmed whether locking by credential
  key eviction emits a broadcast on this phone.
- **Maintenance**: "Check connection" sends `ping`. It succeeds if the intent resolves in the space
  (the toast is shown by the app, not by the space). "Repair space" sends `repair`, which re-runs
  `setupChannel` in the work copy. If the channel is down, nothing in Cloak can reach the work
  copy; the message tells the user to pause and resume Work apps in Quick Settings.
- All user-visible strings are in `strings.xml`. Release lint warns about reading strings through
  `LocalContext.current` inside composables; use `stringResource` and capture the value outside the
  click lambda.

---

## 7. Command reference

All commands go through `Commands.send(context, cmd, arg, uris)` and are signed over
`<len>:cmd|<len>:arg|seq`. Only the profile owner executes them.

| Command | Arg | Runs in work copy | Notes |
|---|---|---|---|
| `ping` | none | Debug builds: toast | Channel test. Silent in release, but the send still succeeds if the intent resolves |
| `lock_now` | none | `PolicyManager.lockNow` | Locks the profile |
| `set_lock` | none | `requireWorkLock` + `ACTION_SET_NEW_PASSWORD` | Opens the lock screen |
| `unlock` | none | No-op (logs in debug) | The system's own prompt does the work |
| `clone` | package | `ApkInstaller.install` from `ClipData` URIs | Needs valid package and own-provider URIs. Activity stays alive up to 60 s |
| `enable_system` | package | `enableSystemApp` | Preinstalled apps only |
| `freeze` / `unfreeze` | package | `setFrozen` | Suspends the app |
| `uninstall` | package | `ACTION_DELETE` | System confirmation dialog |
| `set_privacy` | 4 chars of 0/1 | Save and `applyPrivacy` | Toast if some policies fail |
| `delete_space` | none | `wipeData(0)` | Irreversible |
| `repair` | none | `setupChannel` | Re-runs the full work-copy setup |
| `file_test` | `test.txt` | Reads the URI, toasts content | Debug builds only |

---

## 8. Dead ends and fixes (do not re-learn these)

| Symptom | Cause | Resolution |
|---|---|---|
| Home said "space is set up" with no work profile | Counted any extra user; OxygenOS has `MultiApp` (user 999) | Detect our space by its managed profile |
| No PIN prompt after creating the space | App never required a work lock | `requireWorkLock` plus the Set lock action |
| Crash on Set lock, "using the app outside the work profile" | Missing `REQUEST_PASSWORD_COMPLEXITY`; exception thrown asynchronously | Declare the permission |
| `CrossProfileApps` canInteract/canRequest false | Not exposed to this app on OxygenOS even after `setCrossProfilePackages` | Cross-profile intent filter channel |
| "No Activity found" for our action | Command activity not direct-boot-aware while profile locked | `android:directBootAware="true"` |
| "No Activity found" after the cleanup batch | Work copy never re-ran setup once its own screen was removed; filter or enabled state lost | Self-repair receivers (6.9). Do not remove a setup trigger without adding another |
| Toast from an unsigned shell command | Forwarder launches as the system uid, bypassing the permission | HMAC signatures |
| All commands rejected after a clock change, or after a slow PIN prompt | Timestamp-based replay check (60 s window, strictly increasing timestamps) | Persistent counter, no clock |
| `lockNow` locked the phone, pause asked no PIN | Work profile had no separate lock | Set the work PIN |
| `installExistingPackage` SecurityException | No device owner, profile not affiliated | APK copy + PackageInstaller; `enableSystemApp` for preinstalled apps |
| "Blocked by work policy" installing | Install restrictions | `allowInstalls` |
| "App not installed as package appears to be invalid" | Installed the unsigned release APK | Install the debug APK |
| Work copy not updating from Studio | Shell cannot install to the work user; early installs under restrictions | Plain update works once restrictions are cleared |
| `am start --user <work id>` denied | Shell cannot access managed profile users | Test through the app |
| Work icon visible but unlaunchable | Launcher cache | Restart the launcher |
| File test and cloning rejected after adding the URI check | `Uri.authority` includes the sender user id (`0@...`) | Compare `Uri.host` (6.5) |
| Restart space / resume refused | `requestQuietModeEnabled` needs default-launcher role or system permission | Removed. Use Repair space and the system Quick Settings toggle |
| `SecurityException ... did not specify uses-policy` | Policy added to `device_admin.xml` after the space existed; declaration read at profile-owner set | Delete and recreate the space (6.11) |
| `Cannot access 'AppEntry': it is private in file` and "no parameter named icon" | Old `CloneList.kt` and new `AddAppsScreen.kt` both declared a private `AppEntry` in one package | Delete the old file |
| Unresolved reference `Icons` | `material-icons-core` is not pulled in by Material 3 | Add the dependency (section 5) |
| Bulk `Log` to `Logx` replace made `Logx` call itself | Replace ran after `Logx.kt` existed | Replace first, then create `Logx.kt` |
| Privacy toggle showed a value the space never applied | Optimistic UI update with no rollback | Roll back on send failure |

Mistakes worth remembering: claims about permission enforcement across profiles, filter flag
directions, and cross-profile URI handling were wrong until tested on the device, and several
snippets were written without being compiled. Treat any API behaviour not yet verified on the phone
as a hypothesis. Outside review is useful but can also be wrong: an automated audit claimed the
cross-profile FileProvider was broken because both copies declare the same authority, which
contradicts the verified file bridge (see 10).

---

## 9. Security model

Full text in `docs/THREAT_MODEL.md`. Summary:

Protected:
- Commands cannot be forged by other apps: signature permission, HMAC over a length-prefixed message,
  strictly increasing counter, constant-time comparison, no dependence on the phone clock.
- Command arguments are validated (package-name pattern, self-target block, strict privacy
  decoding, own-FileProvider URIs only).
- Work data is isolated by the work profile and unlocked by the system work lock.
- Backup is disabled, so the secret cannot leave through cloud backup or device transfer.
- Temporary APK exports are in app-private cache and deleted after use.
- Release logs contain no package names, file contents or stack traces.
- Privacy policies and the work launcher state are re-applied by the work copy.

Assumptions and limits:
- Relies entirely on Android's work profile isolation. Not a security product. Rooted phones,
  forensic extraction, and anyone who knows the PIN are out of scope.
- The secret is stored unencrypted in app-private device-protected storage in each copy.
- URIs attached to commands are not signed (receiving side restricts them).
- Commands also run while the work profile is locked (direct boot aware). Acceptable for the
  current commands, but reconsider before adding anything that reveals data.
- Install restrictions are cleared in the space, so any app that can install packages there is not
  blocked.
- No idle auto-lock yet.
- Privacy policies depend on OEM behaviour; clipboard blocking is currently ineffective on the test
  device.
- If the main copy loses its secret the space becomes unmanageable from the app (section 13).
- Cloned apps show in the launcher and Settings, and their notifications show in the main shade.
  Updates happen through the space's Play Store or by cloning again. Deleting the space deletes its
  data; there is no backup.

Decisions:
- **R8 shrinking stays off.** The project is open source and the secret is in a preferences file,
  not in code, so obfuscation protects nothing. The size gain is small and shrinking risks breaking
  DPC and manifest entry points that cannot be fully tested.
- **`MainActivity` stays exported.** It has no intent filter and Studio's Run needs it.
- `INTERACT_ACROSS_PROFILES` was removed because it was proven ineffective here.

---

## 10. External audit triage (DeepSeek, strict audit)

An AI-generated audit was reviewed. Result, for the issue tracker:

| ID | Finding | Verdict |
|---|---|---|
| C-1 | Both copies declare the same FileProvider authority, so file sharing is broken | Likely wrong: cross-profile forwarding rewrites URIs to `content://<userId>@authority/...`, and the file bridge worked on the device. Confirm by cloning an app after the `host` fix |
| C-2 | Clearing the main copy's data orphans the work profile | Real. Planned recovery work, section 13 |
| L-1 | Package regex accepts segments starting with digits | Fixed |
| L-2 | `Commands.send` has no `FLAG_ACTIVITY_NEW_TASK` | Latent, skipped. Adding it could change cross-profile launch behaviour; revisit if a non-Activity caller appears |
| L-3 | `InstallCoordinator` latch race with concurrent clones | Latent. UI serializes clones. Fix with the clone queue |
| L-4 | `commit()` results ignored in `Auth` | Fixed |
| L-5 | Export and cleanup race | Latent. Fix with the clone queue |
| L-6 | Profile selection assumes managed below API 35 | Real, only on multi-profile phones. Test on a second device |
| L-7 | Privacy toggle out of sync after a failed send | Fixed (rollback) |
| L-8 | Partial `setupChannel` failure is silent | Real but mitigated by Repair space. Surface failures in the UI later |
| N-1 | Enable R8 | Declined, see section 9 |
| N-2 | Stale class names in `HANDOFF.md` | Doc cleanup, replaced by this guide |
| N-3 | `findActivity()` can throw | Latent, all callers are Activities |
| N-4 | `cleanup(0)` deletes everything | Consider renaming to `cleanupAll()` |

Items the audit marked as correct (synchronized non-local returns, per-process lock scope, HMAC
format, counter commit ordering, signature permission, direct-boot handling, receiver exports,
provisioning activity guards, self-target guard) match this guide's reading.

Publishing decision: the repo can go public after a short pass, with the remaining items filed as
issues. Nothing in the audit is remotely exploitable. Blockers before going public are the README
rewrite and a check that no keystore or `local.properties` exists in git history
(`git log --all --name-only | findstr /i "keystore jks local.properties"` must print nothing).

---

## 11. Conventions and workflow

- Conventional commits (`feat:`, `fix:`, `refactor:`, `chore:`, `docs:`, `test:`), pushed in batches.
- All `DevicePolicyManager` calls go through `PolicyManager`; UI code never calls them directly.
- Functions return `Result<T>` and log failures; never throw into the UI.
- Log through `Logx`, tag format `SecondSpace/<Class>`. No package names or data in release logs.
- No hardcoded user-visible strings; use `strings.xml`.
- One screen or class per file where practical.
- When fixing a mismatch, add the missing side; do not strip functionality.
- Do not remove a setup trigger in the work copy without adding a replacement (see 6.9).
- Debug tooling stays in `src/debug`.
- Do not trust remembered API behaviour. Verify on the device and record it here.

---

## 12. Open issues and known gaps

1. Lock prompt does not appear automatically after creating the space (Settings > Set lock works).
   The retry loop in `HomeScreen` is kept as is.
2. Clipboard blocking has no effect on the test device (6.11).
3. Lock-screen notification redaction unverified; needs a freshly created space (6.11).
4. Fingerprint is not offered for the work lock (likely an OxygenOS limit). Revisit later.
5. No idle auto-lock. Options considered:
    - Native screen-lock re-locking already works.
    - An alarm in the work copy calling `lockNow` N minutes after the last launch from Cloak: simple,
      but can lock mid-use and ignores apps opened from the launcher.
    - True lock-on-exit needs Usage access granted inside the work profile and a foreground service
      with a notification: fragile on OnePlus battery management.
      Decision pending; needs experiments on the phone.
6. Profile selection below API 35 assumes managed (L-6). `getLauncherUserInfo` may also trigger a
   lint warning at min SDK 31; check.
7. Cloning has a spinner but no real progress for large apps, and only one clone at a time.
   A queue also resolves the latch and export races (L-3, L-5).
8. The 3 s state safety poll remains until broadcast coverage is confirmed on device.
9. `Commands.send` has no `FLAG_ACTIVITY_NEW_TASK` (L-2). `findActivity()` can throw (N-3).
10. `setupChannel` failures are silent in the UI (L-8).
11. `sign` and `verify` (the `Context` parts of `Auth`) are not unit tested. Needs Robolectric or an
    instrumented test.
12. Unit test results have not been confirmed; run them and fix any wrong expectation.
13. The work-to-main file direction is untested.
14. Tested on one device only (OnePlus 13s, OxygenOS 15). Other OEMs and Android versions untested,
    in particular Samsung, Pixel, Android 12 to 14, and phones with Private Space.
15. Cloning after the URI `host` fix and the command hardening should be re-verified.
16. `docs/HANDOFF.md` and the README are outdated (the README still describes
    `installExistingPackage` and a work-copy UI, and the old package id `dev.vex.altspace`).

---

## 13. Roadmap and plans

### 13.1 Recovery and re-binding (next, with more planned)

Problem: the main copy's pairing secret is the only proof that it is the right caller. If it is
lost (data cleared, app reinstalled, storage corruption), the work profile still exists and the
work copy still holds the old secret. `Auth.sign` returns null, every command fails with a generic
error, `SpaceStateReader` still reports the space as present, and the welcome screen never appears.
The user cannot delete, repair or re-pair the space from inside the app. Today the only way out is
system Settings, removing the work profile, which loses all data in the space.

Planned work, in rough order:
1. **Detect the condition.** A space exists (a managed profile is found) but there is no secret in
   the main copy. Add a distinct state (for example `UNPAIRED`) separate from NONE, and show a
   dedicated screen instead of a grid that silently fails. Also distinguish "secret present but
   rejected" (the work copy has a different secret).
2. **Explain and give a safe exit.** The screen says what happened, and offers the guaranteed
   fallback: open system Settings to remove the work profile and start over, with a clear data-loss
   warning. This is the minimum, and does not need any new protocol.
3. **Re-binding without losing data.** The aim is to pair a new main copy with the existing space
   without deleting it. Constraints that any design must respect:
    - The channel cannot be trusted on its own, because the forwarder runs commands as the system uid.
      A re-pair path must be authorized by something only the real owner has, most naturally the
      **work lock** (the user proving they can unlock the space), not just a tap.
    - The work copy has no icon. A pairing screen must be reachable some other way, for example a
      dedicated activity in the work copy that is only enabled while an unpaired state is detected,
      and that requires the work profile to be unlocked.
    - The secret must travel between profiles through something the user confirms, not silently.
      Candidate approaches, none verified: (a) the work copy shows a short one-time pairing code after
      unlock and the user types it into the main copy, which derives a new secret; (b) the work copy
      generates a new secret after an unlock-gated confirmation and passes it through an
      authenticated handoff; (c) store the secret so it survives a data clear (for example Keystore-
      wrapped or a copy held by the work side), which needs a check of what actually survives
      "clear data" on this device before relying on it. These need design review against the threat
      model before implementation, since an unauthenticated re-pair path would let any app in the
      main profile take over the space.
4. **Make deletion always possible.** `delete_space` currently needs a working secret. Ensure the
   unpaired screen can still remove the profile (system Settings link at minimum, a signed or
   PIN-gated wipe if the re-binding design allows it).
5. **Surface setup failures** (L-8): report failed `setupChannel` steps instead of staying silent,
   and let Maintenance show what is wrong.
6. **More recovery plans are expected** from the maintainer and will be added here.

### 13.2 UI overhaul and onboarding

- Replace the simple welcome screen with a guided first run: create the space, set the lock,
  add a Google account for the Play Store, add apps.
- Clear messages when provisioning is not possible, especially when another app already owns a work
  profile, and when the device blocks managed profiles.
- Lock prompt at creation (deferred because Set lock works).
- A proper logo and icon, replacing the placeholder lock glyph.
- A fuller settings screen (about, version, limits, link to the threat model).
- Visual polish beyond the Secure Folder-style layout: spacing, motion, empty states, loading and
  error states, accessibility pass, dark and light decisions, tablet and landscape layouts.
- Possible extras, only if wanted after real use: floating add button, drag to reorder, per-space
  colour themes. Not planned by default.
- More UI work is expected; decisions will follow actual daily use.

### 13.3 Features

- **Auto-lock timer** (section 12.5).
- **"Copy to other space"** for real files, both directions. The work-to-main direction needs a test
  first. Use the existing FileProvider transport and the same URI rules.
- **Clone queue and progress**: serialise clones, show progress for large apps, fix the latch and
  export races.
- **Fix clipboard blocking** or document it as a device limitation after investigation (6.11).
- **Verify notification redaction** on a fresh space.
- **Fingerprint for the work lock**, if the platform allows it.

### 13.4 Quality and performance

- Run and fix the unit tests, then add Robolectric or instrumented tests for `sign` and `verify`.
- Performance: replace the safety poll if broadcasts prove sufficient, lazy icon loading in the
  grid and Add apps list, startup time, clone copy time.
- Test on other phones and Android versions (section 12.14).
- Static analysis and lint pass; review every exported component again after the roadmap changes.

### 13.5 Release

- Create a release keystore (outside the repo, backed up), signing config, versioning scheme.
- Code shrinking stays off (section 9), revisit only with a concrete reason.
- Screenshots.
- Rewrite the README, including these limits: cloned apps appear in the launcher and Settings
  (a third-party launcher such as Nova helps), their notifications show in the main shade, apps
  update through the space's Play Store or by cloning again, deleting the space deletes its data,
  there is no backup. Link `docs/THREAT_MODEL.md`.
- Replace `docs/HANDOFF.md` with this guide and keep the guide current.
- Before publishing: confirm no secrets in git history, file the open issues (section 10 and 12),
  GitHub release with a signed APK.

---

## 14. Milestone history

1. **Skeleton**: profile owner, provisioning, work PIN enforced on resume. Done.
2. **Command channel**: `CrossProfileApps` probe (negative), cross-profile intent filter, signed
   commands, lock now, space state. Done.
3. **App management**: install restrictions cleared, file bridge, APK-copy cloning, system-app
   enabling, freeze, remove. Done.
4. **App grid**: live grid, launch, unlock flow, launcher icon hidden. Done.
5. **Debug-only tooling** split into `src/debug`. Done.
6. **Step 1 of the feature roadmap**: managed-profile selection, privacy settings, delete space.
   Done, with the clipboard and notification caveats in 6.11.
7. **Secure Folder-style UI**: theme, home screen, state screens, grid, add-apps list, settings
   page, Add tile removed. Done.
8. **Hardening and cleanup batch**: backup disabled, counter-based signing, argument and URI
   validation, `Logx`, debug-only commands, export cleanup, installer session cleanup, step-wise
   setup, broadcast-driven state, dead code removed, threat model written. Done. It broke the
   channel because the work copy lost its only setup trigger, which led to the self-repair
   receivers and the Maintenance controls.
9. **Fixes after on-device testing**: forwarded URI `host` check, Repair space replaces Restart
   space, commit-result checks, package regex tightened, privacy rollback on failed send. Done.
10. **Tests**: unit tests for `Auth`, `Commands`, `PrivacySettings`. Written, results pending.
11. **Next**: recovery and re-binding (13.1), then the UI overhaul and onboarding (13.2), then
    features, quality, release.