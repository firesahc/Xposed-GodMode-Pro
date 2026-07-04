package com.kaisar.xposed.godmode.editor;

import android.app.Activity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.kaisar.xposed.godmode.R;
import com.kaisar.xposed.godmode.editor.action.PreviewHandler;
import com.kaisar.xposed.godmode.editor.panel.NodeSelectorPanel;
import com.kaisar.xposed.godmode.editor.panel.PropertyEditorPanel;
import com.kaisar.xposed.godmode.util.GmResources;

import java.lang.ref.WeakReference;

/**
 * 按键事件处理器 — 从 EditorOrchestrator 抽取出的音量键/导航逻辑。
 * 负责编辑模式下的面板切换、节点导航、信息流模式切换等。
 */
public final class KeyEventHandler {

    // =========================================================================
    // 回调接口 — 与 EditorOrchestrator 通信
    // =========================================================================

    public interface KeyCallback {
        NodeSelectorPanel getNodePanel();
        PropertyEditorPanel getPropertyEditor();
        PreviewHandler getPreviewHandler();
        WeakReference<Activity> getCurrentActivityRef();
        int getInteractionMode();
        void setInteractionMode(int mode);
        int getOverlayColor();
        void onShowNodeSelectPanel(Activity activity, int overlayColor);
        void onDismissNodeSelectPanel();
        void onHideGmOverlays(int visibility);
    }

    private final KeyCallback mCallback;

    // =========================================================================
    // 信息流模式状态
    // =========================================================================

    private boolean mInfoFlowMode = false;

    // =========================================================================
    // 构造器
    // =========================================================================

    public KeyEventHandler(KeyCallback callback) {
        this.mCallback = callback;
    }

    // =========================================================================
    // 状态查询
    // =========================================================================

    public boolean isInfoFlowMode() {
        return mInfoFlowMode;
    }

    // =========================================================================
    // 音量键事件处理
    // =========================================================================

    /**
     * 音量键切换编辑面板：若未选择则显示节点选择面板，否则关闭面板。
     * 由 ActivityKeyHook 通过按键事件触发调用。
     */
    public void onVolumeKeyToggle(Activity activity) {
        if (!mCallback.getNodePanel().isKeySelecting() && activity != null) {
            mCallback.onShowNodeSelectPanel(activity, mCallback.getOverlayColor());
        } else if (mCallback.getNodePanel().isKeySelecting()) {
            mCallback.onDismissNodeSelectPanel();
        }
    }

    /**
     * Integrates with ActivityKeyHook for key event dispatch and
     * TouchHook for touch interception in edit mode.
     */
    public void onVolumeKeyNavigate(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            navigatePrevious();
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            navigateNext();
        }
    }

    // =========================================================================
    // 信息流模式切换
    // =========================================================================

    void toggleInfoFlowMode() {
        mInfoFlowMode = !mInfoFlowMode;
        updateInfoFlowModeButton();
        Activity act = mCallback.getCurrentActivityRef().get();
        if (act != null) {
            Toast.makeText(act, GmResources.getString(mInfoFlowMode
                            ? R.string.accessibility_info_flow_on : R.string.accessibility_info_flow_off),
                    Toast.LENGTH_SHORT).show();
        }
    }

    void updateInfoFlowModeButton() {
        View panelView = mCallback.getNodePanel().getPanelView();
        if (panelView == null) return;
        TextView btn = panelView.findViewById(R.id.info_flow_mode_btn);
        if (btn == null) return;
        if (mInfoFlowMode) {
            btn.setText(GmResources.getText(R.string.mode_info_flow_on));
            btn.setTextColor(android.graphics.Color.parseColor("#FFA500"));
        } else {
            btn.setText(GmResources.getText(R.string.mode_info_flow_off));
            btn.setTextColor(android.graphics.Color.GRAY);
        }
    }

    // =========================================================================
    // 节点导航 — 音量键导航选择控件
    // =========================================================================

    private void navigate(int delta) {
        if (mCallback.getPropertyEditor().isShowing()) return;
        mCallback.getNodePanel().navigate(delta);
    }

    private void navigateNext() {
        navigate(+1);
    }

    private void navigatePrevious() {
        navigate(-1);
    }
}
