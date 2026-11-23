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
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            ServiceConnection conn = new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName n, IBinder b) {
                    try { new Dolby(b).applyAll(app); }
                    catch (Throwable t) { Log.e(Dolby.TAG, "boot apply failed", t); }
                    app.unbindService(this);
                    pr.finish();
                }
                @Override public void onServiceDisconnected(ComponentName n) { }
            };
            if (!app.bindService(Dolby.intent(), conn, Context.BIND_AUTO_CREATE)) {
                Log.e(Dolby.TAG, "boot bind failed");
                pr.finish();
            }
        }, 20000);
    }
}
