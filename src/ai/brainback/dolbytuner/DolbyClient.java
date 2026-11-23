package ai.brainback.dolbytuner;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * The app's ONE connection to the Dolby service, bound with the application context and never
 * unbound. DolbyAudioService's ClientManager.register() calls restoreDefaultState() whenever a new
 * client token registers (asynchronously), and again on deregister / client death. Binding and
 * registering per action therefore reset MS12 to defaults right after our values landed: the
 * "enhanced for a split second, then reverts" bug. Register once per process; reuse forever.
 */
final class DolbyClient {
    interface Job { void run(Dolby d) throws Exception; }

    private static Dolby dolby;
    private static boolean binding;
    private static final List<Job> pending = new ArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());

    /** Runs job on the main thread once connected (immediately if already connected). */
    static void run(Context ctx, Job job) {
        main.post(() -> {
            if (dolby != null) { exec(job); return; }
            pending.add(job);
            if (binding) return;
            binding = true;
            Context app = ctx.getApplicationContext();
            boolean ok = app.bindService(Dolby.intent(), new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName n, IBinder b) {
                    Dolby d;
                    try { d = new Dolby(b); } catch (Throwable t) { Log.e(Dolby.TAG, "Dolby connect", t); binding = false; return; }
                    // Our own register() just queued an async restoreDefaultState(): let it land
                    // before applying anything, or it wipes the first values.
                    main.postDelayed(() -> {
                        dolby = d;
                        binding = false;
                        List<Job> jobs = new ArrayList<>(pending);
                        pending.clear();
                        for (Job j : jobs) exec(j);
                    }, 1500);
                }
                @Override public void onServiceDisconnected(ComponentName n) {
                    // Dolby service died; it restarts with defaults. Reconnect and re-apply on next use.
                    dolby = null;
                    Log.e(Dolby.TAG, "Dolby service disconnected");
                }
            }, Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
            if (!ok) { binding = false; pending.clear(); Log.e(Dolby.TAG, "Dolby bind failed"); }
        });
    }

    static Dolby current() { return dolby; }

    private static void exec(Job j) {
        if (dolby == null) return;
        try { j.run(dolby); } catch (Throwable t) { Log.e(Dolby.TAG, "Dolby job", t); }
    }
}
