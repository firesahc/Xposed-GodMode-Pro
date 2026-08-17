package com.kaisar.xposed.godmode.editor;

import static com.kaisar.xposed.godmode.engine.util.GmConstants.TAG_GM_CMP;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.view.View;
import android.widget.FrameLayout;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public final class EditorOverlayCaptureInstrumentedTest {

    @Test
    public void taggedEditorOverlayIsExcludedAndVisibilityIsRestored() {
        AtomicReference<Bitmap> captured = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            FrameLayout root = new FrameLayout(context);
            View content = coloredView(context, Color.GREEN);
            View overlay = coloredView(context, Color.MAGENTA);
            overlay.setTag(TAG_GM_CMP);
            root.addView(content, new FrameLayout.LayoutParams(4, 4));
            root.addView(overlay, new FrameLayout.LayoutParams(4, 4));
            layout(root, 4, 4);

            captured.set(EditorOrchestrator.captureWithoutEditorOverlays(
                    content, Collections.emptyList()));

            assertEquals(View.VISIBLE, overlay.getVisibility());
        });

        Bitmap bitmap = captured.get();
        assertEquals(Color.GREEN, bitmap.getPixel(2, 2));
        bitmap.recycle();
    }

    @Test
    public void hiddenOverlayStatesAreRestoredWhenCaptureReturnsNull() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            View visible = new View(context);
            View gone = new View(context);
            gone.setVisibility(View.GONE);

            Bitmap result = EditorOrchestrator.captureWithHiddenOverlays(
                    Arrays.asList(visible, gone), () -> null);

            assertNull(result);
            assertEquals(View.VISIBLE, visible.getVisibility());
            assertEquals(View.GONE, gone.getVisibility());
        });
    }

    @Test
    public void hiddenOverlayStatesAreRestoredWhenCaptureThrows() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            View invisible = new View(context);
            View gone = new View(context);
            invisible.setVisibility(View.INVISIBLE);
            gone.setVisibility(View.GONE);

            try {
                EditorOrchestrator.captureWithHiddenOverlays(
                        Arrays.asList(invisible, gone), () -> {
                            throw new IllegalStateException("capture failure");
                        });
                fail("capture exception must propagate");
            } catch (IllegalStateException expected) {
                assertEquals("capture failure", expected.getMessage());
            }

            assertEquals(View.INVISIBLE, invisible.getVisibility());
            assertEquals(View.GONE, gone.getVisibility());
        });
    }

    @Test
    public void captureOutsideMainThreadIsRejected() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AtomicReference<View> target = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> target.set(new View(context)));

        try {
            EditorOrchestrator.captureWithoutEditorOverlays(
                    target.get(), Collections.emptyList());
            fail("background capture must be rejected");
        } catch (IllegalStateException expected) {
            assertEquals("Editor snapshot must run on the main thread", expected.getMessage());
        }
    }

    private static View coloredView(Context context, int color) {
        View view = new View(context);
        view.setBackgroundColor(color);
        return view;
    }

    private static void layout(View view, int width, int height) {
        view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }
}
