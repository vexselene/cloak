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
  secret. Anyone who can read that storage can already control the space.
- The recover command is the one command accepted without a signature, because the
  signature secret is exactly what the user has lost. Any app on the phone can send it.
  It is accepted only if all of these hold: recovery was opted into, the space is
  unlocked, the attempt is not rate limited (the first three wrong proofs are free,
  then 30 s, 2 min, 10 min, 1 h and 6 h), the request is well formed, the timestamp
  is within five minutes and newer than the last accepted request, and the proof (an
  HMAC over the new secret and the timestamp, keyed from the token) is valid. A guess
  has about 2^-130 chance. Failed attempts are counted before comparing.
- A valid proof does not pair by itself. The space then asks for the work lock through
  the system credential screen, and the new secret takes effect only after the user
  confirms it. A thief with the token but not the PIN cannot re-pair. If the work lock
  is the same as the phone lock, this is the phone PIN, not a second secret.
- The new secret travels from the main copy to the space copy inside the recover
  intent. Other apps cannot read another app's intents.
- Any app can block recovery for a while by sending bad proofs, which triggers the
  lockout. Normal commands are unaffected.
- A recover request shows toasts in the space. An app spamming it would annoy the
  user, but nothing else.
- Setting the phone's clock back after a recovery makes the next recovery fail until
  the clock catches up.
- File transfer: the share target (`ShareReceiverActivity`) is exported, like any share
  target, so any app can send it content URIs. It accepts only `content://` URIs, does
  nothing until the user picks a destination folder in the system picker, and can only
  read what the sender could already grant. Names from providers are sanitised before
  files are created. Transfers run in a foreground service with a visible, cancellable
  notification. With "Share from Second Space to personal apps" on, every app in the
  space can share to every personal app, not only Cloak. It is off by default.
- In-app file transfer adds three entry points. `TransferInAlias` (an exported alias of the
  share receiver, enabled only in the personal copy) accepts a private action from any app
  there, like a share target: it only reads what the sender grants and does nothing until
  the user picks a destination. `transfer`, `transfer_pick`, `transfer_report` and
  `transfer_done` are signed commands like any other.
- Move deletes originals, so it is gated: the destination reports per source the file count
  and bytes it verified, and the sender deletes a source only if the report equals what it
  recorded when the user picked it, a fresh scan still equals that record, and every size
  was known. Cancelled, failed or partly verified transfers delete nothing. A foreign app
  that sends the private action cannot cause a deletion, because the report goes to a
  ledger entry that only exists for picks the user made in Cloak, and it carries a random
  64-bit id.
- Cloak keeps persisted access to picked folders until the move is settled or seven days
  pass. Forwarded access is dropped after six hours.
- `EventAlias` is an exported alias of `MainActivity` in the personal copy, reachable by a
  cross-profile filter from the space. Events are hints and are not signed. An event only brings
  Cloak forward and makes it re-read the real state, and it never triggers an action. Any app in
  either profile can send it, which only brings Cloak to the front. `MainActivity` is exported
  already, so this adds no capability.