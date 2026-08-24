package com.kaisar.xposed.godmode.orchestrator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.app.Activity;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import com.kaisar.xposed.godmode.engine.matcher.ViewTraversal;
import com.kaisar.xposed.godmode.engine.rule.ModifyEffect;
import com.kaisar.xposed.godmode.rule.RuleRecord;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public final class ViewControllerInstrumentedTest {

    @Before
    public void startTestHost() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        if (findResumedTestHost(null) != null) {
            return;
        }
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
                if (activity instanceof ViewControllerTestActivity) {
                    activity.finish();
                }
            }
        });
        instrumentation.waitForIdleSync();
    }

    @Test
    public void deletingVisibleModifyRuleRestoresOwnedProperties() throws Exception {
        withActivity(activity -> {
                TextView target = activity.getTarget();
                assertEquals("host", target.getText().toString());
                ViewController controller = new ViewController(activity);
                RuleRecord rule = modifyRule(activity, target);

                assertTrue(controller.applyRule(target, rule));
                assertEquals("rule", target.getText().toString());
                assertEquals(180, target.getLayoutParams().width);

                controller.revokeRuleBatch(activity, Collections.singletonList(rule));
                assertEquals("host", target.getText().toString());
                assertEquals(100, target.getLayoutParams().width);
                assertEquals(0.65f, target.getAlpha(), 0f);
        });
    }

    @Test
    public void deletingRemoveRuleRestoresViewHiddenByThatRule() throws Exception {
        withActivity(activity -> {
                TextView target = activity.getTarget();
                ViewController controller = new ViewController(activity);
                RuleRecord rule = removeRule(activity, target);

                assertTrue(controller.applyRule(target, rule));
                assertEquals(View.GONE, target.getVisibility());

                controller.revokeRuleBatch(activity, Collections.singletonList(rule));
                assertEquals(View.VISIBLE, target.getVisibility());
                assertEquals(100, target.getLayoutParams().width);
                assertEquals(50, target.getLayoutParams().height);
                assertEquals(0.65f, target.getAlpha(), 0f);
        });
    }

    @Test
    public void recyclingDetachedItemClearsOwnerBeforeRebind() throws Exception {
        withActivity(activity -> {
                TextView target = activity.getTarget();
                assertEquals("host", target.getText().toString());
                ViewController controller = new ViewController(activity);
                RuleRecord rule = modifyRule(activity, target);
                assertTrue(controller.applyRule(target, rule));

                activity.getRoot().removeView(target);
                assertFalse(target.isAttachedToWindow());
                controller.revokeAllRules(target);
                assertEquals("host", target.getText().toString());
                assertEquals(100, target.getLayoutParams().width);

                target.setText("next-row");
                activity.getRoot().addView(target, new FrameLayout.LayoutParams(100, 50));
                controller.revokeAllRules(target);
                assertEquals("next-row", target.getText().toString());
        });
    }

    @Test
    public void repeatedApplyStillRestoresFirstBaseline() throws Exception {
        withActivity(activity -> {
                TextView target = activity.getTarget();
                ViewController controller = new ViewController(activity);
                RuleRecord first = modifyRule(activity, target);
                RuleRecord second = modifyRule(activity, target).withEffect(
                        new ModifyEffect.Builder().ruleTag("modify").visibility(View.VISIBLE)
                                .modWidth(240).modAlpha(.25f).modText("second").build());

                assertTrue(controller.applyRule(target, first));
                assertTrue(controller.applyRule(target, second));
                controller.revokeRule(target, second);

                assertEquals("host", target.getText().toString());
                assertEquals(100, target.getLayoutParams().width);
                assertEquals(0.65f, target.getAlpha(), 0f);
        });
    }

    @Test
    public void revokePreservesHostValuesChangedAfterApply() throws Exception {
        withActivity(activity -> {
                TextView target = activity.getTarget();
                ViewController controller = new ViewController(activity);
                RuleRecord rule = modifyRule(activity, target);

                assertTrue(controller.applyRule(target, rule));
                target.setText("host-new");
                target.setAlpha(0.8f);
                controller.revokeRule(target, rule);

                assertEquals("host-new", target.getText().toString());
                assertEquals(0.8f, target.getAlpha(), 0f);
                assertEquals(100, target.getLayoutParams().width);
        });
    }

    @Test
    public void recreatedActivityCanApplyAndRevokeWithoutOldOwnerState() throws Exception {
        ViewControllerTestActivity first = awaitInitialActivity();
        runOnMain(first, activity -> {
                ViewController controller = new ViewController(activity);
                assertTrue(controller.applyRule(
                        activity.getTarget(), modifyRule(activity, activity.getTarget())));
        });

        runOnMain(first, ViewControllerTestActivity::recreate);
        ViewControllerTestActivity recreated = awaitActivityOtherThan(first, 10_000L);
        assertNotNull("Activity was not recreated", recreated);

        runOnMain(recreated, activity -> {
                TextView target = activity.getTarget();
                assertEquals("host", target.getText().toString());
                ViewController controller = new ViewController(activity);
                RuleRecord rule = modifyRule(activity, target);
                assertTrue(controller.applyRule(target, rule));
                controller.revokeRule(target, rule);
                assertEquals("host", target.getText().toString());
                assertEquals(100, target.getLayoutParams().width);
                assertEquals(0.65f, target.getAlpha(), 0f);
        });
    }

    @Test
    public void deletingOneRuleDoesNotRevokeAnotherTargetWithSameAction() throws Exception {
        withActivity(activity -> {
                TextView first = activity.getTarget();
                TextView second = activity.getSecondTarget();
                ViewController controller = new ViewController(activity);
                RuleRecord firstRule = modifyRule(activity, first);
                RuleRecord secondRule = modifyRule(activity, second);

                assertTrue(controller.applyRule(first, firstRule));
                assertTrue(controller.applyRule(second, secondRule));
                controller.revokeRuleBatch(activity, Collections.singletonList(firstRule));

                assertEquals("host", first.getText().toString());
                assertEquals("rule", second.getText().toString());
                assertEquals(180, second.getLayoutParams().width);
                controller.revokeRule(second, secondRule);
                assertEquals("host-2", second.getText().toString());
        });
    }

    private static void withActivity(ActivityAssertion assertion) throws Exception {
        ViewControllerTestActivity activity = awaitInitialActivity();
        runOnMain(activity, assertion);
    }

    private static ViewControllerTestActivity awaitInitialActivity() throws Exception {
        ViewControllerTestActivity activity = awaitActivityOtherThan(null, 10_000L);
        assertNotNull("Test host Activity was not started", activity);
        return activity;
    }

    private static ViewControllerTestActivity awaitActivityOtherThan(
            ViewControllerTestActivity previous, long timeoutMillis) {
        long deadline = SystemClock.uptimeMillis() + timeoutMillis;
        while (SystemClock.uptimeMillis() < deadline) {
            ViewControllerTestActivity found = findResumedTestHost(previous);
            if (found != null) {
                return found;
            }
            SystemClock.sleep(50L);
        }
        return null;
    }

    private static ViewControllerTestActivity findResumedTestHost(
            ViewControllerTestActivity previous) {
        AtomicReference<ViewControllerTestActivity> found = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Collection<Activity> resumed = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED);
            for (Activity activity : resumed) {
                if (activity instanceof ViewControllerTestActivity && activity != previous) {
                    found.set((ViewControllerTestActivity) activity);
                    break;
                }
            }
        });
        return found.get();
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
        if (throwable instanceof Exception) {
            throw (Exception) throwable;
        }
        if (throwable instanceof Error) {
            throw (Error) throwable;
        }
    }

    private interface ActivityAssertion {
        void run(ViewControllerTestActivity activity) throws Exception;
    }

    private static RuleRecord modifyRule(ViewControllerTestActivity activity, TextView target) {
        return baseRule(activity, target, View.VISIBLE).withEffect(
                new ModifyEffect.Builder().ruleTag("modify").visibility(View.VISIBLE)
                        .modWidth(180).modAlpha(0.25f).modText("rule").build());
    }

    private static RuleRecord removeRule(ViewControllerTestActivity activity, TextView target) {
        return baseRule(activity, target, View.GONE);
    }

    private static RuleRecord baseRule(ViewControllerTestActivity activity, TextView target,
            int visibility) {
        return new RuleRecord(
                "target",
                activity.getPackageName(),
                "1.0",
                1,
                52,
                null,
                "target",
                0,
                0,
                100,
                50,
                ViewTraversal.getViewHierarchyDepth(target),
                activity.getClass().getName(),
                target.getClass().getName(),
                null,
                "host",
                null,
                visibility,
                1L);
    }
}
