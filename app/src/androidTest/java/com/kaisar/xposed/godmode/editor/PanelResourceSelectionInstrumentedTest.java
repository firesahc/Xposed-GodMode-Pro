package com.kaisar.xposed.godmode.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.res.Configuration;
import android.content.Intent;
import android.os.SystemClock;
import android.os.LocaleList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import com.kaisar.xposed.godmode.R;
import com.kaisar.xposed.godmode.editor.overlay.MaskView;
import com.kaisar.xposed.godmode.editor.panel.NodeSelectorPanel;
import com.kaisar.xposed.godmode.engine.event.ActivityConfigurationSnapshot;
import com.kaisar.xposed.godmode.orchestrator.ViewControllerTestActivity;
import com.kaisar.xposed.godmode.util.ActivityConfigurationSnapshotMapper;
import com.kaisar.xposed.godmode.util.GmResources;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Verifies that Android Resources, rather than XML file presence, selects both variants. */
@RunWith(AndroidJUnit4.class)
public final class PanelResourceSelectionInstrumentedTest {

    /** Match the project's existing instrumentation pattern: launch the app before assertions. */
    @Before
    public void startTestHost() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        if (findResumedHost(null) != null) return;
        Intent intent = new Intent(instrumentation.getTargetContext(),
                ViewControllerTestActivity.class).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        Activity activity = instrumentation.startActivitySync(intent);
        if (!(activity instanceof ViewControllerTestActivity)) {
            throw new AssertionError("Unexpected test host Activity: " + activity);
        }
    }

    @After
    public void finishTestHost() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.runOnMainSync(() -> {
            Collection<Activity> resumed = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED);
            List<Activity> activities = new ArrayList<>(resumed);
            for (Activity activity : activities) {
                if (activity instanceof ViewControllerTestActivity) activity.finish();
            }
        });
        instrumentation.waitForIdleSync();
    }

    @Test
    public void portraitInflationUsesVerticalToolbarColumn() {
        assertToolbarOrientation(Configuration.ORIENTATION_PORTRAIT, LinearLayout.VERTICAL);
    }

    @Test
    public void landscapeInflationUsesHorizontalToolbarColumn() {
        assertToolbarOrientation(Configuration.ORIENTATION_LANDSCAPE, LinearLayout.HORIZONTAL);
    }

    @Test
    public void productionUiContextInflationUsesRequestedConfiguration() throws Exception {
        runOnMain(awaitInitialHost(), activity -> {
                assertProductionUiContext(activity, Configuration.ORIENTATION_PORTRAIT,
                        LinearLayout.VERTICAL);
                assertProductionUiContext(activity, Configuration.ORIENTATION_LANDSCAPE,
                        LinearLayout.HORIZONTAL);
        });
    }

    @Test
    public void configurationOverlayPreservesNonProjectedQualifiers() throws Exception {
        runOnMain(awaitInitialHost(), activity -> {
            Configuration base = new Configuration();
            base.setLocales(new LocaleList(Locale.US));
            base.mcc = 310;
            base.mnc = 260;
            base.keyboard = Configuration.KEYBOARD_QWERTY;
            base.keyboardHidden = Configuration.KEYBOARDHIDDEN_NO;
            base.navigation = Configuration.NAVIGATION_DPAD;
            base.touchscreen = Configuration.TOUCHSCREEN_FINGER;

            ActivityConfigurationSnapshot snapshot = snapshotFor(
                    activity, Configuration.ORIENTATION_LANDSCAPE);
            Configuration overlaid = ActivityConfigurationSnapshotMapper.overlay(base, snapshot);

            assertEquals("en-US", base.getLocales().toLanguageTags());
            assertEquals(310, base.mcc);
            assertEquals(260, base.mnc);
            assertEquals(Configuration.KEYBOARD_QWERTY, base.keyboard);
            assertEquals(Configuration.KEYBOARDHIDDEN_NO, base.keyboardHidden);
            assertEquals(Configuration.NAVIGATION_DPAD, base.navigation);
            assertEquals(Configuration.TOUCHSCREEN_FINGER, base.touchscreen);
            assertEquals("en-US", overlaid.getLocales().toLanguageTags());
            assertEquals(310, overlaid.mcc);
            assertEquals(260, overlaid.mnc);
            assertEquals(Configuration.KEYBOARD_QWERTY, overlaid.keyboard);
            assertEquals(Configuration.KEYBOARDHIDDEN_NO, overlaid.keyboardHidden);
            assertEquals(Configuration.NAVIGATION_DPAD, overlaid.navigation);
            assertEquals(Configuration.TOUCHSCREEN_FINGER, overlaid.touchscreen);
            assertEquals(snapshot.getOrientation(), overlaid.orientation);
        });
    }

    @Test
    public void immediateRebuildLeavesOnePanelAndOneMask() throws Exception {
        runOnMain(awaitInitialHost(), activity -> {
                ViewGroup content = activity.findViewById(android.R.id.content);
                assertEquals(1, content.getChildCount());
                FrameLayout container = (FrameLayout) content.getChildAt(0);
                View target = new View(activity);
                container.addView(target, new FrameLayout.LayoutParams(1, 1));
                NodeSelectorPanel panel = new NodeSelectorPanel();
                ActivityConfigurationSnapshot portrait = snapshotFor(activity,
                        Configuration.ORIENTATION_PORTRAIT);
                ActivityConfigurationSnapshot landscape = snapshotFor(activity,
                        Configuration.ORIENTATION_LANDSCAPE);
                panel.show(Collections.singletonList(new WeakReference<>(target)), activity,
                        container, 0x66000000, null, portrait);
                assertEquals(1, countPanels(container));
                assertEquals(1, countMasks(container));

                panel.dismissImmediatelyForRebuild();
                assertEquals(0, countPanels(container));
                assertEquals(0, countMasks(container));

                panel.show(Collections.singletonList(new WeakReference<>(target)), activity,
                        container, 0x66000000, null, landscape);
                assertEquals(1, countPanels(container));
                assertEquals(1, countMasks(container));
                assertEquals(LinearLayout.HORIZONTAL,
                        resolveToolbarOrientation(panel.getPanelView()));
                panel.dismissImmediatelyForRebuild();
                assertEquals(0, countPanels(container));
                assertEquals(0, countMasks(container));
        });
    }

    private static ViewControllerTestActivity awaitInitialHost() {
        ViewControllerTestActivity activity = awaitHostOtherThan(null, 10_000L);
        assertNotNull("Test host Activity was not started", activity);
        return activity;
    }

    private static ViewControllerTestActivity awaitHostOtherThan(
            ViewControllerTestActivity previous, long timeoutMillis) {
        long deadline = SystemClock.uptimeMillis() + timeoutMillis;
        while (SystemClock.uptimeMillis() < deadline) {
            ViewControllerTestActivity found = findResumedHost(previous);
            if (found != null) return found;
            SystemClock.sleep(50L);
        }
        return null;
    }

    private static ViewControllerTestActivity findResumedHost(
            ViewControllerTestActivity previous) {
        AtomicReference<ViewControllerTestActivity> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Collection<Activity> resumed = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED);
            for (Activity activity : resumed) {
                if (activity instanceof ViewControllerTestActivity && activity != previous) {
                    result.set((ViewControllerTestActivity) activity);
                    return;
                }
            }
        });
        return result.get();
    }

    private static void runOnMain(ViewControllerTestActivity activity,
            ActivityAssertion assertion) throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try {
                assertion.run(activity);
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        Throwable throwable = failure.get();
        if (throwable instanceof Exception) throw (Exception) throwable;
        if (throwable instanceof Error) throw (Error) throwable;
        if (throwable != null) throw new AssertionError(throwable);
    }

    private interface ActivityAssertion {
        void run(ViewControllerTestActivity activity) throws Exception;
    }

    private static ActivityConfigurationSnapshot snapshotFor(Activity activity, int orientation) {
        Configuration configuration = new Configuration(activity.getResources().getConfiguration());
        configuration.orientation = orientation;
        return ActivityConfigurationSnapshotMapper.from(configuration);
    }

    private static void assertProductionUiContext(Activity activity, int orientation,
            int expectedToolbarOrientation) {
        ActivityConfigurationSnapshot requested = snapshotFor(activity, orientation);
        GmResources.UiContext uiContext = GmResources.createUiContext(activity, requested);
        FrameLayout parent = new FrameLayout(activity);
        View panel = GmResources.inflate(uiContext, activity,
                R.layout.panel_node_selector, parent, false);

        assertFalse("test app should use package-context resources", uiContext.isFallback());
        assertEquals(requested, uiContext.getConfigurationSnapshot());
        assertToolbarOrientation(panel, orientation, expectedToolbarOrientation);
    }

    private static int countPanels(ViewGroup root) {
        int count = 0;
        for (int i = 0; i < root.getChildCount(); i++) {
            if (root.getChildAt(i).findViewById(R.id.top_content) != null) count++;
        }
        return count;
    }

    private static int countMasks(ViewGroup root) {
        int count = 0;
        for (int i = 0; i < root.getChildCount(); i++) {
            if (root.getChildAt(i) instanceof MaskView) count++;
        }
        return count;
    }

    private static void assertToolbarOrientation(int orientation, int expected) {
        Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.orientation = orientation;
        Context configured = base.createConfigurationContext(configuration);

        FrameLayout parent = new FrameLayout(configured);
        View panel = LayoutInflater.from(configured).inflate(
                R.layout.panel_node_selector, parent, false);
        assertNotNull(panel);
        assertToolbarOrientation(panel, orientation, expected);
    }

    private static void assertToolbarOrientation(View panel, int orientation, int expected) {
        View topContent = panel.findViewById(R.id.top_content);
        assertTrue(topContent instanceof LinearLayout);
        View toolbar = ((LinearLayout) topContent).getChildAt(0);
        assertTrue(toolbar instanceof LinearLayout);
        LinearLayout toolbarColumn = (LinearLayout) toolbar;
        assertEquals(expected, toolbarColumn.getOrientation());
        assertTrue("toolbar must contain both semantic groups", toolbarColumn.getChildCount() >= 2);
        int firstExpected = orientation == Configuration.ORIENTATION_LANDSCAPE
                ? R.id.action_group : R.id.selection_group;
        int secondExpected = orientation == Configuration.ORIENTATION_LANDSCAPE
                ? R.id.selection_group : R.id.action_group;
        assertEquals(firstExpected, toolbarColumn.getChildAt(0).getId());
        assertEquals(secondExpected, toolbarColumn.getChildAt(1).getId());
    }

    private static int resolveToolbarOrientation(View panel) {
        View topContent = panel.findViewById(R.id.top_content);
        assertTrue(topContent instanceof LinearLayout);
        View toolbar = ((LinearLayout) topContent).getChildAt(0);
        assertTrue(toolbar instanceof LinearLayout);
        return ((LinearLayout) toolbar).getOrientation();
    }
}
