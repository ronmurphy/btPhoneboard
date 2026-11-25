# Signing key

Android only installs an update over an app if both APKs are signed with the
same key. `shared.keystore` is that key for this project. It's committed to
the repo (with its password in `app/build.gradle.kts`) so that GitHub Actions
and Android Studio sign every build the same way, and each new build installs
over the last one without uninstalling first.

The catch: since the key is public, anyone could sign an APK that your phone
would accept as an "update" to this app. That only matters if you install an
APK of this app from somewhere other than your own repo. For a personal
sideloaded app that's an acceptable trade.

Before publishing to the Play Store or sharing the app widely, switch to a
private key: generate a new keystore, store it as a GitHub Actions secret, and
point the `shared` signing config at it. Note that changing the key means
uninstalling the old app once.
