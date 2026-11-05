# Second Space (Cloak): Developer Guide

Package `io.cloak.os`, app label "Cloak", working product name "Second Space".
Min SDK 31, target 36. No root.

Devices used so far:
- **OnePlus 13s, OxygenOS 15 (Android 15):** the main test device. Almost everything was built and verified here.
- **Samsung Galaxy S21 FE, One UI 8.5 (Android 16):** used for the ownership-detection probes, the unpaired and foreign screens, and checking where system settings lets you remove a work profile. It also has Secure Folder installed, which turned out useful as a negative test.

This document records what the app does, how it is built, every non-obvious thing learned while
building it, what has been verified on a device and what has not, and what is still open.
Read sections 3 to 9 before changing anything in `admin/` or `bridge/`.

Verification labels used throughout:
- **Verified**: tested on a real device by the maintainer.
- **Written, not verified**: code exists but has not been confirmed on a device.
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
- Optionally (experimental), hide every launcher app in the space whenever it is locked, which
  stops them and removes the launcher's work tab, so the space is invisible while locked.
- No root, no app virtualization, no vendor framework.

Non-goals (also stated in the README): not a hardened security product, not Knox, one work
profile per device (an Android rule).

Both copies are the same APK and depend on each other. The main copy cannot call profile-owner
APIs. The work copy has no screen the user can reach. If either side falls out of sync (stale
setup, lost secret), the app stops working, which is why self-repair (6.9) and recovery (6.15)
exist.

---

## 2. Current status

| Capability | State | Notes |
|---|---|---|
| Create the space (provision work profile) | Verified | Android 12+ flow with our own provisioning activities |
| Separate work lock | Verified | Set via Settings > Set lock. Does not auto-prompt at creation |
| Space state (none / foreign / unpaired / paused / locked / unlocked) | Verified | Profile broadcasts plus a 3 s safety poll while visible |
| Lock now | Verified | Evicts the profile key. With hide mode on it also hides apps first |
| Command channel, main to work copy | Verified | Cross-profile intent filter, HMAC-signed with a counter |
| Clone a normal app into the space | Verified | After the URI `host` fix |
| Enable a preinstalled app (Play Store, Chrome) | Verified | `enableSystemApp` |
| App grid and launching | Verified | LauncherApps with live callbacks |
| Freeze / unfreeze, remove from space | Verified | Long-press menu |
| Hide the work copy's launcher icon | Verified | Re-applied on update, boot and repair |
| Privacy: block screenshots | Verified | `setScreenCaptureDisabled` |
| Privacy: block clipboard sharing | Verified (later retest) | Earlier test failed; cause not isolated, see 6.11 |
| Privacy: contacts kept private | Verified, OEM dialers vary | |
| Privacy: hide lock-screen notification content | Written, not verified | Needs a freshly created space, see 6.11 |
| Delete space | Verified | `wipeData` behind a confirmation |
| Maintenance: check connection, repair space | Verified | Repair sends a signed `repair` command |
| Self-repair of work copy setup | Verified | Update, boot and profile start |
| **Hide mode** (hide all launcher apps on lock) | Verified, experimental | Off by default, 6.10 |
| **Boot fix** (hide again after a restart) | Verified | On by default, toggleable, 6.10 |
| **Shutdown hide** (hide when the phone shuts down) | Toggle works; reliability with many or heavy apps not measured | Off by default, 6.10 |
| **Hide all packages** (not only launcher apps) | Not built | Greyed-out placeholder in Settings, 6.10 |
| **Unpaired state and screen** | Verified | Space exists but Cloak has no secret, 6.14 |
| **Foreign work profile detection and screen** | Verified for "no Cloak inside" | Other cases left for later, 6.14 |
| **Recovery token: set up, export, import, confirm** | Verified | 6.15 |
| **Recovery: re-pair with token and work PIN** | Verified | Result relay value not recorded, 6.15 |
| Debug tooling (spikes and probes) | Working | `src/debug` only, section 5 |
| Unit tests | Written; confirm they pass | 12 test files, section 5 |
| Auto-lock timer (idle) | Dropped | Screen-off hiding covers the need, 12 |
| Cloak locks its grid on screen lock without hide mode | Not built | Needs a session marker, section 12 |
| User-facing "copy file to other space" | Not built | Transport proven, 6.6 |
| Onboarding | Not built | Section 13 |

---

## 3. Architecture

One APK installed in two Android users:

```
 user 0 (main space)                       managed profile (work profile, id varies)
 ----------------------------------        -------------------------------------------------
 MainActivity  (Role.MAIN)                  MainActivity (Role.AGENT)   - closes immediately
   HomeScreen, grid, add apps, settings,    CommandActivity             - command + recover receiver
   unpaired / foreign / recovery screens    SpaceAdminReceiver          - profile owner (DPC)
   SpaceProfile / SpaceStateReader          PolicyManager               - all DevicePolicyManager calls
   Commands.send()  --signed intent-->      SessionManager              - hide / unhide, session state
   RecoveryRestoreScreen --recover-->       LockService (foreground)    - screen-off and shutdown listener
   ApkExporter  --FileProvider URI-->       RecoveryGate                - checks the recover proof
                                            ApkInstaller / InstallResultReceiver
                                            PackageReplacedReceiver     - update and boot triggers
```

`Role.detect(context)` (`UserManager.isManagedProfile`) decides which behaviour a running copy
has. The main copy never calls profile-owner APIs. It asks the work copy to.

Why this shape: `DevicePolicyManager` calls such as suspending, hiding, locking, wiping and
enabling system apps only work from the profile owner process, which lives inside the profile.

Key consequences:
- The work copy has no visible screen, so anything that must be true there (enabled
  `CommandActivity`, registered intent filter, hidden launcher alias, applied policies, hide
  state) has to be set by code that runs there without user action (6.9).
- The channel is one-way. The main copy sends, the work copy cannot answer. Results reach the user
  only as toasts in the work profile, or through an activity result in the one flow that uses one
  (recovery, 6.15).
- The profile owner cannot be force stopped. The work copy lives until the profile stops or the
  system kills it.

---

## 4. Repository map

```
app/build.gradle.kts              buildConfig on, R8 off, material-icons-core
gradle/libs.versions.toml         version catalog
docs/
  DEVELOPER_GUIDE.md              this file
  THREAT_MODEL.md                 what is and is not protected
  HANDOFF.md                      old note, outdated, superseded by this guide
app/src/main/
  AndroidManifest.xml
  res/xml/device_admin.xml        force-lock, wipe-data, disable-keyguard-features
  res/xml/data_extraction_rules.xml   everything excluded from backup and transfer
  res/xml/file_paths.xml          FileProvider: cache/bridge/
  res/values/strings.xml
  java/io/cloak/os/
    MainActivity.kt               thin shell: finishes if AGENT, else shows HomeScreen
    common/
      Role.kt                     MAIN vs AGENT
      Logx.kt                     logging wrapper, quiet in release
    admin/                        runs mostly in the work copy
      SpaceAdminReceiver.kt       profile owner receiver; stores secret; enables profile
      GetProvisioningModeActivity.kt
      PolicyComplianceActivity.kt
      PolicyManager.kt            every DevicePolicyManager call
      CommandActivity.kt          signed commands, plus the unsigned recover request
      SessionManager.kt           hide mode: hide, unhide, session open/closed, boot reconcile
      LockService.kt              foreground service: screen-off and shutdown listener
      RecoveryGate.kt             validates a recover proof, holds the pending secret
      ApkInstaller.kt             PackageInstaller session writer + InstallCoordinator
      InstallResultReceiver.kt
      PackageUpdateReceiver.kt    declares PackageReplacedReceiver: update and boot triggers
    bridge/                       shared by both copies
      Commands.kt                 command names, argument validation, signed send()
      Auth.kt                     secret storage, signing, verification, replaceSecret
      PrivacySettings.kt          four privacy switches
      LockSettings.kt             hide mode, shutdown hide, boot fix
      SpaceProfile.kt             finds the space, classifies who owns it
      SpaceState.kt               SpaceState, Liveness, SpaceStateReader
      SpaceMaintenance.kt         connection check and repair request
      ApkExporter.kt              main copy: copies APKs to cache/bridge
      RecoveryToken.kt            token format, check characters, key derivation
      RecoveryProof.kt            proof HMAC and freshness window
      RecoveryLockout.kt          escalating delays for wrong proofs
      RecoveryStore.kt            recovery key, attempt counters, pending secrets
      RecoveryClient.kt           builds the unsigned recover and wake intents
    apps/SpaceApp.kt              SpaceApps: list and launch apps in the space
    ui/
      HomeScreen.kt               state machine and navigation
      StateScreens.kt             Welcome, Locked, Opening, Unpaired, Foreign (scrollable)
      SpaceGrid.kt, AddAppsScreen.kt, SettingsScreen.kt
      RecoverySetupScreen.kt      create, export, confirm, turn off
      RecoveryRestoreScreen.kt    re-pair with the token
      ProfileSettings.kt          opens the system screen that removes a work profile
      RememberSpaceApps.kt        live app list, plus OnSpacePackagesChanged
      RememberSpaceState.kt       space state, plus OnStart
      RememberProfileLiveness.kt  unlocked / locked / paused / missing for the recover screen
      theme/SpaceTheme.kt
app/src/debug/
  AndroidManifest.xml             adds ProbeService and PinProbeActivity (debug only)
  java/io/cloak/os/debug/
    DebugPanel.kt, SpikeRunner.kt, ScreenProbe.kt, ProbeService.kt,
    PinProbeActivity.kt, OwnershipProbe.kt
app/src/release/java/io/cloak/os/debug/
  DebugPanel.kt, SpikeRunner.kt   empty stand-ins with the same signatures
app/src/test/java/io/cloak/os/bridge/
  AuthTest, CommandsTest, PrivacySettingsTest, LockSettingsTest, SpaceProfileTest,
  RecoveryTokenTest, RecoveryProofTest, RecoveryLockoutTest
```

### 4.1 PolicyManager functions

| Function | What it does |
|---|---|
| `setupChannel` | Independent steps, one failure does not skip the rest: enable `CommandActivity`; clear and re-add the cross-profile filter; `requireWorkLock`; `allowInstalls`; hide the launcher alias (work copy only); `applyPrivacy` (work copy only); grant `POST_NOTIFICATIONS` to itself on API 33+ (work copy only, needed by the foreground service) |
| `requireWorkLock` | `setRequiredPasswordComplexity(LOW)` |
| `lockNow` | `lockNow(FLAG_EVICT_CREDENTIAL_ENCRYPTION_KEY)` |
| `allowInstalls` | Clears install and unknown-sources restrictions |
| `applyPrivacy` | Screenshots, keyguard redaction, clipboard, contacts, each run separately |
| `deleteSpace` | Profile owner check, then `wipeData(0)` |
| `enableSystemApp`, `setFrozen` | Preinstalled apps, suspend |

Hiding is not in `PolicyManager`. `SessionManager` calls `setApplicationHidden` directly because it
owns the hide record and session state. Keep it that way so the two cannot drift apart.

### 4.2 Manifest summary

- `MainActivity`: exported, no intent filter. Launcher entry is `LauncherAlias`, which the work copy
  disables independently.
- `CommandActivity`: exported, `enabled="false"` by default and enabled only in the work copy,
  `directBootAware`, translucent, `excludeFromRecents`, signature permission
  `io.cloak.os.permission.COMMAND`. Filter action: `io.cloak.os.action.COMMAND`.
- `PackageReplacedReceiver`: not exported, direct-boot-aware, listens for `MY_PACKAGE_REPLACED`,
  `LOCKED_BOOT_COMPLETED`, `BOOT_COMPLETED`.
- `LockService`: not exported, `foregroundServiceType="specialUse"` with a subtype property.
- Permissions: `REQUEST_PASSWORD_COMPLEXITY`, `REQUEST_INSTALL_PACKAGES`, `REQUEST_DELETE_PACKAGES`,
  `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`,
  `POST_NOTIFICATIONS`, and our own signature permission.
- `allowBackup="false"`, `data_extraction_rules.xml` excludes everything.
- `FileProvider`, authority `io.cloak.os.fileprovider`, not exported.
- `device_admin.xml` declares `force-lock`, `wipe-data` and `disable-keyguard-features`.
- The debug manifest (`src/debug/AndroidManifest.xml`) adds `ProbeService`, `PinProbeActivity` and
  the battery-optimization permission. None of it exists in a release build.

---

## 5. Build, run, test, debug tools

- Windows, Android Studio, USB debugging, ADB. Kotlin, Jetpack Compose (Material 3).
- `buildFeatures { compose = true; buildConfig = true }`.
- `material-icons-core` is declared explicitly. Newer Material 3 no longer brings icons in.
- Install the **debug** APK from `app\build\outputs\apk\debug\`. The release folder holds an
  unsigned APK, which Android rejects ("package appears to be invalid").
- Both copies must update together, because the signing format must match.
- Release signing needs a keystore kept outside the repo. A release-signed build cannot be installed
  over a debug-signed one.
- Code shrinking (R8) is off in release on purpose, see section 9.

### Unit tests

Run `gradlew.bat testDebugUnitTest`. Report: `app/build/reports/tests/testDebugUnitTest/index.html`.
They run on the plain JVM, so the logic under test avoids Android APIs and uses `java.util.Base64`.
`Auth.mac`, `Auth.isValid` and `SpaceProfile.classify` are `internal` for that reason.

| Test file | Covers |
|---|---|
| `AuthTest` | signing, replay counter, tamper cases, separator forgery, secret generation |
| `CommandsTest` | package-name validation, which commands take a package |
| `PrivacySettingsTest` | encode/decode round trip, bad input |
| `LockSettingsTest` | three-flag encode/decode, defaults (hide off, boot fix on), shutdown only active with hide |
| `SpaceProfileTest` | the ownership decision table |
| `RecoveryTokenTest` | token shape, alphabet, exhaustive single-character and adjacent-swap detection, file extraction, key derivation |
| `RecoveryProofTest` | proof matching, tampering, freshness window |
| `RecoveryLockoutTest` | delay schedule, countdown, clock set back |

Not covered: anything that needs a `Context` (`Auth.sign`, `Auth.verify`, `RecoveryGate`,
`SessionManager`). That needs Robolectric or an instrumented test.

### ADB cheat sheet (Windows `findstr`)

```
adb shell pm list users                          users: 0 owner, work profile, Secure Folder (150 on Samsung)
adb shell dpm list-owners                        confirms io.cloak.os is profile owner
adb logcat -d | findstr SecondSpace              all our log lines (debug builds)
adb logcat -d | findstr "SecondSpace/Probe"      ownership probe output
adb logcat -d | findstr "SecondSpace/Spike"      hide, PIN and listener spikes
adb logcat -d | findstr "Restore"                recover result code
adb logcat -c                                    clear the log before a test
adb shell pm remove-user <id>                    remove the work profile (may be blocked)
adb shell am start --user 0 -a io.cloak.os.action.COMMAND --es cmd lock_now
                                                 security test: must do nothing
adb shell am start --user 0 -a io.cloak.os.action.COMMAND --es cmd recover
                                                 security test: must do nothing visible
adb shell dumpsys user | findstr /i "no_install copy_paste"
adb shell "dumpsys package io.cloak.os | grep -i pkgFlags"       ALLOW_BACKUP must be absent
adb shell run-as io.cloak.os ls cache/bridge     must be empty or missing after a clone
adb shell dumpsys batterystats --reset           then use the phone, then:
adb shell dumpsys batterystats io.cloak.os
```
Plain `adb logcat -s TAG` filters printed nothing on the test phone even though the lines were
there. Use `findstr` on the whole log instead.

The shell cannot address the work user, so work-profile behaviour is tested through the app and
its logs. The work copy cannot be force stopped either, because it is the profile owner.

Log tags: `SecondSpace/PolicyManager`, `/Command`, `/Installer`, `/Auth`, `/Profile`, `/Home`,
`/Settings`, `/AddApps`, `/Grid`, `/Receiver`, `/AdminReceiver`, `/Maintenance`, `/Session`,
`/LockService`, `/Recovery`, `/Restore`, `/Debug`, `/Spike`, `/Probe`.

### Logging policy (`Logx`)

All production code logs through `common/Logx.kt`, never `android.util.Log` directly.
- Debug builds: normal logging with stack traces.
- Release builds: `d` is silent, `w` logs the message only, `e` logs the message plus the exception
  class name only.
- No package names, file contents, URIs, secrets, tokens or proofs are logged in release builds.
- `src/debug` code may use plain `Log` because it never ships.
- If you bulk-replace `Log.` with `Logx.`, do it before `Logx.kt` exists, or the replace rewrites
  `Logx` into infinite recursion.

### Debug tools (`src/debug`)

These are kept on purpose, to debug and refine features later. They compile only into debug
builds. The release source set holds empty stand-ins (`DebugPanel`, `SpikeRunner`) with the same
signatures, so `CommandActivity` and the UI compile in both. **If you change a signature in the
debug version, change the release stand-in too, or release builds stop compiling.**

Open the **Debug** section at the bottom of the grid (or on a state screen) to use them.

| Tool | Where | What it does |
|---|---|---|
| Ping, File test | `DebugPanel` | Channel test and file bridge test |
| Hide / Unhide one package | `SpikeRunner` ops `hide:<pkg>`, `unhide:<pkg>` | Calls `setApplicationHidden` in the work copy and reports result and time |
| Hide all / Unhide all / Hidden status | `hideall`, `unhideall`, `status` | Hides every launcher app except the work copy and work Settings, using its **own** record |
| Probe: service / Probe: plain | `probe_fgs`, `probe_plain`, `probe_status`, `probe_stop` | Screen-off listener experiments, counts events and reports whether the process survived |
| Battery exemption | `battery` | Opens the battery optimization dialog (turned out not to be needed) |
| PIN prompt now / Lock, then PIN (4 s) | `pin`, `PinProbeActivity` | Raises the work credential prompt and reports OK or cancelled |
| Probe ownership | `OwnershipProbe` | Prints profile list, types, Cloak installed flag, the filter signal, secret present, provisioning allowed and the verdict. Also runs automatically on every state change and logs |

Cautions:
- The spike hide record (`spike` preferences) is separate from the real one (`session`). If you
  hide with the spike tools, use the spike **Unhide all**. Hide mode will not unhide them.
- The spike commands are signed like any command. They do nothing in release builds.
- The debug panel auto-runs the ownership probe on each state change, which adds log lines.
- Never distribute a debug APK.

### Verifying a release build

The debug tools must not ship. After `gradlew assembleRelease`, check the APK in
`app\build\outputs\apk\release\` with Build > Analyze APK, or `apkanalyzer` from the command-line
tools if installed. Look for the absence of:
- classes `ProbeService`, `PinProbeActivity`, `OwnershipProbe`, `ScreenProbe`
- `SpikeRunner` and `DebugPanel` with anything but empty bodies
- manifest entries for `ProbeService`, `PinProbeActivity`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`

Also run `gradlew lintRelease`. Do this before every release, and again after changing anything in
`src/debug` or the debug manifest.

---

## 6. How the pieces work

### 6.1 Provisioning

1. Main copy: "Create Second Space" generates a random secret (6.3), stores it, and launches
   `ACTION_PROVISION_MANAGED_PROFILE` with `SpaceAdminReceiver` and the secret in
   `EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE`. The secret is saved before the system prompt opens, so a
   space being created is never reported as unpaired.
2. The system runs the Android 12+ flow. `GetProvisioningModeActivity` answers
   `PROVISIONING_MODE_MANAGED_PROFILE`. `PolicyComplianceActivity` finishes with `RESULT_OK`.
3. The work copy stores the secret via `Auth.storeFromProvisioning` and calls `enableProfile`:
   `setProfileName`, `setProfileEnabled`, then `setupChannel`.
4. After provisioning the main copy retries `SET_LOCK` for up to 30 s. This does not reliably open the
   lock screen. Settings > Set lock always works. Deferred.
5. `isProvisioningAllowed` returned `true` on the Samsung test phone even while a foreign work profile
   existed, so it cannot be used to decide whether Create is possible. The system flow itself fails
   at the end. That is why Cloak detects foreign profiles itself (6.14).

### 6.2 Command channel

Goal: the main copy asks the work copy to run a privileged action.

- `CrossProfileApps` interaction is unavailable here (`canInteract` and `canRequest` stay false even
  after `setCrossProfilePackages`). That code was removed.
- What works: the profile owner registers a **cross-profile intent filter**
  (`addCrossProfileIntentFilter`) for `io.cloak.os.action.COMMAND` with both
  `FLAG_MANAGED_CAN_ACCESS_PARENT` and `FLAG_PARENT_CAN_ACCESS_MANAGED`. The main copy calls
  `startActivity`. Android's forwarder (`ForwardIntentToManagedProfile`) delivers it to
  `CommandActivity` in the work profile.
- Narrowing the filter to main-to-work only broke the channel on the test device (cause not isolated),
  so both flags stay. The main copy's `CommandActivity` is disabled, so the space still cannot send
  commands to the main copy.
- `CommandActivity` is disabled in the main copy on purpose. Both copies are the same APK, so an enabled
  activity would match locally and the intent would never cross.
- `CommandActivity` must be `directBootAware`, or the intent fails with "No Activity found" while the
  work profile is locked. As a result commands also run while the work lock is locked.
- `ActivityNotFoundException` from `Commands.send` means the system could not resolve the intent. The
  process being killed is not the cause. The real causes are system state: the filter is missing,
  `CommandActivity` is disabled in the work profile, or the profile is stopped. Only code in the work
  copy can fix the first two, which is why 6.9 exists.
- Clone commands keep `CommandActivity` alive on a background thread until the system answers
  (`InstallCoordinator`, up to 60 s). Other commands finish immediately.
- `CommandActivity.handle()` runs inside `runCatching`, so a bad command cannot leave a translucent
  screen stuck.
- The main copy cannot get a reply. Anything the user needs to know comes back as a toast in the work
  profile, or as an activity result (6.15).

### 6.3 Command authentication

The signature permission on `CommandActivity` does **not** protect forwarded intents: the forwarder
re-launches the activity with the system uid, which passes any permission check. A shell command to
user 0 reached the work copy and triggered the toast. So commands are authenticated in code.

- **Secret:** 32 random bytes (Base64), generated by the main copy at "Create Second Space", delivered
  via the provisioning extras, stored in device-protected preferences (`auth`) in both copies.
- **Counter, not clock.** The main copy keeps a persistent `send_seq`. The work copy keeps `last_seq`
  and accepts only a strictly greater value. The earlier timestamp scheme rejected every command after a
  clock jump or a slow PIN prompt.
- **Signature:** `HMAC-SHA256(secret, "<len(cmd)>:<cmd>|<len(arg)>:<arg>|<seq>")`. Length prefixes make
  the message unambiguous.
- **Verification** (`Auth.verify` via the pure `Auth.isValid`): secret exists, signature present,
  `seq > last_seq`, constant-time comparison. `last_seq` is committed before the command executes.
  If a `commit()` fails, `verify` returns false and `sign` returns null.
- `sign` and `verify` run under `synchronized(lock)`, per process, which is enough because the copies
  have separate preference files.
- `Auth.replaceSecret` swaps the secret and zeroes both counters in one write. Only recovery uses it.
- URIs attached to a command are not signed. The receiving side restricts them (6.5).
- Unsigned or invalid commands are logged as rejected and ignored.
- **One exception:** the `recover` request is accepted without a signature, because the secret is exactly
  what the user lost. It has its own checks (6.15) and is handled before the signature check, never
  reaching the command `when`.

Argument validation in `CommandActivity` after the signature check:
- Commands in `Commands.TAKES_PACKAGE` (`clone`, `enable_system`, `freeze`, `unfreeze`, `uninstall`)
  require `Commands.isValidPackage(arg)`: at most 255 chars, matching
  `[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+`.
- A package argument equal to our own package is rejected, so no command can freeze or uninstall Cloak.
- `set_privacy`, `set_lock_mode`, `set_recovery` are decoded or validated strictly, or ignored.
- `ping`, `file_test` and `spike` only do anything in debug builds.

### 6.4 Lock model and state

- The work profile has its own lock. `requireWorkLock` sets the required complexity.
  `ACTION_SET_NEW_PASSWORD` with `EXTRA_PASSWORD_COMPLEXITY` opens the lock screen. It needs
  `REQUEST_PASSWORD_COMPLEXITY`.
- If the user never sets a separate work PIN, the profile uses the phone lock. Then `lockNow` locks the
  whole phone. Set the work lock first. The recovery PIN prompt also becomes a phone-PIN prompt in that
  case: it proves presence, not a second secret.
- `lockNow(FLAG_EVICT_CREDENTIAL_ENCRYPTION_KEY)` needs `<force-lock />`.
- Six states, read by `SpaceStateReader`:
  - **NONE:** no managed profile found. Welcome screen.
  - **FOREIGN:** a managed profile exists that Cloak does not manage (6.14).
  - **UNPAIRED:** a Cloak-managed profile exists but this copy has no secret (6.14).
  - **PAUSED** (`isQuietModeEnabled`): any command triggers the system's "turn on work apps" prompt.
  - **LOCKED** (`!isUserUnlocked`): commands run silently. The locked screen's Unlock button sends
    `unlock`, which makes the system show the PIN prompt.
  - **UNLOCKED:** the grid.
- Opening Cloak while paused or locked sends `unlock` automatically (`OnStart` in `HomeScreen`), also
  when returning from Recents. The state is read fresh at foreground time, because the profile
  broadcasts are not registered while Cloak is stopped.
- `Liveness` (`MISSING`, `PAUSED`, `LOCKED`, `UNLOCKED`) is a smaller read used by the recovery screen,
  which cannot rely on the secret.
- Cloak cannot pause or resume the profile. `requestQuietModeEnabled` is limited to the default launcher
  or an app with a system permission. This is a standard Android rule.
- Observed: plain screen lock does **not** kill work apps (YouTube keeps playing) and does not mark
  Cloak's grid as locked, because the profile stays storage-unlocked and only the system challenge is
  pending. The dedicated Lock key behaves differently because it evicts the key. Making Cloak lock its
  grid on plain screen lock needs a session marker the main copy can read. Not built (section 12).
- Observed: leaving a work app for 2 minutes with the screen on does not re-lock. Idle timeout was
  dropped (section 12).

### 6.5 Cloning apps

- `installExistingPackage` is not usable (needs a device owner or affiliated profile). Do not retry it.
- **Normal apps:** `ApkExporter.export` copies `sourceDir` and all `splitSourceDirs` to
  `cache/bridge/<package>/` and returns FileProvider URIs. `Commands.send(CLONE, pkg, uris)` puts them in
  `ClipData` with `FLAG_GRANT_READ_URI_PERMISSION`. The forwarder keeps the grant and rewrites the URIs
  for the other user. `ApkInstaller.install` streams each file into a `PackageInstaller` session and
  commits, abandoning the session if anything fails. `InstallResultReceiver` launches the system Install
  dialog on `STATUS_PENDING_USER_ACTION`. The user taps Install once. The latch is released as soon as
  the confirm screen opens.
- **Forwarded URI format.** Android rewrites a forwarded `content://` URI to include the sender's user id:
  `content://0@io.cloak.os.fileprovider/...`. `Uri.authority` returns `0@io.cloak.os...`, `Uri.host`
  returns the bare name. `CommandActivity.bridgeUris()` compares `scheme == "content"` and `host`.
  Comparing `authority` rejected every forwarded file.
- **Preinstalled system apps:** `enableSystemApp`. This is how the Play Store appears in the space.
- First clone only: Android may ask to allow installs from Cloak in the work profile's settings.
- **Temporary exports** live in the main copy's cache. They are deleted when the cloned app appears and
  swept when older than 10 minutes at app start.
- Cloning is one app at a time. The UI disables other rows while one is pending (90 s timeout), because
  the counter rejects out-of-order commands and the installer has one latch.
- Adding or removing an app in the space flags the hide-mode package list for refresh (6.10).

### 6.6 File bridge

`FILE_TEST` proved that a file in `cache/bridge/` in the main copy can be read in the work copy through a
granted FileProvider URI sent over the command channel. This is the basis for cloning and for a future
"Copy to other space" action. The work-to-main direction is untested. The file test is debug-only and
accepts only our own provider.

### 6.7 Install restrictions

`allowInstalls` clears `DISALLOW_INSTALL_APPS`, `DISALLOW_INSTALL_UNKNOWN_SOURCES` and
`DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY`. It runs on every `setupChannel`. Side effect: any app that
can install packages inside the space is not blocked from doing so.

### 6.8 Profile selection and the app grid

- `SpaceProfile.detect` and `find` choose the profile (6.14). `find` returns the profile only when Cloak
  manages it. State and grid use the same function so they always agree.
- `getLauncherUserInfo(user).userType` identifies the managed type. It worked on Android 15 and 16. It
  is an API 35 call, so on API 31 to 34 it fails inside `runCatching`, and the code falls back to
  "is a cross-profile target". That fallback is still a gap on phones with several profile types (12).
- `SpaceApps.list` uses `LauncherApps.getActivityList(null, profile)`. `rememberSpaceApps` observes
  `LauncherApps.Callback` so the grid refreshes on add, remove, change, suspend, unsuspend. Hidden apps
  disappear from `getActivityList`, so the grid empties when hide mode locks the space.
- Tap launches via `startMainActivity`. Long-press opens Freeze/Unfreeze and Remove from space.

### 6.9 Keeping the work copy configured (self-repair)

The work copy has no screen, so it repairs itself. `setupChannel` runs in the work copy:
- from `SpaceAdminReceiver.enableProfile` when the profile is provisioned,
- from `PackageReplacedReceiver` on `MY_PACKAGE_REPLACED` (updates),
- from `PackageReplacedReceiver` on `LOCKED_BOOT_COMPLETED` and `BOOT_COMPLETED` (profile start),
- on demand, from the signed `repair` command (Settings > Repair space).

The receiver also reconciles hide mode (6.10): always on an update, on boot only if the boot fix is on.
It always runs `setupChannel`, whatever the toggles say.

An earlier version let the work copy's own `MainActivity` run `setupChannel` when opened. That screen was
removed, and from then on a lost filter or disabled `CommandActivity` was never repaired. Every command
failed with `ActivityNotFoundException`. Do not remove a setup trigger without adding another.

Fallbacks when setup state is lost and no broadcast fires: Settings > Maintenance > Repair space, then
pausing and resuming Work apps, then reinstalling over the app.

`HIDE_WORK_LAUNCHER` in `PolicyManager` is the kill switch for the launcher alias. The launcher caches
icons, so a stale work icon may fail with "Failed to read the app data" until it restarts.

### 6.10 Hide mode, boot fix, shutdown hide (experimental)

**What it does.** With hide mode on, locking the space hides every launcher app in it. Android treats a
hidden package as uninstalled for that user: its process is killed, its notifications clear, it vanishes
from the launcher and from Settings' app list, its data is kept, and **the launcher's work tab disappears
when no work app is visible**. Opening Cloak and entering the work PIN unhides them. This also removes the
need for a third-party launcher and closes the hole of launching work apps outside Cloak.

**Measured on the OnePlus 13s (spike):** hiding an active app took about 18 ms, inactive apps 1 to 5 ms,
unhide about the same, hiding and unhiding 8 light apps about 50 ms. The 8 apps were light (calculator,
calendar and similar), so heavy apps and larger sets would take longer.

**Settings (`LockSettings`, encoded as three characters `hide|shutdown|boot`, default `001`):**

| Setting | Default | Meaning |
|---|---|---|
| Hide apps when locked | off, labelled experimental | Master switch |
| Hide apps again after a restart (recommended) | on | Boot fix |
| Also hide apps on restart (may not finish) | off | Shutdown layer |
| Hide all packages (not implemented) | greyed out | Placeholder |

The second and third switches only appear while hide mode is on.

**Lock sequence** (`SessionManager.lock`, called by screen-off, the Lock command, and the notification's
Lock action): stop the service, hide the apps, mark the session closed, then `lockNow` with key eviction.
Hiding comes first so a failure cannot leave the space half locked. If hiding is incomplete the session
stays marked open.

**Open sequence:** Cloak opens, the system PIN prompt appears, the profile unlocks, Cloak sends `session`.
The work copy unhides the recorded set, marks the session open, builds the package list once if it does
not exist, and starts `LockService`. The main copy shows an "Opening" screen until the apps appear (2 s
timeout).

**Listener (`LockService`).** The screen-off event can only be received by a receiver registered at
runtime, so something must keep the work copy's process alive. That is a foreground service, started on
open and stopped on lock, so nothing runs while the space is locked. It holds one broadcast receiver and
does nothing else, with `START_NOT_STICKY`. It shows a low-importance ongoing notification "Second Space is
open" (hidden on the lock screen, with a Lock action). The foreground type `specialUse` only exists from
API 34, older versions use plain `startForeground`.

Spike results for the listener:
- **Foreground service: reliable.** It counted every screen lock whether Cloak was in Recents or cleared.
- **Plain process with a registered receiver: not reliable.** It counted nothing once Cloak was cleared
  from Recents.
- **Battery exemption:** the dialog opened but allowing it changed nothing visible, and it is not needed
  with the service.
- Idle battery cost: expected to be small (one idle receiver). **Not measured.** Use
  `dumpsys batterystats` over a few hours.

**Why the boot fix exists (log evidence).** After a restart the work copy starts as its own user process
and the receiver fires on `LOCKED_BOOT_COMPLETED`, before the PIN. In that state credential-encrypted
storage is unavailable (`mkdir failed: errno 126 (Required key not available)`), but direct-boot-aware
components and device-protected storage work, and policy calls succeed. The original reconcile asked the
package manager for launcher apps and got **zero** (`hid 0 of 0 apps`), then marked the session closed
with nothing hidden. After the PIN, Cloak saw "no open session", started a fresh one, and showed every
app. The listener also dies with the restart, so nothing hid the apps beforehand. Apps stayed visible but
unusable until the PIN (the encrypted key is gone after a restart), so this was a tidiness problem, not a
security one.

The fix:
- **Saved package list.** The work copy keeps the set of launchable packages in device-protected storage
  and hides by package name at boot, so it never needs to enumerate apps while encrypted.
- **Event-driven list.** It is built once when the first session opens. After that it changes only when an
  app is cloned or removed: Cloak watches the space's package callbacks (`OnSpacePackagesChanged`), sets a
  flag, and sends it on the next `session` command (argument `r`). Each save compares with the stored list
  first and writes only if it changed. `lock()` also saves its live list if it differs, which catches apps
  installed through the work Play Store while Cloak was closed, at no extra enumeration cost.
- **Success means every target is hidden or gone.** An empty list or any failure leaves the session marked
  open, so the later `BOOT_COMPLETED` pass retries (it fires after the PIN).
- **No key eviction at boot.** The restart already evicted it.
- **Stale session check.** On `session`, if the session is marked open, the service is not running and the
  mark is older than 10 seconds, the work copy treats it as stale, hides everything and evicts the key.
  This also covers the case where the boot fix is off or never ran.
- The direct-boot query flags (`MATCH_DIRECT_BOOT_AWARE or MATCH_DIRECT_BOOT_UNAWARE`) are used for
  every app listing.

**Boot fix off:** boot broadcasts skip the hide step (updates still reconcile). A restart leaves the apps
visible until Cloak opens, which then hides them and asks for the PIN again.

**Shutdown layer.** While a session is open the service also registers for `ACTION_SHUTDOWN`
(re-registered when the toggle changes mid-session). It hides only, no key eviction. It stores a note
("hid N apps, complete=…, X ms") because the log buffer is cleared on restart. The note is logged on the
next boot even when the boot fix is off. The system gives a shutdown broadcast only a short window, so
with many or heavy apps it may not finish. The boot fix is the reliable layer.

**Limits and risks:**
- Hidden apps cannot receive messages or updates. Messengers catch up when the space opens.
- If Cloak loses its secret while apps are hidden, nothing can unhide them. Recovery (6.15) is the way
  out, or removing the profile. Apps staying hidden while Cloak is unpaired is intentional.
- Killing Cloak in the main profile changes nothing. The listener lives in the work copy. The work copy
  cannot be force stopped.
- Brief visibility after a restart, until the boot hide runs. The apps always need the work PIN.
- An app cloned and the phone restarted before Cloak next comes to the foreground may not be in the saved
  list. The lock-time save usually covers it.
- Unverified: whether the shutdown broadcast reaches the service reliably, and how heavy apps affect it.

**Hide all packages (future).** Apps without a launcher icon (Play services, WebView, keyboards, providers)
are not hidden today. Hiding them would stop everything from running in the space, but it is not safe to
promise: hiding something the PIN prompt or profile startup depends on could lock the user out, hiding
WebView or the package installer breaks apps and cloning, a work profile has hundreds of packages (seconds
per lock and unlock), restarting Play services on every open costs battery, and the recovery dependency
grows. A cautious path: a debug spike that lists the no-icon packages and hides only the third-party ones
first with timing, then a system subset with a denylist and an always-available "unhide everything". A
cheap middle option is a toggle for third-party no-icon apps only. Secure Folder's "Allow background
activity after locked" is the opposite direction (apps keep running while locked), so this would not be
copying a known feature.

### 6.11 Privacy settings

Four switches in `PrivacySettings`, sent as a four-character argument and stored in device-protected
preferences in both copies.

| Switch | Default | Policy call | Status |
|---|---|---|---|
| Block screenshots | off | `setScreenCaptureDisabled` | Verified |
| Hide lock-screen notification content | on | `setKeyguardDisabledFeatures(KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS)` | Not verified |
| Block clipboard sharing | off | `DISALLOW_CROSS_PROFILE_COPY_PASTE` | Verified on a later retest |
| Keep contacts private | on | `setCrossProfileContactsSearchDisabled` + `setCrossProfileCallerIdDisabled` | Verified, dialers vary |

Flow: the toggle saves locally, sends `set_privacy`, the work copy saves and applies. A failed send rolls
the toggle back with a toast. `setupChannel` re-applies the saved settings on every update, boot and
repair. `DISALLOW_SHARE_INTO_MANAGED_PROFILE` is deliberately not used, because it would block the file
bridge.

Caveats:
- **Declared policies are read when the profile owner is set.** `wipe-data` and
  `disable-keyguard-features` were added to `device_admin.xml` after the first test space existed.
  Updating and rebooting did not refresh the declaration, so `setKeyguardDisabledFeatures` threw a
  `SecurityException` on that space. A freshly created space reads the current declaration. Any space
  created by an older build has the same limitation. Delete and recreate it to verify notification
  redaction.
- **Clipboard** blocked nothing on the first test and worked on a later one. The cause was not isolated
  (stale declaration or space state is the likely suspect). If it regresses, check
  `adb shell "dumpsys user | grep -i -E 'copy_paste|User #|UserInfo'"` for `no_cross_profile_copy_paste`,
  and test with the long-press Paste menu rather than the keyboard's clipboard strip.
- The main copy's saved choices cannot be read back from the space. If Cloak's data is cleared, Settings
  shows defaults until the user changes something.
- Each switch change briefly flashes a transparent screen, because it goes through `CommandActivity`.

### 6.12 Delete space

Settings > Delete Second Space shows a confirmation, then sends `delete_space`. The work copy checks it is
the profile owner and calls `wipeData(0)`, which removes the whole profile. The main app returns to the
welcome screen. Needs `<wipe-data />` in the declared policies (same fresh-space caveat as 6.11). It
needs a working secret. If the secret is lost, the unpaired screen sends the user to system settings
instead (6.14).

### 6.13 User interface

- **Theme** (`SpaceTheme`): one fixed dark scheme with a blue-tinted gradient. Edge to edge, transparent
  bars with light icons.
- **HomeScreen** is the state machine. NONE: `WelcomeScreen`. FOREIGN: `ForeignScreen`. UNPAIRED:
  `UnpairedScreen`, or the restore screen. PAUSED or LOCKED: `LockedScreen`. UNLOCKED: the grid, or a
  sub-page (Add apps, Settings, Recovery setup, Recovery restore), or `OpeningScreen` while hide mode is
  unhiding apps. The page resets to the grid whenever the state is not UNLOCKED. System back steps back
  through sub-pages.
- **Home grid:** top bar with a Lock icon and overflow menu (Add apps, Settings), then the icon grid. An
  empty space shows a hint. There is no Add tile (removed on request). The Debug panel is the last grid
  item in debug builds.
- **Add apps:** search field, a Play Store row, every launchable main-space app. Apps already in the space
  show a check mark. One add at a time with a spinner.
- **Settings:** privacy switches, locking (hide mode, boot fix, shutdown, hide-all placeholder), Set lock,
  Maintenance (Check connection, Repair space), Recovery token, Delete.
- **State screens** share `CenteredPage`, a centered column that now scrolls, with its minimum height set
  to the screen so short content stays centered. Without that, the debug panel ran off the bottom. On the
  Samsung test phone one screen still refused to scroll while another worked, for a reason that was never
  found. The probe no longer needs the screen, because it logs automatically. Treat it as an open bug.
- **State updates** (`rememberSpaceState(refreshKey)`): refresh on `MANAGED_PROFILE_*` broadcasts, on
  `ON_START`, and on a 3 s safety poll while visible. The poll stays because it is unconfirmed whether key
  eviction emits a broadcast on every phone. `OnStart` runs a block on every return to the foreground.
- **Maintenance:** Check connection sends `ping` and succeeds if the intent resolves. Repair space sends
  `repair`. If the channel is down nothing in Cloak can reach the work copy, so the message tells the user
  to pause and resume Work apps.
- All user-visible strings are in `strings.xml`. Read them with `stringResource` outside click lambdas.

### 6.14 Ownership detection: Cloak space, foreign profile, unpaired

**The problem.** A normal app cannot ask Android who owns another profile (that call is hidden). Cloak
infers it.

**Signals** (from probes on the Samsung phone, Android 16):

| Situation | Profile list | Cloak inside | Cross-profile target | Filter resolves |
|---|---|---|---|---|
| No work profile, Secure Folder only | 0 and 150, 150 has `type=null` | no | none | no |
| Cloak space | managed type | yes | yes | yes, to `android/ForwardIntentToManagedProfile` |
| Cloak space, secret cleared | managed type | yes | yes | yes |
| Cloak space, paused | managed type | yes | yes | yes |
| Another owner's profile (TestDPC), no Cloak inside | managed type | no | none | no |
| Another owner's profile, Cloak installed by `adb` | managed type | yes | yes | no |

- Secure Folder never looks like a work profile and does not block creation.
- Only a profile owner can register a cross-profile filter, and our action is private to Cloak, so a filter
  hit outside our own package means a Cloak work copy registered it.
- `isProvisioningAllowed` is `true` in every case, so it carries no information here.
- The probes ran on the S21 FE only. The OnePlus is untested for the filter signal.

**Decision (`SpaceProfile.classify`, unit tested):**

| Cloak inside | Secret | Filter resolves | Verdict |
|---|---|---|---|
| no | – | – | FOREIGN |
| yes | present | not checked | CLOAK |
| yes | missing | yes | CLOAK (unpaired) |
| yes | missing | no | FOREIGN |

The filter query only runs when the secret is missing, so a healthy space pays nothing. A managed profile
is any other profile of the managed type, or one that is a cross-profile target when the type is unknown.

**Screens:**
- **Foreign:** explains that Android allows one work profile and it must be removed from whatever manages
  it. Buttons: Check again, Open system settings. No delete, no recover. If Cloak is installed inside the
  profile, a tip says to restart once and check again, in case it is a Cloak space whose filter was lost.
- **Unpaired:** explains that Cloak lost its link, usually because its data was cleared. Buttons: Recover
  with recovery token, and Delete space in system settings. Removing the profile is the guaranteed exit
  and loses all data.
- Opening system settings tries `android.settings.MANAGED_PROFILE_SETTINGS`, then sync settings, then
  settings. On the Samsung phone it opened the Accounts management page, where the work profile options
  are. The OnePlus path was not recorded.
- Removing the profile returns Cloak to the welcome screen on its own (profile-removed broadcast).

**Verified:** TestDPC profile with no Cloak inside reads FOREIGN, and a normal space reads CLOAK. The
other rows were left unverified on purpose.

**Known gaps:**
- A Cloak space whose filter was lost while its secret is also lost reads as FOREIGN. The boot and update
  receivers restore the filter, hence the restart tip.
- Cloak installed in someone else's profile while the main copy still holds an old secret reads as a Cloak
  space. Only `adb install` produces that.
- A foreign profile that reads `type=null` has not been seen and would be missed.
- "Secret present but rejected" is not detected. A result-returning command would solve it (section 12).
- While a new space is being created it may briefly have no Cloak inside, so the foreign screen could flash.

### 6.15 Recovery token and re-pairing

**Why.** The pairing secret is the only proof that the main copy is the right caller. If Cloak's data is
cleared, the space survives but cannot be controlled. Without recovery the only way out is removing the
profile and losing everything. Recovery is opt-in, and the default stays delete and recreate. The work
copy can never send a new secret back (the channel is one-way), so the **main copy** generates the new
secret and proves it knows the token.

**Token (`RecoveryToken`).** 26 random characters from a 32-symbol alphabet with no `0`, `O`, `1`, `I`
(130 bits) plus 2 check characters, shown as seven groups of four. The check is two weighted sums mod 32,
which always catches any single wrong character and any adjacent swap in the data (tested exhaustively).
Other typos slip through about 1 time in 1,024. Parsing ignores case, spaces and dashes and rejects input
over 128 characters. `extract` finds the token in an exported file. `deriveKey` is
`HMAC-SHA256(key = token, message = "cloak/recovery/key/v1")`, Base64, 44 characters. A word phrase was
rejected because a reliable 2,048-word list was not available.

**Set up (Settings > Recovery token).**
1. Cloak generates a token in memory. Nothing is stored on the main side.
2. Show step: the token on two lines. The window blocks screenshots and Recents thumbnails
   (`FLAG_SECURE`). There is no copy button on purpose, because the clipboard is readable by other apps and
   keyboards. The text is not selectable.
3. **Export to file:** the system "create document" picker, so the user chooses the location and Cloak
   needs no storage permission. The file holds a note and the dashed token. The screen warns that anyone
   who reads it holds the token.
4. **Confirm step:** the token is not shown. The user types it back or imports the exported file (system
   "open document" picker). Import reads at most 4 KB and ignores everything except the token.
5. **Activate** sends the signed `set_recovery` command with the derived key. The work copy stores it in
   its own `recovery` preferences, separate from the pairing secret, and shows a toast. The token never
   crosses the channel and Cloak never stores it. Success is unconfirmed beyond that toast, because there
   is no reply.
6. Replace creates a new token (the old one stops working). Turn off sends `clear_recovery`. If the screen
   is recreated (rotation) during setup, the flow restarts with a new token and nothing was installed.
7. The main copy keeps only an "enabled" hint for Settings. It is cleared when the space is gone.

**Recover (unpaired screen, or Settings > Recovery token > Reconnect).** Reconnect covers a space that
looks paired but rejects commands, which cannot be detected automatically.
1. The screen needs the space unlocked (`Liveness`). If paused or locked, a "Turn on or unlock" button sends
   an unsigned `unlock` intent, which makes the system show its own sheet. The recover button stays
   disabled until the space reads unlocked.
2. The user enters the token (type, paste or import). Cloak generates a **new secret**, computes a proof
   `HMAC(derivedKey, "label|len:secret|ts")`, keeps the new secret as a pending value (5 minutes), and
   starts the unsigned `recover` intent **for a result**. The token itself is never sent.
3. The work copy handles `recover` before the signature check (`CommandActivity.handleRecover`), only as
   the profile owner, through `RecoveryGate.evaluate` in this order: recovery key exists, profile unlocked,
   not locked out, request well formed (secret shape, proof at most 64 characters), timestamp within five
   minutes and newer than the last accepted request, then the attempt is counted **before** the proof is
   compared, then the proof is checked in constant time.
4. **Lockout:** the first three wrong proofs are free, then 30 s, 2 min, 10 min, 1 h, 6 h. A clock set back
   cannot stretch a lockout beyond its scheduled length. Only proofs with an in-window timestamp count.
5. A valid proof does not pair by itself. The work copy stores the new secret as pending (2 minutes) and
   raises the **work credential prompt** (`KeyguardManager.createConfirmDeviceCredentialIntent`). Only a
   confirmed PIN (`RESULT_OK`) makes the pending secret real (`Auth.replaceSecret`, which also zeroes both
   counters) and shows "Second Space is paired with this Cloak". Cancel, Back, locking the screen, or
   timing out discards it. If the profile has no secure lock, recovery is refused.
6. Cloak promotes its own pending secret when the result is `RESULT_OK`. If the system does not relay the
   result, Cloak shows "Waiting for Second Space" and the user taps **It worked** after seeing the toast,
   or **Try again**. Tapping It worked by mistake leaves Cloak with a secret the space does not have. The
   Reconnect path fixes that.
7. The proof is bound to the new secret, so a captured proof cannot install a different secret. The
   timestamp makes it single-use.

**PIN spike results (OnePlus, separate work PIN set).** The work copy's invisible activity can raise the
work credential prompt with our own title. A correct PIN returned OK, a wrong PIN stayed on the prompt,
Back and cancel returned cancelled, locking the screen during the prompt returned cancelled, and Home
cannot be pressed during it, so the prompt cannot be left pending. With the profile locked, the system's
own sheet appeared and the original command was dropped, so recovery requires an unlocked profile. A paused
profile shows the system's unpause prompt first. `isUsingUnifiedPassword` was false after the separate PIN
was set (before that, locking Cloak locked the whole phone).

**Verified:** setup, export, import, confirm, activation, and re-pairing with the token and work PIN all
worked. The recorded result code (`adb logcat -d | findstr Restore`, line `recover result=`) was not
reported, so it is unknown whether the forwarder relays results (`-1` means yes). The manual fallback
exists either way.

**Limits:**
- Covers loss on the main side only. If the work copy's own data is cleared, its key is gone too.
- A lost token means delete and recreate.
- Anyone with the token and the work PIN can take over the space. With a unified lock the PIN is the phone
  PIN, which proves presence but is not a second secret.
- Any app can send bad proofs to trigger the lockout and delay recovery. Normal commands are unaffected.
  Recover requests also show toasts, which an app could use to annoy the user.
- Setting the phone clock back after a recovery makes the next recovery fail until the clock catches up.
  The message says to check the date and time.
- The key sits in device-protected storage and is readable before the first unlock, like the pairing
  secret.
- A cloud-synced export folder puts the token in the cloud. The screen warns but does not check.

---

## 7. Command reference

All commands go through `Commands.send(context, cmd, arg, uris)` and are signed over
`<len>:cmd|<len>:arg|seq`, except `recover`. Only the profile owner executes them.

| Command | Arg | Runs in work copy | Notes |
|---|---|---|---|
| `ping` | none | Debug builds: toast | Channel test |
| `lock_now` | none | `SessionManager.lock` | Hides apps first if hide mode is on, then evicts the key |
| `set_lock` | none | `requireWorkLock` + `ACTION_SET_NEW_PASSWORD` | |
| `unlock` | none | No-op | The system's own prompt does the work |
| `session` | `""` or `r` | `SessionManager.onAppOpened` | Opens the hide-mode session. `r` refreshes the saved package list |
| `set_lock_mode` | 3 chars of 0/1 | Save, then `onSettingsChanged` | Hide, shutdown, boot fix |
| `clone` | package | `ApkInstaller.install` from `ClipData` URIs | Valid package and own-provider URIs only |
| `enable_system` | package | `enableSystemApp` | |
| `freeze` / `unfreeze` | package | `setFrozen` | |
| `uninstall` | package | `ACTION_DELETE` | System confirmation |
| `set_privacy` | 4 chars of 0/1 | Save and `applyPrivacy` | |
| `set_recovery` | derived key (44 chars) | Stores the recovery key | Rejected if not a valid key |
| `clear_recovery` | none | Deletes the recovery key | |
| `delete_space` | none | `wipeData(0)` | Irreversible |
| `repair` | none | `setupChannel` | |
| `recover` | extras: secret, timestamp, proof | `RecoveryGate`, then the PIN prompt | **Unsigned**, own checks (6.15). Returns an activity result |
| `file_test` | `test.txt` | Reads the URI | Debug only |
| `spike` | `op[:value]` | `SpikeRunner` | Debug only, no-op in release |

---

## 8. Dead ends and fixes (do not re-learn these)

| Symptom | Cause | Resolution |
|---|---|---|
| Home said "space is set up" with no work profile | Counted any extra user; OxygenOS has `MultiApp` (user 999) | Detect the managed profile |
| No PIN prompt after creating the space | App never required a work lock | `requireWorkLock` plus Set lock |
| Crash on Set lock | Missing `REQUEST_PASSWORD_COMPLEXITY` | Declare it |
| `CrossProfileApps` canInteract/canRequest false | Not exposed to this app | Cross-profile intent filter channel |
| "No Activity found" for our action | Command activity not direct-boot-aware | `directBootAware="true"` |
| "No Activity found" after the cleanup batch | Work copy lost its only setup trigger when its screen was removed | Self-repair receivers (6.9) |
| Toast from an unsigned shell command | Forwarder launches as the system uid | HMAC signatures |
| All commands rejected after a clock change | Timestamp replay check | Persistent counter |
| `lockNow` locked the whole phone | Work profile had no separate lock | Set the work PIN |
| `installExistingPackage` SecurityException | No device owner | APK copy + PackageInstaller |
| "Blocked by work policy" installing | Install restrictions | `allowInstalls` |
| "Package appears to be invalid" | Installed the unsigned release APK | Install the debug APK |
| File test and cloning rejected | `Uri.authority` includes the user id (`0@...`) | Compare `Uri.host` |
| Restart space / resume refused | `requestQuietModeEnabled` needs default launcher role | Removed. Repair space and the system toggle |
| `SecurityException ... did not specify uses-policy` | Policy added to `device_admin.xml` after the space existed | Delete and recreate the space |
| `Cannot access 'AppEntry': it is private in file` | Old `CloneList.kt` and new `AddAppsScreen.kt` both declared a private `AppEntry` | Delete the old file |
| Unresolved reference `Icons` | `material-icons-core` not pulled in | Add the dependency |
| Bulk `Log` to `Logx` replace made `Logx` call itself | Replace ran after `Logx.kt` existed | Replace first |
| Privacy toggle out of sync after a failed send | No rollback | Roll back on failure |
| Apps visible after a restart with hide mode on | Boot reconcile listed apps while the profile was encrypted and got none (`hid 0 of 0`), then marked the session closed | Saved package list, success only if everything is hidden (6.10) |
| Boot reconcile sometimes left Cloak showing the grid | Same bug: after the PIN, a "closed" session started fresh | Same fix |
| Plain screen-off listener counted nothing | Process killed once Cloak left Recents | Foreground service |
| Battery exemption did nothing visible | Not needed with a foreground service | Dropped |
| Debug panel ran off the bottom of state screens | Plain non-scrolling column | Scrollable `CenteredPage` with minimum height |
| One state screen still would not scroll on the Samsung phone | Not found | Open bug |
| TestDPC profile with Cloak inside read as an unpaired Cloak space | `find` took any managed profile with Cloak | Ownership classification (6.14) |
| Cloak "crashes" inside another owner's profile | The work copy's `MainActivity` finishes immediately and its icon is never hidden there | Expected, not a crash |
| `isProvisioningAllowed` always true | Platform reports it regardless of an existing profile | Detect foreign profiles ourselves |
| Locked profile: PIN command dropped after the system sheet closed | The sheet consumes the original command | Recovery requires an unlocked profile |

Mistakes worth remembering: claims about permission enforcement across profiles, filter flag directions,
cross-profile URI handling, and boot behaviour were wrong until tested. Most snippets in this project were
written without being compiled. Treat any API behaviour not verified on the phone as a hypothesis. Reading
the actual log (the boot log settled an argument that two competing theories could not) beats reasoning.
Outside review is useful but can be wrong: an automated audit claimed the cross-profile FileProvider was
broken because both copies share an authority, which contradicts the verified file bridge (section 10).

---

## 9. Security model

Full text in `docs/THREAT_MODEL.md`. Summary:

Protected:
- Commands cannot be forged by other apps: signature permission, HMAC over a length-prefixed message,
  strictly increasing counter, constant-time comparison, no dependence on the clock.
- Arguments are validated (package pattern, self-target block, strict decoding, own-FileProvider URIs).
- Work data is isolated by the work profile and unlocked by the system work lock.
- With hide mode on, work apps are invisible and stopped while the space is locked.
- Backup is disabled, so the secret cannot leave through backup or device transfer.
- Temporary APK exports are in private cache and deleted after use.
- Release logs contain no package names, file contents, URIs, secrets, tokens or proofs.
- Recovery needs the token **and** the work PIN, and wrong tokens are rate limited.
- Privacy policies, the hidden launcher alias and hide state are re-applied by the work copy.

Assumptions and limits:
- Relies entirely on Android's work profile isolation. Not a security product. Rooted phones, forensic
  extraction, and anyone who knows the PIN are out of scope.
- The secret and the recovery key are stored unencrypted in device-protected storage in each copy.
- URIs attached to commands are not signed (the receiving side restricts them).
- Commands run while the profile is locked (direct boot aware). Fine for the current commands. Reconsider
  before adding anything that reveals data.
- `recover` is the one unauthenticated entry point (6.15). Any app can send it, can trigger the lockout, and
  can show toasts in the space.
- Install restrictions are cleared in the space, so any app that can install packages there is not blocked.
- No idle auto-lock. Privacy policies depend on OEM behaviour. Notification redaction is unverified.
- If the secret is lost and recovery was not set up, the space is unmanageable from the app.
- Cloned apps show in the launcher and Settings while visible, and their notifications show in the main
  shade. With hide mode they disappear while locked.
- Updates happen through the space's Play Store or by cloning again. Deleting the space deletes its data.
  There is no backup.

Decisions:
- **R8 shrinking stays off.** The project is open source and the secret is in a preferences file, not in
  code, so obfuscation protects nothing. The size gain is small and shrinking risks breaking DPC and
  manifest entry points.
- **`MainActivity` stays exported.** It has no intent filter and Studio's Run needs it.
- **`INTERACT_ACROSS_PROFILES` was removed** because it was proven ineffective.
- **Hide mode is opt-in and experimental**, and apps stay hidden while Cloak is unpaired by design.
- **A user-held token, not a server,** for recovery. No account, no network.

---

## 11. Conventions and workflow

- Conventional commits (`feat:`, `fix:`, `refactor:`, `chore:`, `docs:`, `test:`), pushed in batches.
- All `DevicePolicyManager` calls go through `PolicyManager`, except hiding, which `SessionManager` owns.
  UI code never calls them directly.
- Functions return `Result<T>` and log failures. Never throw into the UI.
- Log through `Logx`, tag format `SecondSpace/<Class>`. No package names or secrets in release logs.
- No hardcoded user-visible strings. Use `strings.xml`.
- One screen or class per file where practical.
- When fixing a mismatch, add the missing side. Do not strip functionality.
- Do not remove a setup trigger in the work copy without adding a replacement (6.9).
- Pure decisions go in functions that run on the JVM and get unit tests (`Auth.isValid`,
  `SpaceProfile.classify`, the recovery pieces).
- Debug tooling stays in `src/debug`, with an empty stand-in in `src/release` for every symbol the main
  code touches.
- Check a release build after changes to the debug source set (section 5).
- Do not trust remembered API behaviour. Verify on the device and record it here.

---

## 12. Open issues and known gaps

1. **Lock prompt after creating the space** does not appear automatically (Settings > Set lock works).
2. **Notification redaction** is unverified and needs a freshly created space (6.11).
3. **Clipboard blocking** failed once and worked later with the cause unknown (6.11).
4. **Fingerprint** is not offered for the work lock on the OnePlus. Revisit.
5. **Idle auto-lock dropped.** Screen-off hiding covers the case. A timer would need another long-lived
   component or exact alarms, and exact alarms may not be obtainable (inexact ones can be delayed by
   minutes, a 60 s lease is not achievable that way). Not planned.
6. **Cloak does not lock its grid on plain screen lock without hide mode.** The profile stays
   storage-unlocked, so Cloak sees "unlocked". Fixing this needs the work copy to set a session marker the
   main copy can read, and a way to ask for the credential again. Not built.
7. **Result-returning commands.** The channel has no replies. A command that returns an activity result
   (as `recover` does) would let Cloak confirm that `set_recovery` was stored, detect "secret present but
   rejected", and make Check connection precise. Untested with other commands.
8. **Hide all packages** is a greyed-out placeholder (6.10).
9. **Shutdown hide reliability** with many or heavy apps is unmeasured, and so is the idle battery cost of
   the foreground service.
10. **Profile selection below API 35** falls back to "cross-profile target" and can pick the wrong profile
    on phones with several profile types (L-6). `getLauncherUserInfo` may also trigger a lint warning at
    min SDK 31.
11. **Ownership detection** is verified for "no Cloak inside" and the normal case only. The other rows, the
    paused row with a secret, and the OnePlus are untested (6.14).
12. **One state screen would not scroll** on the Samsung phone while another did (6.13).
13. **Cloning** has a spinner but no progress, and one at a time. A queue also fixes L-3 and L-5.
14. **The 3 s state safety poll** stays until broadcast coverage is confirmed.
15. **Setup failures** are silent in the UI (L-8).
16. **`Commands.send`** has no `FLAG_ACTIVITY_NEW_TASK` (L-2). `findActivity()` can throw (N-3).
17. **Context-based code is not unit tested** (`sign`, `verify`, `RecoveryGate`, `SessionManager`). Needs
    Robolectric or instrumented tests.
18. **Confirm the unit tests pass** (eight test files), and fix any wrong expectation.
19. **The recover result code** was not recorded. Run the recovery once and note the `recover result=` line.
20. **The work-to-main file direction** is untested.
21. **Devices:** the OnePlus 13s (OxygenOS 15) is the only phone used for most behaviour, and the Samsung
    S21 FE (One UI 8.5) for ownership checks only. Other makers, Android 12 to 14, and Private Space
    phones are untested.
22. **README refresh needed:** it does not yet describe hide mode, the boot fix, recovery, the unpaired and
    foreign screens, or the new limits. `docs/HANDOFF.md` is obsolete.

---

## 13. Roadmap and plans

### 13.1 UI overhaul and onboarding (next)

- Replace the simple welcome screen with a guided first run: create the space, set the lock, add a Google
  account for the Play Store, add apps, optionally set a recovery token and turn on hide mode.
- Clear messages when provisioning is impossible (managed profiles blocked by the device or policy), on
  top of the foreign-owner screen that exists now.
- Lock prompt at creation (deferred because Set lock works).
- A proper logo and icon, replacing the placeholder lock glyph and the notification icon.
- A fuller settings screen: about, version, limits, link to the threat model, an explanation of each lock
  setting in plain language, hide mode marked experimental with its trade-offs.
- Visual polish beyond the Secure Folder layout: spacing, motion, empty, loading and error states, an
  accessibility pass, tablet and landscape layouts.
- Fix or understand the state screen that will not scroll.
- Possible extras, only if wanted after real use: floating add button, drag to reorder, per-space colour
  themes. Not planned by default.
- More UI work is expected. Decisions will follow actual daily use.

### 13.2 Recovery follow-ups

- Result-returning commands for confirmed setup and "secret rejected" detection (12.7).
- Optionally nudge users to set a recovery token when they turn on hide mode, since losing the secret then
  also traps the apps.
- Re-test recovery on the Samsung phone and record the result code.

### 13.3 Locking features

- Hide all packages, starting with the debug spike described in 6.10.
- Cloak locking its grid on plain screen lock when hide mode is off (12.6).
- Measure shutdown hide with heavy apps and decide whether to keep it, document it harder, or drop it.
- Measure the foreground service's idle battery use.

### 13.4 Other features

- "Copy to other space" for real files, both directions. Test work-to-main first. Reuse the FileProvider
  transport and the URI rules.
- Clone queue and progress: serialise clones, show progress, fix L-3 and L-5.
- Verify notification redaction on a fresh space.
- Fingerprint for the work lock, if the platform allows it.

### 13.5 Quality and performance

- Run and fix the unit tests, then add Robolectric or instrumented tests for the Context-based code.
- Performance: replace the safety poll if broadcasts prove sufficient, lazy icon loading, startup time,
  clone copy time.
- Test on other phones and Android versions (12.21).
- Static analysis and a lint pass. Review every exported component again after the roadmap changes.
- Decide how long to keep the debug spikes. They are cheap to keep and useful, so the plan is to keep
  them.

### 13.6 Release

- Create a release keystore (outside the repo, backed up), a signing config and a versioning scheme.
- Run the release verification in section 5 so no debug tool ships.
- Code shrinking stays off (section 9).
- Screenshots.
- Rewrite the README with the new features and limits: cloned apps appear in the launcher and Settings
  unless hide mode is on, notifications show in the main shade, apps update through the space's Play Store
  or by cloning again, deleting the space deletes its data, there is no backup, hide mode is experimental
  and apps may be briefly visible after a restart, the recovery token must be kept safely, and behaviour
  differs between phones. Link `docs/THREAT_MODEL.md`.
- Replace `docs/HANDOFF.md` with this guide and keep the guide current.
- Before publishing: confirm no secrets in git history, file the open issues (sections 10 and 12), and make
  a GitHub release with a signed APK.

---

## 14. Milestone history

1. **Skeleton:** profile owner, provisioning, work PIN enforced on resume. Done.
2. **Command channel:** `CrossProfileApps` probe (negative), cross-profile intent filter, signed commands,
   lock now, space state. Done.
3. **App management:** install restrictions cleared, file bridge, APK-copy cloning, system-app enabling,
   freeze, remove. Done.
4. **App grid:** live grid, launch, unlock flow, launcher icon hidden. Done.
5. **Debug-only tooling** split into `src/debug`. Done.
6. **Feature roadmap step 1:** managed-profile selection, privacy settings, delete space. Done.
7. **Secure Folder-style UI:** theme, home, state screens, grid, add-apps list, settings page. Done.
8. **Hardening and cleanup:** backup disabled, counter-based signing, argument and URI validation, `Logx`,
   debug-only commands, export cleanup, installer session cleanup, step-wise setup, broadcast-driven state,
   dead code removed, threat model written. Done. It broke the channel because the work copy lost its only
   setup trigger, which led to the self-repair receivers and the Maintenance controls.
9. **Fixes after on-device testing:** forwarded URI `host` check, Repair space replaces Restart space,
   commit-result checks, package regex, privacy rollback. Done.
10. **Tests:** unit tests for the pure logic. Written, confirm they pass.
11. **Locking research:** spikes for hiding apps and the screen-off listener. Hiding works and hides the
    work tab, the foreground service is reliable, the plain listener and battery exemption are not.
12. **Hide mode:** `SessionManager`, `LockService`, settings toggle, Opening screen. Done, experimental.
13. **Reboot fix:** diagnosed from the boot log, saved package list, boot fix and shutdown toggles,
    event-driven list refresh, hide-all placeholder. Done.
14. **Unpaired state:** detection and safe-exit screen. Done.
15. **Ownership detection:** probes on two phones, foreign owner classification and screen. Done.
16. **Recovery:** PIN prompt spike, token and setup, then the recover entry point with proof, lockout, work
    PIN gate and the restore screens. Done and verified.
17. **Next:** UI overhaul and onboarding (13.1), then locking and feature follow-ups, quality, release.