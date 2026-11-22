package de.plangedev.tagwerk;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Startbildschirm-Widget: zeigt die Termine von heute (bzw. den nächsten Termin).
 * Die Daten schreibt die App über TagwerkWidgetPlugin; das Widget filtert selbst
 * nach dem aktuellen Tag, damit es auch nach Mitternacht stimmt.
 */
public class TagwerkWidget extends AppWidgetProvider {

    static final String PREFS = "tagwerk_widget";
    static final String KEY_DATA = "data";
    static final String KEY_UPDATED = "updated";

    private static final int[] ROWS = {R.id.row1, R.id.row2, R.id.row3, R.id.row4};
    private static final int[] DOTS = {R.id.dot1, R.id.dot2, R.id.dot3, R.id.dot4};
    private static final int[] TIMES = {R.id.time1, R.id.time2, R.id.time3, R.id.time4};
    private static final int[] TITLES = {R.id.title1, R.id.title2, R.id.title3, R.id.title4};

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) render(context, manager, id);
    }

    static void updateAll(Context context) {
        AppWidgetManager m = AppWidgetManager.getInstance(context);
        int[] ids = m.getAppWidgetIds(new ComponentName(context, TagwerkWidget.class));
        for (int id : ids) render(context, m, id);
    }

    private static final class Item {
        long s, e;
        boolean allDay;
        String title;
        int color;
    }

    private static List<Item> load(Context context) {
        List<Item> out = new ArrayList<>();
        String json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DATA, "[]");
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Item it = new Item();
                it.s = o.optLong("s");
                it.e = o.optLong("e");
                it.allDay = o.optInt("a", 0) == 1;
                it.title = o.optString("t", "");
                try { it.color = Color.parseColor(o.optString("c", "#8A7B6A")); }
                catch (Exception ex) { it.color = Color.parseColor("#8A7B6A"); }
                out.add(it);
            }
        } catch (Exception ignored) { }
        return out;
    }

    static void render(Context context, AppWidgetManager manager, int widgetId) {
        RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.tagwerk_widget);

        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        long dayStart = c.getTimeInMillis();
        c.add(Calendar.DAY_OF_MONTH, 1);
        long dayEnd = c.getTimeInMillis();
        long now = System.currentTimeMillis();

        Locale de = Locale.GERMANY;
        v.setTextViewText(R.id.header, new SimpleDateFormat("EEEE, d. MMMM", de).format(new Date(now)));

        List<Item> all = load(context);
        List<Item> today = new ArrayList<>();
        Item next = null;
        for (Item it : all) {
            if (it.s < dayEnd && it.e > dayStart) today.add(it);
            else if (it.s >= dayEnd && next == null) next = it;
        }

        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", de);
        for (int i = 0; i < ROWS.length; i++) {
            if (i < today.size()) {
                Item it = today.get(i);
                String t = it.allDay ? "ganztägig" : (it.s < dayStart ? "läuft" : hm.format(new Date(it.s)));
                v.setViewVisibility(ROWS[i], View.VISIBLE);
                v.setTextViewText(TIMES[i], t);
                v.setTextViewText(TITLES[i], it.title);
                v.setTextColor(DOTS[i], it.color);
                boolean past = !it.allDay && it.e <= now;
                v.setTextColor(TITLES[i], past ? Color.parseColor("#A89F93") : Color.parseColor("#221C16"));
                v.setTextColor(TIMES[i], past ? Color.parseColor("#B8B0A5") : Color.parseColor("#7D7366"));
            } else {
                v.setViewVisibility(ROWS[i], View.GONE);
            }
        }

        if (today.isEmpty()) {
            v.setViewVisibility(R.id.empty, View.VISIBLE);
            v.setTextViewText(R.id.empty, "Heute keine Termine");
        } else {
            v.setViewVisibility(R.id.empty, View.GONE);
        }

        if (today.size() > ROWS.length) {
            v.setViewVisibility(R.id.more, View.VISIBLE);
            v.setTextViewText(R.id.more, "+" + (today.size() - ROWS.length) + " weitere");
        } else if (today.isEmpty() && next != null) {
            v.setViewVisibility(R.id.more, View.VISIBLE);
            String when = new SimpleDateFormat("EE d.M.", de).format(new Date(next.s))
                    + (next.allDay ? "" : " " + hm.format(new Date(next.s)));
            v.setTextViewText(R.id.more, "Nächster: " + when + " · " + next.title);
        } else {
            v.setViewVisibility(R.id.more, View.GONE);
        }

        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        v.setOnClickPendingIntent(R.id.widget_root, pi);

        manager.updateAppWidget(widgetId, v);
    }
}
