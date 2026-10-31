#!/bin/bash
set -euo pipefail
PROJECT_DIR=$(cd "${1:-$(dirname "$0")}" && pwd)
SDK_ROOT=${ANDROID_HOME:-$HOME/android-sdk}
if [ -z "${SDK_JAR:-}" ]; then
  if [ -f "$SDK_ROOT/platforms/android-36/android.jar" ]; then
    SDK_JAR="$SDK_ROOT/platforms/android-36/android.jar"
  else
    SDK_JAR="$SDK_ROOT/android-36/android.jar"
  fi
fi
KEY_DIR=${APP_KEY_DIR:-/root/.buddy/keys}
OUT_DIR="$PROJECT_DIR/build"

prop() { [ -f "$PROJECT_DIR/app.properties" ] && sed -n "s/^$1=//p" "$PROJECT_DIR/app.properties" | head -1 || true; }
VERSION_CODE=$(prop versionCode); VERSION_CODE=${VERSION_CODE:-1}
VERSION_NAME=$(prop versionName); VERSION_NAME=${VERSION_NAME:-1.0}
MIN_SDK=$(prop minSdk); MIN_SDK=${MIN_SDK:-26}

[ -f "$SDK_JAR" ] || { echo "android.jar missing at $SDK_JAR" >&2; exit 1; }
for tool in javac d8 aapt2 apksigner keytool python3; do
  command -v "$tool" >/dev/null || { echo "$tool missing from PATH" >&2; exit 1; }
done
[ -f "$PROJECT_DIR/AndroidManifest.xml" ] || { echo "No AndroidManifest.xml in $PROJECT_DIR" >&2; exit 1; }

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR/gen" "$OUT_DIR/classes" "$OUT_DIR/dex"

RESOURCE_ARGS=()
if [ -d "$PROJECT_DIR/res" ] && [ -n "$(ls -A "$PROJECT_DIR/res")" ]; then
  aapt2 compile --dir "$PROJECT_DIR/res" -o "$OUT_DIR/res.zip"
  RESOURCE_ARGS=("$OUT_DIR/res.zip")
fi
ASSET_ARGS=()
[ ! -d "$PROJECT_DIR/assets" ] || ASSET_ARGS=(-A "$PROJECT_DIR/assets")
aapt2 link -I "$SDK_JAR" --manifest "$PROJECT_DIR/AndroidManifest.xml" \
  --min-sdk-version "$MIN_SDK" --target-sdk-version 36 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  "${ASSET_ARGS[@]}" --java "$OUT_DIR/gen" -o "$OUT_DIR/base.apk" "${RESOURCE_ARGS[@]}"

javac --release 8 -classpath "$SDK_JAR" -Xlint:-options -encoding UTF-8 -nowarn \
  -d "$OUT_DIR/classes" $(find "$PROJECT_DIR/java" "$OUT_DIR/gen" -name '*.java')
d8 --release --min-api "$MIN_SDK" --lib "$SDK_JAR" --output "$OUT_DIR/dex" \
  $(find "$OUT_DIR/classes" -name '*.class')

python3 - "$OUT_DIR/base.apk" "$OUT_DIR/dex/classes.dex" "$OUT_DIR/aligned.apk" <<'PY'
import sys
import zipfile

src, dex, dst = sys.argv[1:]
with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w") as zout:
    entries = [(item, zin.read(item.filename)) for item in zin.infolist()]
    dex_info = zipfile.ZipInfo("classes.dex")
    dex_info.compress_type = zipfile.ZIP_DEFLATED
    entries.append((dex_info, open(dex, "rb").read()))
    for info, data in entries:
        out = zipfile.ZipInfo(info.filename, date_time=info.date_time)
        out.compress_type = info.compress_type
        out.external_attr = info.external_attr
        if out.compress_type == zipfile.ZIP_STORED:
            start = zout.fp.tell() + 30 + len(out.filename.encode())
            out.extra = b"\0" * ((-start) % 4)
        zout.writestr(out, data)
PY

if [ ! -f "$KEY_DIR/apps.jks" ]; then
  mkdir -p "$KEY_DIR"
  chmod 700 "$KEY_DIR"
  head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24 > "$KEY_DIR/password"
  chmod 600 "$KEY_DIR/password"
  keytool -genkeypair -keystore "$KEY_DIR/apps.jks" -alias apps -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$(cat "$KEY_DIR/password")" \
    -keypass "$(cat "$KEY_DIR/password")" -dname "CN=AppleWinDroid app" >/dev/null 2>&1
fi
apksigner sign --ks "$KEY_DIR/apps.jks" --ks-key-alias apps \
  --ks-pass "file:$KEY_DIR/password" \
  --out "$OUT_DIR/app.apk" "$OUT_DIR/aligned.apk"
apksigner verify "$OUT_DIR/app.apk"
echo "Built $OUT_DIR/app.apk"
