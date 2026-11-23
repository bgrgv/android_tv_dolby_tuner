package ai.brainback.dolbytuner;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

import dalvik.system.PathClassLoader;

/**
 * Reflection wrapper over the TV's vendor Dolby service
 * (com.dolby.android.audio.service / IDolbyAudioService). The service is
 * exported without a permission; its AIDL classes live in the vendor jar.
 */
final class Dolby {
    static final String TAG = "DolbyTuner";
    static final String PKG = "com.dolby.android.audio.service";
    static final String SVC = "com.dolby.android.audio.service.DolbyAudioService";
    static final String P = "com.dolby.android.audio.service.";
    static final String JAR = "/vendor/framework/com.dolby.android.audio.jar";
    static final String PREFS = "saved";

    /** Integer controls: label, name used for set<Name>/get<Name>Range/get<Name>, Feature flag. */
    static final String[][] INTS = {
            {"Dialogue enhancer", "DialogueEnhancerStrength", "DIALOGUE_ENHANCER"},
            {"AC-4 dialogue enhancer", "Ac4DialogueEnhancerStrength", "AC4_DIALOGUE_ENHANCER"},
            {"Volume leveller strength", "VolumeLevelerStrength", "VOLUME_LEVELER"},
            {"Virtualizer angle", "SpeakerVirtualizerAngle", "VIRTUALIZER_SPEAKER_ANGLE"},
            {"Virtualizer start freq", "SpeakerVirtualizerStartFrequency", "VIRTUALIZER_SPEAKER_START_FREQUENCY"},
            {"Surround boost", "VirtualizerSurroundBoostStrength", "VIRTUALIZER_SURROUND_BOOST"},
            {"DRC boost (stereo)", "DrcBoostStrengthStereo", "DRC_BOOST_STRENGTH_STEREO"},
            {"DRC cut (stereo)", "DrcCutStrengthStereo", "DRC_CUT_STRENGTH_STEREO"},
            {"DRC boost (multichannel)", "DrcBoostStrengthMultichannel", "DRC_BOOST_STRENGTH_MULTICHANNEL"},
            {"DRC cut (multichannel)", "DrcCutStrengthMultichannel", "DRC_CUT_STRENGTH_MULTICHANNEL"},
            {"AAC reference level", "AacProgramReferenceLevel", "AAC_PROGRAM_REFERENCE_LEVEL"},
            {"Headphone reverb", "HeadphoneVirtualizerReverbStrength", "VIRTUALIZER_HEADPHONE_REVERB"},
    };

    /** Enum controls: label, key, enum class, getter (or null), Feature flag. */
    static final String[][] ENUMS = {
            {"Virtualizer (speaker)", "VirtualizerMode", "VirtualizerMode", null, "VIRTUALIZER"},
            {"Volume leveller mode", "VolumeLevelerMode", "VolumeLevelerMode", null, "VOLUME_LEVELER"},
            {"DRC mode (stereo)", "DrcModeStereo", "DrcMode", "getDrcModeStereo", "DRC_MODE_STEREO"},
            {"DRC mode (multichannel)", "DrcModeMultichannel", "DrcMode", "getDrcModeMultichannel", "DRC_MODE_MULTICHANNEL"},
            {"Downmix mode", "DownmixMode", "DownmixMode", "getDownmixMode", "DOWNMIX_MODE"},
    };

    /** One-press presets: label, then key/stored-value pairs. */
    static final String[][] PRESETS = {
            {"Clear dialogue", "DialogueEnhancerStrength", "i:10", "Ac4DialogueEnhancerStrength", "i:8"},
            {"Big film", "VirtualizerMode", "e:ENABLE", "VirtualizerSurroundBoostStrength", "i:64",
                    "SpeakerVirtualizerAngle", "i:20", "VolumeLevelerMode", "e:DISABLE"},
            {"Late night", "VolumeLevelerMode", "e:ENABLE", "VolumeLevelerStrength", "i:8",
                    "DialogueEnhancerStrength", "i:10"},
            {"Dolby off-ish", "VirtualizerMode", "e:DISABLE", "VolumeLevelerMode", "e:DISABLE",
                    "DialogueEnhancerStrength", "i:0"},
    };

    final Object svc;
    final ClassLoader cl;
    final IBinder token = new Binder();

    Dolby(IBinder b) throws Exception {
        cl = loader();
        Class<?> stub = Class.forName(P + "IDolbyAudioService$Stub", true, cl);
        svc = stub.getMethod("asInterface", IBinder.class).invoke(null, b);
        try { call("register", token); } catch (Throwable t) { Log.w(TAG, "register failed", t); }
    }

    static ClassLoader loader() {
        ClassLoader own = Dolby.class.getClassLoader();
        try {
            Class.forName(P + "IDolbyAudioService$Stub", false, own);
            return own; // provided by <uses-library>
        } catch (Throwable t) {
            return new PathClassLoader(JAR, own);
        }
    }

    static Intent intent() {
        // DolbyAudioService.onBind() calls intent.getAction().equals(IDolbyAudioService.class.getName())
        // with no null check: binding without this action crashes the Dolby service.
        return new Intent(P + "IDolbyAudioService").setComponent(new ComponentName(PKG, SVC));
    }

    Method find(String name) {
        for (Method m : svc.getClass().getMethods()) if (m.getName().equals(name)) return m;
        return null;
    }

    boolean has(String name) { return find(name) != null; }

    Object call(String name, Object... args) throws Exception {
        Method m = find(name);
        if (m == null) throw new NoSuchMethodException(name);
        try {
            return m.invoke(svc, args);
        } catch (InvocationTargetException e) {
            Throwable c = e.getCause();
            throw (c instanceof Exception) ? (Exception) c : e;
        }
    }

    Object[] values(String enumCls) throws Exception {
        return Class.forName(P + enumCls, true, cl).getEnumConstants();
    }

    Object en(String enumCls, String name) throws Exception {
        for (Object o : values(enumCls)) if (((Enum<?>) o).name().equals(name)) return o;
        throw new IllegalArgumentException(enumCls + "." + name);
    }

    /** Apply one stored value ("i:5", "f:1.5", "e:ENABLE"). Returns service result code. */
    int apply(String key, String stored) throws Exception {
        String type = stored.substring(0, 1), v = stored.substring(2);
        Object r;
        if (type.equals("i")) {
            r = call("set" + key, Integer.parseInt(v));
        } else if (type.equals("f")) {
            r = call("set" + key, Float.parseFloat(v));
        } else {
            String cls = enumClassFor(key);
            if (key.equals("VirtualizerMode")) {
                r = call("setVirtualizerMode", en("DeviceType", "SPEAKER"), en(cls, v));
            } else {
                r = call("set" + key, en(cls, v));
            }
        }
        return r instanceof Integer ? (Integer) r : 0;
    }

    boolean supported(String feature) {
        if (feature == null) return true;
        try { return Boolean.TRUE.equals(call("isFeatureSupported", en("Feature", feature))); }
        catch (Throwable t) { return true; }
    }

    static String enumClassFor(String key) {
        for (String[] e : ENUMS) if (e[1].equals(key)) return e[2];
        throw new IllegalArgumentException(key);
    }

    /** Re-apply everything the user saved. Returns a short report. */
    String applyAll(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            String res;
            try { res = String.valueOf(apply(e.getKey(), String.valueOf(e.getValue()))); }
            catch (Throwable t) { res = t.getClass().getSimpleName() + ": " + t.getMessage(); }
            sb.append(e.getKey()).append(" = ").append(e.getValue()).append(" -> ").append(res).append('\n');
        }
        Log.i(TAG, "applyAll:\n" + sb);
        return sb.length() == 0 ? "Nothing saved.\n" : sb.toString();
    }
}
