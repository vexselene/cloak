# Threat model

Second Space hides apps and data behind the work-profile lock. It is a convenience
and privacy tool, not a hardened security product.

## What it protects against
- Someone using your unlocked phone: the space needs its own PIN, and its apps and
  data live in a separate Android user.
- Casual snooping: one visible icon, no work launcher, optional blocked screenshots,
  hidden lock-screen notification content.
- Other apps on the phone: they cannot read the space's data (Android user isolation).
- Other apps sending commands to the space: the command activity requires a
  signature permission AND a per-space HMAC with a monotonic counter.

## Trust boundaries
- Android enforces the profile separation. Cloak relies on it and adds nothing to it.
- The main copy talks to the space copy through one cross-profile intent filter
  (bidirectional, but the main copy’s CommandActivity is disabled, so the space cannot send commands to it.). Commands are signed with a secret created at provisioning.
- The space copy is the profile owner. It only acts on valid, signed commands.

## Not protected / known gaps
- Rooted phones, forensic extraction, or someone who already knows your PIN.
- No idle auto-lock yet. The space re-locks when the screen locks, not after
  leaving an app open on an unlocked screen.
- The pairing secret is stored in app-private storage on both sides, unencrypted.
  Clearing Cloak's data in the main profile loses it, and the space must be recreated.
- Install restrictions are cleared inside the space so apps can be added. Any app
  that can install packages there is not blocked.
- Cloned apps appear in the launcher and Settings (a third-party launcher helps),
  and their notifications appear in the main shade.
- Screenshots, clipboard and contact policies depend on the OEM honoring them.
  Verified on OxygenOS 15 only, and clipboard blocking is currently not confirmed.
- No backup. Deleting the space deletes its data.
- Release builds log only exception class names. Debug builds log more.
- Optional recovery token: when the user opts in, the space copy stores a key derived
    from the token (never the token) in device-protected storage, next to the pairing
    secret. Anyone who can read that storage can already control the space. Setting up
    the token over the signed channel is protected by the same checks as any command.
    The recover entry point is added in a later step and gets its own entry.