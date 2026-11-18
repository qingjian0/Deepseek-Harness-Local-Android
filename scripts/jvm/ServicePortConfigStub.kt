package app.dsh.mobile.engine

/**
 * JVM-only compile stub for FastPicker's port reference.
 *
 * The real ServicePortConfig depends on Android Context and EnginePortManager.
 * Its behavior is covered by the Android/JUnit ServicePortConfigTest; this stub
 * keeps the pure-JVM FastPicker/MemoryIndex behavior suite independent from
 * Android runtime wiring. It is copied only by run-a11y-jvm-test.sh and is
 * never included in the APK.
 */
internal object ServicePortConfig {
    const val embeddingPort: Int = 3085
}
