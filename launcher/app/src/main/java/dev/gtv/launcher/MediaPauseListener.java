package dev.gtv.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.service.notification.NotificationListenerService;

import java.util.List;

/** Needs notification access (granted once with
 *  `cmd notification allow_listener <pkg>/<class>`). With it the adhan can
 *  pause whatever is playing; without it the audio focus request in
 *  {@link AdhanPlayer} still makes well-behaved players pause. */
public class MediaPauseListener extends NotificationListenerService {

    /** Pauses every active media session. Cheap, safe, and repeatable. */
    static void pauseAll(Context ctx) {
        try {
            MediaSessionManager msm = (MediaSessionManager)
                    ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) return;
            List<MediaController> sessions =
                    msm.getActiveSessions(new ComponentName(ctx, MediaPauseListener.class));
            for (MediaController controller : sessions) {
                try {
                    controller.getTransportControls().pause();
                } catch (Exception ignored) {
                    // One stubborn player must not stop the rest.
                }
            }
        } catch (SecurityException ignored) {
            // Notification access not granted yet.
        } catch (Exception ignored) {
        }
    }
}
