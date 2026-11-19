# Fork customization contract

## Application identity

- Android application ID: `dsh.dsh.mobile`.
- Kotlin/Android namespace: `app.dsh.mobile` (intentionally retained from upstream).
- The namespace controls generated `R`/source package names; the application ID controls installation identity, private app-data paths, and package-manager lookups.
- Keeping these values distinct avoids a mass Kotlin package migration while allowing the fork to be installed alongside the official `app.dsh.mobile` build.
- Components whose class names are declared in the manifest remain in the `app.dsh.mobile` namespace. Runtime component identifiers must combine `context.packageName` with the class name; do not assume the namespace is the installed package ID.

## Runtime path rules

- Kotlin code must derive private paths from `Context.filesDir` or `Context.packageName`; never hard-code `/data/user/0/app.dsh.mobile`.
- Engine child processes should receive `DSH_HOME` from the canonical directory resolver. Shell tools may use a package-specific fallback only as a last resort.
- APK lookup for `app_process` must use the installed application ID, while the Java entry class remains in the source namespace.
- Process ownership checks must use the installed package ID and/or canonical engine paths; they must never terminate unrelated applications.

## Upstream compatibility

- Keep upstream source namespace and engine internals unchanged unless a demonstrated fork requirement needs an adapter.
- Fork-specific identity and configuration belong in a small, auditable customization layer.
- Upstream synchronization must preserve these contracts and re-run package identity, runtime-path, build, and install-side-by-side checks.
