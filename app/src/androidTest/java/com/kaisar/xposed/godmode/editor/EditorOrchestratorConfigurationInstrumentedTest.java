package com.kaisar.xposed.godmode.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.SystemClock;
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
import com.kaisar.xposed.godmode.engine.Property;
import com.kaisar.xposed.godmode.engine.event.ActivityConfigurationSnapshot;
import com.kaisar.xposed.godmode.engine.event.ActivityLifecycleEvent;
import com.kaisar.xposed.godmode.ipc.contract.RuleMutationResult;
import com.kaisar.xposed.godmode.ipc.contract.UndoResultParcel;
import com.kaisar.xposed.godmode.ipc.contract.UndoStateParcel;
import com.kaisar.xposed.godmode.orchestrator.ViewControllerTestActivity;
import com.kaisar.xposed.godmode.rule.RuleRecord;
import com.kaisar.xposed.godmode.util.ActivityConfigurationSnapshotMapper;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Exercises the production lifecycle event path that rebuilds the selector panel. */
@RunWith(AndroidJUnit4.class)
public final class EditorOrchestratorConfigurationInstrumentedTest {

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
    public void configChangedRebuildsSelectorAcrossOrientations() throws Exception {
        runOnMain(awaitInitialHost(), activity -> {
            Property<Boolean> switchProp = new Property<>(true);
            EditorOrchestrator orchestrator = new EditorOrchestrator(
                    switchProp, new FakeRuleEditor());
            orchestrator.setActivity(activity);
            orchestrator.setDisplay(true);

            assertPanelState(activity, orchestrator, LinearLayout.VERTICAL);

            ActivityConfigurationSnapshot landscape = snapshotFor(
                    activity, Configuration.ORIENTATION_LANDSCAPE);
            orchestrator.onActivityLifecycle(new ActivityLifecycleEvent(
                    ActivityLifecycleEvent.Type.CONFIG_CHANGED, activity, landscape));
            assertPanelState(activity, orchestrator, LinearLayout.HORIZONTAL);

            orchestrator.onActivityLifecycle(new ActivityLifecycleEvent(
                    ActivityLifecycleEvent.Type.CONFIG_CHANGED, activity, landscape));
            assertPanelState(activity, orchestrator, LinearLayout.HORIZONTAL);

            ActivityConfigurationSnapshot portrait = snapshotFor(
                    activity, Configuration.ORIENTATION_PORTRAIT);
            orchestrator.onActivityLifecycle(new ActivityLifecycleEvent(
                    ActivityLifecycleEvent.Type.CONFIG_CHANGED, activity, portrait));
            assertPanelState(activity, orchestrator, LinearLayout.VERTICAL);
        });
    }

    @Test
    public void layoutObservationAndConfigChangedConvergeToOnePanel() throws Exception {
        ViewControllerTestActivity activity = awaitInitialHost();
        EditorOrchestrator orchestrator = new EditorOrchestrator(
                new Property<>(true), new FakeRuleEditor());
        runOnMain(activity, host -> {
            orchestrator.setActivity(host);
            orchestrator.setDisplay(true);
            assertPanelState(host, orchestrator, LinearLayout.VERTICAL);

            host.getWindow().getDecorView().requestLayout();
            orchestrator.onActivityLifecycle(new ActivityLifecycleEvent(
                    ActivityLifecycleEvent.Type.CONFIG_CHANGED,
                    host,
                    snapshotFor(host, Configuration.ORIENTATION_LANDSCAPE)));
        });

        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        runOnMain(activity, host -> assertPanelState(host, orchestrator,
                LinearLayout.HORIZONTAL));
    }

    @Test
    public void configurationChangedDoesNotOpenHiddenPanel() throws Exception {
        runOnMain(awaitInitialHost(), activity -> {
            EditorOrchestrator orchestrator = new EditorOrchestrator(
                    new Property<>(true), new FakeRuleEditor());
            orchestrator.setActivity(activity);

            orchestrator.onActivityLifecycle(new ActivityLifecycleEvent(
                    ActivityLifecycleEvent.Type.CONFIG_CHANGED,
                    activity,
                    snapshotFor(activity, Configuration.ORIENTATION_LANDSCAPE)));

            assertFalse(orchestrator.getNodePanel().isShowing());
            assertEquals(0, countPanels(decor(activity)));
            assertEquals(0, countMasks(decor(activity)));
        });
    }

    @Test
    public void propertyEditorConfigurationChangedClosesCurrentSession() throws Exception {
        runOnMain(awaitInitialHost(), activity -> {
            Property<Boolean> switchProp = new Property<>(true);
            EditorOrchestrator orchestrator = new EditorOrchestrator(
                    switchProp, new FakeRuleEditor());
            orchestrator.setActivity(activity);
            orchestrator.setDisplay(true);

            View target = new View(activity);
            ViewGroup content = activity.findViewById(android.R.id.content);
            assertNotNull(content);
            content.addView(target, new FrameLayout.LayoutParams(32, 32));
            orchestrator.getPropertyEditor().show(target, activity,
                    decor(activity), false);
            assertTrue(orchestrator.getPropertyEditor().isShowing());

            orchestrator.onActivityLifecycle(new ActivityLifecycleEvent(
                    ActivityLifecycleEvent.Type.CONFIG_CHANGED,
                    activity,
                    snapshotFor(activity, Configuration.ORIENTATION_LANDSCAPE)));

            assertFalse(orchestrator.getPropertyEditor().isShowing());
            assertFalse(orchestrator.getNodePanel().isShowing());
        });
    }

    private static void assertPanelState(ViewControllerTestActivity activity,
            EditorOrchestrator orchestrator, int expectedOrientation) {
        ViewGroup decor = decor(activity);
        assertEquals(1, countPanels(decor));
        assertEquals(1, countMasks(decor));
        assertTrue(orchestrator.getNodePanel().isShowing());
        assertEquals(expectedOrientation,
                orchestrator.getNodePanel().getRenderedToolbarOrientation());
        View panel = orchestrator.getNodePanel().getPanelView();
        assertNotNull(panel);
        View topContent = panel.findViewById(R.id.top_content);
        assertTrue(topContent instanceof ViewGroup);
    }

    private static ViewGroup decor(ViewControllerTestActivity activity) {
        return (ViewGroup) activity.getWindow().getDecorView();
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

    private static ActivityConfigurationSnapshot snapshotFor(Activity activity, int orientation) {
        Configuration configuration = new Configuration(activity.getResources().getConfiguration());
        configuration.orientation = orientation;
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            int width = configuration.screenWidthDp;
            configuration.screenWidthDp = configuration.screenHeightDp;
            configuration.screenHeightDp = width;
        }
        return ActivityConfigurationSnapshotMapper.from(configuration);
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

    private static final class FakeRuleEditor implements IRuleEditor {
        @Override
        public boolean writeRule(String packageName, RuleRecord rule, Bitmap snapshot) {
            return false;
        }

        @Override
        public RuleMutationResult writeUndoableRule(String packageName, RuleRecord rule,
                Bitmap snapshot, Bitmap modifiedSnapshot) {
            return null;
        }

        @Override
        public UndoStateParcel getUndoState(String packageName) {
            return null;
        }

        @Override
        public UndoResultParcel undoLatest(String packageName, UndoStateParcel expected) {
            return null;
        }

        @Override
        public boolean deleteRule(String packageName, RuleRecord rule) {
            return false;
        }

        @Override
        public String getToolbarHiddenItems(String packageName) {
            return null;
        }
    }
}
