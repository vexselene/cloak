# Spike Results: App Hiding and Screen-off Listener

**Date:** 2026-11-03
**Device:** OnePlus 13s, OxygenOS 15 (Android 15), no root.
**Purpose:** Verify that `setApplicationHidden` performs well enough to serve as
the lock mechanism, and that a work-profile process can reliably receive
`ACTION_SCREEN_OFF`.

---

## Spike 1: App Hiding (`setApplicationHidden`)

**Status: PASSED. This is the lock mechanism.**

### What was tested
- Hiding and unhiding a single cloned app that was actively running (audio playing).
- Hiding and unhiding a system app (Chrome).
- Bulk hiding all launchable apps in the space (8 apps at the time of testing).
- Rebooting the phone after a bulk hide to check if hidden state survives.

### Results

| Test | Result |
|---|---|
| Hide active app | Process killed. Audio stopped. Notification removed. App vanished from OxygenOS launcher work tab and from Settings > Apps. |
| Unhide app | App returned to grid and launcher with login and data intact. |
| Hide system app | Same behavior as cloned app. |
| Bulk hide (8 apps) | ~50ms total. The work tab in the OxygenOS launcher disappeared entirely, because no work apps with launcher icons remained. |
| Bulk unhide (8 apps) | ~50ms total. Apps returned correctly. |
| Single hide/unhide timing | ~18ms for an active app, 1–5ms for an inactive app. |
| Reboot after bulk hide | Hidden state survived the reboot. |

### Conclusions
- Hiding is fast enough to run synchronously in the lock flow. 50ms for 8 apps is imperceptible.
- Hiding kills the process, so an app cannot continue running in the background while the space is locked.
- Bulk hiding removes the work tab from the OxygenOS launcher. This is the behavior we want: the user should not be able to see or launch work apps without unlocking Cloak first.
- Hidden state survives a reboot, so we do not need to re-hide on every boot (though the boot receiver is still useful as a safety net if a previous run left the space unlocked).

### Design implications
- The lock sequence is: **hide all apps, then evict the credential encryption key** (`lockNow(FLAG_EVICT_CREDENTIAL_ENCRYPTION_KEY)`). Hiding removes the apps from every surface. Key eviction makes the profile storage inaccessible.
- The unlock sequence is: **unlock the profile** (system PIN prompt), then **unhide all apps**.
- The list of hidden packages must be persisted in device-protected storage, because the process may die between hiding and unhiding. The spike stores this in a `SharedPreferences` set called `hidden_set`.
- Cloak's own package and `com.android.settings` must be excluded from bulk hiding, or the space becomes unrepairable from inside the app.

---

## Spike 2: Screen-off Listener (`ACTION_SCREEN_OFF`)

**Status: Foreground service PASSED. Plain process FAILED.**

### What was tested
- A foreground service holding a runtime-registered `SCREEN_OFF` receiver.
- A plain process (no foreground service) holding the same receiver.
- Whether the receiver survives Cloak being swiped out of Recents.
- Whether the receiver survives 30 minutes of normal use with screen-off/on cycles.

### Results

| Variant | Screen-off events counted | Survives Recents swipe | Alive after 30 min |
|---|---|---|---|
| Foreground service | Yes, correctly counted on every lock | Yes | Yes |
| Plain process | Yes, until swiped from Recents | No | No |
| Battery exemption dialog | Opens correctly, but after granting, a false toast appears and the dialog cannot be reopened | N/A | N/A |

### Conclusions
- **The foreground service is required.** The plain process is killed as soon as Cloak is cleared from Recents, which means the screen-off listener stops working. A user who swipes the app away would leave the space unlocked indefinitely.
- **The battery exemption dialog is broken on OxygenOS.** After granting the exemption, the app shows a toast claiming the dialog opened (it did not). This is a UI bug in the test code, not a blocker, because we are not relying on the exemption path.
- A persistent notification ("Second Space is open") is required by Android for the foreground service. This is acceptable.

### Design implications
- The lock flow starts when the space is opened: the main app sends an `OPEN` command, the work copy starts the foreground service and registers the receiver.
- When `SCREEN_OFF` is received, the service hides all apps, locks the profile, and stops itself (so it does not drain battery while the space is locked).
- Safety nets (in case the service is killed anyway):
    1. **Boot receiver**: re-hides everything on restart.
    2. **Lease alarm**: an inexact alarm set at open time, cancelled on clean lock. If the service dies and the screen turns off, the alarm eventually hides the apps. (may add more overhead)
    3. **On open**: the work copy checks hidden state and re-hides anything left visible before showing the grid.

---

## Bonus: Clipboard Restriction

**Status: VERIFIED WORKING.**

After the recent batch of security fixes (`Auth.commit()` result checks, package regex tightening, privacy toggle rollback), the `DISALLOW_CROSS_PROFILE_COPY_PASTE` user restriction now takes effect correctly. Copying from the main profile to the space is blocked when the switch is enabled, and unblocked when it is disabled.

This resolves the "Not working on test device" note in section 6.11 of the developer guide.

---

## Follow-up actions

1. Implement the `OPEN` command and the foreground service in `src/main` (not just `src/debug`).
2. Persist the hidden package set in device-protected storage and expose it through `PolicyManager`.
3. Wire the lock sequence: hide all, then `lockNow`.
4. Wire the unlock sequence: system PIN prompt, then unhide all.
5. Add the boot receiver as safety nets.
6. Remove the debug-only spikes once the production code is verified.
7. Update section 6.11 of the developer guide: clipboard is now verified.
8. Add a new section to the developer guide covering the lock/hide architecture.