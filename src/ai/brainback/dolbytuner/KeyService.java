package ai.brainback.dolbytuner;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

/**
 * Remote-button remap (no root): an accessibility service that filters key events.
 * The spare button on the Hisense remote sends Linux KEY_MEDIA (scan code 226).
 *   short press -> next listening profile (toast shows which)
 *   long press  -> open Dolby Tuner
 * Enable: adb shell settings put secure enabled_accessibility_services
 *         ai.brainback.dolbytuner/ai.brainback.dolbytuner.KeyService
 */
public class KeyService extends AccessibilityService {
    static final int SCAN_CODE = 226; // KEY_MEDIA (its stock job: open the TV's Media Center)
    static final long LONG_MS = 700;  // this remote sends no key repeats while held: time the press

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        if (e.getScanCode() == SCAN_CODE)
            Log.i(Dolby.TAG, "key " + KeyEvent.keyCodeToString(e.getKeyCode()) + " scan=" + e.getScanCode()
                    + " action=" + e.getAction() + " repeat=" + e.getRepeatCount());
        if (e.getScanCode() != SCAN_CODE) return false;
        if (e.getAction() == KeyEvent.ACTION_UP) {
            if (e.getEventTime() - e.getDownTime() >= LONG_MS) {
                startActivity(new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            } else {
                nextProfile();
            }
        }
        return true; // consume: the button does nothing else
    }

    private void nextProfile() {
        Context app = getApplicationContext();
        String cur = app.getSharedPreferences(EqService.STATE, MODE_PRIVATE).getString(EqService.KEY_ACTIVE, "off");
        int idx = -1;
        for (int i = 0; i < Profiles.ALL.length; i++) if (Profiles.ALL[i].id.equals(cur)) idx = i;
        Profiles.Profile next = Profiles.ALL[(idx + 1) % Profiles.ALL.length];
        Toast.makeText(app, "Sound: " + next.label, Toast.LENGTH_SHORT).show();
        CtrlReceiver.applyProfile(app, next, null);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
}
