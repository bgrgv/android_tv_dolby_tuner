package ai.brainback.dolbytuner;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.audiofx.DynamicsProcessing;
import android.os.IBinder;
import android.util.Log;

/**
 * Holds a global (session 0) DynamicsProcessing EQ + limiter on the output mix, so it
 * applies to YouTube and every other app. Must stay alive to keep the effect, hence a
 * foreground service. Start with EXTRA_PROFILE = profile id, or "off".
 */
public class EqService extends Service {
    static final String EXTRA_PROFILE = "profile";
    static final String STATE = "state";
    static final String KEY_ACTIVE = "active";
    private static final String CH = "eq";

    static volatile String status = "EQ not running";
    private DynamicsProcessing dp;
    /** Frame override for custom (adb) curves; profiles carry their own frameMs. */
    static float frameMs = 40f;

    static void start(Context c, String profileId) {
        c.startForegroundService(new Intent(c, EqService.class).putExtra(EXTRA_PROFILE, profileId));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String id = intent != null ? intent.getStringExtra(EXTRA_PROFILE) : null;
        if (intent != null && intent.hasExtra("frame")) frameMs = intent.getFloatExtra("frame", frameMs);
        if (id == null) id = getSharedPreferences(STATE, MODE_PRIVATE).getString(KEY_ACTIVE, "off");
        Profiles.Profile p = Profiles.byId(id);
        if ("custom".equals(id) && intent != null && intent.hasExtra("gains")) {
            // Measurement / tuning hook (adb via CtrlReceiver): not saved, not restored at boot.
            float[] g = intent.getFloatArrayExtra("gains");
            float[] e = intent.getFloatArrayExtra("edges");
            p = new Profiles.Profile("custom", "Custom", "", new String[0],
                    e != null ? e : Profiles.EDGES, g, intent.getFloatExtra("input", 0f), frameMs,
                    intent.getBooleanExtra("limiter", true));
        }
        startForeground(1, notification(p == null ? "EQ off" : "EQ: " + p.label));
        if (p == null) {
            release();
            status = "EQ off";
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            apply(p);
            status = "EQ on: " + p.label;
        } catch (Throwable t) {
            status = "EQ failed: " + t;
            Log.e(Dolby.TAG, "EQ apply failed", t);
        }
        Log.i(Dolby.TAG, status);
        return START_STICKY;
    }

    private float curFrame = -1;
    private int curBands = -1;

    /**
     * Switching profiles must never leave a gap: deleting the effect and creating a new one let the
     * raw TV sound (+29 dB at 125 Hz) through for a moment, heard as a bass burst on every button
     * press followed by a "drop". Same frame + band count: update the live effect in place.
     * Otherwise build the new effect fully, enable it, and only then release the old one.
     */
    private void apply(Profiles.Profile p) {
        int n = p.edges.length;
        if (dp != null && curFrame == p.frameMs && curBands == n) {
            configure(dp, p);
            return;
        }
        DynamicsProcessing.Config cfg = new DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                true, n,      // pre-EQ
                false, 0,     // multiband compressor
                false, 0,     // post-EQ
                true)         // limiter
                // 10 ms frames give ~100 Hz FFT bins: too coarse to shape bass bands.
                .setPreferredFrameDuration(p.frameMs)
                .build();
        DynamicsProcessing next = new DynamicsProcessing(0, 0, cfg);
        configure(next, p);
        next.setEnabled(true);
        release();
        dp = next;
        curFrame = p.frameMs;
        curBands = n;
    }

    private static void configure(DynamicsProcessing d, Profiles.Profile p) {
        int n = p.edges.length;
        DynamicsProcessing.Eq eq = new DynamicsProcessing.Eq(true, true, n);
        for (int i = 0; i < n; i++) {
            eq.getBand(i).setCutoffFrequency(p.edges[i]);
            eq.getBand(i).setGain(p.eq[i]);
        }
        d.setPreEqAllChannelsTo(eq);
        d.setInputGainAllChannelsTo(p.inputGainDb);
        // Brick-wall-ish limiter: keeps bass boosts from clipping into the TV amp's own limiter.
        // Off only for profiles that ask for no safety net (Thunder).
        d.setLimiterAllChannelsTo(new DynamicsProcessing.Limiter(true, p.limiter, 0,
                1f /*attack ms*/, 60f /*release ms*/, 10f /*ratio*/, -1f /*threshold dB*/, 0f /*post gain*/));
    }

    private void release() {
        if (dp != null) {
            try { dp.setEnabled(false); dp.release(); } catch (Throwable ignored) { }
            dp = null;
            curFrame = -1;
            curBands = -1;
        }
    }

    private Notification notification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH, "Dolby Tuner EQ", NotificationManager.IMPORTANCE_MIN));
        return new Notification.Builder(this, CH)
                .setContentTitle("Dolby Tuner")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        release();
        status = "EQ not running";
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) { return null; }
}
