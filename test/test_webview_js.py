"""UBIQUINT per Android — prova in Chromium dello script iniettato nella WebView (app/assets/ubq-android.js) sulla web app VERA.

La pagina è servita come https://ubiquint.pages.dev/ (stessa origine dell'app), con Firebase 10.7.1 vero (npm) al posto di
gstatic e le API di Google Identity Toolkit simulate. window.UbiquintAndroid imita il ponte Java (addJavascriptInterface) e
registra le chiamate; le stringhe che l'app Java passa a evaluateJavascript sono prodotte da Policy vera (test/Probe.java).

Uso:  python3 test/test_webview_js.py <index.html della web app> <cartella node_modules con firebase@10.7.1 e jspdf>
      (prima: ./build.sh && test/run_tests.sh, che compila anche Probe)"""
import base64, json, pathlib, subprocess, sys, time
from playwright.sync_api import sync_playwright

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent
HTML = pathlib.Path(sys.argv[1]).read_text(encoding='utf-8')
NM = pathlib.Path(sys.argv[2])
INJECT = (ROOT / 'app/assets/ubq-android.js').read_text(encoding='utf-8')
APP = 'https://ubiquint.pages.dev/'
CORS = {'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': '*', 'Access-Control-Allow-Methods': '*'}
UA = ('Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/AP2A; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 '
      'Chrome/130.0.6723.58 Mobile Safari/537.36 UbiquintAndroid/1.0.0')
LIBS = {'jspdf.umd.min.js': NM / 'jspdf/dist/jspdf.umd.min.js'}
for extra in ['jspdf-autotable/dist/jspdf.plugin.autotable.min.js', 'vis-network/standalone/umd/vis-network.min.js',
              'libphonenumber-js/bundle/libphonenumber-max.js']:
    for base in (NM, ROOT.parent / 'ubiquint/node_modules'):
        if (base / extra).exists():
            LIBS[extra.rsplit('/', 1)[1]] = base / extra
            break

res = []
def check(cid, desc, cond, why=''):
    res.append((cid, 'PASS' if cond else 'FAIL', desc, '' if cond else str(why)[:500]))

def probe(*args):
    out = subprocess.run(['java', '-Dstdout.encoding=UTF-8', '-cp', str(ROOT / 'build/test-classes'), 'it.ubiquint.app.Probe', *args],
                         capture_output=True, check=True)
    return out.stdout.decode('utf-8').rstrip('\n').split('\n')

def b64u(d):
    return base64.urlsafe_b64encode(json.dumps(d).encode()).decode().rstrip('=')

NOW = int(time.time())
FB_ID = b64u({'alg': 'none', 'typ': 'JWT'}) + '.' + b64u({'iss': 'https://securetoken.google.com/ubiquint', 'aud': 'ubiquint', 'sub': 'uid-mario',
        'user_id': 'uid-mario', 'email': 'mario.rossi@acme.it', 'iat': NOW, 'exp': NOW + 3600, 'auth_time': NOW,
        'firebase': {'sign_in_provider': 'google.com', 'identities': {'email': ['mario.rossi@acme.it']}}}) + '.c2ln'
GOOGLE_ID = b64u({'alg': 'RS256', 'kid': 'k1'}) + '.' + b64u({'iss': 'https://accounts.google.com', 'aud': '1093739680877-x.apps.googleusercontent.com',
            'sub': '1234567890', 'email': 'mario.rossi@acme.it', 'iat': NOW, 'exp': NOW + 3600}) + '.U2lnbmF0dXJh-_x'
ACCESS = 'ya29.a0AfB_byC-Prova+/='
USER = {'localId': 'uid-mario', 'email': 'mario.rossi@acme.it', 'emailVerified': True, 'displayName': 'Mario Rossi',
        'providerUserInfo': [{'providerId': 'google.com', 'email': 'mario.rossi@acme.it', 'federatedId': '1234567890', 'rawId': '1234567890'}],
        'lastLoginAt': str(NOW * 1000), 'createdAt': str(NOW * 1000)}

idp_bodies, firestore_hits, idp_fail = [], [], {'on': False}

def route(r):
    u = r.request.url
    if r.request.method == 'OPTIONS':
        return r.fulfill(status=204, headers=CORS)
    if u.split('#')[0] in (APP, APP + 'index.html'):
        return r.fulfill(body=HTML, content_type='text/html; charset=utf-8')
    if u.startswith('https://www.gstatic.com/firebasejs/10.7.1/'):
        f = NM / 'firebase' / u.rsplit('/', 1)[1]
        if f.exists():
            return r.fulfill(body=f.read_bytes(), content_type='application/javascript', headers=CORS)
    for k, f in LIBS.items():
        if u.endswith(k):
            return r.fulfill(body=f.read_bytes(), content_type='application/javascript', headers=CORS)
    if 'identitytoolkit.googleapis.com/v1/accounts:signInWithIdp' in u:
        body = json.loads(r.request.post_data or '{}')
        idp_bodies.append(body)
        if idp_fail['on']:
            return r.fulfill(status=400, body=json.dumps({'error': {'code': 400, 'message': 'INVALID_IDP_RESPONSE : token scaduto'}}),
                             headers={**CORS, 'Content-Type': 'application/json'})
        return r.fulfill(body=json.dumps({'federatedId': 'https://accounts.google.com/1234567890', 'providerId': 'google.com',
                         'email': 'mario.rossi@acme.it', 'emailVerified': True, 'localId': 'uid-mario', 'idToken': FB_ID, 'refreshToken': 'rt-prova',
                         'expiresIn': '3600', 'displayName': 'Mario Rossi', 'oauthIdToken': GOOGLE_ID, 'rawUserInfo': '{}', 'kind': 'identitytoolkit#VerifyAssertionResponse'}),
                         headers={**CORS, 'Content-Type': 'application/json'})
    if 'identitytoolkit.googleapis.com/v1/accounts:lookup' in u:
        return r.fulfill(body=json.dumps({'users': [USER]}), headers={**CORS, 'Content-Type': 'application/json'})
    if 'firestore.googleapis.com' in u:
        firestore_hits.append(u)
        return r.abort()
    if u == 'https://example.org/file.pdf':
        return r.fulfill(body=b'%PDF-1.4 prova', headers={**CORS, 'Content-Type': 'application/pdf', 'Content-Disposition': 'attachment; filename="file.pdf"'})
    if u.startswith('https://example.org/'):
        return r.fulfill(status=404, body='', headers=CORS)
    return r.abort()

BRIDGE = """
window.__calls = [];
window.UbiquintAndroid = {
  version: function () { return '1.0.0'; },
  startGoogleLogin: function () { __calls.push(['startGoogleLogin']); },
  openExternal: function (u) { __calls.push(['openExternal', u]); },
  print: function (t) { __calls.push(['print', t]); },
  saveFile: function (n, m, b) { __calls.push(['saveFile', n, m, b]); }
};"""

def calls(pg, kind=None):
    c = pg.evaluate('window.__calls')
    return [x for x in c if kind is None or x[0] == kind]

def reset(pg):
    pg.evaluate('window.__calls.length = 0')

with sync_playwright() as p:
    b = p.chromium.launch()
    ctx = b.new_context(viewport={'width': 412, 'height': 915}, is_mobile=True, has_touch=True, user_agent=UA, accept_downloads=True)
    ctx.add_init_script(BRIDGE)
    ctx.route('**/*', route)
    pg = ctx.new_page()
    errs, popups, downloads, dialogs = [], [], [], []
    pg.on('pageerror', lambda e: errs.append(str(e)))
    pg.on('popup', lambda w: popups.append(w.url))
    pg.on('download', lambda d: downloads.append(d.suggested_filename))
    def on_dialog(d):
        dialogs.append(d.message)
        d.accept() if d.type != 'confirm' else d.dismiss()
    pg.on('dialog', on_dialog)
    pg.goto(APP, wait_until='load')
    pg.wait_for_function('window.__ubiquintAuth && window.__ubiquintAuth.auth', timeout=20000)
    errs_before = list(errs)

    # come evaluateJavascript in onPageCommitVisible + onPageFinished: due iniezioni
    pg.evaluate(INJECT)
    pg.evaluate(INJECT)
    check('A1', "Script iniettato: window.__ubqAndroid con versione dell'app, iniezione ripetuta senza effetti doppi",
          pg.evaluate("window.__ubqAndroid && window.__ubqAndroid.version") == '1.0.0'
          and pg.evaluate("Object.getOwnPropertyDescriptor(window,'__ubqAndroid').writable") is False)

    # --- login
    reset(pg)
    pg.evaluate("document.getElementById('loginScreen').style.display = ''")
    pg.click('#btnGoogleLogin')
    pg.wait_for_timeout(600)
    check('A2', "«Accedi con Google» chiama il login dell'app una sola volta e NON apre il popup di Firebase (bloccato nelle WebView)",
          calls(pg, 'startGoogleLogin') == [['startGoogleLogin']] and not popups and not any('Errore login' in d for d in dialogs),
          (calls(pg), popups, dialogs))

    deliver = probe('deliver', GOOGLE_ID, ACCESS)[0]
    got = pg.evaluate('async (js) => { const ok = await eval(js); const u = window.__ubiquintAuth.auth.currentUser; '
                      'return { ok, email: u && u.email, provider: u && u.providerData.map(p => p.providerId), '
                      'viaModule: !!(window.__ubqFbAuth && window.__ubqFbAuth.signInWithCredential) }; }', deliver)
    pg.wait_for_timeout(1500)
    body = idp_bodies[-1] if idp_bodies else {}
    post = dict(x.split('=', 1) for x in (body.get('postBody') or '').split('&') if '=' in x)
    from urllib.parse import unquote
    check('A3', "Ritorno dal browser: la stringa che l'app Java passa a evaluateJavascript (Policy.loginCall) accede con signInWithCredential",
          got['ok'] is True and got['viaModule'] and got['email'] == 'mario.rossi@acme.it' and got['provider'] == ['google.com']
          and unquote(post.get('id_token', '')) == GOOGLE_ID and unquote(post.get('access_token', '')) == ACCESS and post.get('providerId') == 'google.com',
          (got, body))
    check('A4', "Dopo l'accesso parte il flusso della web app (onAuthStateChanged → lettura whitelist da Firestore)",
          len(firestore_hits) > 0, firestore_hits)

    idp_fail['on'] = True
    n_dialogs = len(dialogs)
    ok = pg.evaluate('async (js) => await eval(js)', deliver)
    pg.wait_for_timeout(300)
    idp_fail['on'] = False
    check('A5', "Credenziali rifiutate da Firebase: completeLogin restituisce false e mostra «Errore login: …» come la web app",
          ok is False and any(d.startswith('Errore login:') for d in dialogs[n_dialogs:]), dialogs[n_dialogs:])

    # --- file esportati
    reset(pg)
    pg.evaluate("download('ubiquint_Rossi.json', JSON.stringify({soggetto:'Mario Rossi', città:'Forlì'}), 'application/json')")
    pg.wait_for_timeout(400)
    s = calls(pg, 'saveFile')
    check('A6', "Esportazione JSON della web app (blob revocato subito dopo il click): salvata dall'app con nome, tipo e contenuto esatti",
          len(s) == 1 and s[0][1] == 'ubiquint_Rossi.json' and s[0][2] == 'application/json'
          and json.loads(base64.b64decode(s[0][3]).decode('utf-8')) == {'soggetto': 'Mario Rossi', 'città': 'Forlì'}, s)

    reset(pg)
    md = '# Report UBIQUINT\n\nSoggetto: Niccolò Perché — ✅ verificato\n'
    pg.evaluate("t => download('ubiquint_Rossi.md', t, 'text/markdown')", md)
    pg.wait_for_timeout(400)
    s = calls(pg, 'saveFile')
    check('A7', "Markdown con accenti ed emoji: byte UTF-8 identici", len(s) == 1 and base64.b64decode(s[0][3]).decode('utf-8') == md, s)

    reset(pg)
    has_pdf = pg.evaluate("!!(window.jspdf && window.jspdf.jsPDF)")
    if has_pdf:
        pg.evaluate("() => { const d = new jspdf.jsPDF(); d.text('UBIQUINT report', 10, 10); d.save('ubiquint_report_Rossi.pdf'); }")
        pg.wait_for_timeout(800)
    s = calls(pg, 'saveFile')
    check('A8', "PDF di jsPDF (doc.save usa dispatchEvent su un link staccato): salvato dall'app, inizia con %PDF-",
          has_pdf and len(s) == 1 and s[0][1] == 'ubiquint_report_Rossi.pdf' and s[0][2] == 'application/pdf'
          and base64.b64decode(s[0][3])[:5] == b'%PDF-', (has_pdf, [x[:3] for x in s]))

    reset(pg)
    pg.evaluate("""() => { const a = document.createElement('a'); a.id = 'dl-data'; a.textContent = 'scarica';
        a.href = 'data:text/csv;charset=utf-8,' + encodeURIComponent('nome;città\\nMario;Forlì'); a.download = 'elenco.csv';
        a.style.cssText = 'position:fixed;top:10px;left:10px;z-index:99999;display:block;padding:10px;background:#fff'; document.body.appendChild(a); }""")
    pg.click('#dl-data')
    pg.wait_for_timeout(400)
    s = calls(pg, 'saveFile')
    check('A9', "Link data: con «download» toccato dall'utente: salvato dall'app", len(s) == 1 and s[0][1] == 'elenco.csv'
          and base64.b64decode(s[0][3]).decode('utf-8') == 'nome;città\nMario;Forlì', s)

    reset(pg)
    pg.evaluate("""() => { const a = document.createElement('a'); a.href = URL.createObjectURL(new Blob(['ciao'], {type:'text/plain'}));
        a.download = 'nota.txt'; a.click(); }""")
    pg.wait_for_timeout(300)
    s = calls(pg, 'saveFile')
    check('A10', "a.click() su link blob: staccato: salvato dall'app", len(s) == 1 and s[0][1] == 'nota.txt' and base64.b64decode(s[0][3]) == b'ciao', s)

    reset(pg)
    url = pg.evaluate("window.__pre = URL.createObjectURL(new Blob(['prima'], {type:'text/plain'}))")
    pg.evaluate(probe('saveurl', url, 'pre.txt')[0])
    pg.wait_for_timeout(300)
    s = calls(pg, 'saveFile')
    check('A11', "Riserva del DownloadListener (Policy.saveUrlCall): il blob viene recuperato e salvato", len(s) == 1 and s[0][1] == 'pre.txt'
          and base64.b64decode(s[0][3]) == b'prima', s)
    check('A12', "Nessun download è arrivato al percorso nativo di Chromium", downloads == [], downloads)

    # --- link e finestre
    reset(pg)
    pg.evaluate("""() => { const d = document.createElement('div'); d.id = 'links';
        d.style.cssText = 'position:fixed;top:60px;left:10px;z-index:99999;background:#fff;padding:6px;display:flex;flex-direction:column;gap:14px';
        d.innerHTML = extLink('https://www.linkedin.com/in/mario-rossi', 'profilo') +
          '<a id="howto" href="how-it-works.html">come funziona</a><a id="hash" href="#sezione">sezione</a>' +
          '<a id="handled" href="https://example.org/gestito">gestito</a><a id="dlhttp" href="https://example.org/file.pdf" download>pdf remoto</a>';
        d.querySelector('#handled').addEventListener('click', e => { e.preventDefault(); window.__handled = true; });
        document.body.appendChild(d); }""")
    url_before = pg.url
    pg.click('#links a[target="_blank"]')
    pg.wait_for_timeout(300)
    check('A13', "Link esterno della web app (target=_blank): aperto nel browser, l'app resta sulla ricerca",
          calls(pg) == [['openExternal', 'https://www.linkedin.com/in/mario-rossi']] and not popups and pg.url == url_before, (calls(pg), popups))
    reset(pg)
    pg.evaluate("openExternal('https://x.com/mrossi_dev')")
    r_open = pg.evaluate("window.open('https://github.com/mrossi', '_blank', 'noopener')")
    check('A14', "window.open e openExternal() della web app: nel browser, window.open restituisce null",
          calls(pg) == [['openExternal', 'https://x.com/mrossi_dev'], ['openExternal', 'https://github.com/mrossi']] and r_open is None and not popups, calls(pg))
    reset(pg)
    pg.click('#howto')
    pg.wait_for_timeout(200)
    check('A15', "Pagina della stessa origine diversa dall'app (how-it-works.html): nel browser, l'app non perde lo stato",
          calls(pg) == [['openExternal', APP + 'how-it-works.html']] and pg.url == url_before, calls(pg))
    reset(pg)
    pg.click('#hash')
    pg.click('#handled')
    pg.wait_for_timeout(200)
    check('A16', "Ancora interna (#sezione) e link gestito dalla web app (preventDefault): non toccati",
          calls(pg) == [] and pg.evaluate('window.__handled === true') and pg.url.endswith('#sezione'), calls(pg))
    reset(pg)
    with pg.expect_download(timeout=3000) as dl:
        pg.click('#dlhttp')
    pg.wait_for_timeout(300)
    check('A17', "Download http(s) con «download»: lasciato al percorso nativo (l'app lo apre nel browser)",
          calls(pg) == [] and dl.value.suggested_filename == 'file.pdf' and pg.url == url_before.split('#')[0] + '#sezione', (calls(pg), pg.url))

    reset(pg)
    pg.evaluate('window.print()')
    check('A18', "window.print («PDF Rapido»): stampa di Android con il titolo della pagina",
          calls(pg) == [['print', pg.evaluate('document.title')]], calls(pg))

    # --- stringhe Java -> JavaScript
    tricky = ['virgolette "doppie" e \'singole\'', 'barra \\ rovesciata', 'a capo\nritorno\r\ttab', '</script><b>x</b>',
              'separatori  ', 'accenti èéìòù — emoji 🔎', '${template}`', 'controllo \u0001\u001f']
    lines = probe('js', *tricky)
    back = pg.evaluate('ls => ls.map(l => eval(l))', lines)
    check('A19', "Policy.jsString: ogni stringa torna identica quando Chromium la valuta", back == tricky, list(zip(tricky, back)))

    new_errs = [e for e in errs if e not in errs_before]
    check('A20', "Nessun errore JavaScript causato dallo script dell'app", new_errs == [], new_errs)
    b.close()

w = max(len(r[0]) for r in res)
for cid, st, desc, why in res:
    print(f"{st}  {cid.ljust(w)}  {desc}" + (f"\n        -> {why}" if why else ''))
fails = sum(1 for r in res if r[1] == 'FAIL')
print(f"\nWebView JS: {len(res) - fails} superate, {fails} fallite")
sys.exit(1 if fails else 0)
