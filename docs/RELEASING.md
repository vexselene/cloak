# Releasing

## One-time setup
1. Create the release key and back it up in two places (see the commands in the developer guide).
2. Create `keystore.properties` in the repo root. It is gitignored.
3. Build once with `gradlew.bat assembleRelease -Pcloak.debugSign=false`, run `apksigner verify
   --print-certs` on the APK, and save the `certificate SHA-256 digest` to `docs/release-cert.sha256`
   (lower case, no colons). Commit it. Put it in `.github/release-body.md` and the README too.
4. On GitHub create an environment named `release` (required reviewer: you, tags `v*` only) and add the
   four secrets `CLOAK_KEYSTORE_B64`, `CLOAK_KEYSTORE_PASSWORD`, `CLOAK_KEY_ALIAS`, `CLOAK_KEY_PASSWORD`.

## Each release
1. Change `cloak.version` in `gradle.properties` (for example `0.2.0`) and commit it. Never lower it.
2. `git tag v0.2.0` then `git push origin v0.2.0`. Approve the `release` environment when asked.
3. The workflow runs the tests, builds, checks the signing certificate and creates a **draft** release.
4. Open the draft, read the notes, test the APK on a phone, then publish.

## Your own phone
Set `cloak.debugSign=true` in `~/.gradle/gradle.properties`. Then `gradlew.bat assembleRelease` signs
with the debug key, and `adb install -r app\build\outputs\apk\release\app-release.apk` updates in
place over a debug build, in both profiles. Never upload that APK anywhere.

## Releasing without CI
Build with `gradlew.bat assembleRelease -Pcloak.debugSign=false`, run `apksigner verify --print-certs`,
compare the digest with `docs/release-cert.sha256`, then
`gh release create vX.Y.Z Cloak-X.Y.Z.apk Cloak-X.Y.Z.apk.sha256 --draft --generate-notes`.

## If the key is lost
Existing public installs cannot be updated. Users must remove Second Space, uninstall Cloak, and
install a build signed with a new key. Their data in the space is lost.