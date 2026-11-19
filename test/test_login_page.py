"""UBIQUINT per Android — prova in Chromium della pagina di accesso web/android-login.html (da pubblicare su Cloudflare Pages).

Servita come https://ubiquint.pages.dev/android-login.html con le intestazioni di sicurezza di web/_headers (CSP compresa).
 - con Firebase 10.7.1 VERO: il modulo si carica sotto la CSP e signInWithPopup apre la finestra di Google del progetto ubiquint;
 - con un Firebase simulato: il collegamento di ritorno all'app viene letto dal codice Java vero (Policy, via test/Probe.java).
Uso:  python3 test/test_login_page.py <cartella node_modules con firebase@10.7.1>   (dopo ./build.sh && test/run_tests.sh)"""
import base64, json, pathlib, re, subprocess, sys, time, urllib.parse
from playwright.sync_api import sync_playwright

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent
NM = pathlib.Path(sys.argv[1])
PAGE = (ROOT / 'web/android-login.html').read_text(encoding='utf-8')
SHOT = ROOT / 'build' / 'android-login.png'
URL = 'https://ubiquint.pages.dev/android-login.html'
UA = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.6723.58 Mobile Safari/537.36'
CORS = {'Access-Control-Allow-Origin': '*'}


def headers_for(path):
    """Intestazioni di _headers che valgono per path (regole /* e /android-login*)."""
    out, rule = {}, None
    for line in (ROOT / 'web/_headers').read_text(encoding='utf-8').splitlines():
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        if not line.startswith((' ', '\t')):
            pat = line.strip()
            rule = re.fullmatch(re.escape(pat).replace(r'\*', '.*'), path) is not None
            continue
        if rule:
            k, v = line.strip().split(':', 1)
            out[k.strip()] = v.strip()
    return out


HDR = headers_for('/android-login.html')
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
GOOGLE_ID = b64u({'alg': 'RS256'}) + '.' + b64u({'iss': 'https://accounts.google.com', 'email': 'mario.rossi@acme.it', 'iat': NOW}) + '.ZmlybWE-_x'
ACCESS = 'ya29.a0AfB_byC-Prova+/='
STATE = '0123456789abcdef0123456789abcdef'

GAPI = """(function () {
  var cb = new URL(document.currentScript.src).searchParams.get('onload');
  var frame = { restyle: function () {}, register: function () {}, send: function () {}, ping: function (f) { return Promise.resolve().then(f); } };
  window.gapi = { load: function (n, o) { (o.callback || o)(); },
    iframes: { CROSS_ORIGIN_IFRAMES_FILTER: {}, Iframe: function () {}, getContext: function () { return { open: function (c, done) { return done(frame); } }; } } };
  if (cb && window[cb]) window[cb]();
})();"""
STUB_APP = 'export function initializeApp(c){ window.__stub = { config: c }; return { name: "app" }; }'
STUB_AUTH = """
export const inMemoryPersistence = { type: 'NONE' };
export const browserPopupRedirectResolver = { stub: true };
export function initializeAuth(app, deps) { window.__stub.deps = deps; return { name: 'auth' }; }
export class GoogleAuthProvider {
  constructor() { this.params = null; window.__stub.provider = this; }
  setCustomParameters(p) { this.params = p; }
  static credentialFromResult(r) { return r.__cred || null; }
}
export async function signInWithPopup() {
  window.__stub.popups = (window.__stub.popups || 0) + 1;
  const m = window.__stubMode || {};
  if (m.err) { const e = new Error('Firebase: Error (' + m.err + ').'); e.code = m.err; throw e; }
  return { user: { email: 'mario.rossi@acme.it' }, __cred: m.nocred ? null : { idToken: m.id, accessToken: m.at } };
}
export async function signOut() { window.__stub.signedOut = true; }
"""


def run(p, stub):
    b = p.chromium.launch()
    ctx = b.new_context(viewport={'width': 412, 'height': 915}, is_mobile=True, has_touch=True, user_agent=UA)
    seen = []
    def route(r):
        u = r.request.url
        seen.append(u)
        if u.split('#')[0] == URL:
            return r.fulfill(body=PAGE, headers={**HDR, 'Content-Type': 'text/html; charset=utf-8'})
        if u.startswith('https://www.gstatic.com/firebasejs/10.7.1/'):
            name = u.rsplit('/', 1)[1]
            if stub and name in ('firebase-app.js', 'firebase-auth.js'):
                return r.fulfill(body=STUB_APP if name == 'firebase-app.js' else STUB_AUTH, content_type='application/javascript', headers=CORS)
            f = NM / 'firebase' / name
            if f.exists():
                return r.fulfill(body=f.read_bytes(), content_type='application/javascript', headers=CORS)
        if u.startswith('https://apis.google.com/js/api.js'):   # gapi finto (solo test): l'iframe di Firebase risponde subito al ping
            return r.fulfill(body=GAPI, content_type='application/javascript', headers=CORS)
        if u.startswith('https://ubiquint.firebaseapp.com/__/auth/'):
            return r.fulfill(body='<!doctype html><title>auth</title>', content_type='text/html')
        return r.abort()
    ctx.route('**/*', route)
    pg = ctx.new_page()
    log = {'errors': [], 'console': [], 'popups': []}
    pg.on('pageerror', lambda e: log['errors'].append(str(e)))
    pg.on('console', lambda m: log['console'].append(m.text))
    pg.on('popup', lambda w: log['popups'].append(w))
    return b, pg, log, seen


with sync_playwright() as p:
    # ---------- Firebase vero
    b, pg, log, seen = run(p, stub=False)
    pg.goto(URL + '#s=' + STATE, wait_until='load')
    try:
        pg.wait_for_function("!document.getElementById('go').disabled", timeout=15000)
        ready = True
    except Exception:
        ready = False
    check('B1', "Firebase 10.7.1 vero si carica sotto la CSP di _headers (tutti gli import esistono) e abilita il pulsante",
          ready and not log['errors'], (log['errors'], log['console'][-5:]))
    check('B2', "Lo stato monouso sparisce subito dalla barra degli indirizzi", pg.url == URL, pg.url)
    pg.screenshot(path=str(SHOT))
    pg.click('#go')
    pg.wait_for_timeout(1500)
    pop = log['popups'][0] if log['popups'] else None
    q = urllib.parse.parse_qs(urllib.parse.urlsplit(pop.url).query) if pop else {}
    check('B3', "signInWithPopup apre la finestra di Google del progetto ubiquint (authDomain, chiave, provider google.com, scelta dell'account)",
          pop is not None and pop.url.startswith('https://ubiquint.firebaseapp.com/__/auth/handler?')
          and q.get('apiKey') == ['AIzaSyBOdaq8t2vXD7UcSFLSvGb9Ce6fwjd7_CE'] and q.get('providerId') == ['google.com']
          and 'select_account' in (q.get('customParameters') or [''])[0] and q.get('authType') == ['signInViaPopup'],
          pop.url if pop else log['console'][-5:])
    if pop:
        pop.close()
    try:
        pg.wait_for_function("!document.getElementById('err').hidden && !document.getElementById('go').disabled", timeout=20000)
        err = pg.inner_text('#err')
    except Exception:
        err = ''
    check('B4', "Finestra di Google chiusa: messaggio in italiano e pulsante di nuovo attivo", 'chiusa prima di completare' in err
          or 'Rete non disponibile' in err, err)
    csp = [m for m in log['console'] if 'Content Security Policy' in m or 'Refused to' in m]
    check('B5', "Nessuna violazione della CSP", not csp, csp)
    b.close()

    # ---------- Firebase simulato: ritorno all'app
    b, pg, log, seen = run(p, stub=True)
    pg.goto(URL + '#s=' + STATE, wait_until='load')
    pg.wait_for_function("!document.getElementById('go').disabled", timeout=10000)
    pg.evaluate('m => window.__stubMode = m', {'id': GOOGLE_ID, 'at': ACCESS})
    pg.click('#go')
    pg.wait_for_function("!document.getElementById('back').hidden", timeout=5000)
    pg.wait_for_timeout(300)
    href = pg.get_attribute('#back', 'href')
    st = pg.evaluate('window.__stub')
    check('B6', "Collegamento di ritorno: intent:// verso il SOLO package it.ubiquint.app, con stato e credenziali",
          href.startswith('intent://login?s=' + STATE + '&id=') and href.endswith('#Intent;scheme=ubiquint;package=it.ubiquint.app;end'), href)
    check('B7', "Persistenza solo in memoria, scelta dell'account, uscita da Firebase nel browser dopo l'accesso",
          st['deps']['persistence'] == {'type': 'NONE'} and st['provider']['params'] == {'prompt': 'select_account'} and st.get('signedOut') is True, st)
    check('B8', "Messaggio con l'account usato e pulsante «Torna all'app» visibile",
          'mario.rossi@acme.it' in pg.inner_text('#msg') and pg.is_visible('#back') and not pg.is_visible('#go'), pg.inner_text('#msg'))
    tried = [m for m in log['console'] if 'intent://login' in m]
    check('B9', "Apertura automatica dell'app tentata (in Chromium da computer lo schema intent:// non esiste: resta il pulsante)",
          bool(tried) or pg.url == URL, (tried, pg.url))
    # Android trasforma intent://login?…#Intent;scheme=ubiquint;… in ubiquint://login?… : lo legge il codice Java vero
    data = 'ubiquint://login?' + href.split('?', 1)[1].split('#', 1)[0]
    got = probe('login', data)
    check('B10', "L'app (Policy.java) riconosce il ritorno e ricava stato, id token e access token identici e validi",
          got == ['true', STATE, GOOGLE_ID, ACCESS, 'true', 'true'], got)

    pg.goto('about:blank')
    pg.goto(URL + '#s=' + STATE, wait_until='load')
    pg.wait_for_function("!document.getElementById('go').disabled", timeout=10000)
    pg.evaluate("() => window.__stubMode = { err: 'auth/popup-blocked' }")
    pg.click('#go')
    pg.wait_for_function("!document.getElementById('err').hidden", timeout=5000)
    check('B11', "Popup bloccato dal browser: spiegazione in italiano, si può riprovare",
          'bloccato la finestra di Google' in pg.inner_text('#err') and not pg.is_disabled('#go') and pg.is_hidden('#back'), pg.inner_text('#err'))
    pg.evaluate("() => window.__stubMode = { nocred: true }")
    pg.click('#go')
    pg.wait_for_timeout(400)
    check('B12', "Google senza credenziali: errore, nessun ritorno all'app", 'non ha restituito le credenziali' in pg.inner_text('#err')
          and pg.is_hidden('#back'), pg.inner_text('#err'))

    for case, h in (('senza stato', ''), ('stato non valido', '#s=xyz'), ('stato con codice', '#s=0123456789abcdef0123456789abcdef&x=1')):
        pg.goto('about:blank')
        pg.goto(URL + h, wait_until='load')
        pg.wait_for_timeout(400)
        check('B13', f"Pagina aperta {case}: nessun accesso possibile, spiega da dove si apre",
              pg.is_hidden('#go') and 'dal pulsante' in pg.inner_text('#msg') and pg.evaluate('!window.__stub || !window.__stub.deps'), pg.inner_text('#msg'))
    check('B14', "Nessun errore JavaScript", not log['errors'], log['errors'])
    b.close()

w = max(len(r[0]) for r in res)
for cid, st, desc, why in res:
    print(f"{st}  {cid.ljust(w)}  {desc}" + (f"\n        -> {why}" if why else ''))
fails = sum(1 for r in res if r[1] == 'FAIL')
print(f"\nPagina di accesso: {len(res) - fails} superate, {fails} fallite  (schermata: {SHOT})")
sys.exit(1 if fails else 0)
