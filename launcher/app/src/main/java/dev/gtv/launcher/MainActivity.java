package dev.gtv.launcher;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.icu.util.Calendar;
import android.icu.util.ULocale;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements AppAdapter.Listener {

    private static final String PREFS = "launcher";
    private static final String KEY_LAST = "last_focused";
    private static final String KEY_PRAYER = "prayer_cache";
    private static final String KEY_VERSE = "verse_cache";
    private static final String KEY_HADITH = "hadith_cache";
    private static final String KEY_VIDEOS = "video_cache";

    // api.myquran.com: today's prayer schedule for one location, plus the
    // random ayah / hadith cards; dakwahhub.com supplies the video list.
    private static final String PRAYER_API =
            "https://api.myquran.com/v3/sholat/jadwal/%s/today?tz=%s";
    /** How long the background refresh waits for a location fix. */
    private static final long LOCATION_TIMEOUT_MS = 10_000L;
    private static final String[] PRAYER_NAMES = {"Subuh", "Dzuhur", "Ashar", "Maghrib", "Isya"};
    private static final String[] PRAYER_KEYS = {"subuh", "dzuhur", "ashar", "maghrib", "isya"};
    private static final String[] SCHED_KEYS =
            {"imsak", "subuh", "terbit", "dhuha", "dzuhur", "ashar", "maghrib", "isya"};
    private static final String[] SCHED_NAMES =
            {"Imsak", "Subuh", "Terbit", "Dhuha", "Dzuhur", "Ashar", "Maghrib", "Isya"};
    /** Which schedule tile each entry of PRAYER_NAMES belongs to. */
    private static final int[] NEXT_TILE = {1, 4, 5, 6, 7};
    /** Videos per row on the Prayer Time page. */
    private static final int VIDEO_COLUMNS = 4;
    private static final String YOUTUBE_TV = "com.google.android.youtube.tv";

    /** The pages, in tab-bar order: Home (favourites), Apps, Prayer Time. */
    private static final int PAGE_HOME = 0;
    private static final int PAGE_APPS = 1;
    private static final int PAGE_PRAYER = 2;

    private FocusRecyclerView favGrid, appGrid;
    private AppAdapter favAdapter, appAdapter;
    private GlowView glow;
    private TextView prayer, clock, date, hijri, verse, verseRef;
    private ImageView pillInputs, pillWifi, pillSettings;

    private View pageHome, pageApps, pagePrayer;
    /** One segment per page, indexed by PAGE_*. */
    private final TextView[] tabs = new TextView[3];
    private View tabTrack, tabThumb;
    private LinearLayout tabSegments;
    private TextView prayerLocation, prayerNext;
    private View quranCard, hadithCard;
    private TextView quranArab, quranText, quranRef, hadithText, hadithRef;
    private LinearLayout videoGrid;
    private final LinearLayout[] scheduleRows = new LinearLayout[2];
    private final View[] tiles = new View[SCHED_KEYS.length];
    private final TextView[] tileNames = new TextView[SCHED_KEYS.length];
    private final TextView[] tileTimes = new TextView[SCHED_KEYS.length];

    private final List<AppItem> favItems = new ArrayList<>();
    private final List<AppItem> appItems = new ArrayList<>();

    private boolean pendingRemoteInputs;
    /** Which page is showing: one of PAGE_HOME, PAGE_APPS, PAGE_PRAYER. */
    private int page = PAGE_HOME;
    /** Column count shared by both app grids, read from the config. */
    private int columns = 5;

    private int lastFavPos = 0;
    private int lastAppsPos = 0;
    private String signature = "";

    /** Subuh..Isya times for the cached day, or null until the API answers. */
    private String[] prayerTimes;

    private long lastVerseFetch;
    private long lastHadithFetch;
    private long lastVideoFetch;
    private final ExecutorService io = Executors.newFixedThreadPool(3);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat dateFmt =
            new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault());
    private final SimpleDateFormat dayFmt =
            new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateClock();
            handler.postDelayed(this, 20000);
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handler.postDelayed(MainActivity.this::rebuildIfNeeded, 400);
        }
    };

    // Five-minute prayer alert banner: overlays Home, never takes focus.
    private View alertBanner;
    private TextView alertTitle;
    private TextView alertText;
    /** The last date the clock drew; detects the midnight roll-over. */
    private String shownDay;

    private final PrayerAlerts.Banner alertSink =
            (title, text) -> runOnUiThread(() -> showAlertBanner(title, text));

    private final Runnable hideAlertBanner = () -> {
        if (alertBanner == null) return;
        alertBanner.animate().alpha(0f).setDuration(300)
                .withEndAction(() -> alertBanner.setVisibility(View.GONE))
                .start();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();

        favGrid = findViewById(R.id.fav_grid);
        appGrid = findViewById(R.id.app_grid);
        glow = findViewById(R.id.glow);
        prayer = findViewById(R.id.prayer);
        clock = findViewById(R.id.clock);
        date = findViewById(R.id.date);
        hijri = findViewById(R.id.hijri);
        verse = findViewById(R.id.verse);
        verseRef = findViewById(R.id.verse_ref);
        pillInputs = findViewById(R.id.pill_inputs);
        pillWifi = findViewById(R.id.pill_wifi);
        pillSettings = findViewById(R.id.pill_settings);

        pageHome = findViewById(R.id.page_home);
        pageApps = findViewById(R.id.page_apps);
        pagePrayer = findViewById(R.id.page_prayer);
        tabs[PAGE_HOME] = findViewById(R.id.tab_home);
        tabs[PAGE_APPS] = findViewById(R.id.tab_apps);
        tabs[PAGE_PRAYER] = findViewById(R.id.tab_prayer);
        tabTrack = findViewById(R.id.tab_track);
        tabThumb = findViewById(R.id.tab_thumb);
        tabSegments = findViewById(R.id.tab_segments);
        prayerLocation = findViewById(R.id.prayer_location);
        prayerNext = findViewById(R.id.prayer_next);
        quranCard = findViewById(R.id.quran_card);
        hadithCard = findViewById(R.id.hadith_card);
        quranArab = findViewById(R.id.quran_arab);
        quranText = findViewById(R.id.quran_text);
        quranRef = findViewById(R.id.quran_ref);
        hadithText = findViewById(R.id.hadith_text);
        hadithRef = findViewById(R.id.hadith_ref);
        videoGrid = findViewById(R.id.video_grid);
        scheduleRows[0] = findViewById(R.id.schedule_row1);
        scheduleRows[1] = findViewById(R.id.schedule_row2);
        alertBanner = findViewById(R.id.alert_banner);
        alertTitle = findViewById(R.id.alert_banner_title);
        alertText = findViewById(R.id.alert_banner_text);

        glow.setAlpha(0f);

        setupLists();
        setupPills();
        setupTabs();
        buildSchedule();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packageReceiver, f);

        updateClock();
        loadPrayer();
        PrayerScheduler.arm(this);      // next reminder/adhan from the cache
        askForPermissions();
        loadVerse();
        pendingRemoteInputs = RemoteKeyService.ACTION_SHOW_INPUTS.equals(getIntent().getAction());
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        PrayerAlerts.setBanner(alertSink);
        handler.removeCallbacks(tick);
        handler.post(tick);
        rebuildIfNeeded();
        if (System.currentTimeMillis() - lastVerseFetch > 5 * 60 * 1000L) loadVerse();
        if (pendingRemoteInputs) {
            pendingRemoteInputs = false;
            pillInputs.post(this::showInputsMenu);
        } else {
            restoreFocus();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        PrayerAlerts.setBanner(null);   // the notification tray takes over here
        handler.removeCallbacks(tick);
        saveFocus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(hideAlertBanner);
        if (alertBanner != null) alertBanner.animate().cancel();
        unregisterReceiver(packageReceiver);
        io.shutdownNow();
    }

    /** The T-5 warning on Home: big amber line, quiet detail line, 30 seconds. */
    private void showAlertBanner(String title, String text) {
        if (alertBanner == null) return;
        alertTitle.setText(title);
        alertText.setText(text);
        handler.removeCallbacks(hideAlertBanner);
        alertBanner.animate().cancel();
        alertBanner.setAlpha(0f);
        alertBanner.setVisibility(View.VISIBLE);
        alertBanner.animate().alpha(1f).setDuration(240).start();
        handler.postDelayed(hideAlertBanner, 30_000L);
        Log.i(PrayerAlerts.TAG, "banner on Home: " + title + " / " + text);
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    // ------------------------------------------------------------------- lists

    private void setupLists() {
        columns = getResources().getInteger(R.integer.columns);

        // Both pages play by the same grid rules: one row or column per press,
        // and the top edge hands focus back to the tab bar.
        for (FocusRecyclerView list : new FocusRecyclerView[]{favGrid, appGrid}) {
            list.setLayoutManager(new GridLayoutManager(this, columns));
            list.setColumns(columns);
            list.setEdgeListener(new FocusRecyclerView.EdgeListener() {
                @Override public void onTopEdge() { focusActiveTab(); }
                @Override public void onBottomEdge() { }   // last row: stay put
            });
        }
        // Re-place the favourites whenever Home changes size or content.
        favGrid.addOnLayoutChangeListener(
                (v, l, t, r, b, ol, ot, or, ob) -> alignFavorites());

        buildAppLists();
    }

    private void buildAppLists() {
        List<AppItem> all = AppItem.loadAll(this);

        // Home shows only the configured favourites, in config order; missing
        // packages are skipped rather than leaving a hole in the row.
        favItems.clear();
        for (String pkg : getResources().getStringArray(R.array.favorite_apps)) {
            for (AppItem item : all) {
                if (item.pkg.equals(pkg)) {
                    favItems.add(item);
                    break;
                }
            }
        }
        // The Apps page lists everything, so no app becomes unreachable.
        appItems.clear();
        appItems.addAll(all);

        if (favAdapter == null) {
            favAdapter = new AppAdapter(favItems, R.layout.item_favorite, this);
            favGrid.setAdapter(favAdapter);
        } else {
            favAdapter.notifyDataSetChanged();
        }

        if (appAdapter == null) {
            appAdapter = new AppAdapter(appItems, R.layout.item_app, this);
            appGrid.setAdapter(appAdapter);
        } else {
            appAdapter.notifyDataSetChanged();
        }

        signature = signatureOf(all);
        favGrid.post(this::alignFavorites);
    }

    /** Pins the favourite cards to the bottom edge of Home, right where the
     *  glow sits; a list that outgrows the block starts at the top and
     *  scrolls instead of running off the bottom. */
    private void alignFavorites() {
        if (favGrid == null) return;
        if (favItems.isEmpty()) {
            favGrid.setPadding(favGrid.getPaddingLeft(), 0,
                    favGrid.getPaddingRight(), 0);
            return;
        }
        int height = favGrid.getHeight();
        RecyclerView.LayoutManager lm = favGrid.getLayoutManager();
        View first = lm == null ? null : lm.findViewByPosition(0);
        if (height == 0 || first == null) return;   // not laid out yet

        ViewGroup.LayoutParams lp = first.getLayoutParams();
        int margins = lp instanceof ViewGroup.MarginLayoutParams
                ? ((ViewGroup.MarginLayoutParams) lp).topMargin
                        + ((ViewGroup.MarginLayoutParams) lp).bottomMargin
                : 0;
        int rows = (favItems.size() + columns - 1) / columns;
        int pad = Math.max(0, height - rows * (first.getHeight() + margins));
        if (pad != favGrid.getPaddingTop()) {
            favGrid.setPadding(favGrid.getPaddingLeft(), pad,
                    favGrid.getPaddingRight(), 0);
        }
    }

    private String signatureOf(List<AppItem> all) {
        StringBuilder sb = new StringBuilder();
        for (AppItem i : all) sb.append(i.pkg).append('|');
        return sb.toString();
    }

    private void rebuildIfNeeded() {
        List<AppItem> all = AppItem.loadAll(this);
        if (!signatureOf(all).equals(signature)) {
            String focusedPkg = currentFocusedPkg();
            buildAppLists();
            // The focused app may be the one that disappeared: land on the
            // tab bar instead of leaving the remote with nowhere to go.
            if (focusedPkg != null && !focusPackage(focusedPkg)) focusActiveTab();
        }
    }

    private String currentFocusedPkg() {
        View f = getCurrentFocus();
        if (f == null || f.getParent() == null) return null;
        RecyclerView rv = findParentRecycler(f);
        if (rv == null) return null;
        int pos = rv.getChildAdapterPosition(f);
        if (pos == RecyclerView.NO_POSITION) return null;
        AppAdapter a = rv == favGrid ? favAdapter : rv == appGrid ? appAdapter : null;
        if (a == null || pos >= a.getItemCount()) return null;
        return a.pkgAt(pos);
    }

    private RecyclerView findParentRecycler(View v) {
        // Walk ViewParents: the chain ends at a ViewRootImpl, which is not a View.
        if (v instanceof RecyclerView) return (RecyclerView) v;
        ViewParent p = v.getParent();
        while (p != null) {
            if (p instanceof RecyclerView) return (RecyclerView) p;
            p = p.getParent();
        }
        return null;
    }

    // ------------------------------------------------------------------- pills

    private void setupPills() {
        pillInputs.setOnClickListener(v -> showInputsMenu());
        pillWifi.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        pillSettings.setOnClickListener(v -> openSystemSettings());
        pillSettings.setOnLongClickListener(v -> {
            Toast.makeText(this, R.string.remote_keys_enable_hint, Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return true;
        });

        View[] pills = {pillInputs, pillWifi, pillSettings};
        for (int i = 0; i < pills.length; i++) {
            final int index = i;
            View p = pills[i];
            p.setOnFocusChangeListener((v, hasFocus) -> {
                v.animate().scaleX(hasFocus ? 1.06f : 1f)
                        .scaleY(hasFocus ? 1.06f : 1f)
                        .setDuration(130).start();
            });
            p.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        // Back to the active segment without changing pages.
                        if (index == 0) {
                            focusActiveTab();
                            return true;
                        }
                        return false;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        return index == pills.length - 1;
                    case KeyEvent.KEYCODE_DPAD_UP:
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        return openPageContent();
                    default:
                        return false;
                }
            });
        }
    }

    // ------------------------------------------------------------------- tabs

    private void setupTabs() {
        tabs[PAGE_HOME].setSelected(true);

        for (int i = 0; i < tabs.length; i++) {
            final int index = i;
            // Walking onto a segment opens its page, like a TV tab bar.
            tabs[i].setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) showPage(index, false);
            });
            // OK steps into the page that is showing.
            tabs[i].setOnClickListener(v -> showPage(index, true));
            tabs[i].setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        return index == 0;               // don't fall off the bar
                    case KeyEvent.KEYCODE_DPAD_UP:
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        return openPageContent();
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        if (index == tabs.length - 1) {
                            // Last segment: step across to the utility pills.
                            pillInputs.requestFocus();
                            return true;
                        }
                        return false;                    // right walks along the bar
                    default:
                        return false;
                }
            });
        }

        // Re-slide the capsule whenever the segments are (re)laid out.
        tabSegments.addOnLayoutChangeListener(
                (v, l, t, r, b, ol, ot, or, ob) -> positionTabThumb());

        quranCard.setOnClickListener(v -> {
            if (System.currentTimeMillis() - lastVerseFetch > 3000) loadVerse();
        });
        hadithCard.setOnClickListener(v -> {
            if (System.currentTimeMillis() - lastHadithFetch > 3000) loadHadith();
        });
    }

    /** Moves the amber glass capsule under the segment owning the open page. */
    private void positionTabThumb() {
        View seg = tabs[page];
        if (seg.getWidth() == 0 || tabThumb == null) return;
        int target = tabSegments.getLeft() + seg.getLeft();
        ViewGroup.LayoutParams lp = tabThumb.getLayoutParams();
        if (lp.width != seg.getWidth()) {
            lp.width = seg.getWidth();
            tabThumb.setLayoutParams(lp);
        }
        if (tabThumb.getVisibility() != View.VISIBLE) {
            tabThumb.setVisibility(View.VISIBLE);
            tabThumb.setX(target);
        } else if (Math.abs(tabThumb.getX() - target) > 0.5f) {
            tabThumb.animate().cancel();
            tabThumb.animate().x(target).setDuration(200).start();
        }
    }

    /**
     * Opens a page. {@code moveFocus} is true when OK (or a click) should carry
     * the remote into the page's content; arrowing between segments does not.
     */
    private void showPage(int target, boolean moveFocus) {
        page = target;
        for (int i = 0; i < tabs.length; i++) tabs[i].setSelected(i == target);
        pageHome.setVisibility(target == PAGE_HOME ? View.VISIBLE : View.GONE);
        pageApps.setVisibility(target == PAGE_APPS ? View.VISIBLE : View.GONE);
        pagePrayer.setVisibility(target == PAGE_PRAYER ? View.VISIBLE : View.GONE);
        positionTabThumb();

        if (target == PAGE_PRAYER) refreshPrayerPage();
        if (!moveFocus) return;
        if (target == PAGE_HOME) {
            restoreFocus();
            return;
        }
        // Keep the remote where it is when it is already inside the page.
        final View content = target == PAGE_APPS ? pageApps : pagePrayer;
        content.post(() -> {
            View f = getCurrentFocus();
            if (f == null || !isInside(content, f)) openPageContent();
        });
    }

    private void focusActiveTab() {
        tabs[page].requestFocus();
    }

    /** The app grid and the app list belonging to the page that is showing. */
    private FocusRecyclerView currentList() {
        return page == PAGE_APPS ? appGrid : favGrid;
    }

    private List<AppItem> currentItems() {
        return page == PAGE_APPS ? appItems : favItems;
    }

    /** Tab-bar / pill "down": first thing on the page that is showing. */
    private boolean openPageContent() {
        if (page == PAGE_PRAYER) {
            if (tiles[0] != null) tiles[0].requestFocus();
            return true;
        }
        List<AppItem> items = currentItems();
        if (items.isEmpty()) return true;
        int pos = page == PAGE_APPS ? lastAppsPos : lastFavPos;
        if (pos < 0 || pos >= items.size()) pos = 0;
        currentList().focusPosition(pos);
        return true;
    }

    private static boolean isInside(View parent, View v) {
        if (v == parent) return true;
        ViewParent p = v.getParent();
        while (p != null) {
            if (p == parent) return true;
            p = p.getParent();
        }
        return false;
    }

    private void refreshPrayerPage() {
        long now = System.currentTimeMillis();
        if (now - lastVerseFetch > 20000) loadVerse();
        if (now - lastHadithFetch > 30000) loadHadith();
        if (now - lastVideoFetch > 30000) loadVideos();
        updateScheduleHighlight();
    }

    // --------------------------------------------------------------- schedule

    private void buildSchedule() {
        for (int i = 0; i < SCHED_KEYS.length; i++) {
            View tile = getLayoutInflater().inflate(
                    R.layout.prayer_tile, scheduleRows[i / 4], false);
            tiles[i] = tile;
            tileNames[i] = tile.findViewById(R.id.tile_name);
            tileTimes[i] = tile.findViewById(R.id.tile_time);
            tileNames[i].setText(SCHED_NAMES[i]);
            tileTimes[i].setText("--:--");
            tile.setOnClickListener(v -> loadPrayer());   // OK reloads the day
            scheduleRows[i / 4].addView(tile);
        }
    }

    private void fillSchedule(JSONObject day, String location) {
        if (day != null) {
            for (int i = 0; i < SCHED_KEYS.length; i++) {
                String t = day.optString(SCHED_KEYS[i], "");
                tileTimes[i].setText(t.isEmpty() ? "--:--" : t);
            }
        }
        if (!TextUtils.isEmpty(location)) prayerLocation.setText(location);
        updateScheduleHighlight();
        PrayerScheduler.arm(this);      // fresh times may move the alerts
    }

    /** Amber time plus a lighter card for whichever prayer comes next. */
    private void updateScheduleHighlight() {
        if (tiles[0] == null) return;
        int next = nextPrayerIndex(new Date());
        for (int i = 0; i < tiles.length; i++) tiles[i].setSelected(false);
        if (next < 0 || next >= NEXT_TILE.length) {
            prayerNext.setText("");
            return;
        }
        tiles[NEXT_TILE[next]].setSelected(true);
        prayerNext.setText(getString(R.string.next_prayer,
                PRAYER_NAMES[next] + "  " + prayerTimes[next]));
    }

    private void showInputsMenu() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(10), dp(10), dp(10), dp(10));
        list.setMinimumWidth(dp(300));
        list.setBackgroundResource(R.drawable.menu_bg);

        TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        List<TvInputInfo> inputs = new ArrayList<>();
        if (tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.isHidden(this)) continue;
                inputs.add(info);
            }
        }

        final PopupWindow[] popup = new PopupWindow[1];

        for (TvInputInfo info : inputs) {
            TextView row = menuRow(info.loadLabel(this).toString());
            row.setOnClickListener(v -> {
                popup[0].dismiss();
                launchInput(info);
            });
            list.addView(row);
        }

        TextView live = menuRow("Live TV");
        live.setOnClickListener(v -> {
            popup[0].dismiss();
            String pkg = getString(R.string.live_tv_package);
            Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) startActivity(i);
        });
        list.addView(live);

        popup[0] = new PopupWindow(list,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup[0].setBackgroundDrawable(getDrawable(R.drawable.menu_bg));
        popup[0].setOutsideTouchable(true);
        popup[0].setFocusable(true);
        popup[0].showAsDropDown(pillInputs, 0, dp(10));
    }

    private void openSystemSettings() {
        startActivity(new Intent(Settings.ACTION_SETTINGS));
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_TV_INPUT || keyCode == KeyEvent.KEYCODE_SETTINGS) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                if (keyCode == KeyEvent.KEYCODE_TV_INPUT) showInputsMenu();
                else openSystemSettings();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private TextView menuRow(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(18f);
        tv.setTypeface(getResources().getFont(R.font.outfit));
        tv.setTextColor(getColorStateList(R.color.menu_row_text));
        tv.setPadding(dp(20), dp(14), dp(20), dp(14));
        tv.setFocusable(true);
        tv.setBackgroundResource(R.drawable.menu_row);
        tv.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    || keyCode == KeyEvent.KEYCODE_DPAD_LEFT)) {
                return true;
            }
            return false;
        });
        return tv;
    }

    private void launchInput(TvInputInfo info) {
        // Public API path: hand the passthrough channel URI to the system TV
        // app. Third-party apps cannot open a TvInputManager session (system API).
        Intent i = new Intent(Intent.ACTION_VIEW,
                android.media.tv.TvContract.buildChannelUriForPassthroughInput(info.getId()));
        try {
            startActivity(i);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ focus

    @Override
    public void onAppFocused(View tile, AppItem item, int position) {
        if (findParentRecycler(tile) == favGrid) {
            lastFavPos = position;
            // Only Home shows the glow, so it keeps the last favourite's tint
            // while the remote is over on the Apps page.
            glow.setColor(AppItem.ambientColor(item.banner != null ? item.banner : item.icon));
            glow.animate().alpha(0.76f).setDuration(180).start();
        } else {
            lastAppsPos = position;
        }
        tile.animate().scaleX(1.04f).scaleY(1.04f).setDuration(150).start();
        saveFocus(item.pkg);
    }

    @Override
    public void onAppClicked(AppItem item) {
        Intent i = item.launchIntent(this);
        if (i != null) {
            try {
                startActivity(i);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onAppSelectedChanged(AppItem item) {
        // Nothing to release; the edge listeners move focus explicitly.
    }

    private void saveFocus() {
        String pkg = currentFocusedPkg();
        if (pkg != null) saveFocus(pkg);
    }

    private void saveFocus(String pkg) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_LAST, pkg).apply();
    }

    private void restoreFocus() {
        if (page == PAGE_PRAYER) {
            // Focus on the tab bar or on page content is already correct.
            if (getCurrentFocus() == null) openPageContent();
            return;
        }
        String last = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_LAST, null);
        if (last != null && focusPackage(last)) return;
        // The saved app can belong to another page: start at the top of this one.
        if (!currentItems().isEmpty()) {
            FocusRecyclerView list = currentList();
            list.post(() -> list.focusPosition(0));
        } else {
            focusActiveTab();   // nothing to focus on the page: use the switch
        }
    }

    /** Focuses {@code pkg} on the page that is showing. Returns false when the
     *  package is not in that page's list, either because it lives on the other
     *  page or because it has just been uninstalled. */
    private boolean focusPackage(String pkg) {
        AppAdapter adapter = page == PAGE_APPS ? appAdapter : favAdapter;
        if (adapter == null) return false;
        int pos = adapter.positionOf(pkg);
        if (pos < 0) return false;
        FocusRecyclerView list = currentList();
        list.post(() -> list.focusPosition(pos));
        return true;
    }

    @Override
    public void onBackPressed() {
        // Apps and Prayer Time go back to Home; the top bar goes to the last tile.
        if (page != PAGE_HOME) {
            showPage(PAGE_HOME, true);
            return;
        }
        if (currentFocusedPkg() == null) restoreFocus();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (RemoteKeyService.ACTION_SHOW_INPUTS.equals(intent.getAction())) {
            pendingRemoteInputs = true;
            if (hasWindowFocus()) {
                pendingRemoteInputs = false;
                pillInputs.post(this::showInputsMenu);
            }
        } else {
            pendingRemoteInputs = false;
            restoreFocus();
        }
    }

    private void updateClock() {
        Date now = new Date();
        clock.setText(timeFmt.format(now));
        date.setText(dateFmt.format(now));
        hijri.setText(hijriLabel(now));
        prayer.setText(prayerLabel(now));
        updateScheduleHighlight();

        String day = dayFmt.format(now);
        if (shownDay == null) {
            shownDay = day;
        } else if (!shownDay.equals(day)) {
            shownDay = day;             // midnight: new prayer day
            loadPrayer();
            PrayerScheduler.arm(this);  // line up the new day's alerts
        }
    }

    /** "12 Rabiul Awwal 1447 H": today's Umm al-Qura date in Indonesian,
     *  converted on the device so it works offline and rolls over at midnight
     *  together with the Gregorian date above it. */
    private String hijriLabel(Date when) {
        try {
            Calendar cal = Calendar.getInstance(
                    ULocale.forLanguageTag("en-u-ca-islamic-umalqura"));
            cal.setTime(when);
            int month = cal.get(Calendar.MONTH);
            String[] names = getResources().getStringArray(R.array.hijri_months);
            if (month < 0 || month >= names.length) return "";
            return cal.get(Calendar.DAY_OF_MONTH) + " " + names[month]
                    + " " + cal.get(Calendar.YEAR) + " H";
        } catch (Throwable ignored) {
            return "";     // no ICU here: the line simply stays empty
        }
    }

    /** One round of permission asks: coordinates for the prayer times (on any
     *  Android version) and the notification on 13+. The banner and the adhan
     *  screen work even when both are refused. */
    private void askForPermissions() {
        List<String> want = new ArrayList<>();
        try {
            // Approximate is enough for city-level times, but asking for both
            // lets Android 12+ offer the choice without asking again later.
            if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                want.add(Manifest.permission.ACCESS_FINE_LOCATION);
                want.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            }
            if (Build.VERSION.SDK_INT >= 33
                    && getResources().getBoolean(R.bool.prayer_alerts_enabled)
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                            != PackageManager.PERMISSION_GRANTED) {
                want.add(Manifest.permission.POST_NOTIFICATIONS);
            }
            if (!want.isEmpty()) {
                requestPermissions(want.toArray(new String[0]), 901);
            }
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------- prayer, verse, hadith

    /** Index into PRAYER_NAMES of the next prayer, or -1 before the API answers. */
    private int nextPrayerIndex(Date now) {
        if (prayerTimes == null) return -1;
        String hhmm = timeFmt.format(now);
        for (int i = 0; i < prayerTimes.length; i++) {
            if (prayerTimes[i] != null && prayerTimes[i].compareTo(hhmm) > 0) return i;
        }
        // Everything for today has passed; tomorrow starts with Subuh.
        return prayerTimes[0] == null ? -1 : 0;
    }

    /** "Ashar 14:43" for the next prayer, the greeting until the API answers. */
    private String prayerLabel(Date now) {
        int next = nextPrayerIndex(now);
        if (next >= 0) return PRAYER_NAMES[next] + "  " + prayerTimes[next];
        return greetingText(now);
    }

    private String greetingText(Date now) {
        String custom = getString(R.string.greeting_override);
        if (!TextUtils.isEmpty(custom)) return custom;
        int hour = Integer.parseInt(new SimpleDateFormat("H", Locale.US).format(now));
        return hour < 12 ? "Good morning" : hour < 18 ? "Good afternoon" : "Good evening";
    }

    /** The place shown with the schedule. First the name reverse geocoded
     *  from the TV's own coordinates, then the config name, then whatever
     *  the fallback API returned. */
    private String locationLabel(JSONObject source) {
        String place = source == null ? "" : source.optString("place", "");
        if (!TextUtils.isEmpty(place)) return place;
        String custom = getString(R.string.prayer_location_label);
        if (!TextUtils.isEmpty(custom)) return custom;
        String kabko = source == null ? "" : source.optString("kabko", "");
        String prov = source == null ? "" : source.optString("prov", "");
        if (kabko.isEmpty()) return prov;
        return prov.isEmpty() || kabko.equalsIgnoreCase(prov) ? kabko : kabko + " · " + prov;
    }

    /** Show the cached schedule for today, then refresh it in the background. */
    private void loadPrayer() {
        String today = dayFmt.format(new Date());
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String cached = prefs.getString(KEY_PRAYER, null);
        if (cached != null) {
            try {
                JSONObject c = new JSONObject(cached);
                if (today.equals(c.optString("date"))) {
                    prayerTimes = timesOf(c.optJSONObject("times"));
                    fillSchedule(c.optJSONObject("times"), locationLabel(c));
                    updateClock();
                }
            } catch (Exception ignored) {
            }
        }
        io.execute(() -> fetchPrayer(today));
    }

    /** Fresh times for today. Preferred source: the device's own coordinates
     *  through api.aladhan.com with KEMENAG's method 20. When the TV has no
     *  location (permission refused, no provider, no fix) or that call fails,
     *  the configured place in api.myquran.com is used instead. */
    private void fetchPrayer(String today) {
        try {
            Geo.Place place = Geo.locate(this, LOCATION_TIMEOUT_MS);
            JSONObject fromPlace = place == null ? null : aladhan(today, place);
            JSONObject data = fromPlace != null ? fromPlace : myQuran(today);
            if (data == null) return;           // offline: whatever is on screen stays
            Log.i(PrayerAlerts.TAG, place == null
                    ? "no location fix: myquran fallback"
                    : fromPlace != null
                            ? "times from aladhan (KEMENAG), place '" + place.label + "'"
                            : "aladhan failed: myquran fallback");

            JSONObject saved = new JSONObject();
            saved.put("date", today);
            saved.put("times", data.optJSONObject("times"));
            saved.put("place", data.optString("place", ""));
            saved.put("kabko", data.optString("kabko", ""));
            saved.put("prov", data.optString("prov", ""));
            getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit().putString(KEY_PRAYER, saved.toString()).apply();

            final JSONObject times = saved.optJSONObject("times");
            final String location = locationLabel(saved);
            final String[] parsed = timesOf(times);
            handler.post(() -> {
                prayerTimes = parsed;
                fillSchedule(times, location);
                updateClock();
            });
        } catch (Exception ignored) {
            // Offline: whatever is on screen (cache or greeting) stays put.
        }
    }

    /** KEMENAG times for the coordinates, in the app's lowercase keys. */
    private JSONObject aladhan(String today, Geo.Place place) {
        try {
            String[] ymd = today.split("-");
            String spec = String.format(Locale.US,
                    "%s%s-%s-%s?latitude=%s&longitude=%s&method=%d",   // DD-MM-YYYY
                    getString(R.string.aladhan_timings_api),
                    ymd[2], ymd[1], ymd[0],
                    place.latText(), place.lngText(),
                    getResources().getInteger(R.integer.prayer_method_id));
            JSONObject data = new JSONObject(Net.get(spec)).optJSONObject("data");
            JSONObject timings = data == null ? null : data.optJSONObject("timings");
            if (timings == null) return null;

            JSONObject times = new JSONObject();
            copyTime(times, timings, "imsak", "Imsak");
            copyTime(times, timings, "subuh", "Fajr");
            copyTime(times, timings, "terbit", "Sunrise");
            copyTime(times, timings, "dzuhur", "Dhuhr");
            copyTime(times, timings, "ashar", "Asr");
            copyTime(times, timings, "maghrib", "Maghrib");
            copyTime(times, timings, "isya", "Isha");
            // KEMENAG publishes no Dhuha, so that tile stays blank.

            JSONObject out = new JSONObject();
            out.put("times", times);
            out.put("place", place.label);
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** "04:03 (WIB)" -> "04:03". */
    private static void copyTime(JSONObject times, JSONObject timings,
                                 String key, String source) {
        String value = timings.optString(source, "");
        int cut = value.indexOf(' ');
        if (cut > 0) value = value.substring(0, cut);
        try {
            if (!value.isEmpty()) times.put(key, value);
        } catch (Exception ignored) {
        }
    }

    /** The configured place in api.myquran.com: the fallback for TVs that
     *  never get a location fix. */
    private JSONObject myQuran(String today) {
        try {
            String url = String.format(Locale.US, PRAYER_API,
                    getString(R.string.prayer_location_id),
                    URLEncoder.encode(getString(R.string.prayer_timezone), "UTF-8"));
            JSONObject data = new JSONObject(Net.get(url)).optJSONObject("data");
            JSONObject jadwal = data == null ? null : data.optJSONObject("jadwal");
            if (jadwal == null) return null;

            JSONObject day = jadwal.optJSONObject(today);
            if (day == null) {
                Iterator<String> keys = jadwal.keys();
                if (keys.hasNext()) day = jadwal.optJSONObject(keys.next());
            }
            if (day == null) return null;

            JSONObject out = new JSONObject();
            out.put("times", day);
            out.put("kabko", data.optString("kabko", ""));
            out.put("prov", data.optString("prov", ""));
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** The five named prayers, in display order; null when nothing parsed. */
    private String[] timesOf(JSONObject day) {
        if (day == null) return null;
        String[] out = new String[PRAYER_KEYS.length];
        boolean any = false;
        for (int i = 0; i < PRAYER_KEYS.length; i++) {
            String t = day.optString(PRAYER_KEYS[i], "");
            out[i] = t.isEmpty() ? null : t;
            if (out[i] != null) any = true;
        }
        return any ? out : null;
    }

    // ----------------------------------------------------------------- verse

    private void loadVerse() {
        try {
            io.execute(this::fetchVerse);
        } catch (Exception ignored) {
            // Executor already shut down with the activity.
        }
    }

    /** Last cached verse first (instant, works offline), then a fresh random one. */
    private void fetchVerse() {
        lastVerseFetch = System.currentTimeMillis();
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String cached = prefs.getString(KEY_VERSE, null);
        if (cached != null) showVerse(cached);
        try {
            String json = Net.get(getString(R.string.verse_api));
            prefs.edit().putString(KEY_VERSE, json).apply();
            showVerse(json);
        } catch (Exception ignored) {
            // Keep the cached verse when the network is unavailable.
        }
    }

    /** Fills the short verse on Home and the full card on Prayer Time. */
    private void showVerse(String json) {
        try {
            JSONObject data = new JSONObject(json).optJSONObject("data");
            if (data == null) return;
            String text = data.optString("translation", "");
            String arab = data.optString("arab", "");
            JSONObject surah = data.optJSONObject("surah");
            String name = surah == null ? ""
                    : surah.optString("name_latin", surah.optString("name", ""));
            String ref = TextUtils.isEmpty(name) ? ""
                    : "QS. " + name + ": " + data.optInt("ayah_number", 0);
            if (text.isEmpty()) return;
            handler.post(() -> {
                verse.setText(text);
                verseRef.setText(ref);
                quranArab.setText(arab);
                quranText.setText(text);
                quranRef.setText(ref);
            });
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- hadith

    private void loadHadith() {
        lastHadithFetch = System.currentTimeMillis();
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String cached = prefs.getString(KEY_HADITH, null);
        if (cached != null) showHadith(cached);
        try {
            io.execute(() -> {
                try {
                    String json = Net.get(getString(R.string.hadith_api));
                    prefs.edit().putString(KEY_HADITH, json).apply();
                    showHadith(json);
                } catch (Exception ignored) {
                    // Offline: the cached hadith stays on screen.
                }
            });
        } catch (Exception ignored) {
        }
    }

    private void showHadith(String json) {
        try {
            JSONObject data = new JSONObject(json).optJSONObject("data");
            if (data == null) return;
            JSONObject text = data.optJSONObject("text");
            String body = text == null ? "" : text.optString("id", "");
            String source = data.optString("takhrij", "");
            String grade = data.optString("grade", "");
            if (body.isEmpty()) return;
            String ref = TextUtils.isEmpty(source) ? grade
                    : TextUtils.isEmpty(grade) ? source : source + "  ·  " + grade;
            handler.post(() -> {
                hadithText.setText(body);
                hadithRef.setText(ref);
            });
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- videos

    private void loadVideos() {
        lastVideoFetch = System.currentTimeMillis();
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String cached = prefs.getString(KEY_VIDEOS, null);
        if (cached != null) showVideos(cached);
        try {
            io.execute(() -> {
                try {
                    String json = Net.get(getString(R.string.video_api));
                    prefs.edit().putString(KEY_VIDEOS, json).apply();
                    showVideos(json);
                } catch (Exception ignored) {
                    // Offline: the cached list stays on screen.
                }
            });
        } catch (Exception ignored) {
        }
    }

    private void showVideos(String json) {
        try {
            JSONArray items = new JSONObject(json).optJSONArray("data");
            if (items == null) return;
            handler.post(() -> fillVideoList(items));
        } catch (Exception ignored) {
        }
    }

    /** Videos as a 4-wide grid of glass cards, two rows for the eight picks. */
    private void fillVideoList(JSONArray items) {
        int[] focus = videoFocusCell();
        videoGrid.removeAllViews();

        List<JSONObject> videos = new ArrayList<>();
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject v = items.optJSONObject(i);
                if (v != null) videos.add(v);
            }
        }

        if (videos.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.no_videos);
            empty.setTypeface(getResources().getFont(R.font.outfit));
            empty.setTextSize(16f);
            empty.setTextColor(0xFF93A8BF);
            empty.setPadding(0, dp(8), 0, 0);
            videoGrid.addView(empty);
            return;
        }

        LinearLayout row = null;
        for (int i = 0; i < videos.size(); i++) {
            if (i % VIDEO_COLUMNS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(0, i == 0 ? 0 : dp(8), 0, 0);
                videoGrid.addView(row);
            }
            addVideoCell(row, videos.get(i));
        }

        // Keep the remote where it was when the grid refreshed under it.
        if (page == PAGE_PRAYER && focus != null) restoreVideoFocus(focus[0], focus[1]);
    }

    /** Which grid cell (row, column) the remote is sitting on, or null. */
    private int[] videoFocusCell() {
        View f = getCurrentFocus();
        if (f == null || !(f.getParent() instanceof ViewGroup)) return null;
        View row = (View) f.getParent();
        if (row.getParent() != videoGrid) return null;
        return new int[]{
                videoGrid.indexOfChild(row),
                ((ViewGroup) row).indexOfChild(f)
        };
    }

    private void restoreVideoFocus(int row, int col) {
        if (row < 0 || row >= videoGrid.getChildCount()) return;
        if (!(videoGrid.getChildAt(row) instanceof ViewGroup)) return;
        View cell = ((ViewGroup) videoGrid.getChildAt(row)).getChildAt(col);
        if (cell != null) cell.requestFocus();
    }

    private void addVideoCell(LinearLayout row, JSONObject v) {
        View cell = getLayoutInflater().inflate(R.layout.item_video, row, false);
        TextView title = cell.findViewById(R.id.video_title);
        TextView meta = cell.findViewById(R.id.video_meta);
        TextView duration = cell.findViewById(R.id.video_duration);
        ImageView thumb = cell.findViewById(R.id.video_thumb);

        title.setText(v.optString("title", ""));
        String channel = v.optString("channelName", "");
        String length = v.optString("duration", "");
        meta.setText(TextUtils.isEmpty(channel) ? length : channel);
        if (TextUtils.isEmpty(length)) duration.setVisibility(View.GONE);
        else duration.setText(length);

        String url = v.optString("url", "");
        String videoId = v.optString("videoId", "");
        cell.setOnClickListener(x -> playVideo(url, videoId));
        row.addView(cell);
        loadThumb(thumb, v.optString("thumbnailUrl", ""));
    }

    private void loadThumb(ImageView view, String url) {
        if (TextUtils.isEmpty(url)) return;
        Bitmap hit = Net.peek(url);
        if (hit != null) {
            view.setImageBitmap(hit);
            return;
        }
        view.setTag(url);
        try {
            io.execute(() -> {
                try {
                    Bitmap b = Net.image(url, 640);
                    if (b != null) {
                        handler.post(() -> {
                            if (url.equals(view.getTag())) view.setImageBitmap(b);
                        });
                    }
                } catch (Exception ignored) {
                    // A cell keeps its dark placeholder without a thumbnail.
                }
            });
        } catch (Exception ignored) {
        }
    }

    /** Leanback YouTube first, then any app that can play the link. */
    private void playVideo(String url, String videoId) {
        String target = !TextUtils.isEmpty(url) ? url
                : TextUtils.isEmpty(videoId) ? ""
                : "https://www.youtube.com/watch?v=" + videoId;
        if (target.isEmpty()) return;

        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(target));
        i.setPackage(YOUTUBE_TV);
        try {
            startActivity(i);
            return;
        } catch (Exception ignored) {
        }
        i.setPackage(null);
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "No app can open this video", Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

}
