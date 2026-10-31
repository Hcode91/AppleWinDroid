#!/bin/sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
NATIVE_DIR=${1:-"$PROJECT_DIR/native-libs/arm64-v8a"}
SKILL_BUILD=/root/.buddy/work/.claude/skills/android-app/build-apk.sh
KEY_DIR=/root/.buddy/keys
OUT_DIR="$PROJECT_DIR/build"
BASE_APK="$OUT_DIR/app.apk"
PACKED_APK="$OUT_DIR/app-with-native-unsigned.apk"

for library in libapplewindroid_jni.so libapplewin_libretro.so; do
  if [ ! -f "$NATIVE_DIR/$library" ]; then
    echo "Missing $NATIVE_DIR/$library" >&2
    echo "Build the Android ARM64 workflow and place both libraries under native-libs/arm64-v8a." >&2
    exit 1
  fi
done

bash "$SKILL_BUILD" "$PROJECT_DIR"
python3 - "$BASE_APK" "$PACKED_APK" "$NATIVE_DIR" <<'PY'
import sys
import zipfile
from pathlib import Path

base, packed, native_dir = map(Path, sys.argv[1:])
signature_suffixes = (".RSA", ".DSA", ".EC", ".SF")
with zipfile.ZipFile(base) as source, zipfile.ZipFile(packed, "w") as output:
    for entry in source.infolist():
        upper = entry.filename.upper()
        if upper == "META-INF/MANIFEST.MF" or (upper.startswith("META-INF/") and upper.endswith(signature_suffixes)):
            continue
        output.writestr(entry, source.read(entry.filename))
    for name in ("libapplewindroid_jni.so", "libapplewin_libretro.so"):
        info = zipfile.ZipInfo("lib/arm64-v8a/" + name)
        info.compress_type = zipfile.ZIP_DEFLATED
        output.writestr(info, (native_dir / name).read_bytes())
PY

apksigner sign \
  --ks "$KEY_DIR/apps.jks" \
  --ks-key-alias apps \
  --ks-pass "file:$KEY_DIR/password" \
  --key-pass "file:$KEY_DIR/password" \
  --out "$BASE_APK" "$PACKED_APK"
apksigner verify "$BASE_APK"
rm -f "$PACKED_APK"
echo "Built native-enabled APK: $BASE_APK"
