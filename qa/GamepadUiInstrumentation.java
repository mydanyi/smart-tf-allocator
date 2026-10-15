package io.github.mydanyi.armadatf.qa;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Standalone gamepad UI QA for MainActivity's AlertDialog handling.
 *
 * Extends {@link android.app.Instrumentation} directly (no androidx.test, no
 * legacy android.test.*, no new Gradle module or dependency) and only uses the
 * public Android SDK surface. It launches the real MainActivity, calls the
 * private {@code showDialog(...)} reflectively on the main thread with a benign
 * callback-only dialog (no TF / storage / card operations whatsoever), and then
 * drives BUTTON_A / BUTTON_B through the dialog window.
 *
 * Asserted behaviour of the destructive-confirm fix:
 *   - A with the negative (Cancel) button focused  -> Cancel runs, positive untouched
 *   - A with the positive button focused           -> positive runs, Cancel untouched
 *   - A while the key is held (repeats)            -> no action until the single ACTION_UP
 *   - A with no focused button + a Cancel present  -> safe default is Cancel
 *   - B always cancels via the negative button (or closes a positive-only dialog)
 *   - the safe default focus is the Cancel button when one exists
 *
 * It also reads the visible version TextView (must contain 0.1.0) and the maker
 * footer TextView (must match the {@code footer_name} string resource). It never
 * touches private app files, the filesystem, storage, or adoptable-card state.
 */
public class GamepadUiInstrumentation extends Instrumentation {

    private static final String TAG = "ArmadaTfGamepadQa";
    private static final String TARGET_PACKAGE = "io.github.mydanyi.armadatf";
    private static final String TARGET_ACTIVITY = "io.github.mydanyi.armadatf.ui.MainActivity";

    private final Bundle results = new Bundle();
    private final AtomicInteger positiveCalls = new AtomicInteger();
    private final AtomicInteger negativeCalls = new AtomicInteger();

    private Activity activity;
    private ClassLoader appLoader;
    private Class<?> function0Class;
    private Object unitInstance;
    private Method showDialogMethod;
    private Field activeDialogField;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        // onStart() runs on the process main thread; move the orchestration to a
        // worker so runOnMainSync() can drive UI work without deadlocking. The
        // activity and instrumentation are always torn down, pass or fail.
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                int code = Activity.RESULT_OK;
                try {
                    runChecks();
                    results.putString("status", "passed");
                    results.putString("test", "gamepad_dialog");
                } catch (Throwable t) {
                    code = Activity.RESULT_CANCELED;
                    results.putString("status", "failed");
                    results.putString("test", "gamepad_dialog");
                    results.putString("error", t.toString());
                    results.putString("stack", Log.getStackTraceString(t));
                } finally {
                    finishOnMain(code);
                }
            }
        }, "armada-tf-gamepad-qa");
        worker.start();
    }

    // --- test body -----------------------------------------------------------

    private void runChecks() throws Exception {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setClassName(TARGET_PACKAGE, TARGET_ACTIVITY);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity = startActivitySync(intent);
        check(activity != null, "MainActivity did not start");
        waitForIdleSync();
        appLoader = activity.getClassLoader();

        function0Class = Class.forName("kotlin.jvm.functions.Function0", false, appLoader);
        Class<?> unitClass = Class.forName("kotlin.Unit", true, appLoader);
        unitInstance = unitClass.getField("INSTANCE").get(null);

        showDialogMethod = activity.getClass().getDeclaredMethod(
                "showDialog",
                String.class, String.class, String.class, String.class, boolean.class,
                function0Class, function0Class);
        showDialogMethod.setAccessible(true);

        activeDialogField = activity.getClass().getDeclaredField("activeDialog");
        activeDialogField.setAccessible(true);

        checkBranding();

        scenarioANegativeFocused();
        scenarioBPositiveFocused();
        scenarioCHeldRepeats();
        scenarioDNoFocusDefaultsNegative();
        scenarioEButtonBCancels();

        results.putInt("scenarios", 5);
    }

    private void scenarioANegativeFocused() throws Exception {
        positiveCalls.set(0);
        negativeCalls.set(0);
        AlertDialog dialog = createTestDialog();
        focusButton(dialog, AlertDialog.BUTTON_NEGATIVE);
        pressButton(dialog, KeyEvent.KEYCODE_BUTTON_A);
        boolean showing = isShowing(dialog);
        dismissQuietly(dialog);
        checkEquals("A(negative-focused).positive", 0, positiveCalls.get());
        checkEquals("A(negative-focused).negative", 1, negativeCalls.get());
        check(!showing, "A on focused Cancel must dismiss the dialog");
    }

    private void scenarioBPositiveFocused() throws Exception {
        positiveCalls.set(0);
        negativeCalls.set(0);
        AlertDialog dialog = createTestDialog();
        focusButton(dialog, AlertDialog.BUTTON_POSITIVE);
        pressButton(dialog, KeyEvent.KEYCODE_BUTTON_A);
        boolean showing = isShowing(dialog);
        dismissQuietly(dialog);
        checkEquals("A(positive-focused).positive", 1, positiveCalls.get());
        checkEquals("A(positive-focused).negative", 0, negativeCalls.get());
        check(!showing, "A on focused positive must dismiss the dialog");
    }

    private void scenarioCHeldRepeats() throws Exception {
        positiveCalls.set(0);
        negativeCalls.set(0);
        AlertDialog dialog = createTestDialog();
        focusButton(dialog, AlertDialog.BUTTON_POSITIVE);
        dispatchKey(dialog, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, 0);
        dispatchKey(dialog, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, 1);
        dispatchKey(dialog, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, 2);
        dispatchKey(dialog, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, 3);
        checkEquals("A-held.before-up.positive", 0, positiveCalls.get());
        checkEquals("A-held.before-up.negative", 0, negativeCalls.get());
        dispatchKey(dialog, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A, 0);
        boolean showing = isShowing(dialog);
        dismissQuietly(dialog);
        checkEquals("A-held.after-up.positive", 1, positiveCalls.get());
        checkEquals("A-held.after-up.negative", 0, negativeCalls.get());
        check(!showing, "the single release after a held press must dismiss once");
    }

    private void scenarioDNoFocusDefaultsNegative() throws Exception {
        positiveCalls.set(0);
        negativeCalls.set(0);
        AlertDialog dialog = createTestDialog();
        clearFocus(dialog);
        pressButton(dialog, KeyEvent.KEYCODE_BUTTON_A);
        boolean showing = isShowing(dialog);
        dismissQuietly(dialog);
        checkEquals("A(no-focus).positive", 0, positiveCalls.get());
        checkEquals("A(no-focus).negative", 1, negativeCalls.get());
        check(!showing, "without focus the safe default is Cancel");
    }

    private void scenarioEButtonBCancels() throws Exception {
        positiveCalls.set(0);
        negativeCalls.set(0);
        AlertDialog dialog = createTestDialog();
        focusButton(dialog, AlertDialog.BUTTON_POSITIVE);
        pressButton(dialog, KeyEvent.KEYCODE_BUTTON_B);
        boolean showing = isShowing(dialog);
        dismissQuietly(dialog);
        checkEquals("B.positive", 0, positiveCalls.get());
        checkEquals("B.negative", 1, negativeCalls.get());
        check(!showing, "B must cancel regardless of current focus");
    }

    // --- dialog plumbing -----------------------------------------------------

    private AlertDialog createTestDialog() throws Exception {
        final AlertDialog[] holder = new AlertDialog[1];
        final Throwable[] failure = new Throwable[1];
        runOnMainSync(new Runnable() {
            @Override
            public void run() {
                try {
                    Object onPositive = newCounterCallback(positiveCalls);
                    Object onNegative = newCounterCallback(negativeCalls);
                    showDialogMethod.invoke(activity,
                            "QA gamepad", "QA harness dialog", "OK", "Cancel",
                            Boolean.TRUE, onPositive, onNegative);
                    holder[0] = (AlertDialog) activeDialogField.get(activity);
                } catch (InvocationTargetException e) {
                    failure[0] = e.getCause() != null ? e.getCause() : e;
                } catch (Throwable t) {
                    failure[0] = t;
                }
            }
        });
        if (failure[0] != null) {
            throw new AssertionError("showDialog invocation failed: " + failure[0], failure[0]);
        }
        check(holder[0] != null, "private activeDialog was not populated");
        // Let the dialog window finish its first layout before any focus request
        // or key dispatch. A real D-pad press makes ViewRoot leave touch mode;
        // the synthetic programmatic KeyEvent used here does not, so exit touch
        // mode explicitly to exercise the true D-pad navigation path.
        waitForIdleSync();
        setTouchModeFalse(holder[0]);
        return holder[0];
    }

    /**
     * A java.lang.reflect.Proxy implementing kotlin.jvm.functions.Function0 from
     * the target class loader. Each invocation bumps the counter and returns the
     * reflected kotlin.Unit.INSTANCE so the Kotlin {@code () -> Unit} callback
     * contract is satisfied without any test-side Kotlin runtime dependency.
     */
    private Object newCounterCallback(final AtomicInteger counter) {
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                String name = method.getName();
                if ("invoke".equals(name)) {
                    counter.incrementAndGet();
                    return unitInstance;
                }
                if ("toString".equals(name)) return "QaCounterCallback";
                if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                if ("equals".equals(name)) return proxy == (args != null && args.length > 0 ? args[0] : null);
                return null;
            }
        };
        return Proxy.newProxyInstance(appLoader, new Class<?>[]{ function0Class }, handler);
    }

    private void pressButton(final AlertDialog dialog, final int keyCode) {
        dispatchKey(dialog, KeyEvent.ACTION_DOWN, keyCode, 0);
        dispatchKey(dialog, KeyEvent.ACTION_UP, keyCode, 0);
    }

    /**
     * Delivers a key event to the dialog's own window callback (Dialog implements
     * Window.Callback), which is exactly where the OnKeyListener lives.
     */
    private void dispatchKey(final AlertDialog dialog, final int action, final int keyCode, final int repeat) {
        runOnMainSync(new Runnable() {
            @Override
            public void run() {
                long now = SystemClock.uptimeMillis();
                dialog.dispatchKeyEvent(new KeyEvent(now, now, action, keyCode, repeat));
            }
        });
    }

    private void focusButton(final AlertDialog dialog, final int which) {
        final boolean[] ok = new boolean[1];
        runOnMainSync(new Runnable() {
            @Override
            public void run() {
                Button button = dialog.getButton(which);
                ok[0] = button != null && button.requestFocus();
            }
        });
        check(ok[0], "could not focus dialog button " + which);
    }

    private void clearFocus(final AlertDialog dialog) {
        runOnMainSync(new Runnable() {
            @Override
            public void run() {
                View focused = dialog.getWindow().getDecorView().findFocus();
                if (focused != null) focused.clearFocus();
            }
        });
    }

    /**
     * Leaves the dialog window's touch mode so the harness mirrors real D-pad
     * navigation, which exits touch mode before focusing a button. Without this
     * the synthetic key events would run against a persistent touch-mode window
     * instead of the state the gamepad actually produces.
     */
    private void setTouchModeFalse(final AlertDialog dialog) {
        // Real D-pad navigation exits touch mode before focusing a button. The
        // public Instrumentation.setInTouchMode(false) is invoked on this QA
        // worker thread; waitForIdleSync() then lets the main thread apply it.
        setInTouchMode(false);
        waitForIdleSync();
    }

    private boolean isShowing(final AlertDialog dialog) {
        final boolean[] showing = new boolean[1];
        runOnMainSync(new Runnable() {
            @Override
            public void run() {
                showing[0] = dialog.isShowing();
            }
        });
        return showing[0];
    }

    private void dismissQuietly(final AlertDialog dialog) {
        runOnMainSync(new Runnable() {
            @Override
            public void run() {
                if (dialog.isShowing()) dialog.dismiss();
            }
        });
    }

    // --- branding ------------------------------------------------------------

    private void checkBranding() {
        final String[] version = new String[1];
        final String[] maker = new String[1];
        final String[] expectedMaker = new String[1];
        runOnMainSync(new Runnable() {
            @Override
            public void run() {
                int versionId = activity.getResources().getIdentifier("version_text", "id", TARGET_PACKAGE);
                int makerId = activity.getResources().getIdentifier("text_footer_name", "id", TARGET_PACKAGE);
                int makerStrId = activity.getResources().getIdentifier("footer_name", "string", TARGET_PACKAGE);
                View versionView = versionId != 0 ? activity.findViewById(versionId) : null;
                if (versionView instanceof TextView) version[0] = String.valueOf(((TextView) versionView).getText());
                View makerView = makerId != 0 ? activity.findViewById(makerId) : null;
                if (makerView instanceof TextView) maker[0] = String.valueOf(((TextView) makerView).getText());
                if (makerStrId != 0) expectedMaker[0] = activity.getString(makerStrId);
            }
        });
        results.putString("version_text", version[0]);
        results.putString("maker_text", maker[0]);
        check(version[0] != null && version[0].contains("0.1.0"),
                "visible version must show 0.1.0, was: " + version[0]);
        check(maker[0] != null && maker[0].equals(expectedMaker[0]),
                "maker footer must match footer_name resource, was: " + maker[0]);
    }

    // --- teardown / assertions ----------------------------------------------

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
