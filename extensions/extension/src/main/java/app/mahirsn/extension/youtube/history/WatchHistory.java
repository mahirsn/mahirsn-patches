package app.mahirsn.extension.youtube.history;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.youtube.addon.AddOnApi;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.shared.PlayerType;
import app.morphe.extension.youtube.shared.VideoState;

/**
 * Keeps the watch history and resume positions on a server of your own, the way YouTube's own
 * history does, so YouTube's history can stay off.
 * <p>
 * Reports the video that plays (id, title, channel, position, length) every few seconds and when
 * playback pauses, ends or moves to another video, and offers to continue a reopened video where
 * it was left. Shorts and the muted previews that play in the feeds are left out. Runs on the
 * Morphe Patches add-on hooks; all network calls are off the main thread and failures are silent,
 * so a server that is down never affects playback.
 */
@SuppressWarnings("unused")
public final class WatchHistory {
    private static final String TAG = "WatchHistory";
    private static final long REPORT_EVERY_MS = 10_000;
    private static final long RESUME_MIN_MS = 15_000;   // not from the first seconds…
    private static final long RESUME_END_MS = 20_000;   // …nor from the credits
    private static final long RESUME_WINDOW_MS = 5_000; // only right after the video starts
    private static final long PROMPT_SHOWN_MS = 10_000;

    /** Settings, in Morphe settings > Personal history. Keys match the preferences WatchHistoryPatch adds. */
    static final class Prefs {
        static final BooleanSetting ASK = new BooleanSetting("mahirsn_history_resume_ask", true);
        static final BooleanSetting BUTTON = new BooleanSetting("mahirsn_history_resume_button", true);
        // The navigation bar is built once, so these take effect after a restart.
        static final BooleanSetting TAB_SHORTS = new BooleanSetting("mahirsn_history_tab_shorts", false, true);
        static final BooleanSetting TAB_HOME = new BooleanSetting("mahirsn_history_tab_home", false, true);
    }

    private static final AtomicBoolean registered = new AtomicBoolean();
    static final ExecutorService io = Executors.newSingleThreadExecutor();
    static final Handler main = new Handler(Looper.getMainLooper());

    // Main thread only (all add-on hooks run there).
    private static String videoId;
    private static String title = "";
    private static String channel = "";
    private static long timeMs, lengthMs, lastReportAt;
    private static boolean resumeChecked;
    private static String resumeFor;      // video the saved position belongs to
    private static long resumeAtMs;
    private static WeakReference<View> playerButton = new WeakReference<>(null);
    private static PopupWindow prompt;

    /** The server, set when patching. */
    static String serverUrl() {
        return "";
    }

    /** The token the server expects in X-Token, set when patching. */
    static String token() {
        return "";
    }

    /** Injection point: called from AddOnManager.registerAddOns() of Morphe Patches. */
    public static void register() {
        if (!registered.compareAndSet(false, true) || serverUrl().isEmpty()) return;
        Prefs.ASK.get(); // registers the settings before the settings screen can open
        AddOnApi.addVideoIdListener(WatchHistory::onVideoId);
        AddOnApi.addVideoTimeListener(WatchHistory::onVideoTime);
        AddOnApi.addVideoStateListener(WatchHistory::onVideoState);
        AddOnApi.addPlayerOverlayButtonsListener(v -> onPlayerButtons((View) v));
    }

    // --- reporting ----------------------------------------------------------------------------

    private static void onVideoId(String id) {
        if (id == null || id.isEmpty() || id.equals(videoId)) return;
        flush(false);
        dismissPrompt();
        videoId = id;
        title = "";
        channel = "";
        timeMs = lengthMs = lastReportAt = 0;
        resumeChecked = false;
        resumeFor = null;
        if (VideoInformation.lastVideoIdIsShort()) return;

        final String asked = id;
        io.execute(() -> {
            String body = request("GET", "/progress/" + asked, null);
            if (body == null) return;
            long pos = (long) (number(body, "pos") * 1000);
            long len = (long) (number(body, "len") * 1000);
            main.post(() -> {
                if (!asked.equals(videoId) || pos <= RESUME_MIN_MS || (len > 0 && pos >= len - RESUME_END_MS)) return;
                resumeFor = asked;
                resumeAtMs = pos;
                if (Prefs.ASK.get()) showPrompt(0);
            });
        });
    }

    /** The watch player is on screen (in any size), not a feed preview or a Short. */
    private static boolean onWatchPlayer() {
        String type = PlayerType.getCurrent().name();
        return type.startsWith("WATCH_WHILE") || type.equals("VIRTUAL_REALITY_FULLSCREEN");
    }

    private static void onVideoTime(long time) {
        // A preview never gets a position, so flush() never reports it either.
        if (videoId == null || VideoInformation.lastVideoIdIsShort() || !onWatchPlayer()) return;
        timeMs = time;
        long length = VideoInformation.getVideoLength();
        if (length > 0) lengthMs = length;
        String t = VideoInformation.getVideoTitle();
        if (t != null && !t.isEmpty()) title = t;
        String c = VideoInformation.getChannelName();
        if (c != null && !c.isEmpty()) channel = c;

        if (!resumeChecked && videoId.equals(resumeFor)) {
            resumeChecked = true;
            if (!Prefs.ASK.get() && time < RESUME_WINDOW_MS) {
                VideoInformation.seekTo(resumeAtMs);
                return;
            }
        }

        long now = System.currentTimeMillis();
        if (now - lastReportAt >= REPORT_EVERY_MS) {
            lastReportAt = now;
            report(true);
        }
    }

    private static void onVideoState(VideoState state) {
        if (state == VideoState.PAUSED || state == VideoState.ENDED) flush(false);
    }

    /** Reports the current video now, if there is one worth reporting. */
    private static void flush(boolean playing) {
        if (videoId != null && timeMs > 0 && !VideoInformation.lastVideoIdIsShort()) report(playing);
    }

    private static void report(boolean playing) {
        final String json = "{\"id\":" + quote(videoId)
                + ",\"title\":" + quote(title)
                + ",\"channel\":" + quote(channel)
                + ",\"pos\":" + (timeMs / 1000.0)
                + ",\"len\":" + (lengthMs / 1000.0)
                + ",\"playing\":" + playing + "}";
        io.execute(() -> request("POST", "/progress", json));
    }

    // --- continuing where it was left ---------------------------------------------------------

    /** Whether the current video has a saved position ahead of where it plays now. */
    private static boolean canResume() {
        return videoId != null && videoId.equals(resumeFor) && resumeAtMs > timeMs + 3_000;
    }

    private static void resume() {
        dismissPrompt();
        if (canResume()) VideoInformation.seekTo(resumeAtMs);
    }

    /** A small bar above the bottom of the screen: "Continue at 12:34". Taps elsewhere pass through. */
    private static void showPrompt(int attempt) {
        View anchor = playerButton.get();
        if (anchor == null || anchor.getWindowToken() == null) {
            // The player overlay is created a moment after the video starts.
            if (attempt < 20) main.postDelayed(() -> showPrompt(attempt + 1), 250);
            return;
        }
        if (!canResume() || !onWatchPlayer()) return;
        dismissPrompt();
        Context ctx = anchor.getContext();

        LinearLayout bar = new LinearLayout(ctx);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(ctx, 16), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF0212121);
        bg.setCornerRadius(dp(ctx, 24));
        bar.setBackground(bg);

        TextView text = new TextView(ctx);
        text.setText("Continue at " + clock(resumeAtMs));
        text.setTextColor(Color.WHITE);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        text.setPadding(0, dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));
        bar.addView(text);

        TextView close = new TextView(ctx);
        close.setText("✕");
        close.setTextColor(0xB3FFFFFF);
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        close.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));
        bar.addView(close);

        PopupWindow p = new PopupWindow(bar, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false);
        p.setOutsideTouchable(false);
        p.setTouchable(true);
        text.setOnClickListener(v -> resume());
        close.setOnClickListener(v -> dismissPrompt());
        try {
            p.showAtLocation(anchor.getRootView(), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, dp(ctx, 120));
        } catch (Exception e) {
            Log.d(TAG, "prompt: " + e);
            return;
        }
        prompt = p;
        main.postDelayed(() -> { if (prompt == p) dismissPrompt(); }, PROMPT_SHOWN_MS);
    }

    private static void dismissPrompt() {
        PopupWindow p = prompt;
        prompt = null;
        if (p != null) try { p.dismiss(); } catch (Exception ignored) { }
    }

    /** Adds the "continue" button next to the player's own buttons (captions, settings). */
    private static void onPlayerButtons(View sourceButton) {
        playerButton = new WeakReference<>(sourceButton);
        if (!Prefs.BUTTON.get()) return;
        try {
            // Reflection keeps android.view types out of the compile-only stubs;
            // the patch checks this method exists before it patches anything.
            Class.forName("app.morphe.extension.youtube.videoplayer.PlayerOverlayButton")
                    .getMethod("addButton", View.class, String.class,
                            View.OnClickListener.class, View.OnLongClickListener.class)
                    .invoke(null, sourceButton, "mahirsn_history_resume",
                            (View.OnClickListener) v -> {
                                if (canResume()) resume();
                                else Toast.makeText(v.getContext(), "Nothing to continue in this video",
                                        Toast.LENGTH_SHORT).show();
                            },
                            (View.OnLongClickListener) v -> {
                                HistoryDialog.show(v.getContext());
                                return true;
                            });
        } catch (Exception e) {
            Log.d(TAG, "player button: " + e);
        }
    }

    // --- History in place of a navigation bar button -----------------------------------------

    /**
     * Injection point: NavigationBar.navigationTabCreatedCallback() of Morphe Patches, after the
     * code other patches add there (so a Shorts button hidden by Morphe comes back when the user
     * chose to turn it into History).
     */
    public static void navigationTabCreated(Enum<?> button, View tab) {
        try {
            String name = button.name();
            if (!(name.equals("SHORTS") && Prefs.TAB_SHORTS.get()) && !(name.equals("HOME") && Prefs.TAB_HOME.get())) {
                return;
            }
            tab.setVisibility(View.VISIBLE);
            tab.post(() -> makeHistoryTab(tab));
        } catch (Exception e) {
            Log.d(TAG, "tab: " + e);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private static void makeHistoryTab(View view) {
        // The hook may hand over the icon rather than the whole button.
        View tab = view;
        while (!tab.isClickable() && tab.getParent() instanceof View) tab = (View) tab.getParent();
        tab.setVisibility(View.VISIBLE);
        Context ctx = tab.getContext();

        if (tab instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) tab;
            ImageView icon = find(group, ImageView.class);
            TextView label = find(group, TextView.class);
            int res = ctx.getResources().getIdentifier("mahirsn_history_tab", "drawable", ctx.getPackageName());
            if (icon != null && res != 0) icon.setImageResource(res);
            if (label != null) label.setText("History");
        }
        tab.setContentDescription("History");
        // A touch listener runs before the app's own click handling, which would open Shorts or
        // Home, and the app does not replace it when it rebinds the button.
        tab.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_UP
                    && e.getX() >= 0 && e.getY() >= 0 && e.getX() <= v.getWidth() && e.getY() <= v.getHeight()) {
                HistoryDialog.show(v.getContext());
            }
            return true;
        });
    }

    private static <T extends View> T find(ViewGroup group, Class<T> type) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (type.isInstance(child) && child.getVisibility() == View.VISIBLE) return type.cast(child);
            if (child instanceof ViewGroup) {
                T found = find((ViewGroup) child, type);
                if (found != null) return found;
            }
        }
        return null;
    }

    // --- helpers ------------------------------------------------------------------------------

    static String request(String method, String path, String json) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(serverUrl() + path).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(5000);
            c.setReadTimeout(10000);
            c.setRequestProperty("X-Token", token());
            if (json != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = c.getOutputStream()) {
                    out.write(json.getBytes(StandardCharsets.UTF_8));
                }
            }
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                for (int n; (n = in.read(chunk)) > 0; ) buf.write(chunk, 0, n);
                return buf.toString("UTF-8");
            }
        } catch (Exception e) {
            Log.d(TAG, method + " " + path + ": " + e);
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static double number(String json, String key) {
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return 0;
        i = json.indexOf(':', i) + 1;
        int j = i;
        while (j < json.length() && "0123456789.-eE+ ".indexOf(json.charAt(j)) >= 0) j++;
        try {
            return Double.parseDouble(json.substring(i, j).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char ch : (s == null ? "" : s).toCharArray()) {
            if (ch == '"' || ch == '\\') b.append('\\').append(ch);
            else if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch));
            else b.append(ch);
        }
        return b.append('"').toString();
    }

    static String clock(long ms) {
        long s = ms / 1000;
        return s >= 3600
                ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    static int dp(Context ctx, float v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    private WatchHistory() {
    }
}
