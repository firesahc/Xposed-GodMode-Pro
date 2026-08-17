package com.kaisar.xposed.godmode.ipc;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.rule.ActRules;

/** Delivers versioned service observer events on the target process main thread. */
public final class ServiceObserver implements Handler.Callback,
        RuleServiceClient.ObserverCallback {

    private static final String TAG = "ServiceObserver";
    private static final int ACTION_EDIT_MODE_CHANGED = 0;
    private static final int ACTION_VIEW_RULES_CHANGED = 1;

    private final Handler mHandler = new Handler(Looper.getMainLooper(), this);
    private final RuleServiceClient mClient;
    private final Callback mCallback;

    public interface Callback {
        void onEditModeChanged(boolean enable);
        void onViewRulesChanged(ActRules rules);
    }

    public ServiceObserver(Callback callback) {
        mClient = RuleServiceClient.getDefault();
        mCallback = callback;
    }

    @Override
    public void onEditModeChanged(boolean enable, long editRevision, long connectionEpoch) {
        mHandler.obtainMessage(ACTION_EDIT_MODE_CHANGED,
                new EditUpdate(enable, editRevision, connectionEpoch)).sendToTarget();
    }

    @Override
    public void onRulesInvalidated(String packageName, long generation, long connectionEpoch) {
        ActRules rules = mClient.getRulesAtLeast(packageName, generation);
        mHandler.obtainMessage(ACTION_VIEW_RULES_CHANGED,
                new RulesUpdate(generation, connectionEpoch, rules)).sendToTarget();
    }

    @Override
    public boolean handleMessage(Message msg) {
        if (msg.obj == null) {
            Logger.w(TAG, "handleMessage: received null message object");
            return true;
        }
        if (msg.what == ACTION_EDIT_MODE_CHANGED) {
            EditUpdate update = (EditUpdate) msg.obj;
            if (mCallback != null
                    && mClient.isCurrentEditEvent(update.connectionEpoch, update.editRevision)) {
                mCallback.onEditModeChanged(update.enabled);
            }
        } else if (msg.what == ACTION_VIEW_RULES_CHANGED) {
            RulesUpdate update = (RulesUpdate) msg.obj;
            if (mCallback != null
                    && mClient.isCurrentRuleEvent(update.connectionEpoch, update.generation)) {
                mCallback.onViewRulesChanged(update.rules);
            }
        }
        return true;
    }

    private static final class EditUpdate {
        final boolean enabled;
        final long editRevision;
        final long connectionEpoch;

        EditUpdate(boolean enabled, long editRevision, long connectionEpoch) {
            this.enabled = enabled;
            this.editRevision = editRevision;
            this.connectionEpoch = connectionEpoch;
        }
    }

    private static final class RulesUpdate {
        final long generation;
        final long connectionEpoch;
        final ActRules rules;

        RulesUpdate(long generation, long connectionEpoch, ActRules rules) {
            this.generation = generation;
            this.connectionEpoch = connectionEpoch;
            this.rules = rules;
        }
    }
}
