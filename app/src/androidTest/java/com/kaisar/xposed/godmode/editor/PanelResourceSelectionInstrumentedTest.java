package com.kaisar.xposed.godmode.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.kaisar.xposed.godmode.R;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Verifies that Android Resources, rather than XML file presence, selects both variants. */
@RunWith(AndroidJUnit4.class)
public final class PanelResourceSelectionInstrumentedTest {

    @Test
    public void portraitInflationUsesVerticalToolbarColumn() {
        assertToolbarOrientation(Configuration.ORIENTATION_PORTRAIT, LinearLayout.VERTICAL);
    }

    @Test
    public void landscapeInflationUsesHorizontalToolbarColumn() {
        assertToolbarOrientation(Configuration.ORIENTATION_LANDSCAPE, LinearLayout.HORIZONTAL);
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
}
