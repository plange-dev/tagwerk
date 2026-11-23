package de.plangedev.tagwerk;

import android.app.AlarmManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.app.NotificationManagerCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/** Widget-Daten und Hilfen rund um Benachrichtigungen. */
@CapacitorPlugin(name = "TagwerkWidget")
public class TagwerkWidgetPlugin extends Plugin {

    @PluginMethod
    public void update(PluginCall call) {
        String data = call.getString("data", "[]");
        Context ctx = getContext();
        ctx.getSharedPreferences(TagwerkWidget.PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(TagwerkWidget.KEY_DATA, data)
                .putLong(TagwerkWidget.KEY_UPDATED, System.currentTimeMillis())
                .apply();
        TagwerkWidget.updateAll(ctx);
        call.resolve();
    }

    /** Zustand der Benachrichtigungen direkt vom System (zur Fehlersuche). */
    @PluginMethod
    public void notifStatus(PluginCall call) {
        Context ctx = getContext();
        JSObject ret = new JSObject();
        ret.put("enabled", NotificationManagerCompat.from(ctx).areNotificationsEnabled());
        boolean exact = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            exact = am != null && am.canScheduleExactAlarms();
        }
        ret.put("exact", exact);
        JSArray channels = new JSArray();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                for (NotificationChannel ch : nm.getNotificationChannels()) {
                    JSObject c = new JSObject();
                    c.put("id", ch.getId());
                    c.put("name", String.valueOf(ch.getName()));
                    c.put("importance", ch.getImportance());
                    channels.put(c);
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    ret.put("dnd", nm.getCurrentInterruptionFilter() != NotificationManager.INTERRUPTION_FILTER_ALL);
                }
            }
        }
        ret.put("channels", channels);
        ret.put("sdk", Build.VERSION.SDK_INT);
        ret.put("model", Build.MANUFACTURER + " " + Build.MODEL);
        call.resolve(ret);
    }

    /** Öffnet die Benachrichtigungs-Einstellungen von Tagwerk. */
    @PluginMethod
    public void openNotifSettings(PluginCall call) {
        Context ctx = getContext();
        Intent i;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            i.putExtra(Settings.EXTRA_APP_PACKAGE, ctx.getPackageName());
        } else {
            i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.getPackageName()));
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject("Einstellungen konnten nicht geöffnet werden", e);
        }
    }

    /** Datei über das Android-Teilen-Menü weitergeben (Gmail, Drive, Nextcloud, Dateien …). */
    @PluginMethod
    public void shareFile(PluginCall call) {
        String name = safeName(call.getString("name", "tagwerk.ics"));
        String data = call.getString("data", "");
        String mime = call.getString("mime", "text/calendar");
        try {
            Context ctx = getContext();
            File dir = new File(ctx.getCacheDir(), "export");
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("Ordner konnte nicht angelegt werden");
            File f = new File(dir, name);
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(data.getBytes(StandardCharsets.UTF_8));
            }
            Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", f);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(mime);
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_SUBJECT, name);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(send, "Kalender exportieren");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ctx.startActivity(chooser);
            call.resolve();
        } catch (Exception e) {
            call.reject("Teilen nicht möglich: " + e.getMessage(), e);
        }
    }

    /** Datei im Ordner „Download/Tagwerk“ speichern (ab Android 10 ohne Berechtigung). */
    @PluginMethod
    public void saveDownload(PluginCall call) {
        String name = safeName(call.getString("name", "tagwerk.ics"));
        String data = call.getString("data", "");
        String mime = call.getString("mime", "text/calendar");
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            call.reject("Speichern in Downloads braucht Android 10 oder neuer – bitte „Teilen“ verwenden.");
            return;
        }
        try {
            ContentResolver cr = getContext().getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            v.put(MediaStore.Downloads.MIME_TYPE, mime);
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Tagwerk");
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new Exception("Datei konnte nicht angelegt werden");
            try (OutputStream out = cr.openOutputStream(uri)) {
                if (out == null) throw new Exception("Datei konnte nicht geöffnet werden");
                out.write(data.getBytes(StandardCharsets.UTF_8));
            }
            JSObject ret = new JSObject();
            ret.put("path", "Download/Tagwerk/" + name);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Speichern nicht möglich: " + e.getMessage(), e);
        }
    }

    private static String safeName(String n) {
        String x = n.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return x.isEmpty() ? "tagwerk.ics" : x;
    }

    /** Öffnet die App-Info (Akku-Einstellungen usw.). */
    @PluginMethod
    public void openAppSettings(PluginCall call) {
        Context ctx = getContext();
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.getPackageName()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject("Einstellungen konnten nicht geöffnet werden", e);
        }
    }
}
