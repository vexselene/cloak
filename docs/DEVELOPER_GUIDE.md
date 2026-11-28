# Second Space (Cloak): Developer Guide

Package `io.cloak.os`, app label "Cloak", working product name "Second Space".
Min SDK 31, target 36. No root.

"Cloak" is the app. "Second Space" is the protected area it manages. Use the two names that way in text.

Devices used so far:
- **OnePlus 13s, OxygenOS 15 (Android 15):** the main test device. Almost everything was built and verified here.
- **Samsung Galaxy S21 FE, One UI 8.5 (Android 16):** used for ownership-detection probes, the unpaired and foreign screens, and checking where system settings lets you remove a work profile. It has Secure Folder installed, which turned out useful as a negative test and as a behaviour reference for unlocking.

**Release status:** version 1.0.0 is the first stable release. Earlier tags were pre-releases. The minimum Android version is 12 (API 31). The app is developed on Android 15 and checked on Android 16. Android 12 to 14 are supported in the code but untested on a real phone (6.20).

This document records what the app does, how it is built, every non-obvious thing learned while building it, what has been verified on a device and what has not, and what is still open. Read sections 3 to 9 before changing anything in `admin/` or `bridge/`.

Verification labels used throughout:
- **Verified**: tested on a real device by the maintainer.
- **Reported working**: the maintainer said it works but edge cases were not listed.
- **Written, not verified**: code exists but has not been confirmed on a device.
- **Hypothesis**: believed from API documentation or memory. Treat it as unproven until tested.

---

## 1. What this app is

A Secure Folder-style second space for Android, built on the managed work profile. The user sees one icon, in the main space (user 0). Opening it shows a grid of apps that live in a hidden, separately locked space. The work profile is the isolation boundary. The app is both its Device Policy Controller (profile owner) and its user interface.

Design goals:
- One visible Cloak icon, in the main space. The work-profile copy of the app is hidden and is never used directly by the user.
- The main-space app is the entire interface. The work-profile copy is a background agent that performs privileged actions on command.
- The gate is the system-enforced work lock (a separate PIN or pattern), not an app-level password, because work apps can be launched from the launcher without passing through our UI.
- Optionally (experimental), hide every launcher app in the space whenever it is locked, which stops them and removes the launcher's work tab, so the space is invisible while locked.
- Copy or move files and folders between the personal side and the space, with a verified move that removes originals only after the copy is checked (6.16).
- A guided first run (6.18) and a recovery token for re-pairing (6.15).
- No root, no app virtualization, no vendor framework.

Non-goals (also stated in the README): not a hardened security product, not Knox, one work profile per device (an Android rule).

Both copies are the same APK and depend on each other. The main copy cannot call profile-owner APIs. The work copy has no screen the user can reach. If either side falls out of sync (stale setup, lost secret), the app stops working, which is why self-repair (6.9) and recovery (6.15) exist.

---

## 2. Current status

| Capability | State | Notes |
|---|---|---|
| Create the space (provision work profile) | Verified | Android 12+ flow with our own provisioning activities. If setup does not finish, a message says so |
| Separate work lock | Verified | Set from onboarding or Settings. `lock_status` checks that it is separate |
| Space state (none / foreign / unpaired / paused / locked / unlocked) | Verified | Profile broadcasts plus a 3 s safety poll while visible, read off the main thread |
| Lock now | Verified | Evicts the profile key. With hide mode on it also hides apps first |
| Command channel, main to work copy | Verified | Cross-profile intent filter, HMAC-signed with a counter |
| Clone a normal app into the space | Verified | After the URI `host` fix |
| Enable a preinstalled app (Play Store, Chrome) | Verified | `enableSystemApp` |
| App grid and launching | Verified | One shared app list (`SpaceAppsHolder`, 6.8) |
| Freeze / unfreeze, remove from space | Verified | Long-press menu |
| Hide the work copy's launcher icon | Verified | Re-applied on update, boot and repair |
| Privacy: block screenshots | Verified | `setScreenCaptureDisabled` |
| Privacy: block clipboard sharing | Verified (later retest) | Earlier failure's cause not isolated, 6.11 |
| Privacy: contacts kept private | Verified, OEM dialers vary | |
| Privacy: hide lock-screen notification content | Written, not verified | Needs a freshly created space, 6.11 |
| Delete space | Verified | `wipeData` behind a confirmation |
| Maintenance: check connection, repair space, allow installs | Verified | Repair sends a signed `repair` command |
| Self-repair of work copy setup | Verified | Update, boot and profile start. The receiver now uses `goAsync` |
| Hide mode (hide all launcher apps on lock) | Verified, experimental | Off by default, 6.10 |
| Boot fix (hide again after a restart) | Verified | On by default, toggleable |
| Shutdown hide | Toggle works; reliability with heavy apps not measured | Off by default |
| Grid settles after unhide on slow phones | Written, not verified | `session` returns a count, Cloak refreshes until it matches, 6.10 |
| Recover apps left hidden after the hide record is lost | Written, not verified | `recoverOrphans`, 6.10 |
| Hide all packages | Not built | Greyed-out row in Settings |
| Unpaired state and screen | Reported working | 6.14 |
| Foreign work profile detection and screen | Verified for "no Cloak inside" and the normal case | Other rows untested. Limited on Android 12 to 14 (6.20) |
| Recovery token: set up, export, import, confirm | Verified | 6.15 |
| Recovery: re-pair with token and work PIN | Verified | Result relay value not recorded |
| File transfer via share sheet, both directions | Reported working | Space to personal needs a setting, 6.16 |
| In-app transfer page, copy, both directions | Reported working | Edge cases untested |
| Move with verified deletion | Reported working | Safety cases untested: source changed mid-copy, swipe-away, restart |
| Conflict prompt mid-copy (replace, keep both, skip, apply to all) | Written, not verified | 6.16 |
| Folder picker inside the space | Verified | Needs a documents app enabled there |
| Secure Folder-style UI, pebble icons, thumb-reach grid, themes | Verified through several feedback rounds | 6.13, 6.17 |
| Returning to Cloak after the work PIN | Partly working | Unlock event written, behaviour mixed, 6.19 |
| Unlock screen stuck after a fingerprint unlock | Open | One report on a Motorola phone, not reproduced, 6.19 |
| Onboarding (lock, Play Store, install permission, apps, protection) | Reported working | 6.18 |
| Android 12 to 14 | Untested | Minimum is API 31, 6.20 |
| Signing paths and release workflow | Reported working | Pre-releases were published. 1.0.0 is the first stable release, section 5 |
| Debug tooling (spikes and probes) | Working | `src/debug` only |
| Unit tests | 18 files | Run the whole suite before every release |
| Auto-lock timer (idle) | Dropped | Screen-off hiding covers the need |
| Cloak locks its grid on plain screen lock without hide mode | Not built | Needs a session marker |
| Sharing UI rework | On hold | A separate batch planned by the maintainer |

---

## 3. Architecture

One APK installed in two Android users:

```
 user 0 (main space)                       managed profile (work profile, id varies)
 ----------------------------------        -------------------------------------------------
 MainActivity  (Role.MAIN)                  MainActivity (Role.AGENT)   - closes immediately
   HomeScreen: onboarding, grid, pages      CommandActivity             - commands, recover, results
   SpaceProfile / SpaceStateReader          SpaceAdminReceiver          - profile owner (DPC)
   Commands.send()/build() --signed-->      PolicyManager               - all DevicePolicyManager calls
   RecoveryRestoreScreen --recover-->       SessionManager, LockService - hide mode, screen-off listener
   TransferReconciler --report/done-->      TransferSendActivity        - picks files in the space
   ShareReceiverActivity (personal)         ShareReceiverActivity       - receives into the space
   EventAlias <------------ event --------  TransferService             - foreground copy and settle
   TransferInAlias <---- TRANSFER_IN -----  RecoveryGate, ReportStore   - proof check, reports
   ApkExporter --FileProvider URI------>    ApkInstaller / InstallResultReceiver
                                            PackageReplacedReceiver     - update and boot triggers
```

`Role.detect(context)` (`UserManager.isManagedProfile`) decides which behaviour a running copy has. The main copy never calls profile-owner APIs. It asks the work copy to.

Why this shape: `DevicePolicyManager` calls such as suspending, hiding, locking, wiping and enabling system apps only work from the profile owner process, which lives inside the profile.

Key consequences:
- The work copy has no visible screen, so anything that must be true there (enabled `CommandActivity`, registered intent filters, hidden launcher alias, applied policies, hide state) has to be set by code that runs there without user action (6.9). `setupChannel` clears and rebuilds **every** cross-profile filter each time it runs, so any new filter must be added there.
- The command channel is one-way: the main copy sends, the work copy cannot answer. **Activity results do cross profiles** (verified with a ping: result `-1` plus an extra), so a command started for a result can return data. That is how `lock_status`, `transfer_report`, `transfer_done` and the debug ping answer. A result is delivered when the activity finishes, so a command that must answer cannot also stay open.
- A result is **not** delivered for a launch that the system swaps for the credential screen (the `unlock` request returned `0`, 6.19).
- Work-initiated messages need their own inbound action with an alias enabled only in the main copy: `TRANSFER_IN` (a transfer from the space) and `SPACE_EVENT` (unlock hint). Both aliases are disabled in the space so the intent cannot resolve locally there.
- The profile owner cannot be force stopped. The work copy lives until the profile stops or the system kills it.

---

## 4. Repository map

```
app/build.gradle.kts              buildConfig on, R8 off, signing paths, derived version code
gradle/libs.versions.toml         version catalog
gradle.properties                 cloak.version
keystore.properties               release key location (gitignored, never committed)
.github/
  workflows/build.yaml            existing build workflow
  workflows/release.yaml          tag-triggered release workflow
  release.yml, release-body.md    release notes grouping and header
docs/
  DEVELOPER_GUIDE.md              this file
  THREAT_MODEL.md                 what is and is not protected
  RELEASING.md                    release steps
  release-cert.sha256             expected public certificate digest
app/src/main/
  AndroidManifest.xml
  res/xml/device_admin.xml        force-lock, wipe-data, disable-keyguard-features
  res/xml/data_extraction_rules.xml   everything excluded from backup and transfer
  res/xml/file_paths.xml          FileProvider: cache/bridge/
  res/values/strings.xml, colors.xml, themes.xml
  res/drawable/                   adaptive launcher icon layers, ic_logo_egg, ic_stat_space, ic_stat_transfer
  java/io/cloak/os/
    MainActivity.kt               thin shell: finishes if AGENT, else HomeScreen; handles SPACE_EVENT
    common/Role.kt, Logx.kt
    admin/                        runs mostly in the work copy
      SpaceAdminReceiver.kt, GetProvisioningModeActivity.kt, PolicyComplianceActivity.kt
      PolicyManager.kt            every DevicePolicyManager call except hiding
      CommandActivity.kt          signed commands, the unsigned recover request, results
      SessionManager.kt           hide mode, hide record, orphan recovery, boot reconcile
      LockService.kt              foreground service: screen-off and shutdown listener
      RecoveryGate.kt             validates a recover proof, holds the pending secret
      TransferSendActivity.kt     picks in the space and forwards to personal
      ApkInstaller.kt, InstallResultReceiver.kt
      PackageReplacedReceiver.kt  update and boot triggers
    bridge/                       shared by both copies
      Commands.kt, Auth.kt, SpaceEvents.kt
      PrivacySettings.kt, LockSettings.kt, TransferSettings.kt
      SpaceProfile.kt, SpaceState.kt, SpaceMaintenance.kt, ApkExporter.kt
      RecoveryToken.kt, RecoveryProof.kt, RecoveryLockout.kt, RecoveryStore.kt, RecoveryClient.kt
    apps/SpaceApp.kt              SpaceApps: list and launch apps in the space
    transfer/                     copy and move between profiles
      TransferModels.kt           policy, action, op, phase, job, state, conflict, formatting helpers
      ConflictRules.kt            clash decisions and case-insensitive name keys
      TransferEngine.kt           plans, copies, pauses on clashes, verifies sizes, builds the report
      TransferHub.kt              in-process state flow, cancel and answer hooks
      TransferService.kt          foreground service: notification, wake lock, runs engine or settle
      TransferText.kt, DocTree.kt, ShareIntents.kt, Grants.kt
      TransferReport.kt, ReportStore.kt, MoveLedger.kt, SourceScanner.kt, SettleEngine.kt
      Pickers.kt, TransferFlows.kt, FolderPicker.kt
    ui/
      HomeScreen.kt               state machine, onboarding switch, navigation
      SpaceHome.kt, HeaderCollapse.kt      grid screen and its collapsing header
      StateScreens.kt             Welcome, Locked, Opening, Unpaired, Foreign
      SpaceGrid.kt, AddAppsScreen.kt, SettingsScreen.kt, SpaceMenu.kt, IconRenderer.kt
      OnboardingStore.kt, OnboardingFlow.kt
      RecoverySetupScreen.kt, RecoveryRestoreScreen.kt, ProfileSettings.kt
      ShareReceiverActivity.kt, TransferScreen.kt, TransferCard.kt, ConflictCard.kt, TransferReconciler.kt
      RememberSpaceApps.kt (SpaceAppsHolder), RememberSpaceState.kt (with OnStart, OnResume, OnSpacePackagesChanged), UnhideSettle.kt,
      RememberProfileLiveness.kt
      theme/Pebble.kt, ThemePreset.kt, SpaceTheme.kt (with ThemeStore, LocalTheme)
app/src/debug/
  AndroidManifest.xml             adds probe components and picker queries (debug only)
  java/io/cloak/os/debug/
    DebugPanel.kt, SpikeRunner.kt, ScreenProbe.kt, ProbeService.kt, PinProbeActivity.kt,
    OwnershipProbe.kt, ShareProbeActivity.kt, TransferProbeActivity.kt, TransferProbeUtil.kt
app/src/release/java/io/cloak/os/debug/
  DebugPanel.kt, SpikeRunner.kt   empty stand-ins with the same signatures
app/src/test/java/io/cloak/os/
  bridge/: AuthTest, CommandsTest, PrivacySettingsTest, LockSettingsTest, SpaceProfileTest,
           RecoveryTokenTest, RecoveryProofTest, RecoveryLockoutTest, TransferSettingsTest, SpaceEventsTest
  transfer/: TransferModelsTest, TransferReportTest, ConflictRulesTest
  ui/: OnboardingRulesTest, UnhideSettleTest, CollapseModelTest
  ui/theme/: PebbleMathTest, ThemePresetTest
```

Removed during the tidy-up: `CloneList.kt`, `backup_rules.xml`, the work-copy `AgentScreen`, `cloneApp`, `allowCrossProfile`, the `INTERACT_ACROSS_PROFILES` permission, `Squircle.kt`, template leftovers (`ui/theme/Color.kt`, `Theme.kt`, `Type.kt`, `ExampleInstrumentedTest`), the unused `.webp` launcher icons and `ic_logo_mark.xml`, and `HANDOFF.md`. `PackageUpdateReceiver.kt` was renamed to `PackageReplacedReceiver.kt` to match its class.

### 4.1 PolicyManager functions

| Function | What it does |
|---|---|
| `setupChannel` | Independent steps, one failure does not skip the rest: enable `CommandActivity`; clear and re-add the command filter; share filters (if the setting is on); transfer filter and alias; event filter and alias; `requireWorkLock`; `allowInstalls`; hide the launcher alias (work copy only); `applyPrivacy`; grant `POST_NOTIFICATIONS` to itself on API 33+; `recoverOrphans`. The work copy runs the agent-only steps |
| `requireWorkLock` | `setRequiredPasswordComplexity(LOW)` |
| `lockNow` | `lockNow(FLAG_EVICT_CREDENTIAL_ENCRYPTION_KEY)` |
| `allowInstalls` | Clears install and unknown-sources restrictions |
| `applyPrivacy` | Screenshots, keyguard redaction, clipboard, contacts, each run separately |
| `deleteSpace` | Profile owner check, then `wipeData(0)` |
| `enableSystemApp`, `setFrozen` | Preinstalled apps, suspend |

Hiding is not in `PolicyManager`. `SessionManager` calls `setApplicationHidden` directly because it owns the hide record and session state.

### 4.2 Manifest summary

- `MainActivity`: exported, `launchMode="singleTop"`, no intent filter. The launcher entry is `LauncherAlias`, which the work copy disables.
- `EventAlias`: exported alias of `MainActivity` for the action `SPACE_EVENT`. Disabled in the space by `setupChannel`.
- `ShareReceiverActivity`: exported, filters `SEND` and `SEND_MULTIPLE` for `*/*`. A share target in both copies.
- `TransferInAlias`: exported alias of the receiver for the private `TRANSFER_IN` action. Disabled in the space.
- `TransferSendActivity`: not exported, translucent.
- `TransferService`: not exported, `dataSync`. `LockService`: not exported, `specialUse`.
- `CommandActivity`: exported, `enabled="false"` by default and enabled only in the work copy, `directBootAware`, translucent, `excludeFromRecents`, signature permission `io.cloak.os.permission.COMMAND`, filter `io.cloak.os.action.COMMAND`.
- `PackageReplacedReceiver`: not exported, direct-boot-aware, listens for `MY_PACKAGE_REPLACED`, `LOCKED_BOOT_COMPLETED`, `BOOT_COMPLETED`.
- Permissions: `REQUEST_PASSWORD_COMPLEXITY`, `REQUEST_INSTALL_PACKAGES`, `REQUEST_DELETE_PACKAGES`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`, `WAKE_LOCK`, and our own signature permission.
- `allowBackup="false"`, `data_extraction_rules.xml` excludes everything.
- `<queries>`: the launcher intent and `OPEN_DOCUMENT_TREE`.
- `FileProvider`, authority `io.cloak.os.fileprovider`, not exported.
- `device_admin.xml` declares `force-lock`, `wipe-data`, `disable-keyguard-features`.
- The debug manifest adds `ProbeService`, `PinProbeActivity`, `ShareProbeActivity` (label "Cloak probe"), `TransferProbeActivity`, the battery-optimization permission and picker queries. None of it exists in a release build.
- `themes.xml`: `Theme.Cloak` sets the window and splash background to the Ocean colour.

---

## 5. Build, run, test, debug tools, releases

- Windows, Android Studio, USB debugging, ADB. Kotlin, Jetpack Compose (Material 3).
- `buildFeatures { compose = true; buildConfig = true }`. `material-icons-core` is declared explicitly.
- Install the **debug** APK from `app\build\outputs\apk\debug\`, or a release build signed with the debug key (below). A release build with no signing is refused by Android and by the build itself.
- Both copies must update together, because the signing format must match. After installing, check that the work copy has updated too: a stale work copy once hid a new debug button.
- Code shrinking (R8) is off in release on purpose, see section 9.

### Unit tests

Run `gradlew.bat testDebugUnitTest`. Report: `app/build/reports/tests/testDebugUnitTest/index.html`. They run on the plain JVM, so the logic under test avoids Android APIs and uses `java.util.Base64`. `Auth.mac`, `Auth.isValid` and `SpaceProfile.classify` are `internal` for that reason.

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
| `TransferSettingsTest` | the share switch encoding |
| `SpaceEventsTest` | known event names, pulse counter |
| `TransferModelsTest` | byte formatting, safe names, progress fraction, running phases |
| `TransferReportTest` | report round trip, malformed input, size limits, id format |
| `ConflictRulesTest` | ask, fixed policies, apply to all, case folding |
| `OnboardingRulesTest` | step clamping, next and previous, existing spaces skip onboarding |
| `UnhideSettleTest` | when the grid refresh loop is done, with and without a count from the space |
| `CollapseModelTest` | header collapse maths and fade curves |
| `PebbleMathTest` | shape bounds, circle-squircle mix, edge curvature |
| `ThemePresetTest` | unique ids, default, fallback, opaque colours |

The first eight passed when they were written. The other ten (18 files in total) were added later, so run the whole suite before every release. Not covered: anything that needs a Context (`Auth.sign`, `Auth.verify`, `RecoveryGate`, `SessionManager`, the transfer engines, hub and service, the stores). That needs Robolectric or an instrumented test.

### ADB cheat sheet (Windows `findstr`)

```
adb shell pm list users                          users: 0 owner, work profile, Secure Folder (150 on Samsung)
adb shell dpm list-owners                        confirms io.cloak.os is profile owner
adb logcat -d | findstr SecondSpace              all our log lines (debug builds)
adb logcat -v time -d | findstr "SecondSpace/Event SecondSpace/Command SecondSpace/Home"
adb logcat -d | findstr "SecondSpace/Probe"      ownership probe output
adb logcat -d | findstr "SecondSpace/Transfer"   transfer and transfer probe lines
adb logcat -d | findstr "orphan scan"            hidden-app recovery
adb logcat -d | findstr "Restore"                recover result code
adb logcat -c                                    clear the log before a test
adb shell pm remove-user <id>                    remove the work profile (may be blocked)
adb shell am start --user 0 -a io.cloak.os.action.COMMAND --es cmd lock_now
                                                 security test: must do nothing
adb shell am start --user 0 -a io.cloak.os.action.COMMAND --es cmd recover
                                                 must do nothing visible
adb shell am start --user 0 -a io.cloak.os.action.SPACE_EVENT --es event unlocked
                                                 only brings Cloak forward
adb shell dumpsys user | findstr /i "no_install copy_paste"
adb shell "dumpsys package io.cloak.os | grep -i pkgFlags"       ALLOW_BACKUP must be absent
adb shell run-as io.cloak.os ls cache/bridge     personal copy only
adb shell dumpsys batterystats --reset           then use the phone, then:
adb shell dumpsys batterystats io.cloak.os
```
Plain `adb logcat -s TAG` filters printed nothing on the test phone even though the lines were there. Use `findstr` on the whole log, or `-s "Tag:*"` with explicit tags. The shell cannot address the work user, and the work copy cannot be force stopped.

Log tags: `SecondSpace/PolicyManager`, `/Command`, `/Installer`, `/Auth`, `/Profile`, `/Home`, `/Settings`, `/AddApps`, `/Grid`, `/Receiver`, `/AdminReceiver`, `/Maintenance`, `/Session`, `/LockService`, `/Recovery`, `/Restore`, `/Transfer`, `/TransferService`, `/TransferSend`, `/ShareReceiver`, `/Settle`, `/Reconcile`, `/Event`, `/Onboarding`, `/Debug`, `/Spike`, `/Probe`.

### Logging policy (`Logx`)

All production code logs through `common/Logx.kt`, never `android.util.Log` directly.
- Debug builds: normal logging with stack traces.
- Release builds: `d` is silent, `w` logs the message only, `e` logs the message plus the exception class name only.
- No package names, file names, URIs, secrets, tokens or proofs are logged in release builds.
- `src/debug` code may use plain `Log` because it never ships.
- If you bulk-replace `Log.` with `Logx.`, do it before `Logx.kt` exists, or the replace rewrites `Logx` into infinite recursion.

### Debug tools (`src/debug`)

Kept on purpose, to debug and refine features later. They compile only into debug builds. The release source set holds empty stand-ins (`DebugPanel`, `SpikeRunner`) with the same signatures. **If you change a signature in the debug version, change the release stand-in too, or release builds stop compiling.**

| Tool | Where | What it does |
|---|---|---|
| Ping, File test, Ping for result | `DebugPanel` | Channel test, file bridge test, and a ping started for a result that shows the code |
| Hide / Unhide one package | `SpikeRunner` ops `hide:<pkg>`, `unhide:<pkg>` | Calls `setApplicationHidden` in the work copy and reports result and time |
| Hide all / Unhide all / Hidden status | `hideall`, `unhideall`, `status` | Hides every launcher app except the work copy and work Settings, using its **own** record |
| Probe: service / Probe: plain | `probe_fgs`, `probe_plain`, `probe_status`, `probe_stop` | Screen-off listener experiments |
| Battery exemption | `battery` | Opens the battery optimization dialog (not needed) |
| PIN prompt now / Lock, then PIN (4 s) | `pin`, `PinProbeActivity` | Raises the work credential prompt and reports OK or cancelled |
| Probe ownership | `OwnershipProbe` | Profile list, types, Cloak installed flag, filter signal, secret, provisioning allowed, verdict. Also runs on every state change and logs |
| Tools (personal) / Tools (space) | `TransferProbeActivity` | Lists picker handlers, candidates and forwarders, makes and shares test files, folder and file grants, picker tests, cache size and delete |
| Cloak probe (share target) | `ShareProbeActivity` | Shows received URIs, checks folder and file grants, Read, Copy into a folder, slow variants |
| Share filters ON / ON (both) / OFF | `share_on`, `share_on_both`, `share_off` | Cleared by every update, restart and repair |
| Enable system app in space | `enable:<pkg>` | Enables a package in the space |
| Delete probe files, Release folder grants | `TransferProbeActivity` | Cleans the bridge cache and persisted grants of this copy |

Cautions:
- The spike hide record (`spike` preferences) is separate from the real one (`session`). Use the spike **Unhide all** for spike hides. Real hide mode also recovers orphans through Repair (6.10).
- The spike commands are signed like any command. They do nothing in release builds.
- Never distribute a debug APK.

### Verifying a release build

The debug tools must not ship. After `gradlew assembleRelease`, check the APK with Build > Analyze APK, or `apkanalyzer` if installed. Look for the absence of:
- classes `ProbeService`, `PinProbeActivity`, `OwnershipProbe`, `ScreenProbe`, `ShareProbeActivity`, `TransferProbeActivity`, `TransferProbeUtil`
- `SpikeRunner` and `DebugPanel` with anything but empty bodies
- manifest entries for those components and `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`

Also run `gradlew lintRelease`. Do this before every release and after changing anything in `src/debug` or the debug manifest.

### Signing and releases

Two paths on one `release` build type. A property chooses the key.

| Build | Key | Use |
|---|---|---|
| `assembleRelease` with `cloak.debugSign=true` | debug key | Your own phone. Updates in place over debug builds, in both profiles |
| `assembleRelease` otherwise | release key from `keystore.properties` or environment | Public APK |
| Neither configured | none | `packageRelease` and `bundleRelease` fail with an explanation. No unsigned APKs |

- Set `cloak.debugSign=true` in `~/.gradle/gradle.properties` (outside the repo). Override it with `-Pcloak.debugSign=false` for a public build on your machine.
- Release key: environment variables `CLOAK_KEYSTORE`, `CLOAK_KEYSTORE_PASSWORD`, `CLOAK_KEY_ALIAS`, `CLOAK_KEY_PASSWORD`, or the same four values in `keystore.properties`.
- Version: `cloak.version=X.Y.Z` in `gradle.properties`. `versionCode = X*10000 + Y*100 + Z`, minor and patch below 100. Debug and release share it, so you can switch between them without a downgrade error. Debug builds add `-debug` to the version name.
- A separate build type was rejected: it would not pick up `src/release`, where the debug stand-ins live.
- `docs/release-cert.sha256` holds the expected certificate digest. The workflow compares the APK against it.
- `.github/workflows/release.yaml` runs on `v*` tags in the protected `release` environment: checks the tag equals `cloak.version`, runs unit tests, restores the keystore from a secret, builds, verifies the certificate, writes checksums, creates a **draft** release (pre-release for 0.x).
- Your builds and the public APK have different signatures and cannot update each other. Moving your phone to the public build means removing the space and creating it again. The recovery token does not help, because it re-pairs the secret, not the signature.
- A lost release key means public users cannot update in place. Keep it in two places. Never upload a debug-signed APK. Full steps are in `docs/RELEASING.md`.
- **First stable release.** Set `cloak.version=1.0.0` for it. The workflow flags versions that start with `0.` as pre-releases and everything else as a full release, so 1.0.0 shows as "Latest". The version code becomes 10000, higher than any 0.x. Update the README Install section and `.github/release-body.md`, which both say pre-release, before tagging.
- The workflow has produced pre-releases. Still worth checking on the first stable tag: the draft's notes (how `gh release create` merged the notes file with generated notes), and that the release is not flagged as a pre-release.

---

## 6. How the pieces work

### 6.1 Provisioning

1. Main copy: "Create Second Space" resets onboarding, generates a random secret (6.3), stores it, and launches `ACTION_PROVISION_MANAGED_PROFILE` with `SpaceAdminReceiver` and the secret in `EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE`. The secret is saved before the system prompt opens, so a space being created is never reported as unpaired.
2. The system runs the Android 12+ flow. `GetProvisioningModeActivity` answers `PROVISIONING_MODE_MANAGED_PROFILE`. `PolicyComplianceActivity` finishes with `RESULT_OK`.
3. The work copy stores the secret via `Auth.storeFromProvisioning` and calls `enableProfile`: `setProfileName`, `setProfileEnabled`, then `setupChannel`.
4. Setting the work lock is now the first onboarding step (6.18). The old 30-second retry loop that tried to open the lock screen after creation was removed.
5. `isProvisioningAllowed` returned `true` on the Samsung phone even while a foreign work profile existed, so it cannot be used to decide whether Create is possible. Cloak detects foreign profiles itself (6.14).

### 6.2 Command channel

Goal: the main copy asks the work copy to run a privileged action.

- `CrossProfileApps` interaction is unavailable here (`canInteract` and `canRequest` stay false even after `setCrossProfilePackages`). That code was removed.
- What works: the profile owner registers a **cross-profile intent filter** (`addCrossProfileIntentFilter`) for `io.cloak.os.action.COMMAND` with both `FLAG_MANAGED_CAN_ACCESS_PARENT` and `FLAG_PARENT_CAN_ACCESS_MANAGED`. The main copy calls `startActivity`. Android's forwarder (`ForwardIntentToManagedProfile`) delivers it to `CommandActivity` in the work profile.
- Narrowing the filter to main-to-work only broke the channel on the test device, so both flags stay. The main copy's `CommandActivity` is disabled, so the space still cannot send commands to the main copy.
- `CommandActivity` is disabled in the main copy on purpose. Both copies are the same APK, so an enabled activity would match locally and the intent would never cross.
- `CommandActivity` must be `directBootAware`, or the intent fails with "No Activity found" while the work profile is locked.
- `ActivityNotFoundException` from `Commands.send` means the system could not resolve the intent. The process being killed is not the cause. The real causes are system state: the filter is missing, `CommandActivity` is disabled in the work profile, or the profile is stopped. Only code in the work copy can fix the first two, which is why 6.9 exists.
- `Commands.build` creates the signed intent and `Commands.send` starts it, so a caller that needs a result can launch the built intent itself.
- Clone commands keep `CommandActivity` alive on a background thread until the system answers (`InstallCoordinator`, up to 60 s). Other commands finish immediately.
- `CommandActivity.handle()` runs inside `runCatching`, so a bad command cannot leave a translucent screen stuck.
- Anything the user needs to know comes back as a toast in the work profile, or as an activity result (see section 3).

### 6.3 Command authentication

The signature permission on `CommandActivity` does **not** protect forwarded intents: the forwarder re-launches the activity with the system uid, which passes any permission check. A shell command to user 0 reached the work copy and triggered the toast. So commands are authenticated in code.

- **Secret:** 32 random bytes (Base64), generated by the main copy at "Create Second Space", delivered via the provisioning extras, stored in device-protected preferences (`auth`) in both copies. Regenerated whenever the space is recreated.
- **Counter, not clock.** The main copy keeps a persistent `send_seq`. The work copy keeps `last_seq` and accepts only a strictly greater value. The earlier timestamp scheme rejected every command after a clock jump or a slow PIN prompt.
- **Signature:** `HMAC-SHA256(secret, "<len(cmd)>:<cmd>|<len(arg)>:<arg>|<seq>")`. Length prefixes make the message unambiguous.
- **Verification** (`Auth.verify` via the pure `Auth.isValid`): secret exists, signature present, `seq > last_seq`, constant-time comparison. `last_seq` is committed before the command executes. If a `commit()` fails, `verify` returns false and `sign` returns null.
- `sign` and `verify` run under `synchronized(lock)`, per process, which is enough because the copies have separate preference files.
- `Auth.replaceSecret` swaps the secret and zeroes both counters in one write. Only recovery uses it.
- URIs attached to a command are not signed. The receiving side restricts them (6.5).
- Unsigned or invalid commands are logged as rejected and ignored.
- **One exception:** the `recover` request is accepted without a signature, because the secret is exactly what the user lost. It has its own checks (6.15) and is handled before the signature check.
- **Events** (`SPACE_EVENT`) are also unsigned, because they carry no authority (6.19).

Argument validation in `CommandActivity` after the signature check:
- Commands in `Commands.TAKES_PACKAGE` (`clone`, `enable_system`, `freeze`, `unfreeze`, `uninstall`) require `Commands.isValidPackage(arg)`: at most 255 chars, matching `[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+`.
- A package argument equal to our own package is rejected, so no command can freeze or uninstall Cloak.
- `set_privacy`, `set_lock_mode`, `set_share`, `set_recovery`, transfer ids and reports are decoded or validated strictly, or ignored.
- `ping`, `file_test` and `spike` only do anything in debug builds.

### 6.4 Lock model and state

- The work profile has its own lock (work challenge). `requireWorkLock` sets the required complexity. `ACTION_SET_NEW_PASSWORD` with `EXTRA_PASSWORD_COMPLEXITY` opens the lock screen. It needs `REQUEST_PASSWORD_COMPLEXITY`.
- If the user never sets a separate work PIN, the profile uses the phone lock. Then `lockNow` locks the whole phone. The recovery PIN prompt also becomes a phone-PIN prompt: it proves presence, not a second secret. Onboarding checks for a separate lock with `lock_status` (6.18).
- `lockNow(FLAG_EVICT_CREDENTIAL_ENCRYPTION_KEY)` needs `<force-lock />`.
- Six states, read by `SpaceStateReader` for the profile chosen by `SpaceProfile`:
  - **NONE:** no managed profile found. Welcome screen.
  - **FOREIGN:** a managed profile exists that Cloak does not manage (6.14).
  - **UNPAIRED:** a Cloak-managed profile exists but this copy has no secret (6.14).
  - **PAUSED** (`isQuietModeEnabled`): any command triggers the system's "turn on work apps" prompt.
  - **LOCKED** (`!isUserUnlocked`): the system raises the work PIN prompt when something in the profile is launched.
  - **UNLOCKED:** the grid.
- **What the `unlock` command does.** It is launched from Cloak when the space is paused or locked. The system interrupts the launch to show its credential screen. The log shows the work copy's `unlock` branch (`unlock requested`) running at the moment the profile unlocks, which is after the PIN. The branch sets `RESULT_OK` and sends the unlock event to the main copy (6.19). A launch-for-result on this command returned `0` (the credential screen drops the result), so nothing relies on it.
- The automatic unlock request fires from `OnResume` (not `OnStart`), after the first frame and a 400 ms settle, at most every 20 seconds, and only if Cloak is still resumed and the space is still paused or locked. The Unlock button on the locked screen always works. The gap prevents backing out of the PIN prompt from re-prompting at once.
- `Liveness` (`MISSING`, `PAUSED`, `LOCKED`, `UNLOCKED`) is a smaller read used by the recovery screen, which cannot rely on the secret.
- Cloak cannot pause or resume the profile. `UserManager.requestQuietModeEnabled` is limited to the default launcher or an app with a system permission. This is a standard Android rule.
- Observed: plain screen lock does **not** kill work apps (YouTube keeps playing) and does not mark Cloak's grid as locked, because the profile stays storage-unlocked and only the system challenge is pending. The Lock key behaves differently because it evicts the key. Making Cloak lock its grid on plain screen lock needs a session marker the main copy can read. Not built.
- Observed: leaving a work app for 2 minutes with the screen on does not re-lock. Idle timeout was dropped.

### 6.5 Cloning apps

- `installExistingPackage` is not usable (needs a device owner or affiliated profile). Do not retry it.
- **Normal apps:** `ApkExporter.export` copies `sourceDir` and all `splitSourceDirs` to `cache/bridge/<package>/` and returns FileProvider URIs. `Commands.send(CLONE, pkg, uris)` puts them in `ClipData` with `FLAG_GRANT_READ_URI_PERMISSION`. The forwarder keeps the grant and rewrites the URIs for the other user. `ApkInstaller.install` streams each file into a `PackageInstaller` session and commits, abandoning the session if anything fails. `InstallResultReceiver` launches the system Install dialog on `STATUS_PENDING_USER_ACTION`. The user taps Install once. The latch is released as soon as the confirm screen opens.
- **Forwarded URI format.** Android rewrites a forwarded `content://` URI to include the sender's user id: `content://0@io.cloak.os.fileprovider/...`. `Uri.authority` returns `0@io.cloak.os...`, `Uri.host` returns the bare name. `CommandActivity.bridgeUris()` compares `scheme == "content"` and `host`. Comparing `authority` rejected every forwarded file.
- **Preinstalled system apps:** `enableSystemApp`. This is how the Play Store appears in the space.
- First clone only: Android may ask to allow installs from Cloak in the work profile's settings.
- **Temporary exports** live in the main copy's cache. They are deleted when the cloned app appears and swept when older than 10 minutes at app start.
- Cloning is one app at a time. The UI disables other rows while one is pending (90 s timeout), because the counter rejects out-of-order commands and the installer has one latch.
- Adding or removing an app in the space flags the hide-mode package list for refresh (6.10).

### 6.6 File bridge

`FILE_TEST` proved that a file in `cache/bridge/` in the main copy can be read in the work copy through a granted FileProvider URI sent over the command channel. This is the basis for cloning. The file test is debug-only and accepts only our own provider. The user-facing transfer feature (6.16) uses document providers, not this FileProvider.

### 6.7 Install restrictions

`allowInstalls` clears `DISALLOW_INSTALL_APPS`, `DISALLOW_INSTALL_UNKNOWN_SOURCES` and `DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY`. It runs on every `setupChannel`. Side effect: any app that can install packages inside the space is not blocked from doing so.

### 6.8 Profile selection and the app grid

- `SpaceProfile.detect` and `find` choose the profile (6.14). `find` returns the profile only when Cloak manages it. State and grid use the same function so they always agree. The result is cached for 3 seconds, because detection costs several binder calls. `SpaceProfile.invalidate()` drops the cache. It runs when a managed-profile broadcast arrives and when the state refresh key changes, so a created or removed profile shows at once. `SpaceApps.profile` has no cache of its own (a second cache doubled the lag).
- `getLauncherUserInfo(user).userType` identifies the managed type. It is an API 35 call, so it is used only when `Build.VERSION.SDK_INT >= 35`. On Android 12 to 14 `isManaged` falls back to "the profile is a cross-profile target", which only works when Cloak is installed in the profile (6.20).
- `SpaceApps.list` uses `LauncherApps.getActivityList(null, profile)` and returns the unbadged icon (`getIcon(0)`). `SpaceApps.count` counts apps without loading icons.
- **One shared list.** `SpaceAppsHolder` (in `RememberSpaceApps.kt`) holds the app list for every screen. It registers one `LauncherApps.Callback` with `registerCallback(callback, Handler(Looper.getMainLooper()))`. The one-argument overload builds a Handler on the calling thread, and calling it from a background coroutine crashed the app at launch (`Can't create handler inside thread DefaultDispatcher-worker-1`). Every refresh goes through a conflated channel read by one worker, so a burst of package callbacks makes one listing and an older listing cannot overwrite a newer one. `rememberSpaceApps()` refreshes on first display and on every `AppListRefresh` pulse.
- Hidden apps disappear from `getActivityList`, so the grid empties when hide mode locks the space.
- Tap launches via `startMainActivity`. Long-press opens Freeze/Unfreeze and Remove from space in a themed rounded menu.

### 6.9 Keeping the work copy configured (self-repair)

The work copy has no screen, so it repairs itself. `setupChannel` runs in the work copy:
- from `SpaceAdminReceiver.enableProfile` when the profile is provisioned,
- from `PackageReplacedReceiver` on `MY_PACKAGE_REPLACED` (updates),
- from `PackageReplacedReceiver` on `LOCKED_BOOT_COMPLETED` and `BOOT_COMPLETED` (profile start),
- on demand, from the signed `repair` command (Settings > Repair space),
- from `set_share`, which re-runs it to change the share filters.

The receiver also reconciles hide mode (6.10): always on an update, on boot only if the boot fix is on. It always runs `setupChannel`, whatever the toggles say.

An earlier version let the work copy's own `MainActivity` run `setupChannel` when opened. That screen was removed, and from then on a lost filter or disabled `CommandActivity` was never repaired. Every command failed with `ActivityNotFoundException`. Do not remove a setup trigger without adding another.

Fallbacks when setup state is lost and no broadcast fires: Settings > Maintenance > Repair space, then pausing and resuming Work apps, then reinstalling over the app.

`HIDE_WORK_LAUNCHER` in `PolicyManager` is the kill switch for the launcher alias. The launcher caches icons, so a stale work icon may fail with "Failed to read the app data" until it restarts.

### 6.10 Hide mode, boot fix, shutdown hide (experimental)

**What it does.** With hide mode on, locking the space hides every launcher app in it. Android treats a hidden package as uninstalled for that user: its process is killed, its notifications clear, it vanishes from the launcher and from Settings' app list, its data is kept, and **the launcher's work tab disappears when no work app is visible**. Opening Cloak and entering the work PIN unhides them. This also removes the need for a third-party launcher and closes the hole of launching work apps outside Cloak.

**Measured on the OnePlus 13s (spike):** hiding an active app took about 18 ms, inactive apps 1 to 5 ms, unhide about the same, hiding and unhiding 8 light apps about 50 ms. The 8 apps were light, so heavy apps and larger sets would take longer.

**Settings (`LockSettings`, three characters `hide|shutdown|boot`, default `001`):**

| Setting | Default | Meaning |
|---|---|---|
| Hide apps when locked | off, experimental | Master switch |
| Hide apps again after a restart (recommended) | on | Boot fix |
| Also hide apps on restart (may not finish) | off | Shutdown layer |
| Hide all packages (coming later) | greyed out | Placeholder |

The second and third switches only appear while hide mode is on. Onboarding offers the master switch in its last step.

**Lock sequence** (`SessionManager.lock`, called by screen-off, the Lock command, and the notification's Lock action): stop the service, hide the apps, mark the session closed, then `lockNow` with key eviction. Hiding comes first so a failure cannot leave the space half locked. If hiding is incomplete the session stays marked open.

**Open sequence:** Cloak opens, the system PIN prompt appears, the profile unlocks, Cloak sends `session`. The work copy unhides the recorded set, marks the session open, builds the package list once if it does not exist, and starts `LockService`. The main copy shows an "Opening" screen until the apps appear (2 s timeout).

**Listener (`LockService`).** The screen-off event can only be received by a receiver registered at runtime, so something must keep the work copy's process alive. That is a foreground service, started on open and stopped on lock, so nothing runs while the space is locked. It holds one broadcast receiver and does nothing else, with `START_NOT_STICKY`. It shows a low-importance ongoing notification "Second Space is open" (hidden on the lock screen, with a Lock action, using `ic_stat_space`). The foreground type `specialUse` only exists from API 34, older versions use plain `startForeground`.

Spike results for the listener:
- **Foreground service: reliable.** It counted every screen lock whether Cloak was in Recents or cleared.
- **Plain process with a registered receiver: not reliable.** It counted nothing once Cloak was cleared from Recents.
- **Battery exemption:** the dialog opened but changed nothing visible, and is not needed with the service.
- Idle battery cost: expected to be small (one idle receiver). **Not measured.**

**Why the boot fix exists (log evidence).** After a restart the work copy starts as its own user process and the receiver fires on `LOCKED_BOOT_COMPLETED`, before the PIN. In that state credential-encrypted storage is unavailable (`mkdir failed: errno 126 (Required key not available)`), but direct-boot-aware components and device-protected storage work, and policy calls succeed. The original reconcile asked the package manager for launcher apps and got **zero** (`hid 0 of 0 apps`), then marked the session closed with nothing hidden. After the PIN, Cloak saw "no open session", started a fresh one, and showed every app. The listener also dies with the restart, so nothing hid the apps beforehand. Apps stayed visible but unusable until the PIN, so this was a tidiness problem, not a security one.

The fix:
- **Saved package list.** The work copy keeps the set of launchable packages in device-protected storage and hides by package name at boot, so it never needs to enumerate apps while encrypted.
- **Event-driven list.** It is built once when the first session opens. After that it changes only when an app is cloned or removed: Cloak watches the space's package callbacks (`OnSpacePackagesChanged`), sets a flag, and sends it on the next `session` command (argument `r`). Each save compares with the stored list first. `lock()` also saves its live list if it differs, which catches apps installed through the work Play Store while Cloak was closed.
- **Success means every target is hidden or gone.** An empty list or any failure leaves the session marked open, so the later `BOOT_COMPLETED` pass retries (it fires after the PIN).
- **No key eviction at boot.** The restart already evicted it.
- **Stale session check.** On `session`, if the session is marked open, the service is not running and the mark is older than 10 seconds, the work copy treats it as stale, hides everything and evicts the key.
- The direct-boot query flags (`MATCH_DIRECT_BOOT_AWARE or MATCH_DIRECT_BOOT_UNAWARE`) are used for every app listing.

**Boot fix off:** boot broadcasts skip the hide step (updates still reconcile). A restart leaves the apps visible until Cloak opens, which then hides them and asks for the PIN again.

**Shutdown layer.** While a session is open the service also registers for `ACTION_SHUTDOWN` (re-registered when the toggle changes mid-session). It hides only, no key eviction. It stores a note ("hid N apps, complete=…, X ms") because the log buffer is cleared on restart. The note is logged on the next boot even when the boot fix is off. The system gives a shutdown broadcast only a short window, so with many or heavy apps it may not finish. The boot fix is the reliable layer.

**Recovering apps left hidden (`recoverOrphans`, written, not verified).** Hiding is stored by the system, but the record of what Cloak hid lives in the work copy's own data. If that data is cleared (or the hide setting is lost), hide mode resets to off and nothing unhides the apps. With hide mode off nothing should be hidden, so `recoverOrphans` lists every package, including hidden ones, unhides what is still hidden, and clears the record. It runs in every `setupChannel` (boot, update, Settings > Repair space) and when hide mode is turned off. With hide mode on it does nothing. If the scan sees only a few packages and shows none, package visibility is filtering the list and `QUERY_ALL_PACKAGES` is the fix. If the **main** copy loses its secret, apps staying hidden is intended and the recovery token (or removing the space) is the way out.

**Settling the grid after unhide.** Unhiding takes longer on slower phones, and the grid used to open empty until Cloak was closed and opened again.
- The `session` command returns `RESULT_OK` with `visible`, the number of launcher apps the space can see after unhiding (`SessionManager.visibleCount`).
- Cloak runs `settleAppList`: it refreshes the grid on a schedule (0.3, 0.5, 0.7, 1, 1.5, 2, 2.5, 3, 4 and 5 seconds, about 20 seconds in total) and stops once `SpaceApps.count` reaches `visible`. If no count came back it stops when the list is not empty and did not change between two reads (`UnhideSettle.settled`, unit tested).
- While it runs, the grid says "Getting your apps ready…". The Opening screen ends as soon as the `session` result arrives, with a 6 second fallback if the command fails, so an empty space does not wait.
- If the two counts ever disagree, the loop runs its full 20 seconds and stops. That is harmless, but it means one side lists an app the other does not.

**Hide-record details.** `hide()` counts a package as done when it is hidden or gone. `isGone` checks `FLAG_INSTALLED`: a system app removed from the space still resolves with `MATCH_UNINSTALLED_PACKAGES`, so without that check the session stayed marked open and the next open re-locked the space. `installedPackages` passes the direct-boot flags to `getInstalledApplications`. Lint's list of allowed flags omits them, so the function carries `@SuppressLint("WrongConstant")`. The platform does honour them: before the first unlock the system lists only direct-boot-aware apps unless both flags are given. `LockService.start` reports success, and the session is marked open only after it works. `recoverOrphans` unhides anything hidden while hide mode is off, whoever hid it, and scans every package on each setup. On a fresh space its log line should say it showed 0.

**Limits and risks:**
- Hidden apps cannot receive messages or updates. Messengers catch up when the space opens.
- If Cloak loses its secret while apps are hidden, nothing can unhide them without recovery (6.15) or removing the profile.
- Killing Cloak in the main profile changes nothing. The listener lives in the work copy, which cannot be force stopped.
- Brief visibility after a restart, until the boot hide runs. The apps always need the work PIN.
- With hide mode on, the screen turning off locks the space and interrupts a transfer that touches space storage (section 12).
- Unverified: whether the shutdown broadcast reaches the service reliably, and how heavy apps affect it.

**Hide all packages (future).** Apps without a launcher icon (Play services, WebView, keyboards, providers) are not hidden today. Hiding them would stop everything from running in the space, but it is not safe to promise: hiding something the PIN prompt or profile startup depends on could lock the user out, hiding WebView or the package installer breaks apps and cloning, a work profile has hundreds of packages (seconds per lock and unlock), restarting Play services on every open costs battery, and the recovery dependency grows. A cautious path: a debug spike that lists the no-icon packages and hides only the third-party ones first with timing, then a system subset with a denylist and an always-available "unhide everything". Secure Folder's "Allow background activity after locked" is the opposite direction (apps keep running while locked).

### 6.11 Privacy settings

Four switches in `PrivacySettings`, sent as a four-character argument and stored in device-protected preferences in both copies.

| Switch | Default | Policy call | Status |
|---|---|---|---|
| Block screenshots | off | `setScreenCaptureDisabled` | Verified |
| Hide lock-screen notification content | on | `setKeyguardDisabledFeatures(KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS)` | Not verified |
| Block clipboard sharing | off | `DISALLOW_CROSS_PROFILE_COPY_PASTE` | Verified on a later retest |
| Keep contacts private | on | `setCrossProfileContactsSearchDisabled` + `setCrossProfileCallerIdDisabled` | Verified, dialers vary |

Flow: the toggle saves locally, sends `set_privacy`, the work copy saves and applies. A failed send rolls the toggle back with a toast. `setupChannel` re-applies the saved settings on every update, boot and repair. `DISALLOW_SHARE_INTO_MANAGED_PROFILE` is deliberately not used, because it would block file sharing.

Caveats:
- **Declared policies are read when the profile owner is set.** `wipe-data` and `disable-keyguard-features` were added to `device_admin.xml` after the first test space existed. Updating and rebooting did not refresh the declaration, so `setKeyguardDisabledFeatures` threw a `SecurityException` on that space. A freshly created space reads the current declaration. Any space created by an older build has the same limitation. Delete and recreate it to verify notification redaction.
- **Clipboard** blocked nothing on the first test and worked on a later one. The cause was not isolated (stale declaration or space state is the likely suspect). If it regresses, check `adb shell "dumpsys user | grep -i -E 'copy_paste|User #|UserInfo'"` for `no_cross_profile_copy_paste`, and test with the long-press Paste menu rather than the keyboard's clipboard strip.
- The main copy's saved choices cannot be read back from the space. If Cloak's data is cleared, Settings shows defaults until the user changes something.
- Each switch change briefly flashes a transparent screen, because it goes through `CommandActivity`.

### 6.12 Delete space

Settings > Remove > Delete Second Space shows a confirmation, then sends `delete_space`. The work copy checks it is the profile owner and calls `wipeData(0)`, which removes the whole profile. The main app returns to the welcome screen and onboarding starts again for the next space. Needs `<wipe-data />` in the declared policies (same fresh-space caveat as 6.11). It needs a working secret. If the secret is lost, the unpaired screen sends the user to system settings instead (6.14).

### 6.13 User interface

- **Theme and shapes:** see 6.17.
- **HomeScreen** is the state machine. NONE: `WelcomeScreen`. FOREIGN: `ForeignScreen`. UNPAIRED: `UnpairedScreen`, or the restore screen. PAUSED or LOCKED: `LockedScreen`. UNLOCKED: `OpeningScreen` while hide mode is unhiding apps, else the onboarding wizard (first run only, 6.18), else the grid or a sub-page (Add apps, Transfer, Settings, Recovery setup, Recovery restore). The page resets to the grid whenever the state is not UNLOCKED. System back steps back through sub-pages, and through wizard steps.
- **Grid screen (`SpaceHome`).** No top bar. A header area (22% of the screen height, between 120dp and 300dp) holds the centred "Second Space" title. Below it is a row with the title (hidden while the header is open) at the left and `+`, Lock and the overflow menu at the right, then the rounded card with the app grid. `HeaderCollapse` is a nested-scroll connection: scrolling up shrinks the header first, then scrolls the grid; scrolling down scrolls the grid back to the top, then grows the header. The centred title fades out (gone by about 55% collapsed) and the row's title fades in. The maths is in `CollapseModel` and is unit tested.
- The buttons are drawn at 38dp with a 2dp gap (`BAR_BUTTON`, `BAR_GAP`) because `IconButton` enforces 48dp. This is under the 48dp touch-target guideline.
- The overflow menu holds Transfer files and Settings. Add apps is the `+` button. Menus use `SpaceDropdownMenu` (rounded, themed).
- **Grid icons.** `IconRenderer` draws each app icon into a pebble tile. Adaptive icons are drawn 1.5x so the mask shows the visible part, plain icons get a light tile. Tiles are 56dp, columns adapt at 72dp. Use `getBadgedIcon(0)` in `SpaceApp.kt` to bring the work badge back.
- **Sub-pages** (Add apps, Transfer, Settings, Recovery) use a plain small top bar with a back arrow.
- **Settings** is grouped in rounded cards: Appearance, Privacy, Locking, File transfer, Second Space lock, Maintenance, Recovery, Remove. Switches are drawn at 80% size and the whole row toggles. Buttons are full width.
- **Transfer UI.** A progress card and a conflict card sit at the bottom of every screen through `TransferCardHost`.
- **State updates** (`rememberSpaceState(refreshKey)`): refresh on `MANAGED_PROFILE_*` broadcasts, on `ON_START`, on a 3 s safety poll while visible, on a space event (6.19), and when the refresh key changes. The 3 s read runs on `Dispatchers.Default`, not the main thread, and `SpaceProfile.invalidate()` runs before every re-read caused by a broadcast or the refresh key. `OnStart` and `OnResume` run a block on those lifecycle events.
- **App list and unhide.** All screens share `SpaceAppsHolder` (6.8). After unlocking with hide mode on, `HomeScreen` launches `session` for a result, ends the Opening screen when the result arrives, and runs `settleAppList` (6.10).
- **Maintenance:** Check connection sends `ping` and succeeds if the intent resolves. Repair space sends `repair`. Allow installs in Second Space opens the install-permission setting inside the space (6.18). If the channel is down nothing in Cloak can reach the work copy, so the message tells the user to pause and resume Work apps.
- All user-visible strings are in `strings.xml`. Read them with `stringResource` outside click lambdas.

### 6.14 Ownership detection: Cloak space, foreign profile, unpaired

**The problem.** A normal app cannot ask Android who owns another profile (that call is hidden). Cloak infers it.

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
- Only a profile owner can register a cross-profile filter, and our action is private to Cloak, so a filter hit outside our own package means a Cloak work copy registered it.
- `isProvisioningAllowed` is `true` in every case, so it carries no information here.
- The probes ran on the S21 FE only. The OnePlus is untested for the filter signal.

**Decision (`SpaceProfile.classify`, unit tested):**

| Cloak inside | Secret | Filter resolves | Verdict |
|---|---|---|---|
| no | – | – | FOREIGN |
| yes | present | not checked | CLOAK |
| yes | missing | yes | CLOAK (unpaired) |
| yes | missing | no | FOREIGN |

The filter query only runs when the secret is missing, so a healthy space pays nothing. A managed profile is any other profile of the managed type, or one that is a cross-profile target when the type is unknown.

**Screens:**
- **Foreign:** explains that Android allows one work profile and it must be removed from whatever manages it. Buttons: Check again, Open system settings. No delete, no recover. If Cloak is installed inside the profile, a tip says to restart once and check again, in case it is a Cloak space whose filter was lost.
- **Unpaired:** explains that Cloak lost its link, usually because its data was cleared. Buttons: Recover with recovery token, and Delete space in system settings. Removing the profile is the guaranteed exit and loses all data.
- Opening system settings tries `android.settings.MANAGED_PROFILE_SETTINGS`, then sync settings, then settings. On the Samsung phone it opened the Accounts management page, where the work profile options are.
- Removing the profile returns Cloak to the welcome screen on its own (profile-removed broadcast).

**Verified:** TestDPC profile with no Cloak inside reads FOREIGN, and a normal space reads CLOAK. The other rows were left unverified on purpose.

**Known gaps:**
- A Cloak space whose filter was lost while its secret is also lost reads as FOREIGN. The boot and update receivers restore the filter, hence the restart tip.
- Cloak installed in someone else's profile while the main copy still holds an old secret reads as a Cloak space. Only `adb install` produces that.
- A foreign profile that reads `type=null` has not been seen and would be missed.
- "Secret present but rejected" is not detected.
- While a new space is being created it may briefly have no Cloak inside, so the foreign screen could flash.

### 6.15 Recovery token and re-pairing

**Why.** The pairing secret is the only proof that the main copy is the right caller. If Cloak's data is cleared, the space survives but cannot be controlled. Without recovery the only way out is removing the profile and losing everything. Recovery is opt-in, and the default stays delete and recreate. The work copy can never send a new secret back (the channel is one-way), so the **main copy** generates the new secret and proves it knows the token.

**Token (`RecoveryToken`).** 26 random characters from a 32-symbol alphabet with no `0`, `O`, `1`, `I` (130 bits) plus 2 check characters, shown as seven groups of four. The check is two weighted sums mod 32, which always catches any single wrong character and any adjacent swap in the data (tested exhaustively). Parsing ignores case, spaces and dashes and rejects input over 128 characters. `extract` finds the token in an exported file. `deriveKey` is `HMAC-SHA256(key = token, message = "cloak/recovery/key/v1")`, Base64, 44 characters.

**Set up (Settings > Recovery token, or the last onboarding step).**
1. Cloak generates a token in memory. Nothing is stored on the main side.
2. Show step: the token on two lines. The window blocks screenshots and Recents thumbnails (`FLAG_SECURE`). There is no copy button on purpose. The text is not selectable.
3. **Export to file:** the system "create document" picker, so the user chooses the location and Cloak needs no storage permission.
4. **Confirm step:** the token is not shown. The user types it back or imports the exported file. Import reads at most 4 KB.
5. **Activate** sends the signed `set_recovery` command with the derived key. The work copy stores it in its own `recovery` preferences and shows a toast. The token never crosses the channel and Cloak never stores it.
6. Replace creates a new token. Turn off sends `clear_recovery`. If the screen is recreated during setup, the flow restarts with a new token and nothing was installed.
7. The main copy keeps only an "enabled" hint for Settings and onboarding. It is cleared when the space is gone.

**Recover (unpaired screen, or Settings > Recovery token > Reconnect).**
1. The screen needs the space unlocked (`Liveness`). If paused or locked, a "Turn on or unlock" button sends an unsigned `unlock` intent.
2. The user enters the token. Cloak generates a **new secret**, computes a proof `HMAC(derivedKey, "label|len:secret|ts")`, keeps the new secret as a pending value (5 minutes), and starts the unsigned `recover` intent **for a result**. The token itself is never sent.
3. The work copy handles `recover` before the signature check (`CommandActivity.handleRecover`), only as the profile owner, through `RecoveryGate.evaluate` in this order: recovery key exists, profile unlocked, not locked out, request well formed, timestamp within five minutes and newer than the last accepted, then the attempt is counted **before** the proof is compared, then the proof is checked in constant time.
4. **Lockout:** the first three wrong proofs are free, then 30 s, 2 min, 10 min, 1 h, 6 h. A clock set back cannot stretch a lockout beyond its scheduled length.
5. A valid proof does not pair by itself. The work copy stores the new secret as pending (2 minutes) and raises the **work credential prompt**. Only a confirmed PIN makes the pending secret real (`Auth.replaceSecret`, which zeroes both counters) and shows "Second Space is paired with this Cloak". Cancel, Back, locking the screen or a timeout discards it. If the profile has no secure lock, recovery is refused.
6. Cloak promotes its own pending secret when the result is `RESULT_OK`. If the system does not relay the result, Cloak shows "Waiting for Second Space" and the user taps **It worked** after seeing the toast, or **Try again**.
7. The proof is bound to the new secret, so a captured proof cannot install a different secret. The timestamp makes it single-use.

**PIN spike results (OnePlus, separate work PIN set).** The work copy's invisible activity can raise the work credential prompt with our own title. A correct PIN returned OK, a wrong PIN stayed on the prompt, Back and cancel returned cancelled, locking the screen during the prompt returned cancelled, and Home cannot be pressed during it. With the profile locked, the system's own sheet appeared and the original command was dropped, so recovery requires an unlocked profile.

**Verified:** setup, export, import, confirm, activation, and re-pairing all worked. The recorded result code (`adb logcat -d | findstr Restore`, line `recover result=`) was not reported.

**Limits:**
- Covers loss on the main side only. If the work copy's own data is cleared, its key is gone too.
- A lost token means delete and recreate.
- Anyone with the token and the work PIN can take over the space. With a unified lock the PIN is the phone PIN, which proves presence but is not a second secret.
- Any app can send bad proofs to trigger the lockout and delay recovery. Normal commands are unaffected.
- Setting the phone clock back after a recovery makes the next recovery fail until the clock catches up.
- The key sits in device-protected storage and is readable before the first unlock, like the pairing secret.
- A cloud-synced export folder puts the token in the cloud.

### 6.16 File transfer

**Rules learned in spikes (OnePlus 13s, OxygenOS 15):**
- Personal to space sharing works by default. Space to personal needs a setting: cross-profile filters for `SEND` and `SEND_MULTIPLE`. Only `FLAG_MANAGED_CAN_ACCESS_PARENT` gave no forwarder in the space. Both `FLAG_MANAGED_CAN_ACCESS_PARENT` and `FLAG_PARENT_CAN_ACCESS_MANAGED` did. `setupChannel` clears every filter, so the share filters are re-added after the command filter. The setting is off by default, because a filter matches intents and not apps: every app in the space can then share to every personal app.
- The space has no folder picker or create-file handler by default (`ActivityNotFoundException`) and no Files app. Enabling the system documents package (`com.google.android.documentsui` here, found at runtime from the personal side) with `enableSystemApp` fixes it. Roots, `Android/` and `Download/` cannot be picked.
- A picker blocks the other profile's tab ("blocked by IT admin"), so a folder must be chosen in the profile that owns it.
- A forwarded `content://` URI gets the sender's user id in its authority (`content://17@com.android.externalstorage.documents/...`). Listing and reading through it work across profiles (352 files listed in 132 ms, about 7 ms per file read, 702 files copied in 9.3 s). `createDocument` and `deleteDocument` through it fail with `SecurityException: Requested authority 17@... doesn't match provider`. So every transfer is a **pull**: the receiver picks its own destination and reads the source through the forwarded grant. The sender deletes its own originals, in its own profile.
- Grants must be persisted on both sides (`takePersistableUriPermission`). Without that, swiping Cloak away revoked the destination grant (`Permission Denial ... MANAGE_DOCUMENTS`). A transfer in a cached process was also frozen until the process was woken, so the copy runs in a foreground service.
- Delete right after writing a large file can fail once, then succeed, so deletes retry.
- 1000 MB/s for 200 MB measured the page cache. Real cost is about 7 ms per file read and 13 to 20 ms per file created and written, so 5,000 files take about 100 s.

**Entry points.**
- Share sheet: `ShareReceiverActivity` is a share target in both copies (label "Cloak: copy to folder"). Copy only.
- In-app: menu, Transfer files. Personal to Space: Cloak picks, then a signed `transfer` command opens the receiver inside the space. Space to Personal: signed `transfer_pick` opens `TransferSendActivity` in the space, which picks and forwards the private action `TRANSFER_IN` to `TransferInAlias` in the personal copy. `setupChannel` adds a cross-profile filter for that action (both flags) and disables the alias in the space.

**Receiver screen.** The user taps **Copy to folder…** (or **Move to folder…** for in-app transfers), picks a folder, and the transfer starts at once. The operation is chosen on the receiving side, so the sender never asks.

**Engine (`TransferEngine`).** Plans every source (strict listings, so a failed listing is an error and not an empty folder), creates folders, then copies file by file in a foreground service with a partial wake lock. Cancel stops before the next buffer and deletes only the partial file it created. An access loss (`SecurityException`) interrupts the transfer and keeps what was copied. Sizes are checked after each file.

**Clashes (`ConflictRules`, written, not verified).** Policy `ASK` pauses before creating anything for a file whose name exists in the destination folder. Names are compared case-insensitively, and files created by this transfer are remembered, so two sources with the same name clash too. The conflict card slides up with **Replace**, **Keep both**, **Skip** and **Apply to all remaining conflicts**. Apply to all lasts for this transfer. The engine polls every 250 ms for an answer, cancel works while paused, and no answer for 10 minutes cancels the transfer (files copied so far stay). Folders always merge. The notification says the file exists and opens Cloak or, in the space, the receiver screen. Move now allows Skip: a skipped file marks its whole source incomplete, so its original stays.

**Move.** The sender records a ledger entry before sending: persisted grants, file counts and byte totals per source (`MoveLedger`, `SourceScanner`), under a random 64-bit id. The receiver's report (`TransferReport`, `ReportStore`) lists, per source, the files and bytes it verified, and only for sources copied without a single problem. A Move also re-reads the size at the destination. The sender then deletes a source only if all of these hold: the transfer finished, the report equals the ledger, a fresh scan of the source still equals the ledger, and every size was known. Skipped, failed, cancelled or changed sources keep their originals.
- Personal to space: when Cloak is next in front, `TransferReconciler` asks the space for the report (`transfer_report`, answered as an activity result) and settles.
- Space to personal: the personal copy keeps the report and, when Cloak is in front, delivers it to the space with a signed `transfer_done`, and the space settles in a foreground service.
- Settling runs in the foreground service, can be cancelled (leaving a partly deleted source, counted as failed), and drops its ledger entry. Ledger entries expire after 7 days. Forwarded grants are pruned after 6 hours.
- The reconcile step starts 2.5 s after Cloak comes forward and at most every 8 s. It flashes a transparent screen only when something is waiting.

**Hardening (code audit).**
- `TransferService` overrides `onTimeout(startId, fgsType)`. Android 15 limits data-sync services to about six hours in 24, and the system crashes an app that does not stop one in time. The override cancels the engine and stops the service. What was copied stays, and the user can start the rest again.
- Progress and conflict notifications use private visibility with a generic public text, so file names do not show on a locked screen. They can still show in the shade when the phone is unlocked, including notifications from the space.
- The engine tracks destination names by (name, is-folder). Before, a file whose name matched a folder was registered over the folder, and later files for that folder landed in a duplicate `name (1)`.
- `TransferReconciler` gives up on an unanswered request after 30 seconds (it used to wait forever if a result was lost). Young transfers poll on every foreground, old ones at most every 10 minutes, so an abandoned in-app transfer no longer launches an invisible activity in the space on every foreground for 12 hours.

**Settings.** Settings, File transfer: the share-from-space switch (`set_share`) and **Enable folder picker in Second Space**.

### 6.17 Theme, shapes and icons

- **Presets.** `ThemePreset` is a data table (Ocean, Forest, Dusk, Ember, Rose). Ocean is the default and the original blue. `ThemeStore` keeps the choice in ordinary (not device-protected) preferences `theme`. `LocalTheme` lets Settings change it live, and `spaceBackground()` gives the gradient (it is a composable function, not a value). The colour scheme also sets the `surfaceContainer*` colours, otherwise menus and dialogs fall back to grey. The receiver screen inside the space has its own theme setting. The window and splash background are fixed to the Ocean colour, so other presets may show a brief blue frame.
- **Pebble.** A superellipse with exponent above 2 has zero curvature at the middle of each edge, so no exponent removes the flat spot. `PebbleMath` blends a circle with a squircle (`SQUARENESS = 0.55`, `SUPER_EXPONENT = 4.0`), which keeps every edge curved and the corners fuller than a circle. Lower `SQUARENESS` is rounder.
- **Icons.** The launcher icon is the sage "nesting egg" on `#0D1511`: outer egg, inner egg, centre dot, with a monochrome layer for themed icons. Variants (Mint, Forest, empty, yolk, crack) are colour or path swaps in `ic_launcher_foreground`, `ic_launcher_monochrome` and `ic_logo_egg`. Notification icons are `ic_stat_space` (session) and `ic_stat_transfer` (arrows). The icon colours are independent of the theme preset.

### 6.18 Onboarding (reported working)

**Flow.** Welcome (existing screen, new text) starts the system setup. Then five steps:
1. **Lock:** the space needs its own lock. Cloak asks the space with `lock_status`, which returns an activity result saying whether the profile has its own secure lock (`isUsingUnifiedPassword` is false and the device is secure). The step re-checks on every resume, so it advances itself when the user returns from the lock screen. It retries up to five times while the space is still getting ready, and then shows a Check again button. **Continue** appears only when the lock is separate. **Skip for now** is always possible.
2. **Play Store:** prepares the Play Store in the space (`enable_system`) and opens it, where the user signs in with a separate Google account. Whether an account was added cannot be detected, so the step is explanatory.
3. **Install permission:** Cloak installs apps into the space from the space copy, so Android needs "install unknown apps" switched on for Cloak there. It is a per-app setting, not a runtime permission, so only the user can grant it. `install_status` returns `allowed` from `canRequestPackageInstalls()` as an activity result. `allow_installs` opens `ACTION_MANAGE_UNKNOWN_APP_SOURCES` for Cloak inside the space, and falls back to the general security settings. The step re-checks on resume and advances itself, and it can be skipped (Android also asks on the first clone). Settings, Maintenance has the same button for people who already have a space.
4. **Apps:** the Add apps list, embedded.
5. **Protect:** optional recovery token and the hide-mode switch. Turning hide mode on without a token shows a warning. **Finish setup** lands on the grid.

**Details.**
- `OnboardingStore` keeps `initialized`, `done` and `step` in ordinary preferences. On the first run of this version, a space that already exists marks onboarding done, so existing users are not shown the wizard. `reset` runs when a new space is created. Progress survives leaving the app and the space locking. A step number saved mid-wizard by an older version shifts by one for the last two steps.
- `OnboardingRules` (step count, clamping, next and previous) is pure and unit tested.
- The wizard only shows while the space is UNLOCKED, on the grid page. Opening the recovery setup from the last step returns to the grid page, which shows the wizard again.
- Each lock or install check flashes a transparent screen. A minimum gap between checks stops a check's own resume from triggering another.
- If the lock step never reaches "has its own lock" after one is set, send `adb logcat -d | findstr Onboarding` and a description of the lock screen. Skip works either way.

### 6.19 Returning to Cloak after the work PIN

**Problem.** If Cloak opens from the launcher while the space is locked, the system PIN prompt appears, and after the PIN the user lands on the launcher, not in Cloak. A manual tap on Unlock while Cloak is already in front returns to Cloak. Secure Folder behaves like our automatic case (it closes after the PIN and relaunches at once).

**What was tried:**
- A launch for a result on `unlock` returned `0` every time. The credential screen drops the result.
- Moving the automatic request from `ON_START` to `ON_RESUME` with a first-frame wait, a 400 ms settle and a 20 s gap did **not** fix the launcher landing. It stayed because it is harmless and keeps the Locked screen reachable.
- The log showed `unlock requested` and `BOOT_COMPLETED` in the same work process 122 ms apart, so the work `unlock` branch runs at the moment of unlock, from a visible translucent activity.

**Current mechanism (written, behaviour mixed).**
- The work `unlock` branch sets `RESULT_OK` and calls `SpaceEvents.sendToMain(UNLOCKED)`, which starts the private action `SPACE_EVENT` with `NEW_TASK | REORDER_TO_FRONT | SINGLE_TOP`.
- `setupChannel` registers a cross-profile filter for that action (both flags) and disables `EventAlias` in the space, so it only resolves in the main copy. `EventAlias` points at `MainActivity`, which is `singleTop`, so the event arrives in `onNewIntent`.
- `MainActivity.handleEvent` accepts only known event names and bumps `SpaceEvents.pulse`. `HomeScreen` collects it and re-reads the real state.
- **Events are not signed.** An event carries no authority. It only brings Cloak forward and makes it re-read the state. `MainActivity` is exported already, so any app can bring it forward today. Anything that acts still goes through signed commands.
- The mechanism relies on the work copy being able to start the main copy's activity. A suggestion from the maintainer's research is that a Profile Owner is exempt from the background activity start restriction. That is a hypothesis. The send happens from a visible work activity, so it does not depend on the exemption.
- Reported result: it kind of worked and did not. Not functionally broken. Known fallbacks if it needs work: `launchMode="singleTask"` on `MainActivity`, or a "Second Space unlocked, tap to open" notification posted while an unlock request is pending (event-driven, no polling).

**Fingerprint report (open).** On a Motorola phone (Android 15 or 16, version not checked), the fingerprint prompt was offered only once. That unlock left Cloak on the Locked screen even though Android had unlocked the space, with no further prompt, and turning the screen off and unlocking again cleared it. It was not reproduced. Android decides when the work challenge accepts a fingerprint (after a restart, after a few days, or after failed attempts it asks for the PIN only), which can explain the first part. For the second part, if it happens again, before turning the screen off run `adb shell dumpsys user | findstr /i "UserInfo State running unlocked quiet"` and `adb logcat -d | findstr SecondSpace`. If Android says the work user is running and unlocked while Cloak shows Locked, the bug is in how Cloak reads the state. If it says locked, the unlock had not finished.

**Unlock-related polling.** Nothing polls in the background. The only poll is the 3-second state read while Cloak is on screen.

### 6.20 Android versions (minimum is 12, API 31)

API 31 is the floor because of `setRequiredPasswordComplexity`, the `GET_PROVISIONING_MODE` activity, `FOREGROUND_SERVICE_IMMEDIATE` and `dataExtractionRules`, which all start there. The app is developed on Android 15 and checked on 16. Android 12 to 14 compile and are guarded, but have not been run on a real phone.

Version-dependent code:

| Where | What | Handling |
|---|---|---|
| `SpaceProfile.isManaged` | `getLauncherUserInfo` is API 35 | Used only on 35+. Below that, "is a cross-profile target" |
| `LockService` | `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` is API 34 | Used only on 34+. Older versions call plain `startForeground` |
| `PolicyManager` | `POST_NOTIFICATIONS` grant is API 33 | Guarded |
| `ShareIntents`, receivers, `Auth` | Typed `getParcelableExtra` overloads are API 33 | Guarded, older overload below |
| `SessionManager.installedPackages` | `ApplicationInfoFlags` is API 33 | Guarded, `@SuppressLint("WrongConstant")` for the direct-boot flags |
| `TransferService.onTimeout` | API 35 | An override, never called on older versions |
| `PolicyManager.lockNow` | `FLAG_EVICT_CREDENTIAL_ENCRYPTION_KEY` | Passed on every version. It exists since API 24, and an earlier guard at API 34 stopped Android 12 and 13 from evicting the key, so the space would never have read as locked |
| `PolicyManager.allowInstalls` | `DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY` is API 29 | Cleared with the other two restrictions. Removing that line risked "blocked by work policy" coming back |
| Debug code | `ProbeService`, `OwnershipProbe` | Same guards as the main code |

What changes below Android 15:
- Cloak cannot identify another app's work profile unless Cloak is installed inside it. The foreign-profile screen therefore may not appear. Create stays enabled, the system flow fails at the end, and the welcome screen shows "Setup did not finish. If another app already manages a work profile on this phone, remove it first. Android allows only one."
- The probes that settled ownership, the share filters, folder grants, boot broadcasts to the work profile and the unlock flow were only observed on Android 15 and 16. On Android 12 to 14, receivers registered without the exported flag go through `ContextCompat` and are believed to still get the system broadcasts hide mode needs (screen off, shutdown, managed-profile events). That is unconfirmed.
- To test: provisioning, hide mode with a restart, a transfer in each direction, the share-sheet setting, the unlock flow, and edge-to-edge insets with three-button navigation (only enforced on 15).
- Run `gradlew lintDebug lintRelease` after any change, since lint is the authority on API levels.

---

## 7. Command reference

All commands go through `Commands.send` or `Commands.build` and are signed over `<len>:cmd|<len>:arg|seq`, except `recover` and the `SPACE_EVENT` action. Only the profile owner executes them.

| Command | Arg | Runs in work copy | Notes |
|---|---|---|---|
| `ping` | none | Debug builds: toast, `RESULT_OK` with an echo extra | Channel test |
| `lock_now` | none | `SessionManager.lock` | Hides apps first if hide mode is on, then evicts the key |
| `set_lock` | none | `requireWorkLock` + `ACTION_SET_NEW_PASSWORD` | |
| `lock_status` | none | Returns `RESULT_OK` with `separate` (true if the work lock is its own) | Used by onboarding |
| `install_status` | none | Returns `RESULT_OK` with `allowed` (`canRequestPackageInstalls`) | Used by onboarding |
| `allow_installs` | none | Opens the install-unknown-apps setting for Cloak inside the space | Onboarding and Settings, Maintenance |
| `unlock` | none | `RESULT_OK`, then sends the unlock event to the main copy | The system prompt does the real work |
| `session` | `""` or `r` | `SessionManager.onAppOpened`, then `RESULT_OK` with `visible` (apps the space sees) | Opens the hide-mode session. `r` refreshes the saved package list. Cloak launches it for a result and settles the grid (6.10) |
| `set_lock_mode` | 3 chars of 0/1 | Save, then `onSettingsChanged` | Hide, shutdown, boot fix |
| `clone` | package | `ApkInstaller.install` from `ClipData` URIs | Valid package and own-provider URIs only |
| `enable_system` | package | `enableSystemApp` | Preinstalled apps only |
| `freeze` / `unfreeze` | package | `setFrozen` | |
| `uninstall` | package | `ACTION_DELETE` | System confirmation |
| `set_privacy` | 4 chars of 0/1 | Save and `applyPrivacy` | |
| `set_share` | `0` or `1` | Saves, then re-runs `setupChannel` | Adds or removes the share filters |
| `set_recovery` | derived key (44 chars) | Stores the recovery key | Rejected if not a valid key |
| `clear_recovery` | none | Deletes the recovery key | |
| `transfer` | transfer id, URIs in `ClipData` | Persists the grants, opens the receiver in the space | Personal to space |
| `transfer_pick` | `files` or `folder` | Opens `TransferSendActivity` | Space to personal |
| `transfer_report` | transfer id | Returns the stored report as an activity result | Main asks, space answers |
| `transfer_done` | encoded report | Settles the ledger entry, removes originals | Main tells the space |
| `delete_space` | none | `wipeData(0)` | Irreversible |
| `repair` | none | `setupChannel` | Re-runs the full work-copy setup, including orphan recovery |
| `recover` | extras: secret, timestamp, proof | `RecoveryGate`, then the PIN prompt | **Unsigned**, own checks (6.15). Returns an activity result |
| `file_test` | `test.txt` | Reads the URI | Debug only |
| `spike` | `op[:value]` | `SpikeRunner` | Debug only, no-op in release. Ops: `hide`, `unhide`, `hideall`, `unhideall`, `status`, `probe_*`, `battery`, `pin`, `picker`, `share_on`, `share_on_both`, `share_off`, `enable:<pkg>` |

Inbound to the main copy: `SPACE_EVENT` (unsigned hint, 6.19) and `TRANSFER_IN` (a transfer from the space, 6.16).

---

## 8. Dead ends and fixes (do not re-learn these)

| Symptom | Cause | Resolution |
|---|---|---|
| Home said "space is set up" with no work profile | Counted any extra user; OxygenOS has `MultiApp` (user 999) | Detect the managed profile |
| No PIN prompt after creating the space | App never required a work lock | `requireWorkLock` plus the onboarding lock step |
| Crash on Set lock | Missing `REQUEST_PASSWORD_COMPLEXITY` | Declare it |
| `CrossProfileApps` canInteract/canRequest false | Not exposed to this app | Cross-profile intent filter channel |
| "No Activity found" for our action | Command activity not direct-boot-aware | `directBootAware="true"` |
| "No Activity found" after the cleanup batch | Work copy lost its only setup trigger when its screen was removed | Self-repair receivers (6.9) |
| Toast from an unsigned shell command | Forwarder launches as the system uid | HMAC signatures |
| All commands rejected after a clock change | Timestamp replay check | Persistent counter |
| `lockNow` locked the whole phone | Work profile had no separate lock | Set the work PIN |
| `installExistingPackage` SecurityException | No device owner | APK copy + PackageInstaller |
| "Blocked by work policy" installing | Install restrictions | `allowInstalls` |
| "Package appears to be invalid" | Installed the unsigned release APK | Install a signed build; the guard task now fails unsigned builds |
| File test and cloning rejected | `Uri.authority` includes the user id (`0@...`) | Compare `Uri.host` |
| Restart space / resume refused | `requestQuietModeEnabled` needs default launcher role | Removed. Repair space and the system toggle |
| `SecurityException ... did not specify uses-policy` | Policy added to `device_admin.xml` after the space existed | Delete and recreate the space |
| `Cannot access 'AppEntry': it is private in file` | Old `CloneList.kt` and `AddAppsScreen.kt` both declared a private `AppEntry` | Delete the old file |
| Unresolved reference `Icons` | `material-icons-core` not pulled in | Add the dependency |
| Bulk `Log` to `Logx` replace made `Logx` call itself | Replace ran after `Logx.kt` existed | Replace first |
| Privacy toggle out of sync after a failed send | No rollback | Roll back on failure |
| Apps visible after a restart with hide mode on | Boot reconcile listed apps while the profile was encrypted and got none (`hid 0 of 0`) | Saved package list, success only if everything is hidden |
| Plain screen-off listener counted nothing | Process killed once Cloak left Recents | Foreground service |
| Battery exemption did nothing visible | Not needed with a foreground service | Dropped |
| TestDPC profile with Cloak inside read as an unpaired Cloak space | `find` took any managed profile with Cloak | Ownership classification (6.14) |
| Cloak "crashes" inside another owner's profile | The work copy's `MainActivity` finishes immediately and its icon is never hidden there | Expected, not a crash |
| `isProvisioningAllowed` always true | Platform reports it regardless of an existing profile | Detect foreign profiles ourselves |
| Locked profile: PIN command dropped after the system sheet closed | The sheet consumes the original command | Recovery requires an unlocked profile |
| "Blocked by IT admin" sharing from the space | Single-flag share filter gave no forwarder | Both filter flags |
| `ActivityNotFoundException` for the folder picker in the space | No documents app enabled there | `enableSystemApp` on the documents package |
| Create or delete through a forwarded folder fails | The provider rejects the added user id in the authority | Pull model, sender deletes its own originals |
| Transfer stops when Cloak is swiped away | Destination grant dropped and a frozen cached process | Persist grants, foreground service |
| Delete after a large write fails once | Transient provider state | Retry deletes |
| Flat middle on icon edges | Any superellipse above exponent 2 has zero edge curvature | Blend with a circle |
| Buttons too far apart | `IconButton` enforces 48dp | Custom 38dp circles |
| Menus and dialogs grey in a coloured theme | Container colours not set | Set `surfaceContainer*` in the scheme |
| A custom build type did not compile | It misses the `src/release` stand-ins | Property on the release type |
| Space copy missing a new debug button | The work copy had not updated | Check both copies after install |
| After the PIN the user lands on the launcher | The credential launch is not tied to Cloak's task, and its result is dropped (`unlock result=0`) | Unlock event to the main copy (6.19). Behaviour mixed |
| Auto unlock at `ON_START` stacked over the launcher | Cloak was not resumed yet | Moved to `OnResume` with a settle delay and gap. Did not fully fix it |
| Apps stay hidden after the work copy's data is cleared | The hide record lived in the cleared data | `recoverOrphans` from system state |
| Stale "recovery not implemented" text | Placeholder string never updated | String tidy-up |
| Crash at launch: `Can't create handler inside thread DefaultDispatcher-worker-1` | `LauncherApps.registerCallback(callback)` builds a Handler on the calling thread, and the shared app-list holder called it from a background coroutine | `registerCallback(callback, Handler(Looper.getMainLooper()))` (6.8) |
| `SuperNotCalledException` when `CommandActivity` is recreated during the PIN prompt | The early return for the recreated case sat above `super.onCreate` | `super.onCreate` first |
| Lock button would not evict the work key on Android 12 and 13 | `lockNow` flag wrongly guarded at API 34 | No guard, the flag exists since API 24 (6.20) |
| "Blocked by work policy" could return | A change dropped the `DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY` clear | Restored |
| Boot or update setup could be cut short | The receiver ran setup on a bare thread, so the process could be killed after `onReceive` returned | `goAsync()` with `pending.finish()` in a `finally` |
| State screens lag up to 3 seconds after a profile change | The new profile cache was not invalidated by broadcasts, and a second cache doubled it | `SpaceProfile.invalidate()`, one cache |
| Space re-locked right after unlocking | A system app removed from the space still resolved with `MATCH_UNINSTALLED_PACKAGES`, so hiding never completed | `isGone` checks `FLAG_INSTALLED` |
| Blank grid after unlock on slow phones, fixed only by reopening | Unhide took longer than the 2 s wait and nothing refreshed the list | `session` returns a count, `settleAppList` refreshes until it matches (6.10) |
| Android 15 foreground-service crash after about six hours | `TransferService` had no `onTimeout` | `onTimeout` cancels the engine and stops the service |
| Files landed in a duplicate `name (1)` folder | Destination names were keyed by name only, so a file registered over a folder | Keyed by (name, is-folder) |
| Lint error `WrongConstant` on `ApplicationInfoFlags.of(...)` | Lint's list of allowed flags omits `MATCH_DIRECT_BOOT_AWARE` and `MATCH_DIRECT_BOOT_UNAWARE`, which are needed before the first unlock | `@SuppressLint("WrongConstant")` with a comment |
| File names visible on the lock screen from transfer notifications | Default notification visibility | Private visibility plus a generic public text |

Mistakes worth remembering: claims about permission enforcement across profiles, filter flag directions, cross-profile URI handling, boot behaviour and the result of credential launches were wrong until tested. Treat any API behaviour not verified on the phone as a hypothesis. Reading the actual log beats reasoning: the boot log settled an argument that two competing theories could not, and the `unlock requested` timing showed where the work copy's hook is. An outside review can be wrong too: one claimed the cross-profile FileProvider was broken because both copies share an authority, which contradicts the verified file bridge (section 10).

---

## 9. Security model

Full text in `docs/THREAT_MODEL.md`. Summary:

Protected:
- Commands cannot be forged by other apps: signature permission, HMAC over a length-prefixed message, strictly increasing counter, constant-time comparison, no dependence on the clock.
- Arguments are validated (package pattern, self-target block, strict decoding, own-FileProvider URIs).
- Work data is isolated by the work profile and unlocked by the system work lock.
- With hide mode on, work apps are invisible and stopped while the space is locked.
- Backup is disabled, so the secret cannot leave through backup or device transfer.
- Temporary APK exports are in private cache and deleted after use.
- Release logs contain no package names, file names, URIs, secrets, tokens or proofs.
- Recovery needs the token **and** the work PIN, and wrong tokens are rate limited.
- Move deletes originals only after a verified copy (6.16).
- Transfer notifications use private visibility with a generic public text, so file names do not appear on a locked screen.
- `allowBackup` is false and the extraction rules exclude everything.
- Privacy policies, the hidden launcher alias and hide state are re-applied by the work copy.

Assumptions and limits:
- Relies entirely on Android's work profile isolation. Not a security product. Rooted phones, forensic extraction, and anyone who knows the PIN are out of scope.
- The secret and the recovery key are stored unencrypted in device-protected storage in each copy.
- URIs attached to commands are not signed (the receiving side restricts them).
- Commands run while the profile is locked (direct boot aware). Fine for the current commands. Reconsider before adding anything that reveals data.
- **Unauthenticated or lightly authenticated entry points:**
  - `recover` is accepted without a signature (6.15). Any app can send it, can trigger the lockout, and can show toasts in the space.
  - `SPACE_EVENT` and `EventAlias` are unsigned hints that only bring Cloak forward and make it re-read state.
  - `ShareReceiverActivity` and `TransferInAlias` are exported, like any share target. They accept only `content://` URIs (at most 500), do nothing until the user picks a destination, can read only what the sender granted, and sanitise provider names before creating files.
- With the share-from-space setting on, every app in the space can share to every personal app. Off by default.
- Move deletion is gated by the report, the ledger and a fresh scan. A foreign app cannot trigger a deletion, because the ledger id is random and exists only for picks made inside Cloak.
- Persisted grants for picked folders last until the move settles or 7 days pass. Forwarded grants are pruned after 6 hours.
- Install restrictions are cleared in the space, so any app that can install packages there is not blocked.
- No idle auto-lock. Privacy policies depend on OEM behaviour. Notification redaction is unverified.
- If the secret is lost and recovery was not set up, the space is unmanageable from the app.
- Cloned apps show in the launcher and Settings while visible, and their notifications show in the main shade. With hide mode they disappear while locked.
- Updates happen through the space's Play Store or by cloning again. Deleting the space deletes its data. There is no backup.
- The release key is the signing root. It lives only on your machine and in the protected `release` environment, and the workflow verifies the certificate before publishing.

Decisions:
- **R8 shrinking stays off.** The project is open source and the secret is in a preferences file, not in code, so obfuscation protects nothing. The size gain is small and shrinking risks breaking DPC and manifest entry points.
- **`MainActivity` stays exported.** It has no intent filter beyond the event alias and Studio's Run needs it.
- **`INTERACT_ACROSS_PROFILES` was removed** because it was proven ineffective.
- **Hide mode is opt-in and experimental**, and apps stay hidden while Cloak is unpaired by design.
- **A user-held token, not a server,** for recovery. No account, no network.
- **Events are unsigned** because they carry no authority.

---

## 10. External review triage

An external review of the code was triaged. Result, for the issue tracker:

| ID | Finding | Verdict |
|---|---|---|
| C-1 | Both copies declare the same FileProvider authority, so file sharing is broken | Wrong: forwarding rewrites URIs to `content://<userId>@authority/...`, and the file bridge works |
| C-2 | Clearing the main copy's data orphans the work profile | Real. Handled by the unpaired screen and the recovery token (6.14, 6.15) |
| L-1 | Package regex accepts segments starting with digits | Fixed |
| L-2 | `Commands.send` has no `FLAG_ACTIVITY_NEW_TASK` | Latent, skipped. Adding it could change cross-profile launch behaviour |
| L-3 | `InstallCoordinator` latch race with concurrent clones | Latent. UI serializes clones. Fix with a clone queue |
| L-4 | `commit()` results ignored in `Auth` | Fixed |
| L-5 | Export and cleanup race | Latent. Fix with a clone queue |
| L-6 | Profile selection assumes managed below API 35 | Partly addressed (6.8). Still a gap on multi-profile phones below API 35 |
| L-7 | Privacy toggle out of sync after a failed send | Fixed (rollback) |
| L-8 | Partial `setupChannel` failure is silent | Real, mitigated by Repair space. Surface failures later |
| N-1 | Enable R8 | Declined (section 9) |
| N-2 | Stale class names in `HANDOFF.md` | File deleted, superseded by this guide |
| N-3 | `findActivity()` can throw | Latent, all callers are Activities |
| N-4 | `cleanup(0)` deletes everything | Consider renaming to `cleanupAll()` |

Items the review marked as correct match this guide's reading. It predates the transfer, onboarding and event features, which have not been reviewed externally.

Publishing checklist, repeated before every release: the README is current, no keystore or `local.properties` is in git history (`git log --all --name-only | findstr /i "keystore jks local.properties"` must print nothing), and the release verification in section 5 passes.

### 10.1 Internal code audit (November 2026)

A read-through of the source after the transfer, onboarding and unlock work, plus a review of the change set that applied the fixes.

| Finding | Status |
|---|---|
| `CommandActivity` returned before `super.onCreate` when recreated during the PIN prompt | Fixed |
| `TransferService` had no `onTimeout` (Android 15 service limit) | Fixed |
| `allowBackup="true"` was back in the manifest | Fixed, now false |
| `isGone` treated a removed system app as present, causing a re-lock after unlock | Fixed |
| Reconciler could get stuck, and polled abandoned transfers for 12 hours | Fixed |
| Opening screen waited 6 s for an empty space | Fixed, ends on the `session` result |
| `LockService.start` was unguarded | Fixed |
| A file and a folder with the same name misplaced files | Fixed |
| Transfer notifications showed file names | Fixed |
| App list loaded once per screen with icons, and profile detection ran on every call | Fixed with a shared holder and a profile cache |
| That holder crashed the app at launch (callback registered off the main thread) | Fixed |
| `lockNow` guarded at API 34 | Fixed, guard removed |
| `allowInstalls` dropped the global unknown-sources clear | Restored |
| Receiver setup ran on a bare thread | Fixed, `goAsync` |
| Profile cache made state lag | Fixed, `invalidate()` and a single cache |
| `recoverOrphans` unhides whatever is hidden and scans every package on each setup | Open, low |
| Report poll flashes a transparent screen | Open, low |

A bundled change set that mixes an SDK change, audit fixes and documentation shipped the launch crash. Apply changes of this kind in small commits, and run the app on a phone after each one.

---

## 11. Conventions and workflow

- Conventional commits (`feat:`, `fix:`, `refactor:`, `chore:`, `docs:`, `test:`, `build:`), pushed in batches.
- All `DevicePolicyManager` calls go through `PolicyManager`, except hiding, which `SessionManager` owns. UI code never calls them directly.
- Functions return `Result<T>` and log failures. Never throw into the UI.
- Log through `Logx`, tag format `SecondSpace/<Class>`. No package names, file names or secrets in release logs.
- No hardcoded user-visible strings. Use `strings.xml`. Use "Cloak" for the app and "Second Space" for the protected area.
- One screen or class per file where practical.
- When fixing a mismatch, add the missing side. Do not strip functionality.
- Do not remove a setup trigger in the work copy without adding a replacement (6.9).
- Any new setup the work copy needs must be added to `setupChannel`, because it clears and rebuilds all cross-profile filters each time it runs.
- Pure decisions go in functions that run on the JVM and get unit tests (`Auth.isValid`, `SpaceProfile.classify`, `ConflictRules`, `CollapseModel`, `OnboardingRules`, the recovery pieces).
- Debug tooling stays in `src/debug`, with an empty stand-in in `src/release` for every symbol the main code touches. Check a release build after changes to the debug source set (section 5).
- Public APKs only come from the workflow or `-Pcloak.debugSign=false` with the release key. A debug-signed APK is for your own phone and is never uploaded.
- After installing a build, confirm the work copy updated too.
- Do not trust remembered API behaviour. Verify on the device and record it here.
- Register `LauncherApps` callbacks with an explicit main-looper `Handler`. The one-argument overload needs a Looper on the calling thread.
- Guard only what lint flags. A version guard on an API that exists on every supported version can break behaviour (`lockNow`, 6.20).
- Apply audit fixes and refactors in small commits and run the app after each, because one bundled commit shipped a launch crash.
- Save diffs with `git diff --output=file`. PowerShell's `>` writes UTF-16 and garbles text.

Working on `admin/` and `bridge/`: check section 8 first, because several obvious approaches do not work for work profiles. When something fails, keep the compiler error or the logcat verbatim, since it usually names the cause.

---

## 12. Open issues and known gaps

1. **Returning to Cloak after the work PIN** works only partly (6.19). Fallbacks are listed there.
2. **The unlock screen can stay up after a fingerprint unlock.** One report on a Motorola phone, not reproduced. What to collect if it happens again is in 6.19.
3. **Fingerprint is not always offered** for the work lock. Android decides, and the PIN is the fallback.
4. **Android 12 to 14 are untested** (6.20). The foreign-profile screen cannot identify another app's profile there.
5. **Locking during a transfer.** With hide mode on, screen-off locks the space, which interrupts a copy that touches space storage. A long conflict pause makes it likelier. Decision pending: make the lock wait until the transfer ends (touches `SessionManager`).
6. **The report poll** flashes a transparent screen over Cloak when something is waiting. Opening Transfer files and tapping Check for finished moves does it on demand.
7. **Android 15 limits** data-sync foreground services (about six hours in 24). `onTimeout` now cancels cleanly and keeps what was copied, but a very large transfer needs a second run.
8. **Replace** overwrites in place, so a cancel mid-file leaves it partial. New files are cleaned up. **Folders always merge.** A skipped file keeps its whole source item on a Move.
9. **The conflict notification is silent.** A louder channel is a small change.
10. A Move interrupted by a restart stays in the ledger until it expires after 7 days. Its originals stay. Settle is not resumable: cancelling it leaves a partly deleted source.
11. **Notification redaction** is unverified and needs a freshly created space (6.11). **Clipboard blocking** failed once and worked later with the cause unknown.
12. **No idle auto-lock.** A timer would need another long-lived component or exact alarms. Not planned. **Cloak does not lock its grid on plain screen lock** without hide mode, because the profile stays storage-unlocked. Needs a session marker.
13. **Command results** are used by `lock_status`, `install_status`, `session`, `transfer_report`, `transfer_done` and the debug ping. They are not yet used to confirm `set_recovery`, `set_privacy` or `repair`, or to detect "secret present but rejected".
14. **Hide all packages** is a greyed-out placeholder (6.10).
15. **Shutdown hide reliability** with many or heavy apps is unmeasured, and so is the idle battery cost of the foreground services.
16. **Ownership detection** is verified for "no Cloak inside" and the normal case only. The other rows, the paused row with a secret, and the OnePlus are untested (6.14).
17. **Cloning** has a spinner but no progress, and runs one at a time. A queue also fixes L-3 and L-5.
18. **Performance leftovers.** `recoverOrphans` scans every package on each setup (hundreds of binder calls in a receiver thread). If the space and Cloak disagree on the app count, the settle loop runs its full 20 seconds. The 3 s state poll stays until broadcast coverage is confirmed.
19. **Setup failures** are silent in the UI (L-8). `Commands.send` has no `FLAG_ACTIVITY_NEW_TASK` (L-2).
20. **Context-based code is not unit tested** (`sign`, `verify`, `RecoveryGate`, `SessionManager`, the transfer engines, the stores, `SpaceAppsHolder`). Needs Robolectric or instrumented tests.
21. **The recover result code** was not recorded. Run the recovery once and note the `recover result=` line.
22. **Debug and public signatures differ.** A build you made yourself cannot update the release APK, and the other way round. Moving between them means recreating the space.
23. **Window and splash background** are fixed to the Ocean colour, and the space's receiver screen has its own theme setting.
24. **Grid header:** a short list may still collapse the header when dragged, and landscape and tablets are untested. The action buttons are 38dp, below the touch-target guideline.
25. **Devices:** the OnePlus 13s (OxygenOS 15) is the only phone used for most behaviour. The Samsung S21 FE (One UI 8.5) was used for ownership checks and unlock behaviour, and a Motorola phone produced the fingerprint report.
26. **Housekeeping.** Some files are misnamed or in the wrong test folder (`ScourceScanner.kt`, `RememeberProfileLiveness.kt`, `PebbleMathtest.kt`, `CollapseModelTest` in the `bridge` folder), and `PinPromptActivity.kt` should be checked against the class it declares. Kotlin does not care, but the project tree is confusing.
27. **Sharing UI rework** is on hold, to be done as a separate batch by the maintainer.

---

## 13. Milestone history

1. **Skeleton:** profile owner, provisioning, work PIN enforced on resume. Done.
2. **Command channel:** `CrossProfileApps` probe (negative), cross-profile intent filter, signed commands, lock now, space state. Done.
3. **App management:** install restrictions cleared, file bridge, APK-copy cloning, system-app enabling, freeze, remove. Done.
4. **App grid:** live grid, launch, unlock flow, launcher icon hidden. Done.
5. **Debug-only tooling** split into `src/debug`. Done.
6. **Feature roadmap step 1:** managed-profile selection, privacy settings, delete space. Done.
7. **Secure Folder-style UI:** theme, home, state screens, grid, add-apps list, settings page. Done.
8. **Hardening and cleanup:** backup disabled, counter-based signing, argument and URI validation, `Logx`, debug-only commands, step-wise setup, broadcast-driven state, dead code removed, threat model written. It broke the channel because the work copy lost its only setup trigger, which led to the self-repair receivers and the Maintenance controls.
9. **Fixes after on-device testing:** forwarded URI `host` check, Repair space replaces Restart space, commit-result checks, package regex, privacy rollback. Done.
10. **Tests:** unit tests for the pure logic. The first eight files passed.
11. **Locking research:** spikes for hiding apps and the screen-off listener. Hiding works and hides the work tab, the foreground service is reliable.
12. **Hide mode:** `SessionManager`, `LockService`, settings toggle, Opening screen. Done, experimental.
13. **Reboot fix:** diagnosed from the boot log, saved package list, boot fix and shutdown toggles, hide-all placeholder. Done.
14. **Unpaired state:** detection and safe-exit screen. Done.
15. **Ownership detection:** probes on two phones, foreign owner classification and screen. Done.
16. **Recovery:** PIN prompt spike, token and setup, then the recover entry point with proof, lockout, work PIN gate and the restore screens. Done and verified.
17. **File transfer:** spikes (share filters, pickers, grant forwarding, persisted grants, result relay), then the pull engine, foreground service, share receiver, transfer page, verified Move. Reported working, edge cases untested.
18. **UI overhaul:** pebble icons, thumb-reach grid card with collapsing header, colour presets, egg icon, grouped settings, themed menus, buttons in the title row. Done after several rounds of feedback.
19. **Conflicts:** mid-copy prompt with apply to all, copy or move chosen on the receiver. Written.
20. **Signing and release:** debug-key and release-key paths, derived version code, release workflow. Pre-releases published.
21. **Unlock return:** auto unlock on resume, then the unlock event and `EventAlias`. Partly working.
22. **Hidden-app recovery, tidy-up and onboarding:** orphan recovery, string and resource cleanup, guided first run. Onboarding reported working.
23. **Unhide settle and the install step:** the grid refreshes until every unhidden app shows, and onboarding asks for the install permission. Written.
24. **Code audit and fixes:** a startup crash, a service-timeout crash, backup flag, relock loop, notification privacy, reconciler, engine naming, shared app list with a profile cache, `goAsync` receivers. The first version of the shared list crashed at launch (callback registered off the main thread), and the change set also broke `lockNow` and `allowInstalls`. All fixed.
25. **Minimum SDK 31:** guards for API 33, 34 and 35 calls, a "setup did not finish" message, and the untested Android 12 to 14 caveat.
26. **First stable release:** 1.0.0. Next: quality-of-life changes in later releases.