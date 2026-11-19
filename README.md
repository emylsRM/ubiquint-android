# UBIQUINT per Android

App Android di UBIQUINT: apre la web app pubblicata (`https://ubiquint.pages.dev/`) in una WebView protetta e aggiunge ciò che una WebView non fa da sola. La grafica è quella della web app, senza modifiche: ogni aggiornamento pubblicato su Cloudflare Pages arriva subito anche nell'app.

| Funzione | Come la gestisce l'app |
|---|---|
| Accedi con Google | Google blocca il login nelle WebView. L'app apre `android-login.html` nel browser, l'utente accede e le credenziali Google tornano **solo** a questa app (`intent://` con package esplicito e stato monouso di 10 minuti). Nell'app l'accesso si chiude con `signInWithCredential`, e il controllo della whitelist dei domini resta quello della web app. |
| Esportazioni JSON, Markdown, HTML, CSV e PDF (jsPDF) | Salvate in `Download/UBIQUINT` (Android 10 e successivi) oppure dove sceglie l'utente (Android 7-9), con «Apri» e «Condividi». |
| «PDF Rapido» (`window.print`) | Stampa di Android, con «Salva come PDF». |
| Link a profili e siti esterni, `window.open` | Si aprono nel browser (scheda personalizzata): la ricerca in corso nell'app non si perde. |
| Caricamento di file (configurazione, foto del soggetto, immagini) | Selettore file di Android. |
| `alert`, `confirm`, `prompt` | Finestre native con il titolo UBIQUINT. |
| Rete assente all'avvio | Schermata «Connessione non disponibile» con «Riprova». |
| Indietro | Torna alla pagina precedente; sulla prima pagina l'app va in background senza chiudersi. |

Requisiti: Android 7.0 (API 24) o successivo, con Android System WebView o Chrome aggiornati. È necessaria la connessione a internet.

## 1. Pubblica la pagina di accesso su Cloudflare Pages (una volta sola)

Senza questa pagina il pulsante «Accedi con Google» dell'app non può funzionare.

1. Copia `web/android-login.html` nella radice del sito, accanto a `index.html`.
2. Aggiungi al tuo `_headers` le ultime righe di `web/_headers` (sezione `/android-login*`). Le altre righe di quel file sono identiche al tuo `_headers` attuale.
3. Pubblica il sito. La pagina deve rispondere all'indirizzo `https://ubiquint.pages.dev/android-login.html`.

In Firebase non c'è nulla da configurare: la pagina sta sullo stesso dominio della web app, che è già autorizzato. Non serve nemmeno lo SHA-1 dell'app, perché l'app non usa il Google Sign-In nativo.

## 2. Installa l'APK

1. Copia `UBIQUINT-<versione>.apk` sul telefono, oppure scaricala dalla release o dagli Artifacts di GitHub Actions.
2. Aprila e consenti l'installazione da questa origine quando Android lo chiede.
3. Gli aggiornamenti si installano sopra la versione precedente solo se sono firmati con la **stessa chiave** e hanno un `VERSION_CODE` più alto.

## 3. Compilare l'APK

L'APK si crea senza Gradle né Android Studio, con gli strumenti Android dei pacchetti Ubuntu. È lo stesso procedimento in locale e su GitHub.

### Su GitHub (consigliato)

1. Il workflow «APK Android» parte da solo a ogni push su `main` e a ogni pull request, e si può avviare a mano da **Actions › APK Android › Run workflow**.
2. Aggiungi i secrets in **Settings › Secrets and variables › Actions**:

   | Secret | Valore |
   |---|---|
   | `UBQ_KEYSTORE_B64` | Il file della chiave codificato in base64 (`base64 -w0 ubiquint-release.keystore`). |
   | `UBQ_KEYSTORE_PASS` | La password della chiave. |
   | `UBQ_KEY_ALIAS` | `ubiquint` |

   Valgono anche i nomi della prima configurazione del repository: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_PASSWORD` e `KEY_ALIAS`.

3. Ogni push su `main` crea l'APK firmata, che trovi negli **Artifacts** del workflow «APK Android».
4. Un tag `v1.0.1` crea la release con l'APK allegata. Prima aumenta `VERSION_CODE` in `app.properties`.

Senza i secrets il workflow firma con una chiave di prova e lo segnala con un avviso. Un'APK firmata così non si installa sopra quella firmata con la tua chiave.

### In locale (Ubuntu, Debian o WSL)

```bash
sudo apt-get install -y android-sdk-platform-23 aapt dalvik-exchange zipalign apksigner default-jdk-headless
./build.sh                       # build/UBIQUINT-<versione>.apk (chiave di prova in .keystore/)
UBQ_KEYSTORE=/percorso/ubiquint-release.keystore UBQ_KEYSTORE_PASS='…' UBQ_KEY_ALIAS=ubiquint ./build.sh   # con la tua chiave
```

Le impostazioni sono in `app.properties`:

- `APP_URL`: l'indirizzo della web app. Se aggiungi un dominio personalizzato, metti quello finale, senza reindirizzamenti.
- `VERSION_NAME` e `VERSION_CODE`: la versione dell'APK.

Il package dell'app è `it.ubiquint.app`. Se lo cambi, cambia anche `ANDROID_PACKAGE` in `web/android-login.html`.

> **La chiave di firma va custodita**: chi la possiede può pubblicare aggiornamenti che il telefono accetta come tuoi, e se la perdi non potrai più aggiornare l'app installata. Non metterla nel repository: `.gitignore` la esclude già.

### Perché una WebView e non la TWA della prima versione

La prima versione di questo repository (commit `5019908`) preparava una Trusted Web Activity con Bubblewrap. Quella strada richiedeva di pubblicare sul sito `manifest.webmanifest`, un nuovo `sw.js` al posto di quello della web app, `assetlinks.json` con l'impronta della chiave e le icone, e di modificare `index.html`. L'app inoltre dipendeva da Chrome installato sul telefono. Il workflow non era mai stato eseguito.

L'app attuale chiede al sito una sola pagina in più (`android-login.html`). Si compila e si prova anche senza Gradle, e tiene i dati della web app nel proprio spazio privato, separato dal browser. Chi preferisce la TWA la ritrova nella storia del repository.

## 4. Sicurezza

- **Navigazione**: nella WebView si apre solo la web app, cioè la radice o `index.html` di `ubiquint.pages.dev` e delle sue anteprime, in https. Ogni altro indirizzo va al browser. `javascript:`, `file:`, `content:`, `intent:` e `data:` sono bloccati, e c'è un controllo di sicurezza anche dopo l'avvio di una navigazione.
- **Ponte JavaScript** (`UbiquintAndroid`): risponde solo quando è aperta la pagina dell'app ed espone cinque funzioni: versione, login, apri fuori, stampa e salva file.
- **Login**: lo stato monouso viene generato con `SecureRandom` e confrontato a tempo costante. Le credenziali tornano soltanto a questo package, vengono controllate nel formato, restano solo in memoria e sono passate alla pagina con escape sicuro. Nel browser Firebase usa la persistenza in memoria e chiude subito la sessione.
- **Dati salvati**: `allowBackup=false`, quindi le chiavi API salvate dalla web app non finiscono nei backup del telefono. Il traffico in chiaro è vietato e non c'è contenuto misto.
- **Nomi dei file salvati**: vengono ripuliti, senza cartelle né caratteri riservati, e c'è un limite di 64 MB.

## 5. Prove

```bash
./build.sh && test/run_tests.sh                                            # 85 prove sulla JVM delle regole dell'app
python3 test/test_webview_js.py <index.html della web app> <node_modules>  # 20 prove in Chromium sulla web app vera
python3 test/test_login_page.py <node_modules>                             # 16 prove della pagina di accesso
```

`<node_modules>` deve contenere `firebase@10.7.1` e `jspdf@4.2.1` (`npm install firebase@10.7.1 jspdf@4.2.1`). Le prove in Chromium usano Firebase vero, la CSP di `_headers` e le stringhe prodotte dal codice Java dell'app.

Da controllare sul telefono alla prima installazione, perché nessun emulatore Android era disponibile durante lo sviluppo:

1. Accesso con Google, andata e ritorno.
2. Esportazione di un PDF e di un JSON (cartella Download/UBIQUINT).
3. Un link esterno.
4. Caricamento di una foto.
5. «PDF Rapido».
6. Rotazione dello schermo durante una ricerca.

## 6. Uso lecito

UBIQUINT raccoglie solo informazioni pubbliche. Chi lo usa risponde della base giuridica del trattamento (GDPR art. 6), della minimizzazione dei dati e della conservazione dei report. L'app Android non aggiunge raccolte di dati, telemetria o tracciamenti: in rete parla solo con la web app e con i servizi che la web app già usa.

## Struttura

```
app/AndroidManifest.xml          manifest (minSdk 24, targetSdk 34)
app/src/it/ubiquint/app/         MainActivity (WebView, ponte, file, login) e Policy (regole provate sulla JVM)
app/assets/ubq-android.js        adattamenti iniettati nella web app (login, download, link, stampa)
app/res/                         icone (dal marchio UBIQUINT), tema scuro, testi
web/android-login.html, _headers da pubblicare su Cloudflare Pages
build.sh, app.properties         creazione dell'APK
.github/workflows/apk.yml        APK su GitHub Actions
test/                            prove (JVM e Chromium)
tools/make_icons.py              rigenera le icone
```
