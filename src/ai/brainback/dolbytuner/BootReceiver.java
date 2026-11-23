package ai.brainback.dolbytuner;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

/** Re-applies saved Dolby values after boot (delayed so the TV's own setup runs first). */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        final Context app = ctx.getApplicationContext();
        String active = app.getSharedPreferences(EqService.STATE, Context.MODE_PRIVATE)
                .getString(EqService.KEY_ACTIVE, "off");
        if (Profiles.byId(active) != null) EqService.start(app, active);
        if (app.getSharedPreferences(Dolby.PREFS, Context.MODE_PRIVATE).getAll().isEmpty()) return;
        final PendingResult pr = goAsync();
        new Handler(Looper.getMainLooper()).postDelayed(() ->
                DolbyClient.run(app, d -> { try { d.applyAll(app); } finally { pr.finish(); } }), 20000);
    }
}
