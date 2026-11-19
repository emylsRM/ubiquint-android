#!/usr/bin/env bash
# Crea l'APK di UBIQUINT senza Gradle né Android Studio, con gli strumenti Android dei pacchetti Ubuntu:
#   sudo apt-get install -y android-sdk-platform-23 aapt dalvik-exchange zipalign apksigner   (+ un JDK: javac, keytool)
# Uso:  ./build.sh                    -> build/UBIQUINT-<versione>.apk
# Firma: UBQ_KEYSTORE, UBQ_KEYSTORE_PASS, UBQ_KEY_ALIAS e, se diversa, UBQ_KEY_PASS (chiave tua).
# Senza, usa una chiave di prova in .keystore/.
set -euo pipefail
cd "$(dirname "$0")"
ROOT=$PWD

prop() { grep -E "^$1=" app.properties | tail -1 | cut -d= -f2- | tr -d '\r'; }
APP_URL=${APP_URL:-$(prop APP_URL)}
LOGIN_PATH=${LOGIN_PATH:-$(prop LOGIN_PATH)}
VERSION_NAME=${VERSION_NAME:-$(prop VERSION_NAME)}
VERSION_CODE=${VERSION_CODE:-$(prop VERSION_CODE)}
ANDROID_JAR=${ANDROID_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}
DX=${DX:-$(command -v dalvik-exchange || command -v dx || true)}
OUT=$ROOT/build
APK=${OUTAPK:-$OUT/UBIQUINT-$VERSION_NAME.apk}

fail() { echo "ERRORE: $*" >&2; exit 1; }
for t in aapt javac zipalign apksigner keytool; do
  command -v "$t" >/dev/null || fail "manca $t. Installa: sudo apt-get install -y android-sdk-platform-23 aapt dalvik-exchange zipalign apksigner default-jdk-headless"
done
[ -n "$DX" ] || fail "manca dalvik-exchange (dx)"
[ -f "$ANDROID_JAR" ] || fail "manca $ANDROID_JAR (pacchetto android-sdk-platform-23)"
[[ "$APP_URL" =~ ^https://[A-Za-z0-9.-]+(:[0-9]+)?/([A-Za-z0-9._~/-]*/)?$ ]] || fail "APP_URL non valido ($APP_URL): https://dominio/ con la barra finale"
[[ "$LOGIN_PATH" =~ ^[A-Za-z0-9._-]+\.html$ ]] || fail "LOGIN_PATH non valido ($LOGIN_PATH)"
[[ "$VERSION_CODE" =~ ^[0-9]{1,9}$ ]] || fail "VERSION_CODE deve essere un numero"
[[ "$VERSION_NAME" =~ ^[A-Za-z0-9._-]+$ ]] || fail "VERSION_NAME non valido"

echo "UBIQUINT per Android $VERSION_NAME ($VERSION_CODE) -> $APP_URL"
rm -rf "$OUT"
mkdir -p "$OUT/gen/it/ubiquint/app" "$OUT/classes"

echo "[1/6] Config.java da app.properties"
cat > "$OUT/gen/it/ubiquint/app/Config.java" <<EOF
package it.ubiquint.app;

/** Generato da build.sh a partire da app.properties: non modificarlo a mano. */
final class Config {
    static final String APP_URL = "$APP_URL";
    static final String LOGIN_URL = "$APP_URL$LOGIN_PATH";
    static final String VERSION = "$VERSION_NAME";
    static final String SCHEME = "ubiquint";

    private Config() {}
}
EOF

echo "[2/6] risorse (aapt)"
aapt package -f -m -J "$OUT/gen" -M app/AndroidManifest.xml -S app/res -A app/assets -I "$ANDROID_JAR" \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" -0 arsc -F "$OUT/res.apk"

echo "[3/6] compilazione Java"
javac -nowarn -Xlint:-options -encoding UTF-8 -source 8 -target 8 -bootclasspath "$ANDROID_JAR" \
  -d "$OUT/classes" $(find app/src "$OUT/gen" -name '*.java' | sort)

echo "[4/6] bytecode Android (dx)"
"$DX" --dex --min-sdk-version=24 --output="$OUT/classes.dex" "$OUT/classes"

echo "[5/6] pacchetto e allineamento"
cp "$OUT/res.apk" "$OUT/unsigned.apk"
(cd "$OUT" && aapt add -f unsigned.apk classes.dex >/dev/null)
zipalign -p -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[6/6] firma"
if [ -n "${UBQ_KEYSTORE:-}" ]; then
  [ -f "$UBQ_KEYSTORE" ] || fail "UBQ_KEYSTORE non trovato: $UBQ_KEYSTORE"
  KS=$UBQ_KEYSTORE; ALIAS=${UBQ_KEY_ALIAS:-ubiquint}
  [ -n "${UBQ_KEYSTORE_PASS:-}" ] || fail "serve UBQ_KEYSTORE_PASS (password della chiave)"
  export UBQ_KS_PASS=$UBQ_KEYSTORE_PASS UBQ_K_PASS=${UBQ_KEY_PASS:-$UBQ_KEYSTORE_PASS}
else
  KS=$ROOT/.keystore/debug.keystore; ALIAS=debug; export UBQ_KS_PASS=android-debug UBQ_K_PASS=android-debug
  if [ ! -f "$KS" ]; then
    mkdir -p "$ROOT/.keystore"
    keytool -genkeypair -keystore "$KS" -storetype PKCS12 -storepass "$UBQ_KS_PASS" -alias "$ALIAS" \
      -keyalg RSA -keysize 3072 -validity 10950 -dname "CN=UBIQUINT debug" >/dev/null 2>&1
  fi
  echo "      ATTENZIONE: firma di prova (.keystore/debug.keystore). Per le versioni da distribuire usa la tua chiave."
fi
apksigner sign --ks "$KS" --ks-key-alias "$ALIAS" --ks-pass env:UBQ_KS_PASS --key-pass env:UBQ_K_PASS \
  --min-sdk-version 24 --out "$APK" "$OUT/aligned.apk"
apksigner verify --min-sdk-version 24 "$APK"
rm -f "$OUT/res.apk" "$OUT/unsigned.apk" "$OUT/aligned.apk" "$APK.idsig"
echo "FATTO: $APK ($(du -k "$APK" | cut -f1) KB)"
