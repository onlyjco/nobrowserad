#!/usr/bin/env bash
# 构建脚本（无 Gradle，直接调用 Android SDK 工具链）
# 依赖：JDK 17、Android SDK build-tools 34、platforms/android-34
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-$HOME/android-sdk}"
BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"

# aapt2 / zipalign 若为 x86_64 二进制，在 arm64 上需通过 qemu 运行
QEMU=""
if [ "$(uname -m)" = "aarch64" ] && ! "$BT/aapt2" version >/dev/null 2>&1; then
  QEMU="qemu-x86_64-static -L /usr/x86_64-linux-gnu"
fi

OUT="$ROOT/build"
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/apk"

echo "[1/6] 编译 Java"
javac -source 17 -target 17 -encoding UTF-8 \
  -classpath "$PLATFORM:$ROOT/libs/api82.jar" \
  -d "$OUT/classes" \
  $(find "$ROOT/app/src/main/java" -name '*.java')

echo "[2/6] 转换为 dex"
"$BT/d8" --release --min-api 26 --lib "$PLATFORM" \
  --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

echo "[3/6] 编译资源"
$QEMU "$BT/aapt2" compile --dir "$ROOT/app/src/main/res" -o "$OUT/res.zip"

echo "[4/6] 链接 APK"
$QEMU "$BT/aapt2" link -o "$OUT/apk/base.apk" \
  -I "$PLATFORM" --manifest "$ROOT/app/src/main/AndroidManifest.xml" \
  "$OUT/res.zip" --min-sdk-version 26 --target-sdk-version 34

echo "[5/6] 打包内容"
python3 - "$OUT" "$ROOT" <<'PY'
import os, sys, zipfile
out, root = sys.argv[1], sys.argv[2]
src = os.path.join(out, 'apk', 'base.apk')
dst = os.path.join(out, 'apk', 'staged.apk')
zin = zipfile.ZipFile(src)
zout = zipfile.ZipFile(dst, 'w', zipfile.ZIP_DEFLATED)
for it in zin.infolist():
    zout.writestr(it, zin.read(it.filename))
zin.close()
zout.write(os.path.join(out, 'dex', 'classes.dex'), 'classes.dex')
zout.write(os.path.join(root, 'app/src/main/assets/xposed_init'), 'assets/xposed_init')
zout.write(os.path.join(root, 'app/src/main/resources/META-INF/xposed/scope.list'),
           'META-INF/xposed/scope.list')
zout.close()
print('  staged ->', dst)
PY

echo "[6/6] 对齐并签名"
$QEMU "$BT/zipalign" -f 4 "$OUT/apk/staged.apk" "$OUT/apk/aligned.apk"

# 签名密钥：优先使用仓库内固定密钥（保证各次构建签名一致，可覆盖安装）
# 也可通过环境变量指定：KEYSTORE / KS_PASS / KEY_ALIAS / KEY_PASS
KEYSTORE="${KEYSTORE:-$ROOT/app/signing.keystore}"
KS_PASS="${KS_PASS:-android}"
KEY_ALIAS="${KEY_ALIAS:-mibrowserclean}"
KEY_PASS="${KEY_PASS:-android}"

if [ ! -f "$KEYSTORE" ]; then
  echo "  密钥不存在，生成临时签名"
  KEYSTORE="$ROOT/build.keystore"
  keytool -genkeypair -keystore "$KEYSTORE" \
    -storepass "$KS_PASS" -keypass "$KEY_PASS" -alias "$KEY_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=MiBrowserClean"
fi

"$BT/apksigner" sign --ks "$KEYSTORE" \
  --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" \
  --out "$OUT/MiBrowserClean.apk" "$OUT/apk/aligned.apk"

echo
echo "构建完成: $OUT/MiBrowserClean.apk"
