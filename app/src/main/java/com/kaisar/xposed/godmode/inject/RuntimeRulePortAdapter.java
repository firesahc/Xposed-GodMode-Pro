package com.kaisar.xposed.godmode.inject;

import android.app.Activity;
import android.view.View;

import com.kaisar.xposed.godmode.editor.RuntimeRulePort;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.orchestrator.RuleLifecycleManager;
import com.kaisar.xposed.godmode.orchestrator.ViewController;
import com.kaisar.xposed.godmode.rule.RuleRecord;

/**
 * Inject-side adapter that exposes the target-process runtime through the editor port.
 *
 * <p>The adapter is intentionally the only place where the editor-facing boundary knows that
 * {@link ViewController} is owned by {@link RuleLifecycleManager}. The Activity-null fallback is
 * retained for the existing preview compatibility path; new editor calls should always provide
 * an Activity.</p>
 */
public final class RuntimeRulePortAdapter implements RuntimeRulePort {

    private static final String TAG = "RuntimeRulePortAdapter";

    @Override
    public Session open(Activity activity) {
        try {
            ViewController controller;
            if (activity == null) {
                controller = ViewController.getDefault();
            } else {
                controller = RuleLifecycleManager.getInstance().getViewController(activity);
            }
            if (controller == null) {
                Logger.w(TAG, "runtime session unavailable: activity=" + activity);
                return null;
            }
            return new ControllerSession(controller);
        } catch (Throwable failure) {
            Logger.w(TAG, "open runtime session failed: activity=" + activity, failure);
            return null;
        }
    }

    private static final class ControllerSession implements Session {

        private final ViewController mController;

        ControllerSession(ViewController controller) {
            mController = controller;
        }

        @Override
        public boolean applyRule(View target, RuleRecord rule) {
            return mController.applyRule(target, rule);
        }

        @Override
        public void revokeRule(View target, RuleRecord rule) {
            mController.revokeRule(target, rule);
        }
    }
}
