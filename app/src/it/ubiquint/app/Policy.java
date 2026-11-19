package it.ubiquint.app;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Regole dell'app senza dipendenze Android, così si provano sulla JVM (test/PolicyTest.java):
 * quali indirizzi restano nell'app, ritorno dal login, nomi e tipi dei file salvati.
 */
final class Policy {
    static final long LOGIN_TTL_MS = 10L * 60L * 1000L;
    static final long MAX_FILE_BYTES = 64L * 1024L * 1024L;

    enum Kind { APP, WEB, MAIL, TEL, BLANK, BLOCKED }

    private static final Pattern JWT = Pattern.compile("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    private static final Pattern ACCESS = Pattern.compile("[A-Za-z0-9._~+/=-]+");
    private static final Pattern STATE = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern MIME = Pattern.compile("[a-z0-9][a-z0-9.+-]*/[a-z0-9*][a-z0-9.+*-]*");
    private static final String[][] EXT = {
        {"application/pdf", "pdf"}, {"text/csv", "csv"}, {"application/json", "json"}, {"text/markdown", "md"},
        {"text/plain", "txt"}, {"text/html", "html"}, {"image/png", "png"}, {"image/jpeg", "jpg"}, {"image/svg+xml", "svg"},
        {"application/zip", "zip"}, {"application/xml", "xml"}, {"text/xml", "xml"},
        {"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"},
        {"application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"},
    };
    private static final String[] IMAGE_EXT = {".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".heic", ".heif", ".avif", ".svg"};

    private Policy() {}

    static String host(String url) {
        try {
            String h = new URI(url).getHost();
            return h == null ? "" : h.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return "";
        }
    }

    static String appHost() { return host(Config.APP_URL); }

    private static String appPath() {
        try {
            String p = new URI(Config.APP_URL).getRawPath();
            return p == null || p.isEmpty() ? "/" : p;
        } catch (Exception e) {
            return "/";
        }
    }

    /** Pagina dell'app: https, host dell'app (o un'anteprima *.host), porta standard, percorso radice o index.html. */
    static boolean isAppPage(String url) {
        if (url == null) return false;
        URI u;
        try {
            u = new URI(url.trim());
        } catch (Exception e) {
            return false;
        }
        if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null) return false;
        String h = u.getHost().toLowerCase(Locale.ROOT), app = appHost();
        if (app.isEmpty() || !(h.equals(app) || h.endsWith("." + app))) return false;
        if (u.getPort() != -1 && u.getPort() != 443) return false;
        String p = u.getRawPath();
        if (p == null || p.isEmpty()) p = "/";
        String home = appPath();
        return p.equals("/") || p.equals("/index.html") || p.equals(home) || p.equals(home + "index.html");
    }

    static Kind kind(String url) {
        if (url == null) return Kind.BLOCKED;
        String s = url.trim(), low = s.toLowerCase(Locale.ROOT);
        if (low.equals("about:blank") || low.startsWith("about:blank#") || low.startsWith("about:blank?")) return Kind.BLANK;
        if (isAppPage(s)) return Kind.APP;
        if (low.startsWith("https://") || low.startsWith("http://")) return host(s).isEmpty() ? Kind.BLOCKED : Kind.WEB;
        if (low.startsWith("mailto:")) return Kind.MAIL;
        if (low.startsWith("tel:")) return Kind.TEL;
        return Kind.BLOCKED;   // javascript:, file:, content:, data:, intent: e qualsiasi altro schema
    }

    /** ubiquint://login?... : ritorno dalla pagina di accesso aperta nel browser. */
    static boolean isLoginReturn(String uri) {
        if (uri == null) return false;
        try {
            URI u = new URI(uri);
            return Config.SCHEME.equalsIgnoreCase(u.getScheme()) && "login".equalsIgnoreCase(u.getHost());
        } catch (Exception e) {
            return false;
        }
    }

    static Map<String, String> query(String uri) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        try {
            String q = new URI(uri).getRawQuery();
            if (q == null) return m;
            for (String part : q.split("&")) {
                if (part.isEmpty()) continue;
                int i = part.indexOf('=');
                String k = URLDecoder.decode(i < 0 ? part : part.substring(0, i), "UTF-8");
                String v = i < 0 ? "" : URLDecoder.decode(part.substring(i + 1), "UTF-8");
                if (!m.containsKey(k)) m.put(k, v);
            }
        } catch (Exception e) {
            m.clear();
        }
        return m;
    }

    static String newState(SecureRandom r) {
        byte[] b = new byte[16];
        r.nextBytes(b);
        StringBuilder sb = new StringBuilder(32);
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x & 0xff));
        return sb.toString();
    }

    /** Lo stato è monouso, vale 10 minuti e si confronta a tempo costante. */
    static boolean stateOk(String expected, long issuedAt, String got, long now) {
        if (expected == null || got == null || !STATE.matcher(expected).matches()) return false;
        if (issuedAt <= 0 || now < issuedAt || now - issuedAt > LOGIN_TTL_MS) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), got.getBytes(StandardCharsets.UTF_8));
    }

    static boolean jwtLike(String s) { return s != null && s.length() <= 8192 && JWT.matcher(s).matches(); }

    static boolean accessTokenLike(String s) {
        return s != null && s.length() >= 10 && s.length() <= 4096 && ACCESS.matcher(s).matches();
    }

    /** Stringa JavaScript sicura da concatenare in evaluateJavascript. */
    static String jsString(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '<': sb.append("\\u003c"); break;
                case '>': sb.append("\\u003e"); break;
                case ' ': sb.append("\\u2028"); break;
                case ' ': sb.append("\\u2029"); break;
                default:
                    if (c < 0x20) sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    /** Chiamata JavaScript che completa l'accesso nella pagina dell'app (vedi assets/ubq-android.js). */
    static String loginCall(String idToken, String accessToken) {
        return "window.__ubqAndroid&&window.__ubqAndroid.completeLogin(" + jsString(idToken) + "," + jsString(accessToken) + ")";
    }

    /** Chiamata JavaScript di riserva per un download blob:/data: arrivato al DownloadListener. */
    static String saveUrlCall(String url, String name) {
        return "window.__ubqAndroid&&window.__ubqAndroid.saveUrl(" + jsString(url) + "," + jsString(name) + ")";
    }

    static String cleanMime(String mime) {
        if (mime == null) return "application/octet-stream";
        String m = mime.trim().toLowerCase(Locale.ROOT);
        int semi = m.indexOf(';');
        if (semi >= 0) m = m.substring(0, semi).trim();
        return MIME.matcher(m).matches() ? m : "application/octet-stream";
    }

    static String extFor(String mime) {
        String m = cleanMime(mime);
        for (String[] e : EXT) if (e[0].equals(m)) return e[1];
        return null;
    }

    /** Nome file sicuro per Download: niente cartelle, caratteri di controllo o riservati; estensione dal tipo se manca. */
    static String fileName(String raw, String mime) {
        String n = raw == null ? "" : raw;
        int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (slash >= 0) n = n.substring(slash + 1);
        StringBuilder sb = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            sb.append(c < 0x20 || c == 0x7f || "<>:\"|?*".indexOf(c) >= 0 ? '_' : c);
        }
        n = sb.toString().trim();
        while (n.startsWith(".")) n = n.substring(1);
        if (n.isEmpty()) n = "ubiquint_export";
        String ext = extFor(mime);
        if (n.indexOf('.') < 0 && ext != null) n = n + "." + ext;
        if (n.length() > 120) {
            int dot = n.lastIndexOf('.');
            String e = dot > 0 && n.length() - dot <= 10 ? n.substring(dot) : "";
            n = n.substring(0, 120 - e.length()) + e;
        }
        return n;
    }

    /** Tipi per il selettore file: solo immagini se l'input accetta solo immagini, altrimenti qualsiasi file (la web app controlla). */
    static String chooserMime(String[] accept) {
        if (accept == null) return "*/*";
        boolean any = false, images = true;
        for (String a : accept) {
            if (a == null) continue;
            for (String t : a.split(",")) {
                t = t.trim().toLowerCase(Locale.ROOT);
                if (t.isEmpty()) continue;
                any = true;
                boolean img = t.startsWith("image/");
                for (String e : IMAGE_EXT) img |= t.equals(e);
                if (!img) images = false;
            }
        }
        return any && images ? "image/*" : "*/*";
    }
}
