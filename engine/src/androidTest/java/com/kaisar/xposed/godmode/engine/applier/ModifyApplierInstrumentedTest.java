package com.kaisar.xposed.godmode.engine.applier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import com.kaisar.xposed.godmode.engine.rule.ActionSpec;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public final class ModifyApplierInstrumentedTest {

    @Test
    public void removeGoneDoesNotOwnLayoutParams() throws Exception {
        withActivity(activity -> {
                TextView view = attachText(activity, "host");
                RemoveApplier applier = new RemoveApplier();
                ActionSpec action = new ActionSpec.Builder()
                        .visibility(View.GONE)
                        .build();

                assertTrue(applier.apply(view, action));
                assertEquals(View.GONE, view.getVisibility());
                assertEquals(100, view.getLayoutParams().width);
                assertEquals(50, view.getLayoutParams().height);
        });
    }

    @Test
    public void removeRevokePreservesHostLayoutAndVisibilityChanges() throws Exception {
        withActivity(activity -> {
                TextView view = attachText(activity, "host");
                view.setAlpha(0.65f);
                view.setClickable(true);
                RemoveApplier applier = new RemoveApplier();
                ActionSpec action = new ActionSpec.Builder()
                        .visibility(View.GONE)
                        .build();

                assertTrue(applier.apply(view, action));
                view.getLayoutParams().width = 240;
                view.getLayoutParams().height = 70;
                view.setVisibility(View.VISIBLE);
                view.setAlpha(0.8f);
                view.setClickable(true);

                assertTrue(applier.revoke(view, action));
                assertEquals(View.VISIBLE, view.getVisibility());
                assertEquals(240, view.getLayoutParams().width);
                assertEquals(70, view.getLayoutParams().height);
                assertEquals(0.8f, view.getAlpha(), 0f);
                assertTrue(view.isClickable());
        });
    }

    @Test
    public void safeDecoderRejectsMalformedImage() throws Exception {
        withActivity(activity -> {
                File file = new File(activity.getCacheDir(), "malformed-image.bin");
                try (FileWriter writer = new FileWriter(file)) {
                    writer.write("not a bitmap");
                }
                try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(file,
                        ParcelFileDescriptor.MODE_READ_ONLY)) {
                    assertNull(SafeBitmapDecoder.decode(descriptor.getFileDescriptor()));
                } finally {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                }
        });
    }

    @Test
    public void applyAndRevokeRestoreCapturedBaseline() throws Exception {
        withActivity(activity -> {
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

    @Test
    public void revokePreservesPropertiesChangedByHost() throws Exception {
        withActivity(activity -> {
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

    @Test
    public void repeatedApplyRetainsFirstBaseline() throws Exception {
        withActivity(activity -> {
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

    @Test
    public void recycleRestoresBaselineBeforeNewBinding() throws Exception {
        withActivity(activity -> {
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

    @Test
    public void lateImageCannotOverwriteNewerRequest() throws Exception {
        withActivity(activity -> {
                File first = bitmapFile(activity, Color.RED, "first.png");
                File second = bitmapFile(activity, Color.BLUE, "second.png");
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

    @Test
    public void clearingActivityStateDropsLateImageWithoutViewWrite() throws Exception {
        withActivity(activity -> {
                File image = bitmapFile(activity, Color.RED, "destroy.png");
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

    private static void withActivity(ActivityAssertion assertion) throws Exception {
        ModifyApplierTestActivity activity = awaitActivity(10_000L);
        assertNotNull("Test host Activity was not started", activity);
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
    }

    private static ModifyApplierTestActivity awaitActivity(long timeoutMillis) {
        long deadline = SystemClock.uptimeMillis() + timeoutMillis;
        while (SystemClock.uptimeMillis() < deadline) {
            AtomicReference<ModifyApplierTestActivity> found = new AtomicReference<>();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                Collection<Activity> resumed = ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED);
                for (Activity activity : resumed) {
                    if (activity instanceof ModifyApplierTestActivity) {
                        found.set((ModifyApplierTestActivity) activity);
                        break;
                    }
                }
            });
            if (found.get() != null) return found.get();
            SystemClock.sleep(50L);
        }
        return null;
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

    private static File bitmapFile(ModifyApplierTestActivity activity, int color, String name) {
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
        return file;
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

    private interface ActivityAssertion {
        void run(ModifyApplierTestActivity activity) throws Exception;
    }
}
