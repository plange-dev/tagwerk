package de.plangedev.tagwerk;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * HTTP für CalDAV (PROPFIND, REPORT, PUT, DELETE …).
 * Androids HttpURLConnection kennt keine WebDAV-Methoden, deshalb OkHttp.
 * Läuft nativ – keine CORS-Einschränkungen wie im Browser.
 *
 * Selbst ausgestellte Zertifikate (z. B. Nextcloud über https://IP):
 * Schlägt die Prüfung fehl, liefert das Plugin den Fingerabdruck des Zertifikats
 * (Fehlercode CERT_UNTRUSTED). Bestätigt der Nutzer ihn, wird genau dieses
 * Zertifikat per "pin" (SHA-256) akzeptiert – alle anderen weiterhin nicht.
 */
@CapacitorPlugin(name = "DavHttp")
public class DavHttpPlugin extends Plugin {

    private final OkHttpClient baseClient = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build();

    private final ConcurrentHashMap<String, OkHttpClient> pinnedClients = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    @PluginMethod
    public void request(PluginCall call) {
        final String url = call.getString("url");
        final String method = call.getString("method", "GET").toUpperCase(Locale.ROOT);
        final String data = call.getString("data", "");
        final String pin = normalizePin(call.getString("pin", null));
        final JSObject headers = call.getObject("headers", new JSObject());

        if (url == null || url.isEmpty()) {
            call.reject("Keine URL angegeben");
            return;
        }

        executor.execute(() -> {
            try {
                Request.Builder rb = new Request.Builder().url(url);
                String contentType = null;
                Iterator<String> keys = headers.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    String v = headers.getString(k);
                    if (v == null) continue;
                    if (k.equalsIgnoreCase("content-type")) contentType = v;
                    rb.header(k, v);
                }

                RequestBody body = null;
                boolean hasData = data != null && !data.isEmpty();
                boolean needsBody = method.equals("POST") || method.equals("PUT") || method.equals("PATCH")
                        || method.equals("PROPPATCH") || method.equals("REPORT");
                if (hasData || needsBody) {
                    MediaType mt = contentType != null ? MediaType.parse(contentType) : null;
                    byte[] bytes = hasData ? data.getBytes(StandardCharsets.UTF_8) : new byte[0];
                    body = RequestBody.create(bytes, mt);
                }
                rb.method(method, body);

                OkHttpClient client = pin != null ? pinnedClient(pin) : baseClient;
                try (Response resp = client.newCall(rb.build()).execute()) {
                    JSObject ret = new JSObject();
                    ret.put("status", resp.code());
                    ret.put("url", resp.request().url().toString());
                    JSObject h = new JSObject();
                    for (String n : resp.headers().names()) {
                        h.put(n.toLowerCase(Locale.ROOT), resp.header(n));
                    }
                    ret.put("headers", h);
                    ResponseBody rbody = resp.body();
                    ret.put("data", rbody != null ? rbody.string() : "");
                    call.resolve(ret);
                }
            } catch (SSLHandshakeException | SSLPeerUnverifiedException e) {
                rejectWithCert(call, url, e);
            } catch (Exception e) {
                if (isCertProblem(e)) {
                    rejectWithCert(call, url, e);
                    return;
                }
                String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                call.reject(msg, e);
            }
        });
    }

    private static boolean isCertProblem(Throwable e) {
        while (e != null) {
            if (e instanceof CertificateException || e instanceof SSLHandshakeException
                    || e instanceof SSLPeerUnverifiedException) return true;
            e = e.getCause();
        }
        return false;
    }

    /** Liest das Zertifikat des Servers (ohne Daten zu senden) und meldet es an die App. */
    private void rejectWithCert(PluginCall call, String url, Exception cause) {
        try {
            X509Certificate cert = fetchLeafCertificate(url);
            JSObject info = new JSObject();
            info.put("fingerprint", fingerprint(cert));
            info.put("subject", cert.getSubjectX500Principal().getName());
            info.put("issuer", cert.getIssuerX500Principal().getName());
            info.put("validTo", new SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(cert.getNotAfter()));
            info.put("selfSigned", cert.getSubjectX500Principal().equals(cert.getIssuerX500Principal()));
            info.put("reason", cause.getMessage() != null ? cause.getMessage() : cause.toString());
            call.reject("Das Zertifikat des Servers ist nicht vertrauenswürdig.", "CERT_UNTRUSTED", cause, info);
        } catch (Exception e) {
            String msg = cause.getMessage() != null ? cause.getMessage() : cause.toString();
            call.reject("Sichere Verbindung fehlgeschlagen: " + msg, "SSL_ERROR", cause);
        }
    }

    private static X509Certificate fetchLeafCertificate(String url) throws Exception {
        URL u = new URL(url);
        int port = u.getPort() > 0 ? u.getPort() : 443;
        final X509Certificate[][] seen = new X509Certificate[1][];
        TrustManager[] capture = new TrustManager[]{new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            public void checkServerTrusted(X509Certificate[] chain, String authType) { seen[0] = chain; }
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }};
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, capture, new SecureRandom());
        try (SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket(u.getHost(), port)) {
            s.setSoTimeout(15000);
            s.startHandshake();
            Certificate[] chain = s.getSession().getPeerCertificates();
            if (chain.length > 0 && chain[0] instanceof X509Certificate) return (X509Certificate) chain[0];
        }
        if (seen[0] != null && seen[0].length > 0) return seen[0][0];
        throw new Exception("Kein Zertifikat erhalten");
    }

    private OkHttpClient pinnedClient(String pin) throws Exception {
        OkHttpClient c = pinnedClients.get(pin);
        if (c != null) return c;

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null);
        X509TrustManager sys = null;
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager) { sys = (X509TrustManager) tm; break; }
        }
        final X509TrustManager system = sys;
        X509TrustManager pinned = new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                if (system != null) system.checkClientTrusted(chain, authType);
            }
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                if (chain != null && chain.length > 0 && pin.equals(fingerprint(chain[0]))) return;
                if (system == null) throw new CertificateException("Keine Vertrauensprüfung möglich");
                system.checkServerTrusted(chain, authType);
            }
            public X509Certificate[] getAcceptedIssuers() {
                return system != null ? system.getAcceptedIssuers() : new X509Certificate[0];
            }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{pinned}, new SecureRandom());
        final HostnameVerifier def = HttpsURLConnection.getDefaultHostnameVerifier();
        HostnameVerifier hv = (String host, SSLSession session) -> {
            try {
                Certificate[] peer = session.getPeerCertificates();
                if (peer.length > 0 && peer[0] instanceof X509Certificate
                        && pin.equals(fingerprint((X509Certificate) peer[0]))) return true;
            } catch (Exception ignored) { }
            return def.verify(host, session);
        };
        c = baseClient.newBuilder()
                .sslSocketFactory(ctx.getSocketFactory(), pinned)
                .hostnameVerifier(hv)
                .build();
        pinnedClients.put(pin, c);
        return c;
    }

    private static String normalizePin(String p) {
        if (p == null) return null;
        String x = p.replace(":", "").replace(" ", "").toUpperCase(Locale.ROOT);
        return x.isEmpty() ? null : formatHex(x);
    }

    private static String formatHex(String hex) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hex.length(); i += 2) {
            if (i > 0) sb.append(':');
            sb.append(hex, i, Math.min(i + 2, hex.length()));
        }
        return sb.toString();
    }

    static String fingerprint(X509Certificate cert) throws CertificateException {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < d.length; i++) {
                if (i > 0) sb.append(':');
                sb.append(String.format(Locale.ROOT, "%02X", d[i]));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new CertificateException(e);
        }
    }
}
