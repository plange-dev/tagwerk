package de.plangedev.tagwerk;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
 */
@CapacitorPlugin(name = "DavHttp")
public class DavHttpPlugin extends Plugin {

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build();

    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    @PluginMethod
    public void request(PluginCall call) {
        final String url = call.getString("url");
        final String method = call.getString("method", "GET").toUpperCase(Locale.ROOT);
        final String data = call.getString("data", "");
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
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                call.reject(msg, e);
            }
        });
    }
}
