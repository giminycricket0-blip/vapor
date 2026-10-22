# Fixed CI debug keystore

`ci-debug.keystore` is a throwaway Android **debug** signing key used only by the
`APK reproducibility` workflow. It is intentionally committed: the reproducibility
lane compares two independently built APKs on separate runners, and Android's
normal debug keystore is generated with a fresh random key per machine, so the
signing block would differ even when every ZIP entry is byte-identical (the first
lane run failed exactly this way and `tools/tests/apk-repro-diff.py` classified
the delta as signing-block-only).

The keystore is a public test key, not a secret:

* store password / key password: `android`
* alias: `androiddebugkey`
* distinguished name: `CN=CI Debug,O=Vapor,C=US`
* validity: 10000 days, RSA 2048

It must never be used to sign Vapor releases or any distributed artifact. Release
signing remains an open gate (see `docs/BUILDING.md`).
