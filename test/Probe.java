package it.ubiquint.app;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.Map;

/** Ponte per le prove nel browser: fa produrre/leggere a Policy (codice vero dell'app) ciò che il test controlla in Chromium. */
public final class Probe {
    public static void main(String[] a) throws Exception {
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8");
        if (a[0].equals("js")) {
            for (int i = 1; i < a.length; i++) out.println(Policy.jsString(a[i]));
        } else if (a[0].equals("login")) {
            Map<String, String> q = Policy.query(a[1]);
            out.println(Policy.isLoginReturn(a[1]));
            out.println(q.get("s"));
            out.println(q.get("id"));
            out.println(q.get("at"));
            out.println(Policy.jwtLike(q.get("id")));
            out.println(Policy.accessTokenLike(q.get("at")));
        } else if (a[0].equals("deliver")) {
            out.println(Policy.loginCall(a[1], a[2]));
        } else if (a[0].equals("saveurl")) {
            out.println(Policy.saveUrlCall(a[1], a[2]));
        } else if (a[0].equals("app")) {
            for (int i = 1; i < a.length; i++) out.println(Policy.kind(a[i]));
        }
    }
}
