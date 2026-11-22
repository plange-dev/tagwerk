package de.plangedev.tagwerk;

import android.app.AlarmManager;
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
