# Second Space: Architecture and Conventions

This document is the architecture contract for the project. Keep it current; paste it at the top of any assistant/chatbot session working on this codebase.

## Overview
One Kotlin APK, package `io.cloak.os`, min SDK 31, target latest. It runs in two Android users:
- **Main instance** (user 0): UI, provisioning launcher, app grid, launching.
- **Agent instance** (managed profile): is the profile owner. Runs DevicePolicyManager calls that only a profile owner process can make (clone, hide, suspend, policies), receives shared files.

`Role.kt` decides which one is running: `UserManager.isManagedProfile` -> `Role.AGENT`, else `Role.MAIN`.

## Stack
Kotlin, Jetpack Compose (Material 3), ViewModel + StateFlow, coroutines, DataStore for settings. No DI framework, no Room, no extra libraries unless approved.

## Packages (`io.cloak.os.*`)
| Package | Contents | Owner                                             |
|---|---|---------------------------------------------------|
| `admin` | `SpaceAdminReceiver`, `GetProvisioningModeActivity`, `PolicyComplianceActivity`, `PolicyManager` | User/Claude (hand-reviewed)                       |
| `apps` | `SecondSpaceApps` (LauncherApps listing/launch), `ProfileAppActions` (clone, hide, suspend) | User/Claude                                       |
| `bridge` | cross-profile command channel, file-copy share target | User/Claude (channel), chatbot (file I/O helpers) |
| `ui` | Compose screens, theme, navigation | User/Claude                                       |
| `common` | `Role`, constants, logging, result types | User/Claude                                       |
| `build/CI` | Gradle tweaks, GitHub Actions, README, screenshots | chatbot                                           |

## Rules for any code touching DevicePolicyManager
- Only a profile owner process may call these. Do not invent API names: check developer.android.com.
- Every call goes through `PolicyManager` or `ProfileAppActions`. UI code never touches `DevicePolicyManager` directly.
- Return `Result<T>`; never throw into UI.

## Cross-profile command channel (OPEN, decide via spike)
Main instance needs to ask the agent to run profile-owner actions. Candidates:
- A. `CrossProfileApps` / bound service with `INTERACT_ACROSS_PROFILES`, whitelisted with `setCrossProfilePackages`. Needs verifying whether user consent is still required on OxygenOS 15.
- B. Cross-profile intent filter (`addCrossProfileIntentFilter`) plus a trampoline activity in the agent.
Decision happens in milestone 2 after testing on the device.

## Conventions
- Conventional commits (`feat:`, `fix:`, `chore:`, `docs:`).
- One class or screen per file. State via `StateFlow`, UI state as one immutable `data class` per screen.
- Fix mismatches by adding the missing side, never by deleting existing functionality.
- Log tag format: `SecondSpace/<Class>`.
- No hardcoded user-visible strings; use `strings.xml`.

## Working with chatbots
- Give one file or one task per prompt, with the exact interface to implement.
- Ask for the complete file, not diffs.
- Paste compiler errors and logcat back verbatim.
- Anything touching `admin/` or `bridge/` channel code comes from Claude, not other chatbots.

## Milestone 1 checklist (skeleton)
1. Remove Shelter, TestDPC, and any existing work profile from the phone. (done)
2. Create the project (package `io.cloak.os`, min SDK 31, Compose). (done)
3. Add `SpaceAdminReceiver`, `device_admin.xml`, and the two provisioning activities in the manifest.
4. Main screen with a "Create Second Space" button that launches `ACTION_PROVISION_MANAGED_PROFILE`.
5. Verify: `adb shell dpm list-owners` shows the app as profile owner, and the work PIN prompt appears.
