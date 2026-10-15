package io.github.mydanyi.armadatf.qa;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Adapter;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Narrow standalone layout/gamepad-focus QA for MainActivity.
 *
 * Same pattern as {@link GamepadUiInstrumentation}: extends
 * {@link android.app.Instrumentation} directly (no androidx, no new
 * dependencies), launches the real MainActivity of
 * io.github.mydanyi.armadatf and drives it only through the public SDK surface
 * plus reflection on private fields/methods. No production hooks, no shell,
 * no storage/service/prepare/start/handoff calls, no real card operations.
 *
 * Suites (argument "suite"):
 *   - "focus": locate button_open_shizuku, replace ONLY its click listener
 *     with a counter, focus it, press gamepad BUTTON_A, assert exactly one
 *     activation. Works on both the original 0.1.0 APK and the updated APK
 *     without referencing new view IDs (the original APK is EXPECTED to be
 *     red here: it always triggers Review regardless of focus).
 *   - "layout" (default): the focused-action check, then a purely synthetic
 *     rendering fixture (a reflected ShizukuManager$CardInfo never backed by
 *     any real disk) with real presentation/allocation math, seek-bar clamp
 *     checks, DPAD direction, log toggle, branding, tagline, responsive
 *     panels and viewport-visibility of the review action.
 *
 * Screenshots (UiAutomation.takeScreenshot) are saved as PNGs under the
 * TARGET APP's getExternalFilesDir(null) with names like ui-a-zh-landscape.png
 * and returned as absolute paths in the instrumentation results. They are a
 * synthetic emulator fixture, not real-card verification.
 *
 * Real failures always produce status=failed; success is never faked.
 */
public class LayoutUiInstrumentation extends Instrumentation {

    private static final String TAG = "ArmadaTfLayoutQa";
    private static final String TARGET_PACKAGE = "io.github.mydanyi.armadatf";
    private static final String TARGET_ACTIVITY = "io.github.mydanyi.armadatf.ui.MainActivity";
    private static final String PREFS_NAME = "armada_settings";
    private static final String KEY_LANGUAGE = "language";
    private static final String KEY_SHOW_LOGS = "show_logs";
    private static final long QA_CARD_BYTES = 1023871549440L;
    private static final String QA_CARD_ID = "qa-card";
    private static final String QA_CARD_CID =
            "0a1b2c3d4e5f60718293a4b5c6d7e8f9"; // synthetic 32-hex, not a real CID
    private static final String STATUS_READY_SHELL = "ready-shell";
    private static final String TAGLINE_ZH_EXACT = "一张TF卡 双系统一起用";

    private final Bundle results = new Bundle();

    private String suite = "layout";
    private String locale = "zh";

    private Activity activity;
    private ClassLoader appLoader;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        if (arguments != null) {
            String s = arguments.getString("suite");
            if ("focus".equals(s) || "layout".equals(s)) suite = s;
            String l = arguments.getString("locale");
            if ("zh".equals(l) || "en".equals(l)) locale = l;
        }
        results.putString("suite", suite);
        results.putString("locale", locale);
        start();
    }

    @Override
    public void onStart() {
        // onStart() runs on the process main thread; move orchestration to a
        // worker so runOnMainSync() can drive UI work without deadlocking.
        // Activity and instrumentation are always torn down, pass or fail.
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                int code = Activity.RESULT_OK;
                try {
                    runChecks();
                    results.putString("status", "passed");
                } catch (Throwable t) {
                    code = Activity.RESULT_CANCELED;
                    results.putString("status", "failed");
                    results.putString("error", t.toString());
                    results.putString("stack", Log.getStackTraceString(t));
                    // Capture the actual failed UI before teardown, so the
                    // failure can be inspected from the saved PNG.
                    if (activity != null) {
                        takeNamedScreenshot("failure");
                    }
                } finally {
                    finishOnMain(code);
                }
            }
        }, "armada-tf-layout-qa");
        worker.start();
    }

    // --- orchestration -------------------------------------------------------

    private void runChecks() throws Exception {
        prepareTargetSettings();

        // ATD (Android Test Device) emulators disable hardware drawing, which
        // makes UiAutomation screenshots all black even with a resumed,
        // awake Activity. Public API 33+ HardwareRenderer.setDrawingEnabled(true)
        // re-enables drawing process-wide. QA-runner-only: no production
        // rendering change, no manifest/app hooks.
        if (Build.VERSION.SDK_INT >= 33) {
            android.graphics.HardwareRenderer.setDrawingEnabled(true);
            results.putBoolean("isDrawingEnabled",
                    android.graphics.HardwareRenderer.isDrawingEnabled());
        } else {
            results.putBoolean("isDrawingEnabled", false);
        }

        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setClassName(TARGET_PACKAGE, TARGET_ACTIVITY);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity = startActivitySync(intent);
        check(activity != null, "MainActivity did not start");
        waitForIdleSync();
        appLoader = activity.getClassLoader();

        recordViewport();

        // Real Activity views are ready: force a full redraw of the window
        // and allow one frame to render before any screenshot is taken.
        forceFrameAndWait();

        focusedActionCheck();

        if ("layout".equals(suite)) {
            layoutSuite();
        }

        results.putString("result_scope", "synthetic emulator fixture, not real-card verification");
    }

    /** Isolated test-app settings only: language + logs off before launch. */
    private void prepareTargetSettings() {
        Context target = getTargetContext();
        check(target != null, "no target context");
        target.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LANGUAGE, locale)
                .putBoolean(KEY_SHOW_LOGS, false)
                .commit();
    }

    // --- focused action (both suites) ---------------------------------------

    /**
     * Locates button_open_shizuku by resource id (present in BOTH APK
     * generations), replaces ONLY its click listener with a counter, focuses it
     * and sends real BUTTON_A down/up. Exactly one activation is expected.
     * The old 0.1.0 APK always triggers Review regardless of focus, so it is
     * expected to fail here (red); the updated APK must pass.
     */
    private void focusedActionCheck() throws Exception {
        final AtomicInteger clicks = new AtomicInteger();
        final int buttonId = resId("button_open_shizuku", "id");
        check(buttonId != 0, "button_open_shizuku id not found (unexpected APK layout)");
        final boolean[] focused = new boolean[1];
        setInTouchMode(false);
        waitForIdleSync();
        runOnMainSync(new Runnable() {
            @Override
            public void run() {
                View button = activity.findViewById(buttonId);
                if (button == null) return;
                button.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        clicks.incrementAndGet();
                    }
                });
                button.setFocusableInTouchMode(true);
                focused[0] = button.requestFocus();
            }
        });
        waitForIdleSync();
        check(focused[0], "button_open_shizuku could not take input focus");
        checkEquals("pre-press.clicks", 0, clicks.get());

        // The Activity consumes BUTTON_A on ACTION_DOWN and activates the
        // focused clickable control on the release. Never launches Shizuku:
        // the original listener is replaced above.
        runOnMainSync(new Runnable() {
            @Override
            public void run() {
                long now = SystemClock.uptimeMillis();
                activity.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_BUTTON_A, 0));
                activity.dispatchKeyEvent(new KeyEvent(now, now + 10, KeyEvent.ACTION_UP,
                        KeyEvent.KEYCODE_BUTTON_A, 0));
            }
        });
        waitForIdleSync();
        checkEquals("BUTTON_A.on-focused-open-shizuku.clicks", 1, clicks.get());
    }

    // --- layout suite ---------------------------------------------------------

    private void layoutSuite() throws Exception {
        installSyntheticCard();
        checkSeekBehaviour();
        checkDpadOnSlider();
        checkLogsToggle();
        checkBrandingAndTagline();
        checkResponsivePanels();
        checkReviewVisibility();
        checkLandscapeFixedViewport();
        checkMainControlsFullyVisible();
        checkReviewLocalizedLabel();

        // Restore the canonical screenshot state before the main image.
        setSeekProgress(50);
        setSwitchShowLogs(false);
        scrollRootTo(0);
        waitForIdleSync();

        // Canonical main screenshot: genuinely logs off.
        takeNamedScreenshot(null);

        // Logs-on capture: turn the real switch on, scroll the root down where
        // it is a ScrollView (scrollTo clamps to range; the fixed landscape
        // FrameLayout root intentionally stays at 0 and uses the drawer
        // overlay), then screenshot.
        setSwitchShowLogs(true);
        scrollRootTo(Integer.MAX_VALUE);
        waitForIdleSync();
        takeNamedScreenshot("logs-on");

        // Wide landscape regression while logs are still ON: the drawer
        // overlay must host the log panel, root stays fixed, and closing
        // through button_close_logs must turn the switch back off.
        checkLandscapeLogDrawerRegression();

        // Live rotation: the manifest keeps MainActivity alive across config
        // changes, so the view tree itself must switch between the wide
        // FrameLayout root and the base ScrollView root in the SAME Activity.
        checkLiveRotationRebinding();

        // Restore state after the logs-on capture.
        setSwitchShowLogs(false);
        setSeekProgress(50);
        scrollRootTo(0);
        waitForIdleSync();
    }

    /**
     * Scrolls the root only when it is an actual ScrollView (base portrait).
     * The wide landscape root is a fixed FrameLayout: scrolling it would move
     * its children instead of revealing hidden content, so it stays at 0.
     */
    private void scrollRootTo(final int y) throws Exception {
        runOnMainSync(new Runnable() {
            @Override
            public void run() {
                View root = activity.findViewById(resId("root", "id"));
                if (root instanceof ScrollView) {
                    root.scrollTo(0, y);
                }
            }
        });
        waitForIdleSync();
    }

    /** Synthetic CardInfo for rendering only; never backed by real storage. */
    private void installSyntheticCard() throws Exception {
        Class<?> cardClass = Class.forName(
                "io.github.mydanyi.armadatf.ui.ShizukuManager$CardInfo", true, appLoader);
        Constructor<?> ctor = cardClass.getConstructor(
                String.class, long.class, String.class, boolean.class, String.class);
        final Object card = ctor.newInstance(
                QA_CARD_ID, Long.valueOf(QA_CARD_BYTES), QA_CARD_CID, Boolean.TRUE, null);

        // Spinner label uses the localized section name + computed size,
        // matching production style, instead of a raw QA byte count.
        final String spinnerLabel = stringRes("card_section") + " · 953.6 GiB";

        runOnMainSync(new Runnable() {
            @Override
            public void run() {
                try {
                    setPrivateField(activity, "selectedCard", card);
                    setPrivateField(activity, "eligibleCards", Collections.singletonList(card));
                    setPrivateField(activity, "cards", Collections.singletonList(card));

                    int spinnerLayoutId = resId("item_spinner", "layout");
                    int dropdownId = resId("item_spinner_dropdown", "layout");
                    ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                            activity,
                            spinnerLayoutId != 0 ? spinnerLayoutId : android.R.layout.simple_spinner_item,
                            Collections.singletonList(spinnerLabel));
                    if (dropdownId != 0) adapter.setDropDownViewResource(dropdownId);
                    Spinner spinner = (Spinner) activity.findViewById(resId("card_spinner", "id"));
                    check(spinner != null, "card_spinner not found");
                    spinner.setAdapter(adapter); // normal selection listener sees the fake card

                    invokePrivateMethod(activity, "updateAllocationLabels");
                } catch (Throwable t) {
                    throw new RuntimeException(t);
                }
            }
        });
        waitForIdleSync();

        // Synthetic rendering fixture only: reflect the ready-shell state and
        // button/spinner enablement so the emulator screenshot looks like the
        // production card-selected screen. Never sets real manager state and
        // never calls any service/storage/prepare/start/handoff path; the
        // buttons are never clicked by this suite (the focused open-shizuku
        // listener is replaced with a counter).
        final String readyShell = stringRes("shizuku_state_ready_shell");
        check(readyShell != null, "shizuku_state_ready_shell localized text not found");
        runOnMainSync(new Runnable() {
            @Override
            public void run() {
                TextView stateText = (TextView) activity.findViewById(
                        resId("shizuku_state_text", "id"));
                check(stateText != null, "shizuku_state_text not found");
                stateText.setText(readyShell);

                View authorize = activity.findViewById(resId("button_authorize", "id"));
                check(authorize != null, "button_authorize not found");
                authorize.setVisibility(View.GONE);

                View review = activity.findViewById(resId("button_review", "id"));
                View refresh = activity.findViewById(resId("button_refresh", "id"));
                Spinner spinner = (Spinner) activity.findViewById(resId("card_spinner", "id"));
                check(review != null && refresh != null && spinner != null,
                        "fixture review/refresh/spinner views not found");

                // Synthetic UI fixture only: this is synthetic rendering, not
                // real manager state. Enable/visible are set explicitly here,
                // then asserted. Never clicked, never backed by storage.
                review.setEnabled(true);
                refresh.setEnabled(true);
                spinner.setEnabled(true);
                if (review.getVisibility() != View.VISIBLE) {
                    review.setVisibility(View.VISIBLE);
                }
                if (refresh.getVisibility() != View.VISIBLE) {
                    refresh.setVisibility(View.VISIBLE);
                }
                if (spinner.getVisibility() != View.VISIBLE) {
                    spinner.setVisibility(View.VISIBLE);
                }

                check(review.getVisibility() == View.VISIBLE && review.isEnabled(),
                        "button_review must be visible and enabled in the fixture");
                check(refresh.getVisibility() == View.VISIBLE && refresh.isEnabled(),
                        "button_refresh must be visible and enabled in the fixture");
                check(spinner.getVisibility() == View.VISIBLE && spinner.isEnabled(),
                        "card_spinner must be visible and enabled in the fixture");
            }
        });
        waitForIdleSync();
        results.putString("fixture_state",
                "synthetic rendering fixture; not real authorization or card acceptance");
    }

    private void checkSeekBehaviour() throws Exception {
        SeekBar seek = (SeekBar) activity.findViewById(resId("seek_allocation", "id"));
        check(seek != null, "seek_allocation not found");
        checkEquals("seek.initial", 50, seek.getProgress());

        setSeekProgress(0);
        checkEquals("seek.set0.clamped", 10, seek.getProgress());
        assertPercentTexts(10);

        setSeekProgress(100);
        checkEquals("seek.set100.clamped", 90, seek.getProgress());
        assertPercentTexts(90);

        setSeekProgress(50);
        checkEquals("seek.set50.restored", 50, seek.getProgress());
        assertPercentTexts(50);

        String androidCap = textViewText("text_android_capacity");
        String linuxCap = textViewText("text_linux_capacity");
        String placeholder = stringRes("allocation_capacity_placeholder");
        results.putString("capacity_android_50", androidCap);
        results.putString("capacity_linux_50", linuxCap);
        check(androidCap.equals(linuxCap), "capacities at 50% must be equal: "
                + androidCap + " vs " + linuxCap);
        check(!androidCap.equals(placeholder),
                "capacity at 50% must not be the placeholder, was: " + androidCap);
        check(androidCap.contains("GiB"), "capacity at 50% must show GiB, was: " + androidCap);

        setSeekProgress(10);
        double android10 = parseCapacityGib(textViewText("text_android_capacity"));
        double linux10 = parseCapacityGib(textViewText("text_linux_capacity"));
        results.putDouble("capacity_android_10_gib", android10);
        results.putDouble("capacity_linux_10_gib", linux10);
        check(android10 < linux10, "at 10% Android capacity must be less than Linux: "
                + android10 + " vs " + linux10);

        setSeekProgress(90);
        double android90 = parseCapacityGib(textViewText("text_android_capacity"));
        double linux90 = parseCapacityGib(textViewText("text_linux_capacity"));
        results.putDouble("capacity_android_90_gib", android90);
        results.putDouble("capacity_linux_90_gib", linux90);
        check(android90 > linux90, "at 90% Android capacity must be greater than Linux: "
                + android90 + " vs " + linux90);

        setSeekProgress(50);
    }

    private void assertPercentTexts(int androidPercent) {
        String expectedAndroid = stringResFormatted("percent_label", androidPercent);
        String expectedLinux = stringResFormatted("percent_label", 100 - androidPercent);
        String actualAndroid = textViewText("text_android_percent");
        String actualLinux = textViewText("text_linux_percent");
        check(expectedAndroid.equals(actualAndroid),
                "android percent label: expected '" + expectedAndroid + "' got '" + actualAndroid + "'");
        check(expectedLinux.equals(actualLinux),
                "linux percent label: expected '" + expectedLinux + "' got '" + actualLinux + "'");
    }

    /** Real DPAD left/right on the focused slider: correct direction, stays 10..90. */
    private void checkDpadOnSlider() throws Exception {
        final SeekBar seek = (SeekBar) activity.findViewById(resId("seek_allocation", "id"));
        check(seek != null, "seek_allocation not found");
        setInTouchMode(false);
        waitForIdleSync();
        runOnMainSync(new Runnable() {
            @Override
            public void run() {
                seek.setFocusableInTouchMode(true);
                seek.requestFocus();
            }
        });
        waitForIdleSync();
        check(seek.hasFocus(), "seek_allocation did not take focus for DPAD check");

        int before = seek.getProgress();
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
        waitForIdleSync();
        int afterRight = seek.getProgress();
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);
        waitForIdleSync();
        int afterLeft = seek.getProgress();

        results.putInt("dpad.before", before);
        results.putInt("dpad.after_right", afterRight);
        results.putInt("dpad.after_left", afterLeft);
        check(afterRight >= before && afterRight <= 90,
                "DPAD_RIGHT must not move slider below " + before + " or above 90, got " + afterRight);
        check(afterLeft < afterRight,
                "DPAD_LEFT must move the slider back down: right=" + afterRight + " left=" + afterLeft);
        check(afterLeft >= 10 && afterLeft <= 90,
                "slider must remain within 10..90 after DPAD, got " + afterLeft);
    }

    private void checkLogsToggle() throws Exception {
        Switch switchShowLogs = (Switch) activity.findViewById(resId("switch_show_logs", "id"));
        View logPanel = activity.findViewById(resId("log_panel", "id"));
        View copyLogs = activity.findViewById(resId("button_copy_logs", "id"));
        check(switchShowLogs != null && logPanel != null && copyLogs != null,
                "logs toggle views not found");

        setSwitchShowLogs(true);
        waitForIdleSync();
        check(logPanel.getVisibility() == View.VISIBLE, "log_panel must be visible with logs on");
        check(copyLogs.getVisibility() == View.VISIBLE, "button_copy_logs must be visible with logs on");

        setSwitchShowLogs(false);
        waitForIdleSync();
        check(logPanel.getVisibility() == View.GONE, "log_panel must be gone with logs off");
        check(copyLogs.getVisibility() == View.GONE, "button_copy_logs must be gone with logs off");
    }

    private void checkBrandingAndTagline() {
        String version = textViewText("version_text");
        results.putString("version_text", version);
        check(version != null && version.contains("0.1.0"),
                "version_text must contain 0.1.0, was: " + version);

        View maker = activity.findViewById(resId("text_footer_name", "id"));
        String expectedMaker = stringRes("footer_name");
        check(maker instanceof TextView && expectedMaker.equals(((TextView) maker).getText().toString()),
                "footer_name text must equal the footer_name resource");
        check(maker.isClickable(), "footer maker name must be clickable");

        // Tagline: find the actual TextView by matching the localized resource.
        String expectedTagline = stringRes("app_tagline");
        check(expectedTagline != null && expectedTagline.length() > 0, "app_tagline resource missing");
        View root = activity.findViewById(resId("root", "id"));
        check(root instanceof ViewGroup, "root not found");
        TextView tagline = findTextViewByText((ViewGroup) root, expectedTagline);
        check(tagline != null, "no TextView shows the localized app_tagline: " + expectedTagline);
        results.putString("tagline", expectedTagline);
        if ("zh".equals(locale)) {
            check(TAGLINE_ZH_EXACT.equals(expectedTagline),
                    "zh tagline must be exactly '" + TAGLINE_ZH_EXACT + "', resource was: "
                            + expectedTagline);
        }
    }

    private void checkResponsivePanels() {
        int widthDp = activity.getResources().getConfiguration().screenWidthDp;
        // Matches the production helper: wide layout requires BOTH the width
        // threshold and landscape orientation.
        boolean wide = widthDp >= 760
                && activity.getResources().getConfiguration().orientation
                        == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout workspace = (LinearLayout) activity.findViewById(
                resId("workspace_panels", "id"));
        LinearLayout cardPanel = (LinearLayout) activity.findViewById(resId("card_panel", "id"));
        LinearLayout allocPanel = (LinearLayout) activity.findViewById(
                resId("allocation_panel", "id"));
        check(workspace != null && cardPanel != null && allocPanel != null,
                "responsive layout views not found");
        results.putInt("screen_width_dp", widthDp);
        results.putString("workspace_orientation", wide ? "horizontal" : "vertical");

        int expectedOrientation = wide
                ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL;
        check(workspace.getOrientation() == expectedOrientation,
                "workspace must be " + (wide ? "horizontal" : "vertical")
                        + " at " + widthDp + "dp");

        int[] cardLoc = new int[2];
        int[] allocLoc = new int[2];
        cardPanel.getLocationOnScreen(cardLoc);
        allocPanel.getLocationOnScreen(allocLoc);
        results.putString("card_panel_bounds", bounds(cardPanel, cardLoc));
        results.putString("allocation_panel_bounds", bounds(allocPanel, allocLoc));
        if (wide) {
            check(cardLoc[0] + cardPanel.getWidth() <= allocLoc[0],
                    "landscape: card panel must be left of allocation panel");
        } else {
            check(allocLoc[1] >= cardLoc[1] + cardPanel.getHeight(),
                    "portrait: allocation panel must be below card panel");
        }

        // No horizontal overflow for header / main controls.
        View root = activity.findViewById(resId("root", "id"));
        int[] rootLoc = new int[2];
        root.getLocationOnScreen(rootLoc);
        int rootLeft = rootLoc[0];
        int rootRight = rootLoc[0] + root.getWidth();
        int[] checks = {
                resId("header_text_group", "id"),
                resId("card_panel", "id"),
                resId("allocation_panel", "id"),
                resId("seek_allocation", "id"),
                resId("button_review", "id"),
        };
        for (int id : checks) {
            if (id == 0) continue;
            View v = activity.findViewById(id);
            if (v == null || v.getVisibility() != View.VISIBLE) continue;
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            check(loc[0] >= rootLeft - 1 && loc[0] + v.getWidth() <= rootRight + 1,
                    "view " + activity.getResources().getResourceEntryName(id)
                            + " overflows horizontally: [" + loc[0] + "," + (loc[0] + v.getWidth())
                            + "] vs root [" + rootLeft + "," + rootRight + "]");
        }
    }

    /**
     * The review action must be fully visible in the root viewport. Landscape
     * (960x540dp) with logs off and scroll 0: no scrolling allowed. Portrait:
     * vertical scrolling is allowed, so scroll to the bottom first and verify
     * reachability there. Fails on genuine clipping.
     */
    private void checkReviewVisibility() throws Exception {
        // Wide landscape root is a fixed FrameLayout; base portrait root is a
        // ScrollView. Both are ViewGroups, so only the ViewGroup contract is used.
        final ViewGroup root = (ViewGroup) activity.findViewById(resId("root", "id"));
        final Button review = (Button) activity.findViewById(resId("button_review", "id"));
        check(root != null && review != null, "root/button_review not found");
        check(review.getVisibility() == View.VISIBLE, "button_review must be visible");

        final boolean landscape = activity.getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        runOnMainSync(new Runnable() {
            @Override
            public void run() {
                if (landscape) {
                    root.scrollTo(0, 0); // landscape: no scrolling allowed
                } else {
                    // portrait: vertical scrolling allowed; verify at bottom
                    root.scrollTo(0, root.getChildAt(0).getHeight());
                }
            }
        });
        waitForIdleSync();
        int[] scroll = new int[1];
        runOnMainSync(new Runnable() {
            @Override
            public void run() {
                scroll[0] = root.getScrollY();
            }
        });
        results.putInt("review_check_scroll_y", scroll[0]);

        int[] reviewLoc = new int[2];
        review.getLocationOnScreen(reviewLoc);
        String fullBounds = bounds(review, reviewLoc);
        results.putString("review_full_bounds", fullBounds);

        // getGlobalVisibleRect is clipped to what is actually on screen, so
        // compare its size against the view's full size: partial clipping
        // (smaller visible rect) must FAIL, with 1px rounding tolerance.
        Rect visible = new Rect();
        boolean anyVisible = review.getGlobalVisibleRect(visible);
        Rect viewport = new Rect();
        root.getGlobalVisibleRect(viewport);
        results.putString("review_visible_rect", visible.toShortString());
        results.putString("root_viewport_rect", viewport.toShortString());
        results.putInt("review_expected_width", review.getWidth());
        results.putInt("review_expected_height", review.getHeight());
        results.putInt("review_visible_width", visible.width());
        results.putInt("review_visible_height", visible.height());
        check(anyVisible, "button_review is not visible in the root viewport at all "
                + "(full bounds " + fullBounds + ", viewport " + viewport.toShortString() + ")");
        check(visible.width() >= review.getWidth() - 1
                        && visible.height() >= review.getHeight() - 1,
                "button_review is clipped by the root viewport (orientation="
                        + (landscape ? "landscape" : "portrait") + "): full bounds "
                        + fullBounds + ", visible " + visible.width() + "x" + visible.height()
                        + " vs expected " + review.getWidth() + "x" + review.getHeight()
                        + ", viewport " + viewport.toShortString());
    }

    // --- landscape fixed-viewport regression ---------------------------------

    /**
     * Wide landscape (widthDp &gt;= 760) with the root scrolled to 0 must be a
     * fixed full viewport: the root ScrollView can scroll neither up nor down.
     * Narrow/portrait keeps the legacy stacked scrolling and is skipped here
     * (its behaviour is covered by checkReviewVisibility).
     */
    private void checkLandscapeFixedViewport() throws Exception {
        if (!isWideLandscape()) {
            results.putString("root_scroll_regression",
                    "skipped: narrow/portrait keeps stacked scrolling");
            return;
        }
        scrollRootTo(0);
        assertRootNotScrollable("landscape.root@0");
        results.putString("root_scroll_regression", "passed: root fixed at scroll 0");
    }

    /** Asserts the root view cannot scroll in either direction. */
    private void assertRootNotScrollable(final String label) throws Exception {
        final boolean[] can = new boolean[2];
        runOnMainSync(new Runnable() {
            @Override
            public void run() {
                View root = activity.findViewById(resId("root", "id"));
                if (root != null) {
                    can[0] = root.canScrollVertically(-1);
                    can[1] = root.canScrollVertically(1);
                }
            }
        });
        check(!can[0], label + ": root.canScrollVertically(-1) must be false (cannot scroll up)");
        check(!can[1], label + ": root.canScrollVertically(1) must be false (cannot scroll down)");
    }

    /**
     * Wide landscape only: header, version, footer, review action, logs
     * switch and every required icon must be FULLY inside the root viewport
     * (getGlobalVisibleRect size equals the view size, with the same 1px
     * rounding tolerance used by checkReviewVisibility), and icons must have
     * a non-null drawable. Portrait/narrow layouts may scroll, so this check
     * is skipped there.
     */
    private void checkMainControlsFullyVisible() {
        if (!isWideLandscape()) {
            results.putString("controls_fully_visible",
                    "skipped: portrait/narrow scrolling is allowed");
            return;
        }
        String[] controls = {
                "header_text_group", "version_text", "text_footer_name",
                "button_review", "switch_show_logs",
                "button_refresh", "button_open_shizuku", "card_spinner",
                "seek_allocation",
        };
        for (String name : controls) {
            assertFullyVisible(name);
        }
        View authorize = activity.findViewById(resId("button_authorize", "id"));
        if (authorize != null && authorize.getVisibility() == View.VISIBLE) {
            assertFullyVisible("button_authorize");
        }
        String[] icons = {
                "icon_android", "icon_linux", "icon_card_large",
                "icon_language", "icon_warning",
        };
        for (String name : icons) {
            assertFullyVisible(name);
            View icon = activity.findViewById(resId(name, "id"));
            Object drawable = (icon instanceof ImageView)
                    ? ((ImageView) icon).getDrawable() : icon.getBackground();
            check(drawable != null, name + " must have a non-null drawable");
        }
        results.putString("controls_fully_visible", "passed");
    }

    /** One view: present, VISIBLE and not clipped by the root viewport. */
    private void assertFullyVisible(String idName) {
        View v = activity.findViewById(resId(idName, "id"));
        check(v != null, idName + " not found in layout");
        check(v.getVisibility() == View.VISIBLE, idName + " must be VISIBLE");
        Rect visible = new Rect();
        boolean anyVisible = v.getGlobalVisibleRect(visible);
        check(anyVisible
                        && visible.width() >= v.getWidth() - 1
                        && visible.height() >= v.getHeight() - 1,
                idName + " is clipped by the viewport: visible "
                        + visible.width() + "x" + visible.height() + " vs expected "
                        + v.getWidth() + "x" + v.getHeight());
    }

    /** The review button label must match the localized required text. */
    private void checkReviewLocalizedLabel() {
        View review = activity.findViewById(resId("button_review", "id"));
        check(review instanceof TextView, "button_review must be a TextView");
        String actual = ((TextView) review).getText().toString();
        String expected = "zh".equals(locale)
                ? "确认分配方案" : "Confirm allocation plan";
        check(expected.equals(actual),
                "button_review label must be '" + expected + "' (locale=" + locale
                        + "), was: '" + actual + "'");
        results.putString("review_label", actual);
    }

    /**
     * Wide landscape regression while logs are ON: the log_drawer overlay (if
     * present) must be visible with log_panel still visible, close/copy
     * actions reachable, the root still fixed, and closing through the real
     * button_close_logs must flip switch_show_logs back to off. Portrait or
     * layouts without the drawer keep the legacy stacked behaviour asserted
     * by {@link #checkLogsToggle()}.
     */
    private void checkLandscapeLogDrawerRegression() throws Exception {
        if (!isWideLandscape()) return;
        View drawer = activity.findViewById(resId("log_drawer", "id"));
        if (drawer == null) {
            results.putString("log_drawer_regression", "skipped: log_drawer not present");
            return;
        }
        check(drawer.getVisibility() == View.VISIBLE,
                "log_drawer must be VISIBLE with logs on in wide landscape");
        View logPanel = activity.findViewById(resId("log_panel", "id"));
        check(logPanel != null && logPanel.getVisibility() == View.VISIBLE,
                "log_panel must remain visible inside the drawer with logs on");
        assertFullyVisible("button_close_logs");
        assertFullyVisible("button_copy_logs");
        assertRootNotScrollable("landscape.logs-on.root");

        // Close through the REAL drawer close control; the suite never
        // dispatches arbitrary clicks elsewhere.
        runOnMainSync(new Runnable() {
            @Override
            public void run() {
                View close = activity.findViewById(resId("button_close_logs", "id"));
                if (close != null) close.performClick();
            }
        });
        waitForIdleSync();

        Switch switchShowLogs = (Switch) activity.findViewById(resId("switch_show_logs", "id"));
        check(switchShowLogs != null && !switchShowLogs.isChecked(),
                "closing via button_close_logs must set switch_show_logs to off");
        check(drawer.getVisibility() != View.VISIBLE,
                "log_drawer must be hidden after button_close_logs");
        results.putString("log_drawer_regression",
                "passed: drawer open with fixed root, closed via button_close_logs");
    }

    /** Matches the production wide-layout rule: width threshold AND landscape. */
    private boolean isWideLandscape() {
        android.content.res.Configuration config = activity.getResources().getConfiguration();
        return config.screenWidthDp >= 760
                && config.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
    }

    // --- live rotation / view-tree rebuild ------------------------------------

    /**
     * ONE focused live-rotation check, wide landscape entry only. Sets the
     * synthetic slider to 60 and logs ON, rotates to portrait via public
     * {@link Activity#setRequestedOrientation}, and asserts the SAME Activity
     * (no recreation) now hosts a ScrollView root with seek 60, the same
     * synthetic selected card identifier and a checked logs switch. Rotates
     * back to landscape and asserts the FrameLayout root with every preference
     * retained, log_drawer visible and a non-scrollable root. Finally returns
     * logs OFF and slider 50. Public SDK surface only; no shell/device
     * commands, no backend actions. The synthetic card fixture survives in the
     * preserved Activity fields, so it is re-checked — not re-installed.
     */
    private void checkLiveRotationRebinding() throws Exception {
        if (!isWideLandscape()) {
            results.putString("rotation_rebind",
                    "skipped: suite did not start in wide landscape");
            return;
        }
        final String cardIdBefore = selectedCardId();
        check(cardIdBefore != null, "synthetic selectedCard missing before rotation");
        setSeekProgress(60);
        setSwitchShowLogs(true);

        requestOrientationAndWait(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        check(!activity.isFinishing(), "Activity must stay alive in portrait");
        View portraitRoot = activity.findViewById(resId("root", "id"));
        check(portraitRoot instanceof ScrollView,
                "portrait root must be a ScrollView after live rotation");
        check(portraitRoot.getContext() == activity,
                "portrait root must belong to the SAME Activity instance (no recreation)");
        checkEquals("rotation.portrait.seek", 60, seekProgress());
        check(cardIdBefore.equals(selectedCardId()),
                "synthetic selected card identifier must survive rotation to portrait");
        Switch logsPortrait = (Switch) activity.findViewById(resId("switch_show_logs", "id"));
        check(logsPortrait != null && logsPortrait.isChecked(),
                "logs switch must remain checked after rotation to portrait");

        requestOrientationAndWait(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        check(!activity.isFinishing(), "Activity must stay alive back in landscape");
        View landscapeRoot = activity.findViewById(resId("root", "id"));
        check(landscapeRoot instanceof FrameLayout && !(landscapeRoot instanceof ScrollView),
                "wide landscape root must be a FrameLayout (non-ScrollView) after rotating back");
        check(landscapeRoot.getContext() == activity,
                "landscape root must belong to the SAME Activity instance (no recreation)");
        checkEquals("rotation.landscape.seek", 60, seekProgress());
        check(cardIdBefore.equals(selectedCardId()),
                "synthetic selected card identifier must survive rotation back to landscape");
        Switch logsLandscape = (Switch) activity.findViewById(resId("switch_show_logs", "id"));
        check(logsLandscape != null && logsLandscape.isChecked(),
                "logs switch must remain checked after rotating back to landscape");
        View drawer = activity.findViewById(resId("log_drawer", "id"));
        check(drawer != null && drawer.getVisibility() == View.VISIBLE,
                "log_drawer must be visible again in wide landscape with logs on");
        assertRootNotScrollable("rotation.landscape.root");

        // Final state for anything rendered/asserted afterwards.
        setSwitchShowLogs(false);
        setSeekProgress(50);
        results.putString("rotation_rebind",
                "passed: same-Activity view tree rebuilt both ways, state preserved");
    }

    /**
     * Requests an orientation on the main thread, then polls (bounded, 5s)
     * with runOnMainSync until both the configuration orientation AND the
     * actual root view type match, so the asynchronous resize/rebind is
     * handled before any assertion runs.
     */
    private void requestOrientationAndWait(final int orientation) throws Exception {
        runOnMainSync(new Runnable() {
            @Override
            public void run() {
                activity.setRequestedOrientation(orientation);
            }
        });
        final boolean wantPortrait =
                orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        long deadline = SystemClock.uptimeMillis() + 5000L;
        while (SystemClock.uptimeMillis() < deadline) {
            final boolean[] done = new boolean[1];
            runOnMainSync(new Runnable() {
                @Override
                public void run() {
                    android.content.res.Configuration config =
                            activity.getResources().getConfiguration();
                    boolean orientationOk = wantPortrait
                            ? config.orientation
                                    == android.content.res.Configuration.ORIENTATION_PORTRAIT
                            : config.orientation
                                    == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
                    View root = activity.findViewById(resId("root", "id"));
                    boolean rootOk = wantPortrait
                            ? root instanceof ScrollView
                            : root instanceof FrameLayout && !(root instanceof ScrollView);
                    done[0] = orientationOk && rootOk;
                }
            });
            if (done[0]) {
                waitForIdleSync();
                return;
            }
            SystemClock.sleep(100);
        }
        throw new AssertionError("timed out waiting for "
                + (wantPortrait ? "portrait" : "landscape") + " layout switch");
    }

    private int seekProgress() {
        SeekBar seek = (SeekBar) activity.findViewById(resId("seek_allocation", "id"));
        return seek != null ? seek.getProgress() : -1;
    }

    /** Reads MainActivity.selectedCard.id via reflection (CardInfo identifier). */
    private String selectedCardId() throws Exception {
        Field cardField = activity.getClass().getDeclaredField("selectedCard");
        cardField.setAccessible(true);
        Object card = cardField.get(activity);
        if (card == null) return null;
        Field idField = card.getClass().getDeclaredField("id");
        idField.setAccessible(true);
        Object id = idField.get(card);
        return id != null ? String.valueOf(id) : null;
    }

    // --- screenshots -----------------------------------------------------------

    /** Forces a full decor redraw on MAIN and waits one frame before capture. */
    private void forceFrameAndWait() throws Exception {
        runOnMainSync(new Runnable() {
            @Override
            public void run() {
                View decor = activity.getWindow() != null
                        ? activity.getWindow().getDecorView() : null;
                if (decor != null) decor.invalidate();
            }
        });
        waitForIdleSync();
        // Allow one frame to actually render after the invalidate.
        SystemClock.sleep(32);
        waitForIdleSync();
    }

    /**
     * Saves a PNG via UiAutomation under the target app's external files dir,
     * before activity teardown, and records the absolute path in the results.
     */
    private void takeNamedScreenshot(String variant) {
        FileOutputStream fos = null;
        try {
            forceFrameAndWait();
            UiAutomation automation = getUiAutomation();
            Bitmap bitmap = automation.takeScreenshot();
            if (bitmap == null) {
                results.putString("screenshot_error_" + safe(variant), "takeScreenshot returned null");
                return;
            }
            File dir = getTargetContext().getExternalFilesDir(null);
            check(dir != null, "target external files dir unavailable");
            File out = new File(dir, screenshotName(variant));
            fos = new FileOutputStream(out);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.flush();
            results.putString("screenshot" + (variant == null ? "" : "_" + variant),
                    out.getAbsolutePath());
        } catch (Throwable t) {
            results.putString("screenshot_error_" + safe(variant), t.toString());
        } finally {
            if (fos != null) {
                try { fos.close(); } catch (Throwable ignored) { }
            }
        }
    }

    private String screenshotName(String variant) {
        String orientation = activity.getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                ? "landscape" : "portrait";
        String name = "ui-a-" + locale + "-" + orientation;
        if (variant != null) name += "-" + variant;
        return name + ".png";
    }

    // --- main-thread helpers ---------------------------------------------------

    private void setSeekProgress(final int progress) throws Exception {
        runOnMainSync(new Runnable() {
            @Override
            public void run() {
                SeekBar seek = (SeekBar) activity.findViewById(resId("seek_allocation", "id"));
                if (seek != null) seek.setProgress(progress);
            }
        });
        waitForIdleSync();
    }

    private void setSwitchShowLogs(final boolean checked) throws Exception {
        runOnMainSync(new Runnable() {
            @Override
            public void run() {
                Switch s = (Switch) activity.findViewById(resId("switch_show_logs", "id"));
                if (s != null) s.setChecked(checked); // fires the real listener
            }
        });
        waitForIdleSync();
    }

    private String textViewText(String idName) {
        final String[] out = new String[1];
        final int id = resId(idName, "id");
        runOnMainSync(new Runnable() {
            @Override
            public void run() {
                View v = activity.findViewById(id);
                if (v instanceof TextView) out[0] = String.valueOf(((TextView) v).getText());
            }
        });
        return out[0];
    }

    private TextView findTextViewByText(ViewGroup parent, String text) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof TextView && text.equals(String.valueOf(((TextView) child).getText()))) {
                return (TextView) child;
            }
            if (child instanceof ViewGroup) {
                TextView found = findTextViewByText((ViewGroup) child, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private int resId(String name, String type) {
        return activity.getResources().getIdentifier(name, type, TARGET_PACKAGE);
    }

    private String stringRes(String name) {
        int id = resId(name, "string");
        return id != 0 ? activity.getString(id) : null;
    }

    private String stringResFormatted(String name, int arg) {
        int id = resId(name, "string");
        return id != 0 ? activity.getString(id, arg) : null;
    }

    private static double parseCapacityGib(String text) {
        if (text == null) return -1;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("([0-9]+(?:\\.[0-9]+)?)").matcher(text);
        if (!m.find()) return -1;
        return Double.parseDouble(m.group(1));
    }

    private static void setPrivateField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void invokePrivateMethod(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(target);
    }

    private static String bounds(View v, int[] loc) {
        return "[" + loc[0] + "," + loc[1] + " " + v.getWidth() + "x" + v.getHeight() + "]";
    }

    private static String safe(String s) {
        return s == null ? "main" : s;
    }

    private void recordViewport() {
        android.content.res.Configuration config = activity.getResources().getConfiguration();
        results.putInt("screen_width_dp", config.screenWidthDp);
        results.putInt("screen_height_dp", config.screenHeightDp);
        results.putString("orientation", config.orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                ? "landscape" : "portrait");
    }

    // --- teardown / assertions -------------------------------------------------

    private void finishOnMain(final int code) {
        runOnMainSync(new Runnable() {
            @Override
            public void run() {
                try {
                    if (activity != null && !activity.isFinishing()) activity.finish();
                } catch (Throwable ignored) {
                    // best-effort cleanup
                }
                finish(code, results);
            }
        });
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void checkEquals(String label, int expected, int actual) {
        if (expected != actual) {
            throw new AssertionError(label + " expected=" + expected + " actual=" + actual);
        }
    }
}
