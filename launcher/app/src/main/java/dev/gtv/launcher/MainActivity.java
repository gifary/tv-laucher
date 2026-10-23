package dev.gtv.launcher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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

    private FocusRecyclerView grid, dock;
    private AppAdapter gridAdapter, dockAdapter;
    private GlowView glow;
    private TextView prayer, clock, date, verse, verseRef;
    private ImageView pillInputs, pillWifi, pillSettings;

    private View pageHome, pagePrayer;
    private TextView tabHome, tabPrayer;
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

    private final List<AppItem> gridItems = new ArrayList<>();
    private final List<AppItem> dockItems = new ArrayList<>();

    private boolean pendingRemoteInputs;
    private boolean prayerTab;

    private int lastGridPos = 0;
    private int lastDockPos = 0;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();

        grid = findViewById(R.id.grid);
        dock = findViewById(R.id.dock);
        glow = findViewById(R.id.glow);
        prayer = findViewById(R.id.prayer);
        clock = findViewById(R.id.clock);
        date = findViewById(R.id.date);
        verse = findViewById(R.id.verse);
        verseRef = findViewById(R.id.verse_ref);
        pillInputs = findViewById(R.id.pill_inputs);
        pillWifi = findViewById(R.id.pill_wifi);
        pillSettings = findViewById(R.id.pill_settings);

        pageHome = findViewById(R.id.page_home);
        pagePrayer = findViewById(R.id.page_prayer);
        tabHome = findViewById(R.id.tab_home);
        tabPrayer = findViewById(R.id.tab_prayer);
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
        loadVerse();
        pendingRemoteInputs = RemoteKeyService.ACTION_SHOW_INPUTS.equals(getIntent().getAction());
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        handler.removeCallbacks(tick);
        handler.post(tick);
        rebuildIfNeeded();
        if (System.currentTimeMillis() - lastVerseFetch > 5 * 60 * 1000L) loadVerse();
        if (pendingRemoteInputs) {
            pendingRemoteInputs = false;
            grid.post(this::showInputsMenu);
        } else {
            restoreFocus();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
        saveFocus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(packageReceiver);
        io.shutdownNow();
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
        int columns = getResources().getInteger(R.integer.columns);

        grid.setLayoutManager(new GridLayoutManager(this, columns));
        grid.setColumns(columns);
        grid.setEdgeListener(new FocusRecyclerView.EdgeListener() {
            @Override public void onTopEdge() { focusActiveTab(); }
            @Override public void onBottomEdge() {
                if (!dockItems.isEmpty()) dock.focusPosition(lastDockPos);
            }
        });

        dock.setLayoutManager(new LinearLayoutManager(this, RecyclerView.HORIZONTAL, false));
        dock.setHorizontalList(true);
        dock.setEdgeListener(new FocusRecyclerView.EdgeListener() {
            @Override public void onTopEdge() {
                if (!gridItems.isEmpty()) grid.focusPosition(lastGridPos);
                else focusPill(pillInputs);
            }
            @Override public void onBottomEdge() { }
        });

        buildAppLists();
    }

    private void buildAppLists() {
        List<AppItem> all = AppItem.loadAll(this);
        Set<String> dockPkgs = new HashSet<>();
        for (String p : getResources().getStringArray(R.array.dock_apps)) dockPkgs.add(p);

        dockItems.clear();
        gridItems.clear();

        String[] order = getResources().getStringArray(R.array.dock_apps);
        for (String pkg : order) {
            for (AppItem item : all) {
                if (item.pkg.equals(pkg)) {
                    dockItems.add(item);
                    break;
                }
            }
        }
        for (AppItem item : all) {
            if (!dockPkgs.contains(item.pkg)) gridItems.add(item);
        }

        if (gridAdapter == null) {
            gridAdapter = new AppAdapter(gridItems, false, this);
            grid.setAdapter(gridAdapter);
        } else {
            gridAdapter.notifyDataSetChanged();
        }

        if (dockAdapter == null) {
            dockAdapter = new AppAdapter(dockItems, true, this);
            dock.setAdapter(dockAdapter);
        } else {
            dockAdapter.notifyDataSetChanged();
        }

        signature = signatureOf(all);
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
            if (focusedPkg != null) focusPackage(focusedPkg);
        }
    }

    private String currentFocusedPkg() {
        View f = getCurrentFocus();
        if (f == null || f.getParent() == null) return null;
        RecyclerView rv = findParentRecycler(f);
        if (rv == null) return null;
        int pos = rv.getChildAdapterPosition(f);
        if (pos == RecyclerView.NO_POSITION) return null;
        AppAdapter a = rv == grid ? gridAdapter : dockAdapter;
        return pos < a.getItemCount() ? a.pkgAt(pos) : null;
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

    private void focusPill(View pill) {
        pill.requestFocus();
    }

    // ------------------------------------------------------------------- tabs

    private void setupTabs() {
        tabHome.setSelected(true);

        View[] tabs = {tabHome, tabPrayer};
        for (int i = 0; i < tabs.length; i++) {
            final int index = i;
            final boolean prayer = index == 1;
            // Walking onto a segment opens its page, like a TV tab bar.
            tabs[i].setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) showPage(prayer, false);
            });
            // OK steps into the page that is showing.
            tabs[i].setOnClickListener(v -> showPage(prayer, true));
            tabs[i].setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        return index == 0;             // don't fall off the bar
                    case KeyEvent.KEYCODE_DPAD_UP:
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        return openPageContent();
                    default:
                        return false;                  // right walks on to the pills
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
        View seg = prayerTab ? tabPrayer : tabHome;
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
    private void showPage(boolean prayer, boolean moveFocus) {
        prayerTab = prayer;
        tabHome.setSelected(!prayer);
        tabPrayer.setSelected(prayer);
        pageHome.setVisibility(prayer ? View.GONE : View.VISIBLE);
        pagePrayer.setVisibility(prayer ? View.VISIBLE : View.GONE);
        positionTabThumb();

        if (prayer) {
            refreshPrayerPage();
            if (moveFocus) {
                pagePrayer.post(() -> {
                    View f = getCurrentFocus();
                    if (f == null || !isInside(pagePrayer, f)) openPageContent();
                });
            }
        } else if (moveFocus) {
            restoreFocus();
        }
    }

    private void focusActiveTab() {
        (prayerTab ? tabPrayer : tabHome).requestFocus();
    }

    /** Tab-bar / pill "down": first thing on the page that is showing. */
    private boolean openPageContent() {
        if (prayerTab) {
            if (tiles[0] != null) tiles[0].requestFocus();
            return true;
        }
        if (!gridItems.isEmpty()) grid.focusPosition(lastGridPos);
        else if (!dockItems.isEmpty()) dock.focusPosition(lastDockPos);
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
        boolean isDockItem = findParentRecycler(tile) == dock;
        if (isDockItem) {
            lastDockPos = position;
            glow.setColor(AppItem.ambientColor(item.banner != null ? item.banner : item.icon));
            glow.animate().alpha(0.76f).setDuration(180).start();
        } else {
            lastGridPos = position;
            glow.animate().alpha(0f).setDuration(100).start();
        }
        float scale = isDockItem ? 1.05f : 1.04f;
        tile.animate().scaleX(scale).scaleY(scale).setDuration(150).start();
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
        if (prayerTab) {
            // Focus on the tab bar or on page content is already correct.
            if (getCurrentFocus() == null) openPageContent();
            return;
        }
        String last = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_LAST, null);
        if (last != null && focusPackage(last)) return;
        if (!gridItems.isEmpty()) {
            grid.post(() -> grid.focusPosition(0));
        } else if (!dockItems.isEmpty()) {
            dock.post(() -> dock.focusPosition(0));
        }
    }

    private boolean focusPackage(String pkg) {
        int gp = gridAdapter == null ? -1 : gridAdapter.positionOf(pkg);
        if (gp >= 0) {
            int finalGp = gp;
            grid.post(() -> grid.focusPosition(finalGp));
            return true;
        }
        int dp = dockAdapter == null ? -1 : dockAdapter.positionOf(pkg);
        if (dp >= 0) {
            int finalDp = dp;
            dock.post(() -> dock.focusPosition(finalDp));
            return true;
        }
        return false;
    }

    @Override
    public void onBackPressed() {
        // Prayer Time goes back to Home; the utility bar goes to the last tile.
        if (prayerTab) {
            showPage(false, true);
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
                grid.post(this::showInputsMenu);
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
        prayer.setText(prayerLabel(now));
        updateScheduleHighlight();
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

    /** The place shown as the schedule's location, e.g. "Kab Ciamis". */
    private String locationLabel(JSONObject source) {
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

    private void fetchPrayer(String today) {
        try {
            String url = String.format(Locale.US, PRAYER_API,
                    getString(R.string.prayer_location_id),
                    URLEncoder.encode(getString(R.string.prayer_timezone), "UTF-8"));
            JSONObject data = new JSONObject(Net.get(url)).optJSONObject("data");
            JSONObject jadwal = data == null ? null : data.optJSONObject("jadwal");
            if (jadwal == null) return;

            JSONObject day = jadwal.optJSONObject(today);
            if (day == null) {
                Iterator<String> keys = jadwal.keys();
                if (keys.hasNext()) day = jadwal.optJSONObject(keys.next());
            }
            if (day == null) return;

            JSONObject saved = new JSONObject();
            saved.put("date", today);
            saved.put("times", day);
            saved.put("kabko", data.optString("kabko", ""));
            saved.put("prov", data.optString("prov", ""));
            getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit().putString(KEY_PRAYER, saved.toString()).apply();

            String[] times = timesOf(day);
            String location = locationLabel(data);
            JSONObject finalDay = day;
            handler.post(() -> {
                prayerTimes = times;
                fillSchedule(finalDay, location);
                updateClock();
            });
        } catch (Exception ignored) {
            // Offline: whatever is on screen (cache or greeting) stays put.
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
        if (prayerTab && focus != null) restoreVideoFocus(focus[0], focus[1]);
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
