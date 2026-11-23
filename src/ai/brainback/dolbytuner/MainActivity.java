package ai.brainback.dolbytuner;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Arrays;

/** D-pad friendly control panel for the TV's Dolby MS12 engine. */
public class MainActivity extends Activity implements ServiceConnection {
    private Dolby dolby;
    private SharedPreferences prefs;
    private LinearLayout root, controls;
    private TextView status, probe;
    private boolean bound;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences(Dolby.PREFS, Context.MODE_PRIVATE);
        ScrollView sv = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(48), dp(24), dp(48), dp(24));
        sv.addView(root);
        setContentView(sv);

        TextView title = text("Dolby Tuner", 28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        status = text("Connecting to Dolby service...", 16);
        root.addView(status);

        LinearLayout actions = row();
        actions.addView(button("Reapply saved", v -> status(dolby == null ? "Not connected" : dolby.applyAll(this))));
        actions.addView(button("Clear saved", v -> {
            prefs.edit().clear().apply();
            status("Saved values cleared. Change sound mode or restart the TV to get stock Dolby settings back.");
        }));
        actions.addView(button("Refresh info", v -> runProbe()));
        root.addView(actions);

        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        root.addView(controls);

        root.addView(text("Engine info", 20));
        probe = text("", 13);
        probe.setTypeface(Typeface.MONOSPACE);
        root.addView(probe);

        // Shared, never-unbound connection: a fresh register() would reset the Dolby engine.
        DolbyClient.run(this, d -> onDolby(d));
    }

    @Override
    public void onServiceConnected(ComponentName n, IBinder b) { }

    private void onDolby(Dolby d) {
        try {
            dolby = d;
            status("Connected. Library loader: " + dolby.cl.getClass().getSimpleName()
                    + ". Saved values: " + prefs.getAll().size());
            buildControls();
            runProbe();
        } catch (Throwable t) {
            Log.e(Dolby.TAG, "connect failed", t);
            status("Connect failed: " + t);
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName n) {
        dolby = null;
        status("Dolby service disconnected");
    }

    // ---- controls ----

    private void buildControls() {
        controls.removeAllViews();
        String active = getSharedPreferences(EqService.STATE, MODE_PRIVATE).getString(EqService.KEY_ACTIVE, "off");
        Profiles.Profile ap = Profiles.byId(active);
        controls.addView(text("YouTube profiles  (active: " + (ap == null ? "none" : ap.label) + ")", 22));
        LinearLayout yp = null;
        for (int i = 0; i < Profiles.ALL.length; i++) {
            if (i % 4 == 0) { yp = row(); controls.addView(yp); }
            Profiles.Profile p = Profiles.ALL[i];
            Button b = button(p.label, v -> applyProfile(p));
            b.setTextSize(18);
            yp.addView(b, new LinearLayout.LayoutParams(dp(200), dp(80)));
        }
        LinearLayout offRow = row();
        offRow.addView(button("EQ off", v -> {
            getSharedPreferences(EqService.STATE, MODE_PRIVATE).edit().putString(EqService.KEY_ACTIVE, "off").apply();
            EqService.start(this, "off");
            buildControls();
            status("EQ off (Dolby values left as they are)");
        }));
        controls.addView(offRow);
        if (ap != null) controls.addView(text(ap.blurb, 14));
        controls.addView(text("Presets (saved, reapplied at boot)", 20));
        LinearLayout pr = row();
        for (String[] p : Dolby.PRESETS) pr.addView(button(p[0], v -> applyPreset(p)));
        controls.addView(pr);
        controls.addView(text("Speaker processing", 20));
        for (String[] e : Dolby.ENUMS) if (dolby.supported(e[4])) addEnumRow(e[0], e[1], e[2], e[3]);
        for (String[] i : Dolby.INTS) if (dolby.supported(i[2])) addIntRow(i[0], i[1]);
        addFloatRow("PCM stream gain (linear)", "PcmPlaybackStreamGain");
    }

    private void applyProfile(Profiles.Profile p) {
        SharedPreferences.Editor ed = prefs.edit();
        StringBuilder sb = new StringBuilder(p.label).append(": ");
        int fails = 0;
        for (int i = 0; i + 1 < p.dolby.length; i += 2) {
            try {
                int r = dolby.apply(p.dolby[i], p.dolby[i + 1]);
                if (r != 0) fails++;
                ed.putString(p.dolby[i], p.dolby[i + 1]);
            } catch (Throwable t) { fails++; Log.e(Dolby.TAG, "profile " + p.dolby[i], t); }
        }
        ed.apply();
        getSharedPreferences(EqService.STATE, MODE_PRIVATE).edit().putString(EqService.KEY_ACTIVE, p.id).apply();
        EqService.start(this, p.id);
        sb.append("Dolby ").append(fails == 0 ? "ok" : fails + " failed");
        buildControls();
        status.postDelayed(() -> status(sb + " | " + EqService.status), 800);
    }

    private void applyPreset(String[] p) {
        SharedPreferences.Editor ed = prefs.edit();
        StringBuilder sb = new StringBuilder(p[0]).append(": ");
        for (int i = 1; i + 1 < p.length; i += 2) {
            try { sb.append(p[i]).append('=').append(dolby.apply(p[i], p[i + 1])).append(' '); ed.putString(p[i], p[i + 1]); }
            catch (Throwable t) { sb.append(p[i]).append(" ERR ").append(t.getMessage()).append(' '); }
        }
        ed.apply();
        buildControls();
        status(sb.toString());
    }

    private void addIntRow(String label, String key) {
        int[] range;
        try { range = (int[]) dolby.call("get" + key + "Range"); }
        catch (Throwable t) { controls.addView(text(label + ": unavailable (" + t.getClass().getSimpleName() + ")", 14)); return; }
        final int min = range[0], max = range[1];
        final int step = Math.max(1, (max - min) / 20);
        Integer cur = null;
        String saved = prefs.getString(key, null);
        if (saved != null) cur = Integer.parseInt(saved.substring(2));
        else if (dolby.has("get" + key)) {
            try { cur = (Integer) dolby.call("get" + key); } catch (Throwable ignored) { }
        }
        final int[] val = {cur == null ? min : cur};
        final boolean[] known = {cur != null};
        LinearLayout r = row();
        TextView l = text(label + "  [" + min + ".." + max + "]", 16);
        r.addView(l, new LinearLayout.LayoutParams(0, -2, 1));
        TextView vt = text(known[0] ? String.valueOf(val[0]) : "?", 18);
        vt.setGravity(Gravity.CENTER);
        View.OnClickListener dec = v -> setInt(key, val, known, vt, Math.max(min, val[0] - step));
        View.OnClickListener inc = v -> setInt(key, val, known, vt, Math.min(max, known[0] ? val[0] + step : min + step));
        r.addView(button("-", dec));
        r.addView(vt, new LinearLayout.LayoutParams(dp(110), -2));
        r.addView(button("+", inc));
        controls.addView(r);
    }

    private void setInt(String key, int[] val, boolean[] known, TextView vt, int nv) {
        String stored = "i:" + nv;
        try {
            int res = dolby.apply(key, stored);
            val[0] = nv; known[0] = true;
            vt.setText(String.valueOf(nv));
            prefs.edit().putString(key, stored).apply();
            status(key + " = " + nv + "  (result " + res + ")");
        } catch (Throwable t) { status(key + " failed: " + t); }
    }

    private void addFloatRow(String label, String key) {
        float[] range;
        try { range = (float[]) dolby.call("get" + key + "Range"); }
        catch (Throwable t) { controls.addView(text(label + ": unavailable", 14)); return; }
        final float min = range[0], max = range[1], step = (max - min) / 20f;
        String saved = prefs.getString(key, null);
        final float[] val = {saved != null ? Float.parseFloat(saved.substring(2)) : 0f};
        LinearLayout r = row();
        r.addView(text(label + "  [" + min + ".." + max + "]", 16), new LinearLayout.LayoutParams(0, -2, 1));
        TextView vt = text(saved != null ? String.valueOf(val[0]) : "?", 18);
        vt.setGravity(Gravity.CENTER);
        if (saved == null) val[0] = max; // assume stock = full gain
        View.OnClickListener dec = v -> setFloat(key, val, vt, Math.max(min, Math.round((val[0] - step) * 100) / 100f));
        View.OnClickListener inc = v -> setFloat(key, val, vt, Math.min(max, Math.round((val[0] + step) * 100) / 100f));
        r.addView(button("-", dec));
        r.addView(vt, new LinearLayout.LayoutParams(dp(110), -2));
        r.addView(button("+", inc));
        controls.addView(r);
    }

    private void setFloat(String key, float[] val, TextView vt, float nv) {
        String stored = "f:" + nv;
        try {
            int res = dolby.apply(key, stored);
            val[0] = nv;
            vt.setText(String.valueOf(nv));
            prefs.edit().putString(key, stored).apply();
            status(key + " = " + nv + "  (result " + res + ")");
        } catch (Throwable t) { status(key + " failed: " + t); }
    }

    private void addEnumRow(String label, String key, String cls, String getter) {
        Object[] opts;
        try {
            opts = key.equals("VirtualizerMode") && dolby.has("getSupportedVirtualizerModes")
                    ? (Object[]) dolby.call("getSupportedVirtualizerModes")
                    : dolby.values(cls);
            if (opts == null || opts.length == 0) opts = dolby.values(cls);
        } catch (Throwable t) { controls.addView(text(label + ": unavailable", 14)); return; }
        final String[] names = new String[opts.length];
        for (int i = 0; i < opts.length; i++) names[i] = ((Enum<?>) opts[i]).name();
        String cur = null;
        String saved = prefs.getString(key, null);
        if (saved != null) cur = saved.substring(2);
        else if (getter != null) {
            try { cur = ((Enum<?>) dolby.call(getter)).name(); } catch (Throwable ignored) { }
        }
        LinearLayout r = row();
        r.addView(text(label, 16), new LinearLayout.LayoutParams(0, -2, 1));
        final String[] curA = {cur};
        Button bt = button(cur == null ? "? (tap to set)" : cur, null);
        bt.setOnClickListener(v -> {
            int idx = curA[0] == null ? -1 : Arrays.asList(names).indexOf(curA[0]);
            String nv = names[(idx + 1) % names.length];
            String stored = "e:" + nv;
            try {
                int res = dolby.apply(key, stored);
                curA[0] = nv;
                bt.setText(nv);
                prefs.edit().putString(key, stored).apply();
                status(key + " = " + nv + "  (result " + res + ")");
            } catch (Throwable t) { status(key + " failed: " + t); }
        });
        r.addView(bt, new LinearLayout.LayoutParams(dp(300), -2));
        controls.addView(r);
    }

    // ---- probe ----

    private void runProbe() {
        if (dolby == null) { status("Not connected"); return; }
        StringBuilder sb = new StringBuilder();
        String[] calls = {
                "getDialogueEnhancerStrengthRange", "getAc4DialogueEnhancerStrengthRange",
                "getVolumeLevelerStrengthRange", "getSpeakerVirtualizerAngleRange",
                "getSpeakerVirtualizerStartFrequencyRange", "getVirtualizerSurroundBoostStrengthRange",
                "getHeadphoneVirtualizerReverbStrengthRange", "getAacProgramReferenceLevelRange",
                "getPcmPlaybackStreamGainRange", "getSupportedVirtualizerModes",
                "getDrcModeStereo", "getDrcModeMultichannel",
                "getDrcBoostStrengthStereo", "getDrcBoostStrengthStereoRange",
                "getDrcCutStrengthStereo", "getDrcCutStrengthStereoRange",
                "getDrcBoostStrengthMultichannel", "getDrcBoostStrengthMultichannelRange",
                "getDrcCutStrengthMultichannel", "getDrcCutStrengthMultichannelRange",
                "getDownmixMode", "isContinuousOutputOn", "isAudioDumpOn",
        };
        for (String c : calls) sb.append(c).append(" = ").append(safe(c)).append('\n');
        sb.append("isBitstreamOutputLockedToAtmos = ").append(safe("isBitstreamOutputLockedToAtmos", dolby.token)).append('\n');
        sb.append("getBitstreamOutputChannelMode = ").append(safe("getBitstreamOutputChannelMode", dolby.token)).append('\n');
        try {
            for (Object f : dolby.values("Feature"))
                sb.append("feature ").append(((Enum<?>) f).name()).append(" = ").append(safe("isFeatureSupported", f)).append('\n');
        } catch (Throwable t) { sb.append("features: ").append(t).append('\n'); }
        probe.setText(sb);
        Log.i(Dolby.TAG, "probe:\n" + sb);
    }

    private String safe(String name, Object... args) {
        try { return fmt(dolby.call(name, args)); }
        catch (Throwable t) { return "ERR " + t.getClass().getSimpleName() + ": " + t.getMessage(); }
    }

    private static String fmt(Object o) {
        if (o instanceof int[]) return Arrays.toString((int[]) o);
        if (o instanceof float[]) return Arrays.toString((float[]) o);
        if (o instanceof Object[]) return Arrays.toString((Object[]) o);
        return String.valueOf(o);
    }

    // ---- ui helpers ----

    private void status(String s) {
        status.setText(s);
        Log.i(Dolby.TAG, s);
    }

    private TextView text(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setPadding(0, dp(6), 0, dp(6));
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        if (l != null) b.setOnClickListener(l);
        return b;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
