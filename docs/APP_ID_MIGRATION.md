# Android application ID and user-data migration

## Canonical application ID

The canonical installed package ID is `dsh.dsh.mobile`.

The Android Gradle namespace intentionally remains `app.dsh.mobile`. The namespace controls generated Kotlin/Java resource and BuildConfig package references; it does not determine the installed package identity.

PR #27 changes `defaultConfig.applicationId` from `app.dsh.mobile` to `dsh.dsh.mobile`. The inspected app Gradle file has no `applicationIdSuffix` or product-flavor application ID override.

## Important: changing applicationId does not migrate the Android sandbox

Android gives each installed application ID its own private data sandbox. A build installed as `dsh.dsh.mobile` cannot directly read another package's private `/data/user/0/<old-package>/` directory. Keeping the old application installed does not grant the new package access to that private directory.

- Do not uninstall the old package or clear its data as part of this migration.
- Do not claim that changing `applicationId` automatically carries sessions, profile credentials, plugin settings, or other private files forward.
- A safe cross-package migration needs an explicit export/import path (for example, a user-selected document/archive) or a separately designed, permission-checked shared-storage migration. Verify the archive and preserve the source until import is confirmed.
- Audit the existing Data Export/Import feature's coverage of private profile data and credentials before presenting it as a complete migration path.

## Existing data layout and permission behavior

The app's `EngineConfig` keeps DSH home under `context.filesDir/dsh-home`. It selectively externalizes `sessions` and `workspaces`; `profiles` remains private because the upstream credential layer requires restrictive Unix permissions.

The preferred shared data root is `/storage/emulated/0/Documents/dshdata`. It is used only when All Files Access is granted and the directory passes usability checks. Otherwise the code falls back to the app-specific external files directory (`getExternalFilesDir()/dshdata`), which is tied to the package and removed on uninstall.

- All Files Access is not a prerequisite for starting the engine; it determines whether the app can use the shared Documents data root.
- If the app falls back to package-specific external storage, a package-ID change does not make the old package's external directory automatically available to the new package.
- Do not delete existing symlink targets merely because a probe temporarily fails. Preserve data and report the condition for recovery.

## Self-check interpretation

- A missing `storages/workspace.json` is currently reported as `OK: not created yet`. This can be a first-use state when no workspace has been registered; it is not proof that workspace initialization completed.
- A missing `profiles/web/package.json` is currently reported as `WARN: not created yet`. `ProfilePluginProvisioner.ensureAll()` runs before engine spawn, but its bundle-entry helper does not create a missing profile manifest. Observe the first successful engine initialization before changing this warning's meaning.
- Self-check prints the actual `context.filesDir`-derived path. A path such as `/data/user/0/dsh.dah.mobile/files/dsh-home` does not match the canonical package ID. Treat it as an installed-artifact/package-identity discrepancy to verify, not as a path to hard-code or rewrite.

## Required verification before release

1. Inspect the built APK manifest and assert its package is exactly `dsh.dsh.mobile`.
2. Install that APK and verify `context.packageName` and `context.filesDir` on a device.
3. Test installation without clearing or uninstalling the old package first.
4. Verify sessions and workspaces from both the shared Documents root and package-specific external fallback.
5. Audit Data Export/Import coverage for profiles, credentials, plugin state, sessions, and workspaces. Never silently overwrite or delete the source.
6. Confirm first successful engine boot creates the expected profile manifest, then rerun self-check.
7. Record unavailable repository, signing, build, or device permissions in release notes; do not report unrun build/device tests as passed.

## Current validation status

- Source check: canonical application ID is `dsh.dsh.mobile` on `main`.
- Source check: namespace remains `app.dsh.mobile`.
- Source check: no app Gradle `applicationIdSuffix` or flavor-specific application ID override was found.
- Not yet validated: clean Android build, APK manifest package, target-device installation, and old-package data migration.
