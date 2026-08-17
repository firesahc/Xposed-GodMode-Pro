package com.kaisar.xposed.godmode.editor.toolbar;

import android.view.View;

import com.kaisar.xposed.godmode.R;
import com.kaisar.xposed.godmode.editor.RuleEditorClient;

import java.util.Set;

public final class ToolbarVisibilityController {

    private ToolbarVisibilityController() {
    }

    /**
     * 将工具栏可见性偏好应用到节点选择面板。
     * 在面板 View 创建完毕后调用。
     */
    public static void apply(View panel) {
        if (panel == null) return;

        Set<String> hiddenItems = ToolbarPrefsManager.parseHiddenItems(
                RuleEditorClient.getInstance().getToolbarHiddenItems(
                        panel.getContext().getPackageName()));

        if (hiddenItems.contains("pref_show_remove_mode")) {
            hideView(panel, R.id.remove_mode_toggle);
            hideView(panel, R.id.remove_menu);
        }
        if (hiddenItems.contains("pref_show_modify_mode")) {
            hideView(panel, R.id.modify_mode_toggle);
            hideView(panel, R.id.modify_menu);
        }
        if (hiddenItems.contains("pref_show_info_flow_mode")) {
            hideView(panel, R.id.info_flow_mode_toggle);
        }
    }

    private static void hideView(View parent, int viewId) {
        View view = parent.findViewById(viewId);
        if (view != null) {
            view.setVisibility(View.GONE);
        }
    }
}
