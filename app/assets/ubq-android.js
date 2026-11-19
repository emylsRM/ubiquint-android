/* UBIQUINT per Android: adattamenti della web app dentro la WebView (iniettato dall'app, non cambia la grafica).
   - «Accedi con Google»: il popup di Google non funziona in una WebView, quindi l'accesso si fa nel browser
     (pagina android-login.html) e torna qui con le credenziali Google -> signInWithCredential.
   - Esportazioni (JSON, Markdown, HTML, CSV, PDF di jsPDF): i link blob:/data: con «download» vanno salvati dall'app.
   - Link esterni e window.open: si aprono nel browser, così la ricerca in corso nell'app non si perde.
   - window.print («PDF Rapido»): stampa di Android, con «Salva come PDF». */
(function () {
  'use strict';
  if (window.__ubqAndroid) return;
  var B = window.UbiquintAndroid;
  if (!B) return;

  var AUTH_FALLBACK = 'https://www.gstatic.com/firebasejs/10.7.1/firebase-auth.js';
  var blobs = new Map();
  var nativeCreate = URL.createObjectURL, nativeRevoke = URL.revokeObjectURL;
  var nativeClick = HTMLAnchorElement.prototype.click;
  var nativeDispatch = EventTarget.prototype.dispatchEvent;
  var nativeOpen = window.open;

  function say(msg) { try { alert(msg); } catch (e) {} }

  /* --- file --- */
  URL.createObjectURL = function (obj) {
    var u = nativeCreate.apply(URL, arguments);
    try { if (obj instanceof Blob) blobs.set(u, obj); } catch (e) {}
    return u;
  };
  URL.revokeObjectURL = function (u) {
    var r = nativeRevoke.apply(URL, arguments);
    setTimeout(function () { blobs.delete(u); }, 60000);   // l'app può ancora leggerlo se il click arriva in ritardo
    return r;
  };

  function sendBlob(blob, name) {
    var fr = new FileReader();
    fr.onload = function () {
      var s = String(fr.result || ''), i = s.indexOf(',');
      B.saveFile(name || '', blob.type || 'application/octet-stream', i >= 0 ? s.slice(i + 1) : '');
    };
    fr.onerror = function () { say('Impossibile salvare il file: ' + ((fr.error && fr.error.message) || 'errore di lettura')); };
    fr.readAsDataURL(blob);
  }

  function sendDataUrl(href, name) {
    var m = /^data:([^;,]*)((?:;[^;,]*)*),(.*)$/i.exec(href);
    if (!m) { say('Collegamento del file non valido.'); return; }
    var mime = m[1] || 'text/plain', b64 = /;base64/i.test(m[2]), body = m[3];
    if (b64) { B.saveFile(name || '', mime, body.replace(/\s+/g, '')); return; }
    var bytes = new TextEncoder().encode(decodeURIComponent(body)), bin = '';
    for (var i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    B.saveFile(name || '', mime, btoa(bin));
  }

  function saveUrl(href, name) {
    if (/^data:/i.test(href)) { sendDataUrl(href, name); return; }
    var b = blobs.get(href);
    if (b) { sendBlob(b, name); return; }
    fetch(href).then(function (r) { return r.blob(); })
      .then(function (blob) { sendBlob(blob, name); })
      .catch(function () { say('Il file non è più disponibile: ripeti l\'esportazione.'); });
  }

  /* true se l'app ha preso in carico il link (download di blob:/data:) */
  function takeDownload(a) {
    if (!a || !a.hasAttribute || !a.hasAttribute('download')) return false;
    var href = a.href || '';
    if (!/^(blob|data):/i.test(href)) return false;          // download http(s): ci pensa il browser
    saveUrl(href, a.getAttribute('download') || '');
    return true;
  }

  HTMLAnchorElement.prototype.click = function () {
    if (takeDownload(this)) return;
    return nativeClick.apply(this, arguments);
  };
  EventTarget.prototype.dispatchEvent = function (ev) {          // FileSaver/jsPDF usano dispatchEvent(new MouseEvent('click'))
    if (ev && ev.type === 'click' && this instanceof HTMLAnchorElement && takeDownload(this)) return true;
    return nativeDispatch.apply(this, arguments);
  };

  /* --- link e finestre --- */
  function inApp(url) {
    try {
      var u = new URL(url, location.href);
      return u.origin === location.origin && (u.pathname === '/' || u.pathname === '/index.html' || u.pathname === location.pathname);
    } catch (e) { return false; }
  }
  function external(url) {
    var abs;
    try { abs = new URL(String(url), location.href).href; } catch (e) { return false; }
    if (!/^(https?|mailto|tel):/i.test(abs)) return false;
    B.openExternal(abs);
    return true;
  }
  window.open = function (url) {
    var s = url == null ? '' : String(url);
    if (s === '' || /^about:blank/i.test(s)) return nativeOpen.apply(window, arguments);
    external(s);
    return null;
  };

  /* fase di bolla su window: gli handler della web app hanno già agito; se hanno annullato il click non si tocca nulla */
  window.addEventListener('click', function (e) {
    if (e.defaultPrevented || e.button !== 0) return;
    var a = e.target && e.target.closest ? e.target.closest('a[href]') : null;
    if (!a) return;
    if (takeDownload(a)) { e.preventDefault(); return; }
    if (a.hasAttribute('download')) return;
    var href = a.href || '';
    if (!/^https?:/i.test(href)) return;                          // mailto:, tel:, javascript: ... decide l'app nativa
    if (a.target === '_blank' || !inApp(href)) { e.preventDefault(); external(href); }
  }, false);

  /* --- login con Google --- */
  window.addEventListener('click', function (e) {
    var t = e.target && e.target.closest ? e.target.closest('#btnGoogleLogin') : null;
    if (!t) return;
    e.preventDefault();
    e.stopImmediatePropagation();                                   // niente signInWithPopup: nella WebView Google lo blocca
    if (!t.disabled) B.startGoogleLogin();
  }, true);

  function authModuleUrl() {
    var re = /https:\/\/www\.gstatic\.com\/firebasejs\/[0-9][0-9.]*\/firebase-auth\.js/;
    var ss = document.querySelectorAll('script[type="module"]');
    for (var i = 0; i < ss.length; i++) { var m = re.exec(ss[i].textContent || ''); if (m) return m[0]; }
    return AUTH_FALLBACK;
  }
  function waitAuth(ms) {
    return new Promise(function (ok, ko) {
      var t0 = Date.now();
      (function poll() {
        if (window.__ubiquintAuth && window.__ubiquintAuth.auth) return ok(window.__ubiquintAuth);
        if (Date.now() - t0 > ms) return ko(new Error(window.__ubiquintInitError || 'autenticazione non inizializzata'));
        setTimeout(poll, 100);
      })();
    });
  }
  /* Le funzioni di Firebase che la web app non espone si prendono con uno <script type="module"> della pagina:
     stesso URL della web app = stessa istanza del modulo, quindi lo stesso oggetto auth. */
  function loadAuthModule() {
    if (window.__ubqFbAuth) return Promise.resolve(window.__ubqFbAuth);
    return new Promise(function (ok, ko) {
      var s = document.createElement('script'), timer;
      function done() { clearTimeout(timer); window.removeEventListener('ubq-fbauth', done); ok(window.__ubqFbAuth); }
      function error(msg) { clearTimeout(timer); window.removeEventListener('ubq-fbauth', done); ko(new Error(msg)); }
      window.addEventListener('ubq-fbauth', done);
      timer = setTimeout(function () { error('modulo di accesso Firebase non caricato'); }, 20000);
      s.type = 'module';
      s.onerror = function () { error('modulo di accesso Firebase non raggiungibile'); };
      s.textContent = 'import { GoogleAuthProvider, signInWithCredential } from ' + JSON.stringify(authModuleUrl()) + ';\n'
        + 'window.__ubqFbAuth = { GoogleAuthProvider: GoogleAuthProvider, signInWithCredential: signInWithCredential };\n'
        + 'window.dispatchEvent(new Event("ubq-fbauth"));';
      (document.head || document.documentElement).appendChild(s);
    });
  }
  function completeLogin(idToken, accessToken) {
    return waitAuth(20000).then(function (a) {
      return loadAuthModule().then(function (m) {
        var cred = m.GoogleAuthProvider.credential(idToken || null, accessToken || null);
        return m.signInWithCredential(a.auth, cred);
      });
    }).then(function () { return true; }, function (e) {
      say('Errore login: ' + ((e && e.message) || e));
      return false;
    });
  }

  /* --- stampa («PDF Rapido») --- */
  window.print = function () { B.print(document.title || 'UBIQUINT'); };

  Object.defineProperty(window, '__ubqAndroid', {
    value: Object.freeze({ completeLogin: completeLogin, saveUrl: saveUrl, version: String(B.version ? B.version() : '') }),
    writable: false, configurable: false, enumerable: false
  });
})();
