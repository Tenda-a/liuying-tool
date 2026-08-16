#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
APP="$ROOT/app"
BUILD="$ROOT/build"
COMPILED="$BUILD/compiled"
GEN="$BUILD/gen"
CLASSES="$BUILD/classes"
DEX="$BUILD/dex"
OUT="$BUILD/outputs"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$SDK" || ! -d "$SDK" ]]; then
  for candidate in "$HOME/android-sdk" "/data/data/com.termux/files/home/android-sdk" "/d/Android/sdk"; do
    if [[ -d "$candidate" ]]; then SDK="$candidate"; break; fi
  done
fi
if [[ -z "$SDK" || ! -d "$SDK" ]]; then
  echo '找不到 Android SDK,请设置 ANDROID_SDK_ROOT 或 ANDROID_HOME。' >&2
  exit 1
fi

latest_dir() { find "$1" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -1; }
PLATFORM="$(latest_dir "$SDK/platforms")"
ANDROID_JAR="$PLATFORM/android.jar"

resolve_tool() {
  local name="$1"
  local path
  path="$(command -v "$name" 2>/dev/null || true)"
  if [[ -n "$path" ]]; then
    printf '%s\n' "$path"
    return
  fi
  local tools
  tools="$(latest_dir "$SDK/build-tools")"
  if [[ -x "$tools/$name" ]]; then
    printf '%s\n' "$tools/$name"
  fi
  return 0
}

AAPT2="$(resolve_tool aapt2)"
DX="$(resolve_tool dx)"
D8="$(resolve_tool d8)"
ZIPALIGN="$(resolve_tool zipalign)"
APKSIGNER="$(resolve_tool apksigner)"

for entry in "android.jar:$ANDROID_JAR" "aapt2:$AAPT2" "zipalign:$ZIPALIGN" "apksigner:$APKSIGNER"; do
  name="${entry%%:*}"
  path="${entry#*:}"
  if [[ ! -f "$path" && ! -x "$path" ]]; then
    echo "缺少构建文件或工具:$name ($path)" >&2
    exit 1
  fi
done

rm -rf "$BUILD"
mkdir -p "$COMPILED" "$GEN" "$CLASSES" "$DEX" "$OUT"

while IFS= read -r -d '' file; do
  "$AAPT2" compile -o "$COMPILED" "$file"
done < <(find "$APP/res" -type f -print0)
mapfile -t FLATS < <(find "$COMPILED" -type f -name '*.flat' -print)

UNSIGNED_APK="$OUT/app-unsigned.apk"
ALIGNED_APK="$OUT/app-aligned.apk"
FINAL_APK="$OUT/liuying-video.apk"

"$AAPT2" link \
  -o "$UNSIGNED_APK" \
  -I "$ANDROID_JAR" \
  --manifest "$APP/AndroidManifest.xml" \
  --java "$GEN" \
  --min-sdk-version 23 \
  --target-sdk-version 30 \
  --version-code 1 \
  --version-name 1.0.0 \
  "${FLATS[@]}"

mapfile -t JAVA_FILES < <(find "$APP/src" "$GEN" -type f -name '*.java' -print)
javac -Xlint:-options -encoding UTF-8 -source 8 -target 8 \
  -cp "$ANDROID_JAR" \
  -d "$CLASSES" \
  "${JAVA_FILES[@]}"

if [[ -n "$D8" ]]; then
  CLASSES_JAR="$BUILD/classes.jar"
  jar cf "$CLASSES_JAR" -C "$CLASSES" .
  "$D8" --min-api 23 --lib "$ANDROID_JAR" --output "$DEX" "$CLASSES_JAR"
elif [[ -n "$DX" ]]; then
  "$DX" --dex --min-sdk-version=23 --output="$DEX/classes.dex" "$CLASSES"
else
  echo '缺少 D8 或 DX。' >&2
  exit 1
fi

jar uf "$UNSIGNED_APK" -C "$DEX" classes.dex
"$ZIPALIGN" -f 4 "$UNSIGNED_APK" "$ALIGNED_APK"

KEYSTORE="$BUILD/debug.keystore"
keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
  -alias androiddebugkey -dname 'CN=Android Debug,O=Liuying,C=CN' \
  -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --out "$FINAL_APK" "$ALIGNED_APK"
"$APKSIGNER" verify --verbose "$FINAL_APK"

echo "APK: $FINAL_APK"
echo "Android SDK: $SDK"
echo "Android Platform: $PLATFORM"
