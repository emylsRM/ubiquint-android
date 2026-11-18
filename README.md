# UBIQUINT Android (APK privato)

App Android (Trusted Web Activity) che apre `https://ubiquint.pages.dev`. Gli aggiornamenti di Ubiquint arrivano dal deploy su Pages: l'APK va ricompilato solo se cambi nome, icona o package.

## 1. Prepara il sito (una volta)
Copia nella radice del progetto Pages:
- `pwa/manifest.webmanifest` → `/manifest.webmanifest`
- `pwa/sw.js` → `/sw.js`
- `pwa/.well-known/assetlinks.json` → `/.well-known/assetlinks.json` (dopo il punto 2)
- icone PNG in `/icons/`: `icon-192.png`, `icon-512.png`, `maskable-512.png`
- incolla in `index.html` quanto indicato in `pwa/head-snippet.html`

Deploy e verifica che `https://ubiquint.pages.dev/manifest.webmanifest` si apra.

## 2. Crea la chiave di firma (una volta, sul PC con Java)
```
keytool -genkeypair -v -keystore android.keystore -alias ubiquint -keyalg RSA -keysize 2048 -validity 10000
keytool -list -v -keystore android.keystore -alias ubiquint   # copia la riga SHA256
```
Incolla lo SHA256 in `assetlinks.json` al posto di `INSERISCI_QUI_SHA256_DEL_KEYSTORE`.
**Conserva keystore e password in un posto sicuro: senza non puoi aggiornare l'app. Non caricare mai il keystore nel repo.**

Converti in base64:
- Linux/Mac: `base64 -w0 android.keystore > ks.txt`
- Windows PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("android.keystore")) > ks.txt`

## 3. Secrets del repository
Settings → Secrets and variables → Actions → New repository secret:
- `KEYSTORE_BASE64` = contenuto di ks.txt
- `KEYSTORE_PASSWORD` = password del keystore
- `KEY_PASSWORD` = password della chiave (di solito uguale)

## 4. Genera l'APK
Actions → **Build APK privato** → Run workflow (aumenta `versionCode` a ogni nuova build). A fine build scarica l'artifact `ubiquint-apk-...` e installa `app-release-signed.apk` sul telefono (consenti installazione da origini sconosciute).

## Note
- Se la barra di Chrome resta visibile in alto, `assetlinks.json` non è raggiungibile o lo SHA256 non corrisponde.
- `ALLOWED_ORIGIN` del Worker non va modificato.
- Opzionale: proteggi `ubiquint.pages.dev` con Cloudflare Access.
