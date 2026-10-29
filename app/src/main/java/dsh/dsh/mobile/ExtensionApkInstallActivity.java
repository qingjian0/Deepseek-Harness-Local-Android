package dsh.dsh.mobile;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Bundle;

import dsh.dsh.mobile.engine.ApkInstallStatus;

/**
 * Explicit, non-exported destination for a PackageInstaller IntentSender.
 * Pending user action is an intent to launch, not a successful installation.
 */
public final class ExtensionApkInstallActivity extends Activity {
    public static final String EXTRA_OWNED_SESSION = "dsh.dsh.mobile.extra.APK_INSTALL_SESSION";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        handleResult(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleResult(intent);
    }

    private void handleResult(Intent intent) {
        try {
            if (intent == null || !intent.hasExtra(EXTRA_OWNED_SESSION)
                    || !intent.hasExtra(PackageInstaller.EXTRA_STATUS)) return;
            int sessionId = intent.getIntExtra(EXTRA_OWNED_SESSION, -1);
            if (sessionId < 0) return;
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                // Ignore stale confirmation callbacks after timeout/cancel/terminal result.
                if (!ApkInstallStatus.isPending(sessionId)) return;
                // The framework attaches the system confirmation activity to this callback.
                @SuppressWarnings("deprecation")
                Intent confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirmation == null) {
                    ApkInstallStatus.complete(sessionId, false,
                            "APK installer did not provide a confirmation intent");
                    return;
                }
                try {
                    startActivity(confirmation);
                } catch (RuntimeException error) {
                    ApkInstallStatus.complete(sessionId, false,
                            "Cannot open APK installation confirmation: " + error.getMessage());
                }
                return;
            }
            String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            ApkInstallStatus.complete(sessionId, status == PackageInstaller.STATUS_SUCCESS,
                    message == null || message.isEmpty()
                            ? "APK installer returned status " + status : message);
        } finally {
            // Never replace the user's WebView with an empty callback screen.
            finish();
        }
    }
}
