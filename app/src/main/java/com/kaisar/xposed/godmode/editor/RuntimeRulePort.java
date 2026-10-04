package com.kaisar.xposed.godmode.editor;

import android.app.Activity;
import android.view.View;

import com.kaisar.xposed.godmode.rule.RuleRecord;

/**
 * Editor-facing boundary for applying a rule to the currently inspected runtime view.
 *
 * <p>The editor deliberately receives a session instead of a {@code ViewController}. The
 * session hides Activity-scoped runtime ownership while preserving the invariant that a paired
 * apply/revoke operation uses the same runtime owner.</p>
 */
public interface RuntimeRulePort {

    /**
     * Opens a runtime session for the supplied Activity.
     *
     * @return a session when the runtime is ready, or {@code null} when the Activity has no
     *         available runtime owner
     */
    Session open(Activity activity);

    /**
     * Activity-scoped rule operation session. Implementations must keep the runtime owner stable
     * for the lifetime of the session.
     */
    interface Session {

        /** Applies the rule and reports whether the physical application succeeded. */
        boolean applyRule(View target, RuleRecord rule);

        /** Revokes a rule previously applied through this session. */
        void revokeRule(View target, RuleRecord rule);
    }
}
