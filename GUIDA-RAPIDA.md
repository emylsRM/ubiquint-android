# Guida rapida (3 clic)

1. **Actions → Build APK privato → Run workflow** (versionCode `1` la prima volta).
2. A fine build scarica gli Artifacts:
   - `ubiquint-apk` → l'APK da installare sul telefono.
   - `file-per-il-sito` → `.well-known/assetlinks.json` e `icons/` da mettere nel sito su Pages.
   - `CHIAVE-PRIVATA-da-conservare` (solo la prima volta, resta 3 giorni) → keystore e password.
3. **Subito**: da `credenziali.txt` e `KEYSTORE_BASE64.txt` crea in Settings → Secrets → Actions i segreti
   `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_PASSWORD`. Così le build successive usano la stessa chiave
   e l'app si aggiorna senza disinstallarla. Conserva una copia della chiave offline.

Se i Secrets mancano, ogni build genera una chiave NUOVA (APK non aggiornabile sopra il precedente).
