package ai.brainback.dolbytuner;

/**
 * Listening profiles for YouTube on the TV's 2.1 speakers. Each sets the Dolby engine
 * and a global EQ curve (EqService). YouTube is stereo PCM, so DRC / AC-4 settings do
 * not apply; the levers are dialogue enhancer, leveller, virtualizer, and the EQ.
 */
final class Profiles {
    /** EQ band upper edges (Hz). Band i covers (EDGES[i-1], EDGES[i]]. */
    static final float[] EDGES = {40, 80, 160, 315, 630, 1250, 2500, 5000, 10000, 20000};

    static final class Profile {
        final String id, label, blurb;
        final String[] dolby;     // key/stored-value pairs for Dolby.apply
        final float[] eq;         // dB per band, same length as EDGES
        final float inputGainDb;  // headroom so boosts do not clip into the limiter
        Profile(String id, String label, String blurb, String[] dolby, float[] eq, float inputGainDb) {
            this.id = id; this.label = label; this.blurb = blurb;
            this.dolby = dolby; this.eq = eq; this.inputGainDb = inputGainDb;
        }
    }

    static final Profile MUSIC = new Profile("music", "Music",
            "Thump + depth + clarity: 40-160 Hz lift, mud cut, presence and air, wide virtualizer, no leveller",
            new String[]{
                    "VirtualizerMode", "e:ENABLE",
                    "SpeakerVirtualizerAngle", "i:15",
                    "VirtualizerSurroundBoostStrength", "i:32",
                    "VolumeLevelerMode", "e:DISABLE",
                    "VolumeLevelerStrength", "i:0",
                    "DialogueEnhancerStrength", "i:0",
            },
            //  <40   80   160   315   630  1.25k 2.5k  5k   10k  20k
            new float[]{1f, 5f, 3.5f, 0f, -1.5f, 0f, 0.5f, 2f, 2.5f, 2f},
            -4f);

    static final Profile VOICE = new Profile("voice", "Podcasts & docs",
            "Voice clarity: rumble cut, presence lift, centred (no virtualizer), dialogue enhancer, gentle leveller",
            new String[]{
                    "VirtualizerMode", "e:DISABLE",
                    "VolumeLevelerMode", "e:ENABLE",
                    "VolumeLevelerStrength", "i:5",
                    "DialogueEnhancerStrength", "i:10",
                    "SpeakerVirtualizerAngle", "i:10",
                    "VirtualizerSurroundBoostStrength", "i:0",
            },
            //   <40   80   160   315   630  1.25k 2.5k  5k   10k  20k
            new float[]{-10f, -6f, -2f, 0f, -1.5f, 0f, 2f, 3.5f, 1.5f, 0f},
            -3f);

    /** Same EQ as MUSIC, Dolby speaker virtualizer pushed harder (virtual surround). */
    static final Profile MUSIC_WIDE = new Profile("music_wide", "Music wide",
            "Music + stronger Dolby virtual surround (angle 25, surround boost 64)",
            new String[]{
                    "VirtualizerMode", "e:ENABLE",
                    "SpeakerVirtualizerAngle", "i:25",
                    "VirtualizerSurroundBoostStrength", "i:64",
                    "VolumeLevelerMode", "e:DISABLE",
                    "VolumeLevelerStrength", "i:0",
                    "DialogueEnhancerStrength", "i:0",
            },
            MUSIC.eq, MUSIC.inputGainDb);

    static final Profile[] ALL = {MUSIC, MUSIC_WIDE, VOICE};

    static Profile byId(String id) {
        for (Profile p : ALL) if (p.id.equals(id)) return p;
        return null;
    }
}
