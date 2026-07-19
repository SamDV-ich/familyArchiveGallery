# Releasing Family Archive Gallery

## Overview

Production APKs are built, signed, and published automatically by GitHub Actions whenever a tag matching `v*` is pushed to GitHub.

The updater in the Android TV application checks the latest published release from:

```text
https://api.github.com/repos/SamDV-ich/familyArchiveGallery/releases/latest
```

The repository, or at least the repository used for Releases, must be public. Do not embed a GitHub personal access token in the Android application.

## Release Assets

Every release workflow publishes exactly these files:

```text
familyarchivegallery.apk
familyarchivegallery.apk.sha256
```

The application downloads the APK, verifies its SHA-256 checksum, and then opens the Android package installer.

## One-Time Signing Setup

Create a release keystore locally. Keep this file private and back it up securely:

```bash
keytool -genkeypair \
  -v \
  -keystore familyarchivegallery-release.jks \
  -alias familyarchivegallery \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000
```

Do not commit the keystore or its passwords. The `.gitignore` file excludes common keystore filenames.

Encode the keystore as a single-line Base64 value on macOS or Linux:

```bash
base64 < familyarchivegallery-release.jks | tr -d '\n'
```

In the GitHub repository, open **Settings → Secrets and variables → Actions** and add these repository secrets:

| Secret | Value |
| --- | --- |
| `SIGNING_KEY_BASE64` | Single-line Base64 representation of the `.jks` file |
| `RELEASE_STORE_PASSWORD` | Keystore password |
| `RELEASE_KEY_ALIAS` | Key alias, for example `familyarchivegallery` |
| `RELEASE_KEY_PASSWORD` | Key password |

The workflow writes the decoded keystore only to the temporary GitHub runner directory.

## Preparing a Release

Before creating a tag, update both values in `app/build.gradle.kts`:

```kotlin
versionCode = 2
versionName = "1.1.0"
```

Rules:

- `versionCode` must increase for every APK update.
- `versionName` must match the Git tag without the leading `v`.
- Every release must use the same signing keystore.
- Do not change `applicationId` after the first production installation.

Commit and push the version change, then create and push the tag:

```bash
git add app/build.gradle.kts
git commit -m "Prepare release 1.1.0"
git push origin main
git tag v1.1.0
git push origin v1.1.0
```

Pushing the tag starts `.github/workflows/release.yml`. The workflow:

1. Checks out the tagged source.
2. Configures Java and Gradle.
3. Restores the signing key from GitHub Secrets.
4. Runs unit tests and release Android Lint.
5. Builds the signed release APK.
6. Generates the SHA-256 checksum.
7. Creates a GitHub Release with generated release notes.
8. Uploads the APK and checksum assets.

## First Installation

Install the signed APK from the first GitHub Release, not `app-debug.apk`.

The debug build uses a different application ID and debug signing key. It cannot be upgraded in place by the production APK.

## In-App Update Flow

On the category screen, select **Check for updates**. If a newer release exists, the button changes to **Update to X.Y.Z**.

After the user selects it, the application:

1. Downloads `familyarchivegallery.apk` from the latest GitHub Release.
2. Verifies its SHA-256 checksum.
3. Requests the per-app “install unknown apps” permission if needed.
4. Opens the Android system installer.
5. Lets the user confirm the update with the remote control.

Android preserves application data when the application ID and signing certificate match and the new `versionCode` is higher.

## Troubleshooting

### No release found

- Confirm that the repository is public.
- Confirm that the release is published, not a draft or prerelease.
- Confirm that the release contains `familyarchivegallery.apk`.

### APK does not update the installed application

- Confirm that both APKs use the same application ID.
- Confirm that both APKs are signed with the same release key.
- Confirm that the new APK has a higher `versionCode`.
- Remove a previously installed debug build before the first production installation.

### GitHub Actions signing failure

- Recreate `SIGNING_KEY_BASE64` as a single line.
- Verify all four signing secrets.
- Verify that the key alias and passwords match the keystore.
