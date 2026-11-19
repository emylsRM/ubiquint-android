package it.ubiquint.app;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Prove delle regole pure dell'app (Policy) sulla JVM. Uso: test/run_tests.sh (dopo ./build.sh). */
public final class PolicyTest {
    private static int pass, fail;

    private static void check(String id, String what, boolean ok) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "PASS " : "FAIL ") + id + "  " + what);
    }

    private static void eq(String id, String what, Object got, Object want) {
        boolean ok = want == null ? got == null : want.equals(got);
        check(id, what + (ok ? "" : "   atteso=" + want + " ottenuto=" + got), ok);
    }

    public static void main(String[] args) {
        // --- pagine dell'app
        String[] app = {"https://ubiquint.pages.dev/", "https://ubiquint.pages.dev", "https://ubiquint.pages.dev/index.html",
            "https://ubiquint.pages.dev/#risultati", "https://ubiquint.pages.dev/?x=1", "https://UBIQUINT.pages.dev/",
            "https://a1b2c3.ubiquint.pages.dev/", "https://ubiquint.pages.dev:443/"};
        for (String u : app) check("P1", "pagina dell'app: " + u, Policy.isAppPage(u));
        String[] notApp = {"http://ubiquint.pages.dev/", "https://ubiquint.pages.dev.evil.com/", "https://evilubiquint.pages.dev/",
            "https://ubiquint.pages.dev:8443/", "https://ubiquint.pages.dev/how-it-works.html", "https://ubiquint.pages.dev/android-login.html",
            "javascript:alert(1)", "file:///android_asset/x.html", "https://pages.dev/", "", null, "https://ubiquint.pages.dev/../x.html"};
        for (String u : notApp) check("P2", "non è l'app: " + u, !Policy.isAppPage(u));

        // --- tipo di indirizzo
        eq("K1", "about:blank", Policy.kind("about:blank"), Policy.Kind.BLANK);
        eq("K2", "sito esterno https", Policy.kind("https://www.linkedin.com/in/x"), Policy.Kind.WEB);
        eq("K3", "sito esterno http", Policy.kind("http://example.org/a"), Policy.Kind.WEB);
        eq("K4", "app", Policy.kind("https://ubiquint.pages.dev/"), Policy.Kind.APP);
        eq("K5", "mailto", Policy.kind("mailto:a@b.it"), Policy.Kind.MAIL);
        eq("K6", "tel", Policy.kind("tel:+390612345678"), Policy.Kind.TEL);
        String[] blocked = {"intent://x#Intent;scheme=a;end", "javascript:alert(1)", "file:///sdcard/a", "content://x/y",
            "data:text/html,<b>x</b>", "https:///senza-host", "ubiquint://login?s=1", null};
        for (String u : blocked) eq("K7", "bloccato: " + u, Policy.kind(u), Policy.Kind.BLOCKED);

        // --- ritorno dal login
        check("L1", "ritorno login riconosciuto", Policy.isLoginReturn("ubiquint://login?s=abc&id=x"));
        check("L2", "maiuscole", Policy.isLoginReturn("UBIQUINT://LOGIN?s=abc"));
        check("L3", "altro host no", !Policy.isLoginReturn("ubiquint://altro?s=abc"));
        check("L4", "https no", !Policy.isLoginReturn("https://login?s=abc"));
        check("L5", "null no", !Policy.isLoginReturn(null));
        Map<String, String> q = Policy.query("ubiquint://login?s=0123&id=a.b-c_d.e&at=ya29.A%2BB%2F%3D&s=dopo&vuoto");
        eq("Q1", "parametro s (il primo vince)", q.get("s"), "0123");
        eq("Q2", "id", q.get("id"), "a.b-c_d.e");
        eq("Q3", "at decodificato", q.get("at"), "ya29.A+B/=");
        eq("Q4", "parametro senza valore", q.get("vuoto"), "");
        check("Q5", "indirizzo malformato -> vuoto", Policy.query("ubiquint://login?%zz=1").isEmpty());

        // --- stato monouso
        SecureRandom r = new SecureRandom();
        Set<String> seen = new HashSet<String>();
        boolean fmt = true;
        for (int i = 0; i < 1000; i++) {
            String s = Policy.newState(r);
            fmt &= s.matches("[0-9a-f]{32}");
            seen.add(s);
        }
        check("S1", "stato: 32 cifre esadecimali", fmt);
        check("S2", "stato: 1000 valori tutti diversi", seen.size() == 1000);
        String st = Policy.newState(r);
        long t = 1_700_000_000_000L;
        check("S3", "stato giusto entro 10 minuti", Policy.stateOk(st, t, st, t + 9 * 60_000L));
        check("S4", "stato scaduto", !Policy.stateOk(st, t, st, t + 11 * 60_000L));
        check("S5", "stato diverso", !Policy.stateOk(st, t, Policy.newState(r), t + 1000));
        check("S6", "nessuno stato atteso", !Policy.stateOk(null, t, st, t + 1000));
        check("S7", "orologio indietro", !Policy.stateOk(st, t, st, t - 1));
        check("S8", "stato atteso malformato", !Policy.stateOk("abc", t, "abc", t + 1));
        check("S9", "stato ricevuto mancante", !Policy.stateOk(st, t, null, t + 1));

        // --- credenziali
        String jwt = "eyJhbGciOiJSUzI1NiJ9.eyJpc3MiOiJodHRwczovL2FjY291bnRzLmdvb2dsZS5jb20ifQ.c2lnbmF0dXJlX2Zha2U-_";
        check("T1", "id token con forma JWT", Policy.jwtLike(jwt));
        check("T2", "JWT con due parti no", !Policy.jwtLike("a.b"));
        check("T3", "JWT con codice no", !Policy.jwtLike("a.b.c\");alert(1);//"));
        check("T4", "JWT troppo lungo no", !Policy.jwtLike(new String(new char[9000]).replace('\0', 'a') + ".b.c"));
        check("T5", "access token Google", Policy.accessTokenLike("ya29.a0AfB_byC-xyz123+/="));
        check("T6", "access token corto no", !Policy.accessTokenLike("ya29"));
        check("T7", "access token con virgolette no", !Policy.accessTokenLike("ya29.abcdefgh'"));

        // --- stringhe JavaScript
        eq("J1", "virgolette e barre", Policy.jsString("a\"b\\c"), "\"a\\\"b\\\\c\"");
        eq("J2", "a capo e tag", Policy.jsString("x\n</script>"), "\"x\\n\\u003c/script\\u003e\"");
        eq("J3", "separatori di riga Unicode", Policy.jsString("\u2028\u2029"), "\"\\u2028\\u2029\"");
        eq("J4", "carattere di controllo", Policy.jsString("\u0001"), "\"\\u0001\"");
        eq("J5", "null", Policy.jsString(null), "null");

        // --- file salvati
        eq("F1", "tipo con charset", Policy.cleanMime("text/plain;charset=utf-8"), "text/plain");
        eq("F2", "tipo maiuscolo", Policy.cleanMime("TEXT/CSV"), "text/csv");
        eq("F3", "tipo non valido", Policy.cleanMime("evil\"type"), "application/octet-stream");
        eq("F4", "tipo assente", Policy.cleanMime(null), "application/octet-stream");
        eq("F5", "niente cartelle nel nome", Policy.fileName("../../etc/passwd", "text/plain"), "passwd.txt");
        eq("F6", "barra rovesciata", Policy.fileName("a\\b.pdf", "application/pdf"), "b.pdf");
        eq("F7", "caratteri riservati", Policy.fileName("rep:ort?.pdf", "application/pdf"), "rep_ort_.pdf");
        eq("F8", "nome vuoto", Policy.fileName("", "application/pdf"), "ubiquint_export.pdf");
        eq("F9", "nome nascosto", Policy.fileName(".config", "application/json"), "config.json");
        eq("F10", "estensione dal tipo", Policy.fileName("ubiquint_Rossi", "text/markdown"), "ubiquint_Rossi.md");
        eq("F11", "estensione già presente", Policy.fileName("ubiquint_Rossi.json", "application/json"), "ubiquint_Rossi.json");
        eq("F12", "caratteri di controllo", Policy.fileName("a\u0000b\nc.csv", "text/csv"), "a_b_c.csv");
        String lungo = Policy.fileName(new String(new char[300]).replace('\0', 'x') + ".pdf", "application/pdf");
        check("F13", "nome lungo accorciato a 120 con estensione", lungo.length() == 120 && lungo.endsWith(".pdf"));
        eq("F14", "tipo sconosciuto senza estensione", Policy.fileName("dati", "application/x-ignoto"), "dati");

        // --- selettore file
        eq("C1", "solo immagini", Policy.chooserMime(new String[]{"image/*"}), "image/*");
        eq("C2", "JSON -> qualsiasi file", Policy.chooserMime(new String[]{".json", "application/json"}), "*/*");
        eq("C3", "JSON in una stringa", Policy.chooserMime(new String[]{".json,application/json"}), "*/*");
        eq("C4", "immagini per estensione", Policy.chooserMime(new String[]{"image/png", ".JPG"}), "image/*");
        eq("C5", "nessun tipo", Policy.chooserMime(new String[]{""}), "*/*");
        eq("C6", "null", Policy.chooserMime(null), "*/*");

        System.out.println("\nPolicyTest: " + pass + " superate, " + fail + " fallite");
        System.exit(fail == 0 ? 0 : 1);
    }
}
