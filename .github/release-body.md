## Install

Download the APK below and install it on Android 12 or newer. Cloak is early software tested on a
small number of phones. Read the limitations in the README and `docs/THREAT_MODEL.md` first.

## Verify the download

Compare the SHA-256 in the `.sha256` file with `sha256sum` of the APK, and check the signer:

    apksigner verify --print-certs Cloak-<version>.apk

The certificate SHA-256 digest must be:

    b4df89f0679ccc781c3df58e1d0f58145da58f9ef55ebee7820fc47458caecd2

## Updating

Updates install over the previous version only if both were signed with this release key. A build
you made yourself, or one signed with a different key, cannot be updated in place. Android refuses,
and the only way forward is to remove Second Space and create it again, which deletes its data.
