package su.dsr.f515.qnx_adas;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.SystemClock;
import android.util.Log;

/** First FCW's triple pulse, exclusively for warning level 2. */
public final class NavigationAudioBuzzer {
    private static final String TAG = "QnxAdasBuzzer";
    private static final long REPEAT_MS = 1800;
    private static SoundPool soundPool;
    private static int emergencyId;
    private static int streamId;
    private static boolean ready;
    private static long lastEmergencyTime = -REPEAT_MS;

    public static synchronized void init(Context context) {
        if (soundPool != null) return;
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            soundPool = new SoundPool.Builder().setMaxStreams(1).setAudioAttributes(attrs).build();
            soundPool.setOnLoadCompleteListener((pool, id, status) -> {
                synchronized (NavigationAudioBuzzer.class) {
                    if (pool != soundPool || id != emergencyId) return;
                    ready = status == 0;
                    if (ready) Log.i(TAG, "Original FCW emergency chime ready");
                    else Log.e(TAG, "Emergency chime load failed: " + status);
                }
            });
            try (AssetFileDescriptor afd = context.getAssets().openFd("fcw_legacy_emergency.wav")) {
                emergencyId = soundPool.load(afd, 1);
            }
        } catch (Exception e) {
            Log.e(TAG, "Emergency chime initialization failed", e);
            release();
        }
    }

    public static synchronized void onWarningState(int level, boolean enabled) {
        // Level 1 stays visual-only, including a downgrade during a level-2 pulse.
        if (!enabled || level != 2) {
            stop();
            return;
        }
        if (soundPool == null || !ready || emergencyId <= 0) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastEmergencyTime < REPEAT_MS) return;
        int played = soundPool.play(emergencyId, 1f, 1f, 1, 0, 1f);
        if (played != 0) {
            streamId = played;
            lastEmergencyTime = now;
            Log.i(TAG, "Original FCW chime: warning level 2");
        }
    }

    public static synchronized void stop() {
        if (soundPool != null && streamId != 0) soundPool.stop(streamId);
        streamId = 0;
    }

    public static synchronized void release() {
        stop();
        if (soundPool != null) soundPool.release();
        soundPool = null;
        emergencyId = 0;
        ready = false;
        lastEmergencyTime = -REPEAT_MS;
    }
}
