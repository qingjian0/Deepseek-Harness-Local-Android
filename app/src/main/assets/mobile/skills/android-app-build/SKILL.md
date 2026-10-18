---
name: android-app-build
description: 在设备上编译、dex、签名 Android APK 的完整命令序列（javac／d8／aapt／zipalign／apksigner）。
whenToUse: 需要构建、重打包或签名 APK 时
---

`android-buildtools` provides `aapt`, `apksigner`, `gradle`, `d8`; `javac` comes from an OpenJDK extension. **$HOME/android.jar is already staged for you** — point `-cp` at it directly; there is no download or extraction step.

```sh
javac -cp $HOME/android.jar -d out src/com/example/App.java
java -cp $PREFIX/extensions/android-buildtools/share/java/d8.jar com.android.tools.r8.D8 --output out-dex --lib $HOME/android.jar out/com/example/*.class
aapt package -f -M AndroidManifest.xml -I /system/framework/framework-res.apk -F app-unsigned.apk
zipalign -f 4 app-unsigned.apk app-aligned.apk && apksigner sign --ks my.keystore --out app-signed.apk app-aligned.apk
```

- Pass `-I /system/framework/framework-res.apk` to aapt: it carries the framework resource table that `android:xxx` attributes resolve against. The staged jar is a **javac classpath only**.
- `d8` is a Java program. If `java` is not on PATH, discover the JDK — `JAVA=$(ls -d $PREFIX/extensions/*/lib/jvm/*/bin/java 2>/dev/null | head -1)` — rather than hard-coding a JDK version.
- Gradle also works, but still needs that classpath jar and downloads its own dependencies.
