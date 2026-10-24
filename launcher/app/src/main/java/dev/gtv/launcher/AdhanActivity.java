package dev.gtv.launcher;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/** The adhan screen: the prayer and its time in very large type over the
 *  Masjid an-Nabawi photo, and a single glass Stop button. It closes on its
 *  own when the adhan finishes, so there is no countdown to show. Nothing
 *  else can take focus, OK stops the sound and Back does the same. */
public class AdhanActivity extends Activity {

    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_TIME = "time";
    public static final String EXTRA_LOCATION = "location";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true);

        setContentView(R.layout.activity_adhan);
        hideSystemUi();

        // Normally the receiver started the sound a moment before this screen
        // appeared; if the process was restarted by the full-screen intent,
        // start it here instead so the screen is never silent.
        if (!AdhanPlayer.isPlaying()) {
            AdhanPlayer.start(this);
        }

        String name = str(getIntent().getStringExtra(EXTRA_NAME));
        String time = str(getIntent().getStringExtra(EXTRA_TIME));
        String location = str(getIntent().getStringExtra(EXTRA_LOCATION));

        TextView title = findViewById(R.id.adhan_title);
        title.setText(getString(R.string.adhan_title, name));
        TextView clock = findViewById(R.id.adhan_time);
        clock.setText(time);
        TextView place = findViewById(R.id.adhan_location);
        place.setText(location);
        place.setVisibility(location.isEmpty() ? View.GONE : View.VISIBLE);

        View stop = findViewById(R.id.adhan_stop);
        stop.setOnClickListener(v -> finish());
        stop.requestFocus();

        if (AdhanPlayer.usingSystemTone(this)) {
            TextView hint = findViewById(R.id.adhan_hint);
            hint.setVisibility(View.VISIBLE);
        }

        // The screen must close itself when the sound runs out on its own.
        AdhanPlayer.setStoppedListener(this::finish);
        Log.i(PrayerAlerts.TAG, "adhan screen shown for " + name);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (isFinishing()) AdhanPlayer.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        AdhanPlayer.setStoppedListener(null);
        if (isFinishing()) AdhanPlayer.stop();
    }

    @Override
    public void onBackPressed() {
        finish();                            // Back stops the adhan too
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private static String str(String s) {
        return s == null ? "" : s;
    }
}
