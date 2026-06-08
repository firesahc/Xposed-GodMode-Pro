package com.kaisar.xposed.godmode.injection;

import static com.kaisar.xposed.godmode.GodModeApplication.TAG;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import com.kaisar.xposed.godmode.engine.event.RulesChangedEvent;
import com.kaisar.xposed.godmode.engine.event.Subscribe;
import com.kaisar.xposed.godmode.injection.ViewController;
import com.kaisar.xposed.godmode.util.Logger;
import com.kaisar.xposed.godmode.rule.ActRules;
import com.kaisar.xposed.godmode.rule.RuleRecord;
import com.kaisar.xposed.godmode.engine.util.Preconditions;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 鐩戝惉 Activity 鐢熷懡鍛ㄦ湡锛屽湪 Activity 鎭㈠/閿€姣佹椂搴旂敤/鎾ら攢瑙勫垯銆?
 * <p>
 * 閫氳繃 EventBus 璁㈤槄 {@link RulesChangedEvent} 鎺ユ敹瑙勫垯鍙樻洿閫氱煡銆?
 */
public final class LifecycleObserver extends XC_MethodHook {

    private final WeakHashMap<Activity, OnLayoutChangeListener> mActivities = new WeakHashMap<>();
    private final ActRules mActRules = new ActRules();
    private final Handler mDebounceHandler = new Handler(Looper.getMainLooper());
    private final java.util.Map<Activity, Runnable> mPendingReapply = new java.util.WeakHashMap<>();
    private boolean mRecyclerViewHooksInstalled;

    @Override
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
        super.afterHookedMethod(param);
        Activity activity = (Activity) param.thisObject;
        String methodName = param.method.getName();
        ViewGroup decorView = (ViewGroup) activity.getWindow().getDecorView();
        if ("onPostResume".equals(methodName)) {
            if (!mActivities.containsKey(activity)) {
                OnLayoutChangeListener listener = new OnLayoutChangeListener(activity);
                decorView.getViewTreeObserver().addOnGlobalLayoutListener(listener);
                mActivities.put(activity, listener);
                decorView.post(listener::applyRuleIfMatchCondition);
                // 纭繚鍦ㄨ鍒欏凡鍒拌揪浣?mActivities 灏氫负绌猴紙瑙勫垯鏃╀簬 onPostResume锛?
                // 鎴栬鍥惧湪鍒濇 applyRuleIfMatchCondition 鏃跺皻鏈氨缁椂鏈変竴涓欢杩熼噸璇曘€?
                scheduleRuleReapplication(activity);
            }
            installRecyclerViewHooks(activity);
            Logger.d(TAG, "[Lifecycle] resume: " + activity.getClass().getSimpleName() + " (total=" + mActivities.size() + ")");
        } else if ("onDestroy".equals(methodName)) {
            OnLayoutChangeListener listener = mActivities.remove(activity);
            decorView.getViewTreeObserver().removeOnGlobalLayoutListener(listener);
            synchronized (mPendingReapply) {
                Runnable r = mPendingReapply.remove(activity);
                if (r != null) mDebounceHandler.removeCallbacks(r);
            }
            Logger.d(TAG, "[Lifecycle] destroy: " + activity.getClass().getSimpleName() + " (total=" + mActivities.size() + ")");
        }
    }

    /**
     * 鎺ユ敹瑙勫垯鍙樻洿閫氱煡锛圗ventBus 璺緞锛夈€?
     * 鎾ら攢鏃ц鍒欙紝搴旂敤鏂拌鍒欙紝鐒跺悗涓烘墍鏈夊凡璺熻釜 Activity 璋冨害寤惰繜閲嶈瘯銆?
     */
    @SuppressWarnings("unchecked")
    @Subscribe
    public void onRulesChanged(RulesChangedEvent event) {
        ActRules newActRules = (ActRules) event.rules;
        if (newActRules == null) return;
        // 瑙勫垯鏈彉鍖栨椂璺宠繃锛岄伩鍏嶄笉蹇呰鐨勬挙閿€鈫掑啀搴旂敤瀵艰嚧鐨勯棯鍥?
        // 瑙﹀彂鍦烘櫙锛欼PC addObserver 鎺ㄩ€佺殑瑙勫垯涓?onPostResume 涓凡搴旂敤鐨勮鍒欏畬鍏ㄧ浉鍚屾椂
        if (newActRules.equals(mActRules)) return;
        ViewController.getDefault().clearBlockedCache();
        Set<Map.Entry<String, List<RuleRecord>>> entries = newActRules.entrySet();
        for (Map.Entry<String, List<RuleRecord>> entry : entries) {
            String key = entry.getKey();
            List<RuleRecord> oldRules = mActRules.get(key);
            List<RuleRecord> newRules = entry.getValue();
            if (newRules != null && oldRules != null) {
                oldRules.removeAll(newRules);
                if (oldRules.isEmpty()) mActRules.remove(key);
            }
        }
        // revoke old rules
        if (!mActRules.isEmpty()) {
            entries = mActRules.entrySet();
            for (Map.Entry<String, List<RuleRecord>> entry : entries) {
                List<RuleRecord> rules = entry.getValue();
                if (rules == null || rules.isEmpty()) continue;
                List<RuleRecord> revRemove = new java.util.ArrayList<>();
                List<RuleRecord> revModify = new java.util.ArrayList<>();
                for (RuleRecord r : rules) {
                    if (r.isModifyRule()) revModify.add(r);
                    else revRemove.add(r);
                }
                for (Activity activity : mActivities.keySet()) {
                    if (TextUtils.equals(activity.getComponentName().getClassName(), entry.getKey())) {
                        if (!revRemove.isEmpty()) ViewController.getDefault().revokeRuleBatch(activity, revRemove);
                        if (!revModify.isEmpty()) ViewController.getDefault().revokeRuleBatch(activity, revModify);
                    }
                }
            }
        }
        // apply new rules
        mActRules.clear();
        mActRules.putAll(newActRules);
        entries = mActRules.entrySet();
        for (Map.Entry<String, List<RuleRecord>> entry : entries) {
            List<RuleRecord> rules = entry.getValue();
            for (Activity activity : mActivities.keySet()) {
                if (TextUtils.equals(activity.getComponentName().getClassName(), entry.getKey())) {
                    if (!rules.isEmpty()) {
                        ViewController.getDefault().applyRuleBatch(activity, rules);
                    }
                }
            }
        }
        // 淇: 鍦ㄨ鍒欏瓨鏀惧悗涓烘墍鏈夊凡璺熻釜 Activity 璋冨害閲嶅簲鐢紝
        // 浠ュ鐞嗚鍥惧湪瑙勫垯棣栨鍒拌揪鏃跺皻涓嶅瓨鍦ㄧ殑鎯呭喌锛堜緥濡傚紓姝ュ～鍏呫€丗ragment 鎳掑姞杞斤級銆?
        // 濡傛灉瑙嗗浘灏氫笉鍙敤锛宎pplyRuleBatch 浼氶潤榛樺け璐ワ紝
        // 鑰?onGlobalLayout 鍦ㄩ潤鎬?UI 涓婂彲鑳芥案杩滀笉浼氬啀娆¤Е鍙戙€?
        // scheduleRuleReapplication锛?00ms 娑堟姈锛夋彁渚涢噸璇曠獥鍙ｄ互鎹曡幏鍔ㄦ€佸垱寤虹殑瑙嗗浘銆?
        for (Activity activity : mActivities.keySet()) {
            scheduleRuleReapplication(activity);
        }
    }

    private void scheduleRuleReapplication(final Activity activity) {
        synchronized (mPendingReapply) {
            Runnable existing = mPendingReapply.get(activity);
            if (existing != null) mDebounceHandler.removeCallbacks(existing);
            Runnable r = () -> {
                synchronized (mPendingReapply) { mPendingReapply.remove(activity); }
                // 涓嶆竻鐞嗙紦瀛橈細閲嶅簲鐢ㄥ簲澧為噺琛ュ厖鏈鐩栫殑瑙勫垯锛岃€岄潪鐮村潖宸茬敓鏁堢殑淇敼
                OnLayoutChangeListener listener = mActivities.get(activity);
                if (listener != null) listener.applyRuleIfMatchCondition();
            };
            mPendingReapply.put(activity, r);
            mDebounceHandler.postDelayed(r, 200);
        }
    }

    private void installRecyclerViewHooks(Activity activity) {
        if (mRecyclerViewHooksInstalled) return;
        try {
            Class<?> adapterClass = XposedHelpers.findClass("androidx.recyclerview.widget.RecyclerView$Adapter", activity.getClassLoader());
            XposedHelpers.findAndHookMethod(adapterClass, "notifyDataSetChanged", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (mActRules.isEmpty()) return; // 鏃犺鍒欐椂涓嶈Е鍙戦噸鍖归厤
                    for (Activity act : mActivities.keySet()) {
                        if (act != null && !act.isFinishing()) scheduleRuleReapplication(act);
                    }
                }
            });
            mRecyclerViewHooksInstalled = true;
            Logger.i(TAG, "[Lifecycle] DynamicContent: RecyclerView adapter hook installed");
        } catch (Throwable t) {
            Logger.d(TAG, "[Lifecycle] DynamicContent: RecyclerView hook skipped: " + t.getMessage());
        }
    }

    final class OnLayoutChangeListener implements ViewTreeObserver.OnGlobalLayoutListener {

        final WeakReference<Activity> activityReference;
        private volatile boolean mApplying; // 闃查噸鍏ユ爣蹇?

        OnLayoutChangeListener(Activity activity) {
            activityReference = new WeakReference<>(activity);
        }

        @Override
        public void onGlobalLayout() {
            if (mApplying) return; // 闃叉瑙勫垯搴旂敤瑙﹀彂鐨勫竷灞€鍙樻洿瀵艰嚧閫掑綊閲嶅叆
            applyRuleIfMatchCondition();
        }

        void applyRuleIfMatchCondition() {
            if (mApplying) return;
            mApplying = true;
            try {
                Activity activity = Preconditions.checkNotNull(activityReference.get());
                List<RuleRecord> rules = mActRules.get(activity.getComponentName().getClassName());
                if (rules != null && !rules.isEmpty()) {
                    ViewController.getDefault().applyRuleBatch(activity, rules);
                }
            } catch (Exception e) {
                Logger.w(TAG, "[Lifecycle] OnLayoutChange: applyRuleIfMatchCondition failed: " + e.getMessage());
            } finally {
                mApplying = false;
            }
        }

    }

}
