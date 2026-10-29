#!/usr/bin/env bash
# build-libtermux.sh —— 重建 16 KB 页对齐的 libtermux.so（终端 JNI 层）。
#
# 背景：真 PTY 终端复用 Termux 拆出的 terminal-view / terminal-emulator
# （com.termux.termux-app，Apache 2.0）。AAR 自带的 jni/arm64-v8a/libtermux.so
# 是 4 KB 页对齐（LOAD p_align=0x1000），在 Android 15+ 的 16 KB 页内核真机上
# dlopen 会报 program alignment 失败。DSHA 的做法是自行重编同版本 JNI 并以
# pickFirsts 覆盖 AAR 旧库；本脚本沿用同一策略，源文件为上游 v0.118.0 的
# terminal-emulator/src/main/jni/termux.c（未做任何 proot 相关改动）。
#
# 用法: ./build-libtermux.sh <NDK路径> <输出jniLibs目录>
# 产物: <输出>/<abi>/libtermux.so（arm64-v8a / armeabi-v7a / x86 / x86_64）
set -euo pipefail

NDK="${1:?用法: $0 <NDK路径> <输出jniLibs目录>}"
OUT="${2:?用法: $0 <NDK路径> <输出jniLibs目录>}"
SRC="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)/termux.c"

CLANG="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang"
SYSROOT="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
# NDK 的 lld 入口在部分宿主（aarch64 + qemu-user）下无法嵌套执行；
# 统一绕过包装器、直接用 qemu 调 lld.real（gnu 风格）。
LLD_REAL="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/lld.real"
if [ -x /usr/bin/qemu-amd64-static ]; then
  LLD="/usr/bin/qemu-amd64-static -L /usr/x86_64-linux-gnu $LLD_REAL -flavor gnu"
else
  LLD="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/ld.lld"
fi

build() {
  local target="$1" libdir="$2" abi="$3"
  local obj="/tmp/termux-$abi.o"
  "$CLANG" --target="$target" --sysroot="$SYSROOT" -fPIC -O2 -c "$SRC" -o "$obj"
  mkdir -p "$OUT/$abi"
  # shellcheck disable=SC2086
  $LLD -shared -soname libtermux.so -z max-page-size=16384 \
    -o "$OUT/$abi/libtermux.so" "$obj" -L"$SYSROOT/usr/lib/$libdir/26" -lc
}

build aarch64-linux-android26   aarch64-linux-android   arm64-v8a
build armv7a-linux-androideabi26 arm-linux-androideabi   armeabi-v7a
build i686-linux-android26       i686-linux-android      x86
build x86_64-linux-android26     x86_64-linux-android    x86_64
echo "libtermux.so -> $OUT"
