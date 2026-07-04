package com.kaisar.xposed.godmode.ipc;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import com.kaisar.xposed.godmode.IObserver;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.rule.ActRules;

/**
 * Binder 观察者 — 将 system_server 的 IPC 推送转为主线程事件。
 * <p>
 * 实现 {@link IObserver.Stub} 以接收 Binder 回调，
 * 通过 Handler 切换到主线程后委托给调用方分发。
 * AppInjector 在注入目标应用时创建并注册此观察者。
 */
public class ServiceObserver extends IObserver.Stub implements Handler.Callback {

    private static final String TAG = "ServiceObserver";

    private final Handler mHandler = new Handler(Looper.getMainLooper(), this);
    private static final int ACTION_EDIT_MODE_CHANGED = 0;
    private static final int ACTION_VIEW_RULES_CHANGED = 1;
    private final Callback mCallback;

    public interface Callback {
        void onEditModeChanged(boolean enable);
        void onViewRulesChanged(ActRules rules);
    }

    public ServiceObserver(Callback callback) {
        this.mCallback = callback;
    }

    @Override
    public void onEditModeChanged(boolean enable) {
        mHandler.obtainMessage(ACTION_EDIT_MODE_CHANGED, enable).sendToTarget();
    }

    @Override
    public void onViewRuleChanged(String packageName, ActRules actRules) {
        mHandler.obtainMessage(ACTION_VIEW_RULES_CHANGED, actRules).sendToTarget();
    }

    @Override
    public boolean handleMessage(Message msg) {
        if (msg.obj == null) {
            Logger.w(TAG, "handleMessage: received null message object");
            return true;
        }
        if (msg.what == ACTION_EDIT_MODE_CHANGED) {
            if (mCallback != null) {
                mCallback.onEditModeChanged((Boolean) msg.obj);
            }
        } else if (msg.what == ACTION_VIEW_RULES_CHANGED) {
            if (mCallback != null) {
                mCallback.onViewRulesChanged((ActRules) msg.obj);
            }
        }
        return true;
    }

}
