# Fixed CI debug keystore

`ci-debug.keystore` is a throwaway Android **debug** signing key used only by the
`APK reproducibility` workflow. It is intentionally committed: the reproducibility
lane compares two independently built APKs on separate runners, and Android's
normal debug keystore is generated with a fresh random key per machine, so the
signing block would differ even when every ZIP entry is byte-identical (the first
lane run failed exactly this way and `tools/tests/apk-repro-diff.py` classified
the delta as signing-block-only).

The workflow passes the key through the app's explicit signing hook
(`KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`; see
`app/build.gradle`), and a guard step runs `:app:signingReport` and fails unless
`standardDebug` resolves to this keystore. Copying it to `~/.android/debug.keystore`
was tried first and was not picked up by AGP on the runners.

The keystore is a public test key, not a secret:

* store password / key password: `android`
* alias: `androiddebugkey`
* distinguished name: `CN=CI Debug,O=Vapor,C=US`
* validity: 10000 days, RSA 2048
* certificate SHA-256: `6F:07:6A:D8:B2:85:10:A3:10:7A:CA:3F:36:7B:1C:D3:EE:96:87:C2:C0:61:1E:F0:40:9C:A8:AD:59:BA:62:F6`

It must never be used to sign Vapor releases or any distributed artifact. Release
signing remains an open gate (see `docs/BUILDING.md`).
