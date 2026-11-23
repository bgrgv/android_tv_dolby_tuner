#!/bin/sh
# Builds build/DolbyTuner.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
#
# Needs an Android SDK with build-tools and one platform, plus a JDK 11+.
#   ANDROID_HOME   SDK root (default: $ANDROID_SDK_ROOT, ~/Library/Android/sdk, Homebrew cmdline-tools)
#   BUILD_TOOLS    build-tools version (default: newest installed)
#   PLATFORM       platform dir name, e.g. android-34 (default: newest installed)
#   JAVA_HOME      JDK (default: Android Studio's bundled JBR on macOS, else whatever is on PATH)
set -e
cd "$(dirname "$0")"

pick_sdk() {
  for d in "$ANDROID_HOME" "$ANDROID_SDK_ROOT" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" \
           /opt/homebrew/share/android-commandlinetools /usr/local/share/android-commandlinetools; do
    [ -n "$d" ] && [ -d "$d/build-tools" ] && { echo "$d"; return; }
  done
  echo "No Android SDK found. Set ANDROID_HOME." >&2; exit 1
}
SDK=$(pick_sdk)
BT=$SDK/build-tools/${BUILD_TOOLS:-$(ls "$SDK/build-tools" | sort -V | tail -1)}
AJ=$SDK/platforms/${PLATFORM:-$(ls "$SDK/platforms" | sort -V | tail -1)}/android.jar

if [ -z "$JAVA_HOME" ] && [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
  JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi
[ -n "$JAVA_HOME" ] && export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"

echo "SDK: $SDK"; echo "build-tools: $BT"; echo "android.jar: $AJ"

rm -rf build && mkdir -p build/classes build/dex
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/unsigned.apk -I "$AJ" --manifest AndroidManifest.xml build/res.zip
javac -nowarn --release 11 -classpath "$AJ" -d build/classes $(find src -name '*.java')
"$BT/d8" --min-api 30 --lib "$AJ" --output build/dex $(find build/classes -name '*.class')
(cd build/dex && zip -q -j ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
  -alias tuner -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Dolby Tuner" >/dev/null 2>&1
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --out build/DolbyTuner.apk build/aligned.apk
echo "built build/DolbyTuner.apk"
