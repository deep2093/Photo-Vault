# Preview signing key

`preview-signing.p12` is PhotoVault's own **non-production** signing identity (password `android`).
It exists so every CI preview build installs as an update over the previous one.

It is not Veyra's key, and it must never sign a release. Release builds are signed only with a key
supplied outside the repository (see `app/build.gradle.kts`).
