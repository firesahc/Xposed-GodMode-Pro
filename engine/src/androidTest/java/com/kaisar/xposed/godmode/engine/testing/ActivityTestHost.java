package com.kaisar.xposed.godmode.engine.testing;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import java.util.ArrayList;
import java.util.Collection;

/** Starts and closes same-process Activity fixtures for engine instrumentation tests. */
public final class ActivityTestHost {

    private ActivityTestHost() {
    }

    public static <T extends Activity> T requireResumed(Class<T> activityClass) {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        T resumed = findResumed(instrumentation, activityClass);
        if (resumed != null) {
            return resumed;
        }
        Intent intent = new Intent(instrumentation.getTargetContext(), activityClass)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        Activity activity = instrumentation.startActivitySync(intent);
        if (!activityClass.isInstance(activity)) {
            throw new AssertionError("Unexpected test host Activity: " + activity);
        }
        return activityClass.cast(activity);
    }

    private static <T extends Activity> T findResumed(Instrumentation instrumentation,
            Class<T> activityClass) {
        final Activity[] result = new Activity[1];
        instrumentation.runOnMainSync(() -> {
            Collection<Activity> resumed = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED);
            for (Activity activity : resumed) {
                if (activityClass.isInstance(activity)) {
                    result[0] = activity;
                    return;
                }
            }
        });
        return result[0] == null ? null : activityClass.cast(result[0]);
    }

    public static void finishResumed(Class<? extends Activity> activityClass) {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.runOnMainSync(() -> {
            Collection<Activity> resumed = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED);
            for (Activity activity : new ArrayList<>(resumed)) {
                if (activityClass.isInstance(activity)) {
                    activity.finish();
                }
            }
        });
        instrumentation.waitForIdleSync();
    }
}
