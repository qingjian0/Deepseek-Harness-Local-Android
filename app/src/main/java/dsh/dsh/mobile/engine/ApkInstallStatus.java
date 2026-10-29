package dsh.dsh.mobile.engine;

import java.util.concurrent.ConcurrentHashMap;

/** Terminal PackageInstaller callbacks, scoped to one session and cleared by its owner. */
public final class ApkInstallStatus {
    private ApkInstallStatus() {}

    private static final ConcurrentHashMap<Integer, Result> RESULTS = new ConcurrentHashMap<>();

    public static final class Result {
        public final boolean success;
        public final String message;

        private Result(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    private static final Result PENDING = new Result(false, "");

    public static void begin(int sessionId) {
        if (sessionId < 0) throw new IllegalArgumentException("Invalid APK install session");
        if (RESULTS.putIfAbsent(sessionId, PENDING) != null) {
            throw new IllegalStateException("APK install session is already tracked");
        }
    }

    public static boolean isPending(int sessionId) {
        return RESULTS.get(sessionId) == PENDING;
    }

    public static void complete(int sessionId, boolean success, String message) {
        if (sessionId < 0) return;
        RESULTS.computeIfPresent(sessionId, (key, previous) ->
                previous == PENDING ? new Result(success, message) : previous);
    }

    public static Result get(int sessionId) {
        Result result = RESULTS.get(sessionId);
        return result == PENDING ? null : result;
    }

    public static void clear(int sessionId) {
        RESULTS.remove(sessionId);
    }
}
