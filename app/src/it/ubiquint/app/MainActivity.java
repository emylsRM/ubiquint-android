package it.ubiquint.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * UBIQUINT per Android: la web app pubblicata (Config.APP_URL) in una WebView protetta, con le parti che una WebView
 * non fa da sola: accesso Google nel browser, salvataggio delle esportazioni, selettore file, stampa, link esterni.
 * Compilato contro l'API 23: le API più recenti si usano solo dietro controllo di versione o per riflessione.
 */
public class MainActivity extends Activity {
    private static final int REQ_FILE = 41, REQ_SAVE = 42;
    private static final int BG = 0xFF02040A, GOLD = 0xFFC9A227, TEXT = 0xFFF0E5C8, MUTED = 0xFF98A7C3;
    private static final String PREF_STATE = "login_state", PREF_STATE_T = "login_state_t";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SecureRandom random = new SecureRandom();
    private SharedPreferences prefs;
    private WebView web;
    private ProgressBar bar;
    private LinearLayout offline;
    private TextView offlineDetail;
    private String injectJs;

    private ValueCallback<Uri[]> fileCallback;
    private byte[] pendingBytes;
    private String pendingName, pendingMime;
    private String pendingIdToken, pendingAccessToken;
    private boolean pageReady, everLoadedApp;
    private String errorUrl;                                   // indirizzo dell'ultima pagina principale non caricata
    private volatile boolean appPageActive;
    private long lastForeignNav;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("ubiquint", MODE_PRIVATE);
        injectJs = readAsset("ubq-android.js");
        buildUi();
        configureWeb();
        boolean restored = saved != null && web.restoreState(saved) != null;
        if (!restored) web.loadUrl(Config.APP_URL);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    // ------------------------------------------------------------------ interfaccia

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private TextView label(CharSequence s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        web = new WebView(this);
        web.setBackgroundColor(BG);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgressTintList(ColorStateList.valueOf(GOLD));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3), Gravity.TOP);
        root.addView(bar, bp);

        offline = new LinearLayout(this);
        offline.setOrientation(LinearLayout.VERTICAL);
        offline.setGravity(Gravity.CENTER);
        offline.setBackgroundColor(BG);
        offline.setPadding(dp(32), dp(32), dp(32), dp(32));
        offline.setClickable(true);
        TextView title = label("UBIQUINT", 26, GOLD);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setLetterSpacing(0.25f);
        offline.addView(title);
        TextView msg = label(getString(R.string.offline_title), 17, TEXT);
        msg.setPadding(0, dp(24), 0, dp(8));
        offline.addView(msg);
        offlineDetail = label("", 13, MUTED);
        offline.addView(offlineDetail);
        TextView retry = label(getString(R.string.retry), 15, GOLD);
        GradientDrawable border = new GradientDrawable();
        border.setCornerRadius(dp(6));
        border.setStroke(dp(1), GOLD);
        retry.setBackground(border);
        retry.setPadding(dp(28), dp(12), dp(28), dp(12));
        retry.setClickable(true);
        retry.setFocusable(true);
        retry.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { retry(); }
        });
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(28);
        offline.addView(retry, rp);
        offline.setVisibility(View.GONE);
        root.addView(offline, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
    }

    private void showOffline(CharSequence detail) {
        offlineDetail.setText(detail == null ? "" : detail);
        offline.setVisibility(View.VISIBLE);
        bar.setVisibility(View.GONE);
    }

    private void retry() {
        offline.setVisibility(View.GONE);
        errorUrl = null;
        String u = web.getUrl();
        if (u == null || !Policy.isAppPage(u)) web.loadUrl(Config.APP_URL);
        else web.reload();
    }

    private void toast(int res) { Toast.makeText(this, res, Toast.LENGTH_LONG).show(); }

    private void info(String msg) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this, R.style.UbqDialog).setTitle("UBIQUINT").setMessage(msg)
            .setPositiveButton(android.R.string.ok, null).show();
    }

    private String readAsset(String name) {
        try {
            InputStream in = getAssets().open(name);
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toString("UTF-8");
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ WebView

    private void configureWeb() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setSupportMultipleWindows(true);                 // le nuove finestre passano da onCreateWindow e vanno nel browser
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setGeolocationEnabled(false);
        s.setUserAgentString(s.getUserAgentString() + " UbiquintAndroid/" + Config.VERSION);
        WebView.setWebContentsDebuggingEnabled(false);
        CookieManager.getInstance().setAcceptCookie(true);

        web.addJavascriptInterface(new Bridge(), "UbiquintAndroid");
        web.setWebViewClient(new MainClient());
        web.setWebChromeClient(new ChromeClient());
        web.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String disposition, String mime, long length) {
                if (url == null) return;
                if (url.startsWith("blob:") || url.startsWith("data:")) {
                    String name = URLUtil.guessFileName(url, disposition, mime);
                    web.evaluateJavascript(Policy.saveUrlCall(url, name), null);
                } else {
                    openExternal(url, false);                  // download http(s): lo gestisce il browser
                }
            }
        });
    }

    private void inject(WebView v, String url) {
        if (injectJs != null && Policy.isAppPage(url)) v.evaluateJavascript(injectJs, null);
    }

    /** true = navigazione annullata (aperta fuori o bloccata). */
    private boolean route(String url) {
        switch (Policy.kind(url)) {
            case APP:
            case BLANK:
                return false;
            case WEB:
                if (!everLoadedApp) {                          // l'indirizzo dell'app reindirizza altrove: non aprire schede a vuoto
                    showOffline(getString(R.string.redirect_blocked, Policy.host(url)));
                    return true;
                }
                openExternal(url, true);
                return true;
            case MAIL:
            case TEL:
                openExternal(url, false);
                return true;
            default:
                return true;
        }
    }

    private final class MainClient extends WebViewClient {
        // API 24+: chiamato dal sistema anche se l'SDK di compilazione (23) non lo dichiara
        @SuppressWarnings("unused")
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (!request.isForMainFrame()) return false;
            return route(request.getUrl().toString());
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return route(url);
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            pageReady = false;
            appPageActive = Policy.isAppPage(url);
            Policy.Kind k = Policy.kind(url);
            if (k != Policy.Kind.APP && k != Policy.Kind.BLANK) {
                // rete di sicurezza: una pagina estranea non deve mai restare nella WebView che ha il ponte con l'app
                view.stopLoading();
                long now = System.currentTimeMillis();
                final boolean loop = now - lastForeignNav < 10000 || !everLoadedApp;
                final boolean isWeb = k == Policy.Kind.WEB;
                final String foreign = url;
                final WebView v = view;
                lastForeignNav = now;
                ui.post(new Runnable() {                       // fuori dalla callback di navigazione
                    @Override public void run() {
                        if (loop) {
                            showOffline(getString(R.string.redirect_blocked, Policy.host(foreign)));
                            return;
                        }
                        if (isWeb) openExternal(foreign, true);
                        if (v.canGoBack()) v.goBack(); else v.loadUrl(Config.APP_URL);
                    }
                });
                return;
            }
            bar.setVisibility(View.VISIBLE);
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            appPageActive = Policy.isAppPage(url);
            if (appPageActive) everLoadedApp = true;
        }

        @Override
        public void onPageCommitVisible(WebView view, String url) {
            inject(view, url);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            bar.setVisibility(View.GONE);
            if (!Policy.isAppPage(url)) return;
            inject(view, url);
            if (errorUrl == null || !errorUrl.equals(url)) {   // vale in qualunque ordine arrivino onReceivedError e onPageFinished
                errorUrl = null;
                everLoadedApp = true;
                offline.setVisibility(View.GONE);
                pageReady = true;
                deliverLogin();
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (!request.isForMainFrame()) return;
            errorUrl = request.getUrl().toString();
            showOffline(getString(R.string.offline_hint) + "\n\n" + error.getDescription());
        }
    }

    private final class ChromeClient extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int p) {
            bar.setProgress(p);
            if (p >= 100) bar.setVisibility(View.GONE);
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
            final WebView child = new WebView(MainActivity.this);
            final PopupCatcher catcher = new PopupCatcher(child);
            child.setWebViewClient(catcher);
            WebView.WebViewTransport t = (WebView.WebViewTransport) resultMsg.obj;
            t.setWebView(child);
            resultMsg.sendToTarget();
            ui.postDelayed(new Runnable() {
                @Override public void run() { catcher.finish(); }
            }, 15000);
            return true;
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = callback;
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType(Policy.chooserMime(params.getAcceptTypes()));
            if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            try {
                startActivityForResult(i, REQ_FILE);
            } catch (ActivityNotFoundException e) {
                fileCallback = null;
                callback.onReceiveValue(null);
                toast(R.string.no_app);
            }
            return true;
        }

        @Override
        public boolean onJsAlert(WebView view, String url, String message, final JsResult result) {
            if (isFinishing()) return false;
            new AlertDialog.Builder(MainActivity.this, R.style.UbqDialog).setTitle("UBIQUINT").setMessage(message)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { result.confirm(); }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override public void onCancel(DialogInterface d) { result.cancel(); }
                })
                .show();
            return true;
        }

        @Override
        public boolean onJsConfirm(WebView view, String url, String message, final JsResult result) {
            if (isFinishing()) return false;
            new AlertDialog.Builder(MainActivity.this, R.style.UbqDialog).setTitle("UBIQUINT").setMessage(message)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { result.confirm(); }
                })
                .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { result.cancel(); }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override public void onCancel(DialogInterface d) { result.cancel(); }
                })
                .show();
            return true;
        }

        @Override
        public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, final JsPromptResult result) {
            if (isFinishing()) return false;
            final EditText input = new EditText(MainActivity.this);
            input.setText(defaultValue == null ? "" : defaultValue);
            input.setSingleLine(true);
            new AlertDialog.Builder(MainActivity.this, R.style.UbqDialog).setTitle("UBIQUINT").setMessage(message).setView(input)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { result.confirm(input.getText().toString()); }
                })
                .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { result.cancel(); }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override public void onCancel(DialogInterface d) { result.cancel(); }
                })
                .show();
            return true;
        }
    }

    /** Finestra nuova (target=_blank, window.open sfuggito allo script): si prende l'indirizzo e si apre nel browser. */
    private final class PopupCatcher extends WebViewClient {
        private final WebView child;
        private boolean done;

        PopupCatcher(WebView child) { this.child = child; }

        private void take(String url) {
            if (done || url == null || url.startsWith("about:")) return;
            done = true;
            openExternal(url, true);
            ui.post(new Runnable() {
                @Override public void run() { child.stopLoading(); child.destroy(); }
            });
        }

        void finish() {
            if (done) return;
            done = true;
            child.destroy();
        }

        @SuppressWarnings("unused")
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            take(request.getUrl().toString());
            return true;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            take(url);
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            take(url);
        }
    }

    // ------------------------------------------------------------------ ponte JavaScript

    /** Pubblica: i metodi @JavascriptInterface sono l'unica superficie visibile alla pagina. */
    public final class Bridge {
        @JavascriptInterface
        public String version() { return Config.VERSION; }

        @JavascriptInterface
        public void startGoogleLogin() {
            if (!appPageActive) return;
            ui.post(new Runnable() {
                @Override public void run() { openLogin(); }
            });
        }

        @JavascriptInterface
        public void openExternal(final String url) {
            if (!appPageActive) return;
            ui.post(new Runnable() {
                @Override public void run() { MainActivity.this.openExternal(url, true); }
            });
        }

        @JavascriptInterface
        public void print(final String title) {
            if (!appPageActive) return;
            ui.post(new Runnable() {
                @Override public void run() { printPage(title); }
            });
        }

        @JavascriptInterface
        public void saveFile(String name, String mime, String base64) {
            if (!appPageActive || base64 == null) return;
            if ((long) base64.length() * 3L / 4L > Policy.MAX_FILE_BYTES) {
                ui.post(new Runnable() {
                    @Override public void run() { toast(R.string.file_too_big); }
                });
                return;
            }
            final byte[] data;
            try {
                data = Base64.decode(base64, Base64.DEFAULT);
            } catch (IllegalArgumentException e) {
                ui.post(new Runnable() {
                    @Override public void run() { toast(R.string.save_failed); }
                });
                return;
            }
            final String m = Policy.cleanMime(mime);
            final String n = Policy.fileName(name, m);
            ui.post(new Runnable() {
                @Override public void run() { saveBytes(n, m, data); }
            });
        }
    }

    // ------------------------------------------------------------------ link esterni, stampa

    /** customTab=true: scheda personalizzata del browser (si torna all'app con indietro), se il browser la supporta. */
    private void openExternal(String url, boolean customTab) {
        Policy.Kind k = Policy.kind(url);
        Intent i;
        if (k == Policy.Kind.WEB || k == Policy.Kind.APP) {
            i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            if (customTab) {
                Bundle extras = new Bundle();
                extras.putBinder("android.support.customtabs.extra.SESSION", null);
                i.putExtras(extras);
                i.putExtra("android.support.customtabs.extra.TOOLBAR_COLOR", BG);
            }
        } else if (k == Policy.Kind.MAIL) {
            i = new Intent(Intent.ACTION_SENDTO, Uri.parse(url));
        } else if (k == Policy.Kind.TEL) {
            i = new Intent(Intent.ACTION_DIAL, Uri.parse(url));
        } else {
            return;
        }
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            toast(R.string.no_app);
        }
    }

    private void printPage(String title) {
        PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
        if (pm == null) return;
        String job = title == null || title.trim().isEmpty() ? getString(R.string.print_job) : title.trim();
        pm.print(job, web.createPrintDocumentAdapter(job), new PrintAttributes.Builder().build());
    }

    // ------------------------------------------------------------------ accesso con Google

    private void openLogin() {
        String state = Policy.newState(random);
        prefs.edit().putString(PREF_STATE, state).putLong(PREF_STATE_T, System.currentTimeMillis()).apply();
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(Config.LOGIN_URL + "#s=" + state));
        i.addCategory(Intent.CATEGORY_BROWSABLE);
        try {
            startActivity(i);
            toast(R.string.login_in_browser);
        } catch (ActivityNotFoundException e) {
            info(getString(R.string.no_browser));
        }
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String data = intent.getDataString();
        if (!Policy.isLoginReturn(data)) return;
        setIntent(new Intent(Intent.ACTION_MAIN));         // non rielaborare lo stesso ritorno se l'attività viene ricreata
        Map<String, String> q = Policy.query(data);
        String expected = prefs.getString(PREF_STATE, null);
        long issued = prefs.getLong(PREF_STATE_T, 0L);
        prefs.edit().remove(PREF_STATE).remove(PREF_STATE_T).apply();   // monouso
        String id = q.get("id"), at = q.get("at");
        boolean idOk = Policy.jwtLike(id), atOk = Policy.accessTokenLike(at);
        if (!Policy.stateOk(expected, issued, q.get("s"), System.currentTimeMillis()) || (!idOk && !atOk)) {
            info(getString(R.string.login_invalid));
            return;
        }
        pendingIdToken = idOk ? id : "";
        pendingAccessToken = atOk ? at : "";
        deliverLogin();
    }

    private void deliverLogin() {
        if (pendingIdToken == null || !pageReady || !appPageActive) return;
        String js = Policy.loginCall(pendingIdToken, pendingAccessToken);
        pendingIdToken = null;
        pendingAccessToken = null;
        web.evaluateJavascript(js, null);
    }

    // ------------------------------------------------------------------ file

    private void saveBytes(final String name, final String mime, final byte[] data) {
        if (Build.VERSION.SDK_INT >= 29) {
            new Thread(new Runnable() {
                @Override public void run() {
                    final Uri uri = saveToDownloads(name, mime, data);
                    ui.post(new Runnable() {
                        @Override public void run() {
                            if (uri == null) toast(R.string.save_failed);
                            else savedDialog(uri, mime, getString(R.string.saved_downloads, name));
                        }
                    });
                }
            }).start();
        } else {                                               // Android 7-9: l'utente sceglie dove salvare, senza permessi di archiviazione
            pendingBytes = data;
            pendingName = name;
            pendingMime = mime;
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType(mime);
            i.putExtra(Intent.EXTRA_TITLE, name);
            try {
                startActivityForResult(i, REQ_SAVE);
            } catch (ActivityNotFoundException e) {
                pendingBytes = null;
                toast(R.string.save_failed);
            }
        }
    }

    /** Android 10+: MediaStore.Downloads (costanti lette per riflessione: l'SDK 23 non le ha). */
    private Uri saveToDownloads(String name, String mime, byte[] data) {
        ContentResolver cr = getContentResolver();
        Uri collection;
        try {
            collection = (Uri) Class.forName("android.provider.MediaStore$Downloads").getField("EXTERNAL_CONTENT_URI").get(null);
        } catch (Exception e) {
            collection = Uri.parse("content://media/external/downloads");
        }
        ContentValues v = new ContentValues();
        v.put("_display_name", name);
        v.put("mime_type", mime);
        v.put("relative_path", "Download/UBIQUINT");
        v.put("is_pending", 1);
        Uri uri = null;
        try {
            uri = cr.insert(collection, v);
            if (uri == null) return null;
            OutputStream os = cr.openOutputStream(uri);
            try {
                os.write(data);
            } finally {
                os.close();
            }
            ContentValues done = new ContentValues();
            done.put("is_pending", 0);
            cr.update(uri, done, null, null);
            return uri;
        } catch (Exception e) {
            if (uri != null) {
                try { cr.delete(uri, null, null); } catch (Exception ignored) { }
            }
            return null;
        }
    }

    private void writeChosen(final Uri uri, final String name, final String mime, final byte[] data) {
        new Thread(new Runnable() {
            @Override public void run() {
                boolean ok;
                try {
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    try {
                        os.write(data);
                    } finally {
                        os.close();
                    }
                    ok = true;
                } catch (Exception e) {
                    ok = false;
                }
                final boolean fine = ok;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (fine) savedDialog(uri, mime, name);
                        else toast(R.string.save_failed);
                    }
                });
            }
        }).start();
    }

    private void savedDialog(final Uri uri, final String mime, String where) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this, R.style.UbqDialog).setTitle(R.string.saved_title).setMessage(where)
            .setPositiveButton(R.string.open, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(uri, mime);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try { startActivity(i); } catch (ActivityNotFoundException e) { toast(R.string.no_app); }
                }
            })
            .setNeutralButton(R.string.share, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType(mime);
                    i.putExtra(Intent.EXTRA_STREAM, uri);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try { startActivity(Intent.createChooser(i, null)); } catch (ActivityNotFoundException e) { toast(R.string.no_app); }
                }
            })
            .setNegativeButton(R.string.close, null)
            .show();
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        if (request == REQ_FILE) {
            ValueCallback<Uri[]> cb = fileCallback;
            fileCallback = null;
            if (cb != null) cb.onReceiveValue(result == RESULT_OK ? pickedUris(data) : null);
            return;
        }
        if (request == REQ_SAVE) {
            byte[] bytes = pendingBytes;
            String name = pendingName, mime = pendingMime;
            pendingBytes = null;
            if (result == RESULT_OK && data != null && data.getData() != null && bytes != null) writeChosen(data.getData(), name, mime, bytes);
            return;
        }
        super.onActivityResult(request, result, data);
    }

    private static Uri[] pickedUris(Intent data) {
        if (data == null) return null;
        List<Uri> out = new ArrayList<Uri>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri u = clip.getItemAt(i).getUri();
                if (u != null) out.add(u);
            }
        }
        if (out.isEmpty() && data.getData() != null) out.add(data.getData());
        return out.isEmpty() ? null : out.toArray(new Uri[0]);
    }

    // ------------------------------------------------------------------ ciclo di vita

    @Override
    public void onBackPressed() {
        if (offline.getVisibility() == View.VISIBLE && everLoadedApp && web.canGoBack()) {
            offline.setVisibility(View.GONE);
            web.goBack();
            return;
        }
        if (web.canGoBack()) {
            web.goBack();
            return;
        }
        moveTaskToBack(true);     // come Android 12+: l'app resta aperta in background, la ricerca in corso non si perde
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onDestroy() {
        if (fileCallback != null) {
            fileCallback.onReceiveValue(null);
            fileCallback = null;
        }
        ViewGroup parent = (ViewGroup) web.getParent();
        if (parent != null) parent.removeView(web);
        web.destroy();
        super.onDestroy();
    }
}
