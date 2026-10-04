package app.plyvanta.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicReference;

import app.plyvanta.R;

/** Exercises the real Settings switch and its saved state through an activity restart. */
@RunWith(AndroidJUnit4.class)
public final class TorSettingsInstrumentedTest {
    private static final long WAIT_TIMEOUT_MS = 10_000L;

    private Instrumentation instrumentation;
    private Context context;
    private Activity launchedActivity;
    private NetworkPreferenceSnapshot originalPreferences;
    private AccessibilityServiceInfo originalAccessibilityServiceInfo;

    @Before
    public void setUp() {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        originalPreferences = new NetworkPreferenceSnapshot(context);
        originalAccessibilityServiceInfo = instrumentation.getUiAutomation().getServiceInfo();
        AccessibilityServiceInfo interactiveWindows = instrumentation.getUiAutomation()
                .getServiceInfo();
        interactiveWindows.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        instrumentation.getUiAutomation().setServiceInfo(interactiveWindows);
    }

    @After
    public void tearDown() {
        finishActivity();
        if (originalPreferences != null) {
            originalPreferences.close();
        }
        if (originalAccessibilityServiceInfo != null) {
            instrumentation.getUiAutomation().setServiceInfo(originalAccessibilityServiceInfo);
        }
    }

    @Test
    public void enablingTorInSettingsPersistsAndRemainsCheckedAfterActivityRestart() {
        AppNetwork.setTorEnabled(false, AppNetwork.DEFAULT_TOR_PORT);
        launchActivity();
        openSettings();
        AccessibilityNodeInfo torSwitch = waitForDescription(context.getString(R.string.tor_use));
        assertTrue("Tor control must expose its switch state to accessibility",
                torSwitch.isCheckable());
        assertFalse(torSwitch.isChecked());
        assertTrue("Could not click the Tor switch",
                torSwitch.performAction(AccessibilityNodeInfo.ACTION_CLICK));
        instrumentation.waitForIdleSync();
        assertTrue("The Settings switch did not enable the network policy",
                AppNetwork.isTorEnabled());
        assertTrue("The Settings switch did not save the network policy",
                context.getSharedPreferences(NetworkPreferenceSnapshot.PREFERENCES_NAME,
                        Context.MODE_PRIVATE).getBoolean("tor_enabled", false));

        finishActivity();
        launchActivity();
        openSettings();
        AccessibilityNodeInfo restoredSwitch = waitForDescription(
                context.getString(R.string.tor_use)
        );
        assertTrue("Tor must remain selected after reopening the app",
                restoredSwitch.isChecked());
        assertTrue(AppNetwork.isTorEnabled());
    }

    @Test
    public void externalTorChangeRefreshesForegroundBannerWithoutActivityRestart() {
        AppNetwork.setTorEnabled(true, AppNetwork.DEFAULT_TOR_PORT);
        launchActivity();
        // Resolve the first-launch explanation before observing the foreground activity.
        waitForDescription(context.getString(R.string.settings));
        waitForText(context.getString(R.string.tor_routing_banner));

        // Change the shared policy directly, without clicking Settings or resuming the activity.
        AppNetwork.setTorEnabled(false, AppNetwork.DEFAULT_TOR_PORT);
        waitUntilTextIsAbsent(context.getString(R.string.tor_routing_banner));
        assertFalse("The foreground activity should remain open during a route change",
                launchedActivity.isFinishing());
        assertFalse(AppNetwork.isTorEnabled());

        openSettings();
        AccessibilityNodeInfo torSwitch = waitForDescription(context.getString(R.string.tor_use));
        assertTrue(torSwitch.isCheckable());
        assertFalse("Reopened Settings must reflect the externally selected route",
                torSwitch.isChecked());
    }

    @Test
    public void torBrowserDisclosureBlocksBugReportHandoffUntilContinueIsChosen() {
        AppNetwork.setTorEnabled(true, AppNetwork.DEFAULT_TOR_PORT);
        launchActivity();
        openSettings();
        clickText(context.getString(R.string.start_report));

        AccessibilityNodeInfo description = waitForEditableReport();
        Bundle text = new Bundle();
        String fixtureDescription = "Tor disclosure instrumentation fixture.";
        text.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                fixtureDescription);
        assertTrue("Could not enter the local report fixture",
                description.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text));
        dismissKeyboardIfVisible();
        clickText(context.getString(R.string.review_report));

        BlockingHandoffMonitor monitor = new BlockingHandoffMonitor();
        instrumentation.addMonitor(monitor);
        try {
            clickText(context.getString(R.string.open_github));
            waitForText(context.getString(R.string.tor_external_title));
            waitForText(context.getString(R.string.tor_external_detail));
            assertNull("No browser handoff is allowed before Tor disclosure confirmation",
                    monitor.capturedIntent.get());

            clickText(context.getString(android.R.string.cancel));
            waitForText(context.getString(R.string.open_github));
            assertNull("Cancelling the disclosure must keep the report in Plyvanta",
                    monitor.capturedIntent.get());

            clickText(context.getString(R.string.open_github));
            waitForText(context.getString(R.string.tor_external_title));
            clickText(context.getString(R.string.tor_external_continue));
            long deadline = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MS;
            while (monitor.capturedIntent.get() == null
                    && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(50L);
            }
            Intent captured = monitor.capturedIntent.get();
            assertNotNull("Confirming the disclosure did not prepare a browser handoff", captured);
            Intent browser = captured.getParcelableExtra(Intent.EXTRA_INTENT);
            assertNotNull(browser);
            assertEquals(Intent.ACTION_VIEW, browser.getAction());
            assertNotNull(browser.getData());
            assertEquals("github.com", browser.getData().getHost());
            assertTrue(browser.getData().getPath().endsWith("/issues/new"));
            assertTrue(browser.getData().getQueryParameter("body").contains(fixtureDescription));
        } finally {
            instrumentation.removeMonitor(monitor);
        }
    }

    private void launchActivity() {
        Intent intent = context.getPackageManager()
                .getLaunchIntentForPackage(context.getPackageName());
        assertNotNull("Debug app has no launch intent", intent);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        launchedActivity = instrumentation.startActivitySync(intent);
        assertNotNull("Debug app did not launch", launchedActivity);
        instrumentation.waitForIdleSync();
    }

    private void finishActivity() {
        if (instrumentation != null && launchedActivity != null) {
            Activity activity = launchedActivity;
            launchedActivity = null;
            instrumentation.runOnMainSync(activity::finish);
            instrumentation.waitForIdleSync();
        }
    }

    private void openSettings() {
        AccessibilityNodeInfo settings = waitForDescription(context.getString(R.string.settings));
        assertTrue("Could not open Settings",
                settings.performAction(AccessibilityNodeInfo.ACTION_CLICK));
        instrumentation.waitForIdleSync();
    }

    private AccessibilityNodeInfo waitForDescription(String expected) {
        long deadline = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MS;
        do {
            dismissFirstLaunchNotificationExplanation();
            AccessibilityNodeInfo result = findDescription(expected);
            if (result != null) {
                return result;
            }
            SystemClock.sleep(50L);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Control with content description was not visible: " + expected);
    }

    private AccessibilityNodeInfo findDescription(String expected) {
        return findNode(expected, true, false);
    }

    private AccessibilityNodeInfo waitForText(String expected) {
        long deadline = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MS;
        do {
            AccessibilityNodeInfo result = findNode(expected, false, false);
            if (result != null) {
                return result;
            }
            SystemClock.sleep(50L);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Text was not visible: " + expected);
    }

    private void waitUntilTextIsAbsent(String expected) {
        long deadline = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MS;
        do {
            if (findNode(expected, false, false) == null) {
                return;
            }
            SystemClock.sleep(50L);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Stale text remained visible: " + expected);
    }

    private void clickText(String expected) {
        AccessibilityNodeInfo node = waitForText(expected);
        while (!node.isClickable() && node.getParent() != null) {
            node = node.getParent();
        }
        assertTrue("Could not click " + expected,
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK));
        instrumentation.waitForIdleSync();
    }

    private AccessibilityNodeInfo waitForEditableReport() {
        long deadline = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MS;
        do {
            AccessibilityNodeInfo result = findNode(
                    context.getString(R.string.bug_description_hint), false, true
            );
            if (result != null) {
                return result;
            }
            SystemClock.sleep(50L);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Bug report description input was not visible");
    }

    private void dismissFirstLaunchNotificationExplanation() {
        if (findNode(context.getString(R.string.update_permission_title), false, false) != null) {
            AccessibilityNodeInfo notNow = findNode(
                    context.getString(R.string.not_now), false, false
            );
            if (notNow != null) {
                assertTrue("Could not dismiss the first-launch notification explanation",
                        notNow.performAction(AccessibilityNodeInfo.ACTION_CLICK));
                instrumentation.waitForIdleSync();
            }
        }
    }

    private AccessibilityNodeInfo findNode(String expected, boolean byDescription,
            boolean editableHint) {
        Deque<AccessibilityNodeInfo> remaining = new ArrayDeque<>();
        // Dialog and keyboard windows can coexist; the active window may be the keyboard.
        for (AccessibilityWindowInfo window : instrumentation.getUiAutomation().getWindows()) {
            if (window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root != null) {
                    remaining.add(root);
                }
            }
        }
        AccessibilityNodeInfo activeRoot = instrumentation.getUiAutomation().getRootInActiveWindow();
        if (activeRoot != null) {
            remaining.add(activeRoot);
        }
        while (!remaining.isEmpty()) {
            AccessibilityNodeInfo node = remaining.removeFirst();
            CharSequence candidate = editableHint ? node.getHintText()
                    : byDescription ? node.getContentDescription() : node.getText();
            // AlertDialog action labels use Android's all-caps display transformation.
            if (candidate != null && expected.equalsIgnoreCase(candidate.toString())
                    && (!editableHint || node.isEditable())) {
                return node;
            }
            for (int index = 0; index < node.getChildCount(); index++) {
                AccessibilityNodeInfo child = node.getChild(index);
                if (child != null) {
                    remaining.addLast(child);
                }
            }
        }
        return null;
    }

    private void dismissKeyboardIfVisible() {
        for (AccessibilityWindowInfo window : instrumentation.getUiAutomation().getWindows()) {
            if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                assertTrue("Could not dismiss the keyboard above the report actions",
                        instrumentation.getUiAutomation().performGlobalAction(
                                AccessibilityService.GLOBAL_ACTION_BACK
                        ));
                instrumentation.waitForIdleSync();
                return;
            }
        }
    }

    /** Intercepts the prepared chooser so this test never opens or sends an actual report. */
    private static final class BlockingHandoffMonitor extends Instrumentation.ActivityMonitor {
        private final AtomicReference<Intent> capturedIntent = new AtomicReference<>();

        BlockingHandoffMonitor() {
            super();
        }

        @Override
        public Instrumentation.ActivityResult onStartActivity(Intent intent) {
            if (!Intent.ACTION_CHOOSER.equals(intent.getAction())) {
                return null;
            }
            capturedIntent.set(new Intent(intent));
            return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null);
        }
    }
}
