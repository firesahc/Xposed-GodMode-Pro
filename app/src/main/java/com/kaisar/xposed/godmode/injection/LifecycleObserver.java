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
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.engine.util.Preconditions;
import com.kaisar.xposed.godmode.rule.ActRules;
import com.kaisar.xposed.godmode.rule.RuleRecord;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 閻╂垵鎯?Activity 閻㈢喎鎳￠崨銊︽埂閿涘苯婀?Activity 閹垹顦?闁库偓濮ｄ焦妞傛惔鏃傛暏/閹俱倝鏀㈢憴鍕灟閵?
 * <p>
 * 闁俺绻?EventBus 鐠併垽妲?{@link RulesChangedEvent} 閹恒儲鏁圭憴鍕灟閸欐ɑ娲块柅姘辩叀閵?
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
                // 绾喕绻氶崷銊潐閸掓瑥鍑￠崚鎷屾彧娴?mActivities 鐏忔矮璐熺粚鐚寸礄鐟欏嫬鍨弮鈺€绨?onPostResume閿?
                // 閹存牞顫嬮崶鎯ф躬閸掓繃顐?applyRuleIfMatchCondition 閺冭泛鐨婚張顏勬皑缂侇亝妞傞張澶夌娑擃亜娆㈡潻鐔煎櫢鐠囨洏鈧?
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
     * 閹恒儲鏁圭憴鍕灟閸欐ɑ娲块柅姘辩叀閿涘湕ventBus 鐠侯垰绶為敍澶堚偓?
     * 閹俱倝鏀㈤弮褑顫夐崚娆欑礉鎼存梻鏁ら弬鎷岊潐閸掓瑱绱濋悞璺烘倵娑撶儤澧嶉張澶婂嚒鐠虹喕閲?Activity 鐠嬪啫瀹冲鎯扮箿闁插秷鐦妴?
     */
    @SuppressWarnings("unchecked")
    @Subscribe
    public void onRulesChanged(RulesChangedEvent event) {
        ActRules newActRules = (ActRules) event.rules;
        if (newActRules == null) return;
        // 鐟欏嫬鍨張顏勫綁閸栨牗妞傜捄瀹犵箖閿涘矂浼╅崗宥勭瑝韫囧懓顩﹂惃鍕寵闁库偓閳帒鍟€鎼存梻鏁ょ€佃壈鍤ч惃鍕／閸?
        // 鐟欙箑褰傞崷鐑樻珯閿涙PC addObserver 閹恒劑鈧胶娈戠憴鍕灟娑?onPostResume 娑擃厼鍑℃惔鏃傛暏閻ㄥ嫯顫夐崚娆忕暚閸忋劎娴夐崥灞炬
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
        // 娣囶喖顦? 閸︺劏顫夐崚娆忕摠閺€鎯ф倵娑撶儤澧嶉張澶婂嚒鐠虹喕閲?Activity 鐠嬪啫瀹抽柌宥呯安閻㈩煉绱?
        // 娴犮儱顦╅悶鍡氼潒閸ユ儳婀憴鍕灟妫ｆ牗顐奸崚鎷屾彧閺冭泛鐨绘稉宥呯摠閸︺劎娈戦幆鍛枌閿涘牅绶ユ俊鍌氱磽濮濄儱锝為崗鍛偓涓梤agment 閹虫帒濮炴潪鏂ょ礆閵?
        // 婵″倹鐏夌憴鍡楁禈鐏忔矮绗夐崣顖滄暏閿涘畮pplyRuleBatch 娴兼岸娼ゆ妯恒亼鐠愩儻绱?
        // 閼?onGlobalLayout 閸︺劑娼ら幀?UI 娑撳﹤褰查懗鑺ユ鏉╂粈绗夋导姘晙濞喡ば曢崣鎴欌偓?
        // scheduleRuleReapplication閿?00ms 濞戝牊濮堥敍澶嬪絹娓氭盯鍣哥拠鏇犵崶閸欙絼浜掗幑鏇″箯閸斻劍鈧礁鍨卞铏规畱鐟欏棗娴橀妴?
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
                // 娑撳秵绔婚悶鍡欑处鐎涙﹫绱伴柌宥呯安閻劌绨叉晶鐐哄櫤鐞涖儱鍘栭張顏囶洬閻╂牜娈戠憴鍕灟閿涘矁鈧矂娼惍鏉戞綎瀹歌尙鏁撻弫鍫㈡畱娣囶喗鏁?
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
                    if (mActRules.isEmpty()) return; // 閺冪姾顫夐崚娆愭娑撳秷袝閸欐垿鍣搁崠褰掑帳
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
        private volatile boolean mApplying; // 闂冩煡鍣搁崗銉︾垼韫?

        OnLayoutChangeListener(Activity activity) {
            activityReference = new WeakReference<>(activity);
        }

        @Override
        public void onGlobalLayout() {
            if (mApplying) return; // 闂冨弶顒涚憴鍕灟鎼存梻鏁ょ憴锕€褰傞惃鍕鐏炩偓閸欐ɑ娲跨€佃壈鍤ч柅鎺戠秺闁插秴鍙?
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
