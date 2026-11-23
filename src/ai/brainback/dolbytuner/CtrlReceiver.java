package ai.brainback.dolbytuner;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.IBinder;
import android.util.Log;

import java.util.Random;

/**
 * adb control surface for measuring and tuning (protected by DUMP, which only the shell holds).
 *
 *   am broadcast -n ai.brainback.dolbytuner/.CtrlReceiver --es cmd tone --es kind sweep --ef secs 10 --ef level -12
 *   am broadcast -n ai.brainback.dolbytuner/.CtrlReceiver --es cmd dolby --es pairs "VirtualizerMode=e:DISABLE;DialogueEnhancerStrength=i:0"
 *   am broadcast -n ai.brainback.dolbytuner/.CtrlReceiver --es cmd eq --es id custom --efa edges 40,80 --efa gains 0,0 --ef input 0
 *   am broadcast -n ai.brainback.dolbytuner/.CtrlReceiver --es cmd profile --es id music
 */
public class CtrlReceiver extends BroadcastReceiver {
    static final int SR = 48000;

    @Override
    public void onReceive(Context ctx, Intent in) {
        final Context app = ctx.getApplicationContext();
        String cmd = in.getStringExtra("cmd");
        if (cmd == null) return;
        final PendingResult pr = goAsync();
        switch (cmd) {
            case "tone": {
                String kind = str(in, "kind", "sweep");
                float secs = in.getFloatExtra("secs", 10f), level = in.getFloatExtra("level", -12f);
                float f1 = in.getFloatExtra("f1", 20f), f2 = in.getFloatExtra("f2", 20000f);
                // Finish the broadcast at once: manifest broadcasts are delivered one at a time,
                // so holding it would block profile switches sent during playback.
                new Thread(() -> {
                    try { play(kind, secs, level, f1, f2); }
                    catch (Throwable t) { Log.e(Dolby.TAG, "tone failed", t); }
                }).start();
                pr.finish();
                break;
            }
            case "dolby":
                withDolby(app, d -> {
                    StringBuilder sb = new StringBuilder("dolby:");
                    for (String kv : str(in, "pairs", "").split(";")) {
                        if (!kv.contains("=")) continue;
                        String k = kv.substring(0, kv.indexOf('=')), v = kv.substring(kv.indexOf('=') + 1);
                        try { sb.append(' ').append(k).append('=').append(d.apply(k, v)); }
                        catch (Throwable t) { sb.append(' ').append(k).append(" ERR ").append(t.getMessage()); }
                    }
                    Log.i(Dolby.TAG, sb.toString());
                }, pr);
                break;
            case "eq": {
                Intent s = new Intent(app, EqService.class).putExtra(EqService.EXTRA_PROFILE, str(in, "id", "off"));
                if (in.hasExtra("gains")) {
                    s.putExtra("gains", in.getFloatArrayExtra("gains"));
                    s.putExtra("edges", in.getFloatArrayExtra("edges"));
                    s.putExtra("input", in.getFloatExtra("input", 0f));
                }
                if (in.hasExtra("frame")) s.putExtra("frame", in.getFloatExtra("frame", 40f));
                s.putExtra("limiter", in.getBooleanExtra("limiter", true));
                app.startForegroundService(s);
                Log.i(Dolby.TAG, "eq -> " + str(in, "id", "off"));
                pr.finish();
                break;
            }
            case "restore": { // restart the EQ for the saved active profile (e.g. after reinstall)
                String a = app.getSharedPreferences(EqService.STATE, Context.MODE_PRIVATE).getString(EqService.KEY_ACTIVE, "off");
                Profiles.Profile p = Profiles.byId(a);
                if (p != null) applyProfile(app, p, pr); else pr.finish();
                Log.i(Dolby.TAG, "restore -> " + a);
                break;
            }
            case "profile": {
                Profiles.Profile p = Profiles.byId(str(in, "id", ""));
                if (p == null) { Log.e(Dolby.TAG, "no profile"); pr.finish(); break; }
                applyProfile(app, p, pr);
                break;
            }
            default:
                Log.e(Dolby.TAG, "unknown cmd " + cmd);
                pr.finish();
        }
    }

    /** Dolby values + EQ for a profile, saved as active (used by adb and the remote button). */
    static void applyProfile(Context app, Profiles.Profile p, PendingResult pr) {
        app.getSharedPreferences(EqService.STATE, Context.MODE_PRIVATE).edit()
                .putString(EqService.KEY_ACTIVE, p.id).apply();
        EqService.start(app, p.id);
        withDolby(app, d -> {
            SharedPreferences.Editor ed = app.getSharedPreferences(Dolby.PREFS, Context.MODE_PRIVATE).edit();
            for (int i = 0; i + 1 < p.dolby.length; i += 2) {
                try { d.apply(p.dolby[i], p.dolby[i + 1]); ed.putString(p.dolby[i], p.dolby[i + 1]); }
                catch (Throwable t) { Log.e(Dolby.TAG, "profile " + p.dolby[i], t); }
            }
            ed.apply();
            Log.i(Dolby.TAG, "profile -> " + p.id);
        }, pr);
    }

    interface DolbyJob { void run(Dolby d) throws Exception; }

    static void withDolby(Context app, DolbyJob job, PendingResult pr) {
        // One shared registration (see DolbyClient): re-registering resets the Dolby engine.
        DolbyClient.run(app, d -> {
            try { job.run(d); } finally { if (pr != null) pr.finish(); }
        });
    }

    private static String str(Intent i, String k, String def) {
        String v = i.getStringExtra(k);
        return v == null ? def : v;
    }

    /** Plays 0.5 s silence, the signal (both channels identical), 1 s silence. */
    static void play(String kind, float secs, float levelDb, float f1, float f2) {
        int pre = SR / 2, n = Math.round(secs * SR), post = SR;
        float amp = (float) Math.pow(10, levelDb / 20.0);
        float[] sig = new float[pre + n + post];
        if (kind.equals("sweep")) {
            // Exponential (Farina) sine sweep; the Mac side regenerates it for deconvolution.
            double T = secs, L = T / Math.log(f2 / f1);
            int fade = SR / 20;
            for (int i = 0; i < n; i++) {
                double t = (double) i / SR;
                double v = Math.sin(2 * Math.PI * f1 * L * (Math.exp(t / L) - 1));
                double w = i < fade ? (double) i / fade : (i > n - fade ? (double) (n - i) / fade : 1);
                sig[pre + i] = (float) (amp * v * w);
            }
        } else if (kind.equals("pink")) {
            Random r = new Random(1);
            double b0 = 0, b1 = 0, b2 = 0, b3 = 0, b4 = 0, b5 = 0, b6 = 0;
            for (int i = 0; i < n; i++) {
                double w = r.nextGaussian() * 0.25;
                b0 = 0.99886 * b0 + w * 0.0555179; b1 = 0.99332 * b1 + w * 0.0750759;
                b2 = 0.96900 * b2 + w * 0.1538520; b3 = 0.86650 * b3 + w * 0.3104856;
                b4 = 0.55000 * b4 + w * 0.5329522; b5 = -0.7616 * b5 - w * 0.0168980;
                double p = b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362; b6 = w * 0.115926;
                sig[pre + i] = (float) Math.max(-1, Math.min(1, amp * p * 0.5));
            }
        } // "silence": zeros
        float[] st = new float[sig.length * 2];
        for (int i = 0; i < sig.length; i++) { st[2 * i] = sig[i]; st[2 * i + 1] = sig[i]; }
        if (kind.equals("pinkstereo")) {
            // Independent L/R pink noise: exercises the Dolby virtualizer (it works on L-R content).
            Random[] rs = {new Random(1), new Random(2)};
            for (int ch = 0; ch < 2; ch++) {
                double b0 = 0, b1 = 0, b2 = 0, b3 = 0, b4 = 0, b5 = 0, b6 = 0;
                for (int i = 0; i < n; i++) {
                    double w = rs[ch].nextGaussian() * 0.25;
                    b0 = 0.99886 * b0 + w * 0.0555179; b1 = 0.99332 * b1 + w * 0.0750759;
                    b2 = 0.96900 * b2 + w * 0.1538520; b3 = 0.86650 * b3 + w * 0.3104856;
                    b4 = 0.55000 * b4 + w * 0.5329522; b5 = -0.7616 * b5 - w * 0.0168980;
                    double p = b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362; b6 = w * 0.115926;
                    st[2 * (pre + i) + ch] = (float) Math.max(-1, Math.min(1, amp * p * 0.5));
                }
            }
        }
        AudioTrack at = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(SR)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(SR * 2 * 4 / 4)
                .build();
        Log.i(Dolby.TAG, "tone start " + kind + " " + secs + "s " + levelDb + "dBFS");
        at.play();
        int off = 0, chunk = 4800;
        while (off < st.length) {
            int w = at.write(st, off, Math.min(chunk, st.length - off), AudioTrack.WRITE_BLOCKING);
            if (w <= 0) break;
            off += w;
        }
        try { Thread.sleep(600); } catch (InterruptedException ignored) { }
        at.stop();
        at.release();
        Log.i(Dolby.TAG, "tone done " + kind);
    }
}
