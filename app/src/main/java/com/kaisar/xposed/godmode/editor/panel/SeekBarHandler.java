package com.kaisar.xposed.godmode.editor.panel;

import android.view.View;
import android.widget.SeekBar;

import com.kaisar.xposed.godmode.engine.matcher.ViewTraversal;
import com.kaisar.xposed.godmode.engine.util.GmConstants;
import com.kaisar.xposed.godmode.editor.overlay.MaskView;
import com.kaisar.xposed.godmode.util.ViewUtils;

import java.lang.ref.WeakReference;
import java.util.List;

/**
 * SeekBar 事件处理器 — 从 KeyInterceptor 提取的独立 SeekBar 交互逻辑。
 * <p>
 * 负责在节点选择器面板的 SeekBar 滑动时更新遮罩层和视图选中索引。
 */
public final class SeekBarHandler implements SeekBar.OnSeekBarChangeListener {

    private static final int OVERLAY_COLOR = GmConstants.OVERLAY_COLOR_RED;
    private static final int OVERLAY_COLOR_REPEATABLE = GmConstants.OVERLAY_COLOR_ORANGE;

    private final NodeSelectorPanel mNodePanel;
    private final PropertyEditorPanel mPropertyEditor;

    public SeekBarHandler(NodeSelectorPanel nodePanel, PropertyEditorPanel propertyEditor) {
        this.mNodePanel = nodePanel;
        this.mPropertyEditor = propertyEditor;
    }

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        if (mPropertyEditor.isShowing()) return;
        List<WeakReference<View>> viewNodes = mNodePanel.getViewNodes();
        MaskView maskView = mNodePanel.getMaskView();
        if (viewNodes != null && progress < viewNodes.size()) {
            mNodePanel.setCurrentIndexSilent(progress);
            if (fromUser) mNodePanel.setHasUserSelection(true);
            View view = viewNodes.get(mNodePanel.getCurrentIndex()).get();
            if (view != null && maskView != null) {
                if (ViewTraversal.isInRecyclerView(view)) {
                    maskView.setMaskOverlay(OVERLAY_COLOR_REPEATABLE);
                } else {
                    maskView.setMaskOverlay(OVERLAY_COLOR);
                }
                maskView.updateOverlayBounds(ViewUtils.getLocationInWindow(view));
            }
        }
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {
        View panelView = mNodePanel.getPanelView();
        if (panelView != null) panelView.setAlpha(0.2f);
    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
        View panelView = mNodePanel.getPanelView();
        if (panelView != null) panelView.setAlpha(1f);
    }
}
