package de.plangedev.tagwerk;

import android.content.Context;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/** Übergibt die anstehenden Termine an das Startbildschirm-Widget. */
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
}
