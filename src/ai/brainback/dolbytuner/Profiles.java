package ai.brainback.dolbytuner;

/**
 * Listening profiles for YouTube on the TV's 2.1 speakers. Each sets the Dolby engine
 * and a global EQ curve (EqService). YouTube is stereo PCM, so DRC / AC-4 settings do
 * not apply; the levers are dialogue enhancer, leveller, virtualizer, and the EQ.
 *
 * The EQ curves are MEASURED room corrections (tools/measure.py + tools/design_eq.py, curves
 * and targets in tuning/): sweeps from the TV recorded at the listening seat, fitted over three
 * closed-loop passes. They are specific to one TV, room, seat and TV sound settings
 * (Sound Mode Theatre, Bass Boost 5, Subwoofer on, Wall Mount on). Re-measure for yours.
 */
final class Profiles {
    /** Coarse default bands (Hz upper edges), used by hand-written curves. */
    static final float[] EDGES = {40, 80, 160, 315, 630, 1250, 2500, 5000, 10000, 20000};

    /** 1/3-octave band upper edges (Hz) for the measured curves: centres 20 Hz .. 20 kHz. */
    static final float[] THIRDS = {22.4f, 28.1f, 35.4f, 44.9f, 56.1f, 70.7f, 89.8f, 112.2f, 140.3f,
            179.6f, 224.5f, 280.6f, 353.6f, 449f, 561.2f, 707.2f, 898f, 1122.5f, 1403.1f, 1795.9f,
            2244.9f, 2806.2f, 3535.8f, 4489.8f, 5612.3f, 7071.5f, 8979.7f, 11224.6f, 14030.8f,
            17959.4f, 20000f};

    static final class Profile {
        final String id, label, blurb;
        final String[] dolby;     // key/stored-value pairs for Dolby.apply
        final float[] eq;         // dB per band, same length as edges
        final float inputGainDb;  // headroom so boosts do not clip into the limiter
        final float[] edges;      // band upper edges (Hz), same length as eq
        final float frameMs;      // DynamicsProcessing FFT frame: longer = finer bass control, more delay
        final boolean limiter;    // false = no safety net (clipping possible at high volume)
        Profile(String id, String label, String blurb, String[] dolby, float[] eq, float inputGainDb) {
            this(id, label, blurb, dolby, EDGES, eq, inputGainDb, 10f);
        }
        Profile(String id, String label, String blurb, String[] dolby, float[] edges, float[] eq,
                float inputGainDb, float frameMs) {
            this(id, label, blurb, dolby, edges, eq, inputGainDb, frameMs, true);
        }
        Profile(String id, String label, String blurb, String[] dolby, float[] edges, float[] eq,
                float inputGainDb, float frameMs, boolean limiter) {
            if (edges.length != eq.length) throw new IllegalArgumentException(id + ": edges/eq length");
            this.id = id; this.label = label; this.blurb = blurb; this.dolby = dolby; this.edges = edges;
            this.eq = eq; this.inputGainDb = inputGainDb; this.frameMs = frameMs; this.limiter = limiter;
        }
    }

    /**
     * Target: punchy 50-80 Hz for the woofer, controlled 100-250 Hz (the TV + room had a +29 dB
     * hump at 125 Hz), flat mids, 2-2.5 kHz presence dip filled, gentle air. Measured result:
     * 1.9 dB RMS from target (50 Hz - 12.5 kHz). 80 ms frame: music needs the bass precision,
     * and has no lips to sync.
     */
    static final float[] MUSIC_EQ = {-6f, -6f, -6f, -6f, 6f, -6.01f, -14.47f, -15f, -15f, -15f,
            -13.18f, -8.1f, -5.96f, -4.87f, -2.47f, -2.15f, -2.08f, -0.03f, 1.13f, 2.39f, 4.65f,
            4.71f, 1.16f, -2.39f, -3.24f, -3.52f, -3.47f, -1.59f, 3.61f, 0f, 0f};

    static final Profile MUSIC = new Profile("music", "Music",
            "Measured room correction: punchy 50-80 Hz, boom at 100-250 Hz removed, presence restored, Dolby virtual surround",
            new String[]{
                    "VirtualizerMode", "e:ENABLE",
                    "SpeakerVirtualizerAngle", "i:25",
                    "VirtualizerSurroundBoostStrength", "i:72",
                    "VolumeLevelerMode", "e:DISABLE",
                    "VolumeLevelerStrength", "i:0",
                    "DialogueEnhancerStrength", "i:0",
            },
            THIRDS, MUSIC_EQ, -7f, 80f);

    /**
     * Target: lean below 80 Hz, slight warmth 100-250 Hz, presence +2..3 dB at 2-4 kHz, soft top.
     * Measured result: 2.6 dB RMS from target. 60 ms frame keeps lip sync acceptable for podcasts.
     */
    static final float[] VOICE_EQ = {-12f, -12f, -12f, -12f, -6.87f, -17.24f, -20f, -20f, -20f, -20f,
            -14.35f, -7.74f, -5.14f, -4.22f, -1.74f, -1.5f, -1.84f, -0.2f, 1.02f, 3.36f, 6f, 6f,
            4.16f, -0.07f, -2.54f, -4.03f, -4.5f, -3.49f, 0.83f, 0f, 0f};

    static final Profile VOICE = new Profile("voice", "Podcasts & docs",
            "Measured voice clarity: TV bass boom removed, speech presence lifted, centred, dialogue enhancer, gentle leveller",
            new String[]{
                    "VirtualizerMode", "e:DISABLE",
                    "VolumeLevelerMode", "e:ENABLE",
                    "VolumeLevelerStrength", "i:5",
                    "DialogueEnhancerStrength", "i:10",
                    "SpeakerVirtualizerAngle", "i:10",
                    "VirtualizerSurroundBoostStrength", "i:0",
            },
            THIRDS, VOICE_EQ, -7f, 60f);

    /** Same EQ as MUSIC, Dolby speaker virtualizer at maximum (virtual surround). */
    static final Profile MUSIC_WIDE = new Profile("music_wide", "Music wide",
            "Music + maximum Dolby virtual surround (angle 30, surround boost 96)",
            new String[]{
                    "VirtualizerMode", "e:ENABLE",
                    "SpeakerVirtualizerAngle", "i:30",
                    "VirtualizerSurroundBoostStrength", "i:96",
                    "VolumeLevelerMode", "e:DISABLE",
                    "VolumeLevelerStrength", "i:0",
                    "DialogueEnhancerStrength", "i:0",
            },
            THIRDS, MUSIC_EQ, -7f, 80f);

    /** Measured fit, see tuning/thunder.json. */
    static final Profile THUNDER = new Profile("thunder", "Thunder",
            "No safety net: +12 dB at 45-100 Hz, no limiter, no headroom, maximum Dolby surround. Clips at high volume by design",
            new String[]{
                    "VirtualizerMode", "e:ENABLE",
                    "SpeakerVirtualizerAngle", "i:30",
                    "VirtualizerSurroundBoostStrength", "i:96",
                    "VolumeLevelerMode", "e:DISABLE",
                    "VolumeLevelerStrength", "i:0",
                    "DialogueEnhancerStrength", "i:0",
            },
            THIRDS, new float[]{-6f, -6f, -6f, -6f, 12f, 7.3f, -4.43f, -13f, -15f, -15f, -12.6f, -6.73f, -3.2f, -2.07f, -0.11f, -0.02f, -2.18f, -2.05f, -0.68f, 0.76f, 2.78f, 3.31f, 0.84f, -2.5f, -3.5f, -3.21f, -2.48f, -0.22f, 5.2f, 0f, 0f},
            0f, 80f, false);

    /** Measured fit, see tuning/movies.json. */
    static final Profile MOVIES = new Profile("movies", "Movies",
            "Thunder + vocals: big 50-80 Hz impact, controlled boom, dialogue presence, Dolby surround, dialogue enhancer 8",
            new String[]{
                    "VirtualizerMode", "e:ENABLE",
                    "SpeakerVirtualizerAngle", "i:25",
                    "VirtualizerSurroundBoostStrength", "i:80",
                    "VolumeLevelerMode", "e:DISABLE",
                    "VolumeLevelerStrength", "i:0",
                    "DialogueEnhancerStrength", "i:8",
            },
            THIRDS, new float[]{-6f, -6f, -6f, -6f, 8f, -2.57f, -12.33f, -15f, -15f, -15f, -14.41f, -7.8f, -3.47f, -2.01f, 0.24f, 0.58f, -1.68f, -1.28f, 0.65f, 2.39f, 5.36f, 6.74f, 3.8f, -0.41f, -2.28f, -2.68f, -2.94f, -1.9f, 3.45f, 0f, 0f},
            -9f, 60f);

    /** Measured fit, see tuning/melodies.json. */
    static final Profile MELODIES = new Profile("melodies", "Melodies",
            "Warm and smooth: rounded bass, low-mid body, softened 3-5 kHz, silky air, gentle surround",
            new String[]{
                    "VirtualizerMode", "e:ENABLE",
                    "SpeakerVirtualizerAngle", "i:20",
                    "VirtualizerSurroundBoostStrength", "i:48",
                    "VolumeLevelerMode", "e:DISABLE",
                    "VolumeLevelerStrength", "i:0",
                    "DialogueEnhancerStrength", "i:0",
            },
            THIRDS, new float[]{-6f, -6f, -6f, -6f, 4.47f, -8.38f, -15f, -15f, -15f, -15f, -13.31f, -6.03f, -1.73f, -1.01f, 0.26f, 0.13f, -2.04f, -1.7f, -0.23f, 0.75f, 2.68f, 3.26f, 0.03f, -4.29f, -5.43f, -4.26f, -3.02f, -0.22f, 6f, 0f, 0f},
            -7f, 80f);

    /** Measured fit, see tuning/night.json. */
    static final Profile NIGHT = new Profile("night", "Night",
            "Quiet hours: bass boom removed, voices forward, strong leveller, dialogue enhancer 10",
            new String[]{
                    "VirtualizerMode", "e:DISABLE",
                    "VolumeLevelerMode", "e:ENABLE",
                    "VolumeLevelerStrength", "i:8",
                    "DialogueEnhancerStrength", "i:10",
                    "SpeakerVirtualizerAngle", "i:10",
                    "VirtualizerSurroundBoostStrength", "i:0",
            },
            THIRDS, new float[]{-12f, -12f, -12f, -12f, -16.23f, -20f, -20f, -20f, -20f, -20f, -16.61f, -9.42f, -4.2f, -1.56f, 0.88f, 0.56f, -2.05f, -1.85f, -0.31f, 1.41f, 4.72f, 6f, 3.88f, -0.18f, -2.04f, -3f, -3.92f, -2.92f, 2.01f, 0f, 0f},
            -7f, 60f);

    /** Measured fit, see tuning/pure.json. */
    static final Profile PURE = new Profile("pure", "Pure",
            "Neutral reference: measured room correction to a studio-style in-room curve, no Dolby effects",
            new String[]{
                    "VirtualizerMode", "e:DISABLE",
                    "VolumeLevelerMode", "e:DISABLE",
                    "VolumeLevelerStrength", "i:0",
                    "DialogueEnhancerStrength", "i:0",
                    "SpeakerVirtualizerAngle", "i:10",
                    "VirtualizerSurroundBoostStrength", "i:0",
            },
            THIRDS, new float[]{-6f, -6f, -6f, -6f, 4.71f, -9.11f, -15f, -15f, -15f, -15f, -14.18f, -7.01f, -2.72f, -1.59f, 0.31f, 0.38f, -1.99f, -2.26f, -1.15f, 0.19f, 2.66f, 3.71f, 0.67f, -3.87f, -6f, -6.19f, -6.08f, -4.97f, 0.11f, 0f, 0f},
            -5.7f, 80f);

    /** Order = remote-button cycle order and on-screen order. */
    static final Profile[] ALL = {MUSIC, MUSIC_WIDE, MELODIES, THUNDER, MOVIES, VOICE, NIGHT, PURE};

    static Profile byId(String id) {
        for (Profile p : ALL) if (p.id.equals(id)) return p;
        return null;
    }
}
