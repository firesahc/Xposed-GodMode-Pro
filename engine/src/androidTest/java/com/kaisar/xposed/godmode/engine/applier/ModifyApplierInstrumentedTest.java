package com.kaisar.xposed.godmode.engine.applier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.ParcelFileDescriptor;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.kaisar.xposed.godmode.engine.rule.ActionSpec;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;

@RunWith(AndroidJUnit4.class)
public final class ModifyApplierInstrumentedTest {

    @Test
    public void applyAndRevokeRestoreCapturedBaseline() {
        try (ActivityScenario<ModifyApplierTestActivity> scenario = launch()) {
            scenario.onActivity(activity -> {
                TextView view = attachText(activity, "host");
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) view.getLayoutParams();
                params.leftMargin = 7;
                params.topMargin = 9;
                view.setLayoutParams(params);
                view.setAlpha(0.65f);

                ModifyApplier applier = directApplier();
                ActionSpec action = modifyAction();

                assertTrue(applier.apply(view, action));
                assertEquals(180, view.getLayoutParams().width);
                assertEquals(90, view.getLayoutParams().height);
                assertEquals(17, ((FrameLayout.LayoutParams) view.getLayoutParams()).leftMargin);
                assertEquals(29, ((FrameLayout.LayoutParams) view.getLayoutParams()).topMargin);
                assertEquals(0.25f, view.getAlpha(), 0f);
                assertEquals("rule", view.getText().toString());

                assertTrue(applier.revoke(view, action));
                assertEquals(100, view.getLayoutParams().width);
                assertEquals(50, view.getLayoutParams().height);
                assertEquals(7, ((FrameLayout.LayoutParams) view.getLayoutParams()).leftMargin);
                assertEquals(9, ((FrameLayout.LayoutParams) view.getLayoutParams()).topMargin);
                assertEquals(0.65f, view.getAlpha(), 0f);
                assertEquals("host", view.getText().toString());
            });
        }
    }

    @Test
    public void revokePreservesPropertiesChangedByHost() {
        try (ActivityScenario<ModifyApplierTestActivity> scenario = launch()) {
            scenario.onActivity(activity -> {
                TextView view = attachText(activity, "host");
                ModifyApplier applier = directApplier();
                ActionSpec action = modifyAction();
                assertTrue(applier.apply(view, action));

                view.setText("host-new");
                view.setAlpha(0.8f);
                view.getLayoutParams().width = 260;

                assertTrue(applier.revoke(view, action));
                assertEquals("host-new", view.getText().toString());
                assertEquals(0.8f, view.getAlpha(), 0f);
                assertEquals(260, view.getLayoutParams().width);
                assertEquals(50, view.getLayoutParams().height);
            });
        }
    }

    @Test
    public void repeatedApplyRetainsFirstBaseline() {
        try (ActivityScenario<ModifyApplierTestActivity> scenario = launch()) {
            scenario.onActivity(activity -> {
                TextView view = attachText(activity, "host");
                ModifyApplier applier = directApplier();
                ActionSpec action = modifyAction();
                assertTrue(applier.apply(view, action));

                view.setText("host-drift");
                view.setAlpha(0.8f);
                assertTrue(applier.apply(view, action));
                assertEquals("rule", view.getText().toString());
                assertEquals(0.25f, view.getAlpha(), 0f);

                assertTrue(applier.revoke(view, action));
                assertEquals("host", view.getText().toString());
                assertEquals(1f, view.getAlpha(), 0f);
            });
        }
    }

    @Test
    public void recycleRestoresBaselineBeforeNewBinding() {
        try (ActivityScenario<ModifyApplierTestActivity> scenario = launch()) {
            scenario.onActivity(activity -> {
                TextView view = attachText(activity, "row-a");
                ModifyApplier applier = directApplier();
                assertTrue(applier.apply(view, modifyAction()));
                assertTrue(applier.revokeForView(view));
                assertEquals("row-a", view.getText().toString());
                assertEquals(100, view.getLayoutParams().width);

                view.setText("row-b");
                assertFalse(applier.revokeForView(view));
                assertEquals("row-b", view.getText().toString());
            });
        }
    }

    @Test
    public void lateImageCannotOverwriteNewerRequest() throws Exception {
        try (ActivityScenario<ModifyApplierTestActivity> scenario = launch()) {
            File first = bitmapFile(scenario, Color.RED, "first.png");
            File second = bitmapFile(scenario, Color.BLUE, "second.png");
            scenario.onActivity(activity -> {
                ImageView view = new ImageView(activity);
                Drawable baseline = new ColorDrawable(Color.GREEN);
                view.setImageDrawable(baseline);
                activity.getRoot().addView(view, new FrameLayout.LayoutParams(64, 64));

                QueuedExecutor executor = new QueuedExecutor();
                Queue<Runnable> dispatches = new ArrayDeque<>();
                ModifyApplier applier = new ModifyApplier(
                        path -> ParcelFileDescriptor.open(new File(path),
                                ParcelFileDescriptor.MODE_READ_ONLY),
                        activity.getClass().getName(), executor,
                        (target, action) -> dispatches.offer(action));

                assertTrue(applier.apply(view, imageAction(first.getAbsolutePath())));
                Runnable firstLoad = executor.remove();
                firstLoad.run();
                assertTrue(applier.apply(view, imageAction(second.getAbsolutePath())));
                Runnable secondLoad = executor.remove();

                secondLoad.run();
                dispatches.remove().run();
                dispatches.remove().run();

                assertEquals(Color.BLUE, pixel(view));
                assertTrue(applier.revoke(view, imageAction(second.getAbsolutePath())));
                assertSame(baseline, view.getDrawable());
            });
        }
    }

    @Test
    public void clearingActivityStateDropsLateImageWithoutViewWrite() throws Exception {
        try (ActivityScenario<ModifyApplierTestActivity> scenario = launch()) {
            File image = bitmapFile(scenario, Color.RED, "destroy.png");
            scenario.onActivity(activity -> {
                ImageView view = new ImageView(activity);
                Drawable baseline = new ColorDrawable(Color.GREEN);
                view.setImageDrawable(baseline);
                activity.getRoot().addView(view, new FrameLayout.LayoutParams(64, 64));
                QueuedExecutor executor = new QueuedExecutor();
                Queue<Runnable> dispatches = new ArrayDeque<>();
                ModifyApplier applier = new ModifyApplier(
                        path -> ParcelFileDescriptor.open(new File(path),
                                ParcelFileDescriptor.MODE_READ_ONLY),
                        activity.getClass().getName(), executor,
                        (target, action) -> dispatches.offer(action));

                assertTrue(applier.apply(view, imageAction(image.getAbsolutePath())));
                applier.clearCache();
                executor.remove().run();
                assertTrue(dispatches.isEmpty());
                assertSame(baseline, view.getDrawable());
            });
        }
    }

    private static ActivityScenario<ModifyApplierTestActivity> launch() {
        Intent intent = new Intent(
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                        .getTargetContext(), ModifyApplierTestActivity.class);
        return ActivityScenario.launch(intent);
    }

    private static ModifyApplier directApplier() {
        return new ModifyApplier(path -> null, null, Runnable::run,
                (view, action) -> {
                    action.run();
                    return true;
                });
    }

    private static TextView attachText(ModifyApplierTestActivity activity, String text) {
        TextView view = new TextView(activity);
        view.setText(text);
        activity.getRoot().addView(view, new FrameLayout.LayoutParams(100, 50));
        return view;
    }

    private static ActionSpec modifyAction() {
        return new ActionSpec.Builder()
                .ruleTag("modify")
                .modWidth(180)
                .modHeight(90)
                .modAlpha(0.25f)
                .modXOffset(10)
                .modYOffset(20)
                .origLeftMargin(7)
                .origTopMargin(9)
                .modText("rule")
                .build();
    }

    private static ActionSpec imageAction(String path) {
        return new ActionSpec.Builder().ruleTag("modify").modImagePath(path).build();
    }

    private static File bitmapFile(ActivityScenario<ModifyApplierTestActivity> scenario,
            int color, String name) {
        final File[] output = new File[1];
        scenario.onActivity(activity -> {
            File file = new File(activity.getCacheDir(), name);
            Bitmap bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(color);
            try (FileOutputStream stream = new FileOutputStream(file)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
            } catch (Exception error) {
                throw new AssertionError(error);
            } finally {
                bitmap.recycle();
            }
            output[0] = file;
        });
        return output[0];
    }

    private static int pixel(ImageView view) {
        Bitmap bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        view.getDrawable().setBounds(0, 0, 1, 1);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
        view.getDrawable().draw(canvas);
        int color = bitmap.getPixel(0, 0);
        bitmap.recycle();
        return color;
    }

    private static final class QueuedExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        Runnable remove() {
            return tasks.remove();
        }
    }
}
