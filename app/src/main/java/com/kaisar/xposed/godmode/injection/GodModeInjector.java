package com.kaisar.xposed.godmode.injection;

import static com.kaisar.xposed.godmode.GodModeApplication.TAG;

import android.app.Activity;
import android.content.res.Resources;
import android.content.res.XModuleResources;
import android.os.Binder;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import com.kaisar.xposed.godmode.R;
import com.kaisar.xposed.godmode.engine.event.EditModeEvent;
import com.kaisar.xposed.godmode.engine.event.EventBus;
import com.kaisar.xposed.godmode.engine.event.RulesChangedEvent;
import com.kaisar.xposed.godmode.injection.bridge.GodModeManager;
import com.kaisar.xposed.godmode.injection.bridge.ManagerObserver;
import com.kaisar.xposed.godmode.injection.editor.EditorOrchestrator;
import com.kaisar.xposed.godmode.injection.entry.ActivityKeyHook;
import com.kaisar.xposed.godmode.injection.entry.DebugLayoutHook;
import com.kaisar.xposed.godmode.injection.entry.TouchHook;
import com.kaisar.xposed.godmode.injection.LifecycleObserver;
import com.kaisar.xposed.godmode.injection.util.BlockListChecker;
import com.kaisar.xposed.godmode.injection.util.GmResources;
import com.kaisar.xposed.godmode.util.Logger;
import com.kaisar.xposed.godmode.engine.Property;
import com.kaisar.xposed.godmode.rule.ActRules;
import com.kaisar.xposed.godmode.service.GodModeManagerService;
import com.kaisar.xservicemanager.XServiceManager;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * GodMode 闁?Xposed 闁稿繈鍎辫ぐ娑㈠Υ?
 * <p>
 * 闁?{@link IXposedHookZygoteInit} 闂傚啳鍩栭宀勫礉閻樼儤绁版俊顖椻偓铏仴闁煎浜ｉ棅鈺冩導閸曨剛鐖卞ù鐘劙缁岃泛鈻旈妸銉ュ汲闁告帗澹嗗ú浼村冀閸パ呭畨闁活潿鍔婇埀?
 * 闁?{@link IXposedHookLoadPackage} 闂傚啳鍩栭宀勬晬?
 * <ul>
 *   <li>閻庣敻鈧稓鑹?{@code "android"}闁挎稑婢儁stem_server闁挎稑顧€缁变即鏌呭宕囩畺闁告搩浜ｉ崚娑㈠级閸喖袥闁归晲绀侀惃?
 *       {@link GodModeManagerService} 婵炲鍔岄崬鑺ョ▔閾忓綊鍏囩紓浣哄枑濠€鍥礉鎺抽埀?/li>
 *   <li>閻庣敻鈧稓鑹鹃柣鈺婂枟閻栵絾鎯旈弮鍌涙殢闁挎稒顑杘ok Activity 闁汇垻鍠庨幊锟犲川閵婏附鍩傞柕鍡曟祰琚濋柟浠嬫櫜缁ㄣ劍绂掗煬娴嬪亾娴ｇ懓鐦婚梺娆惧枙缁ㄣ劍绂掔拋鍦
 *       妤犵偠鍩栭弫鐐哄礃?IPC 閻熸瑥鍊搁惂鍌炴嚀閸涱兛绨伴柟鎭掑劜閺佸湱鎲撮崟顐㈢仧闁告瑦蓱濞插潡濡?/li>
 * </ul>
 */
public final class GodModeInjector implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    // =========================================================================
    // 闁告瑯鍨甸～鍥┾偓鐢靛枔婵悂骞€?闁?閻忓繐妫涚槐顏呮綇閹寸伣浣割嚕韫囨挻瀚查悷娆忓閸垶宕ｅΟ缁樼函濞磋偐濮甸幐閬嶅礆閺夋寧鍊楀☉?Hook
    // =========================================================================

    // 闁告帗绻傞～鎰板礌閺嶏箒绀嬮悗鐟邦槸閸欏繑顪€濡鍚囬柛濠勩€嬬槐婵嬫⒓閸欏鍓鹃柛锔哄姀椤洨鈧數鍠曢埀顒€鎳庡ú鏍嫬閸愩劌鐓傞弶鍫熷劤婢х娀宕欓搹鐟扮疀 null 闁瑰嘲妫涢?NPE闁?
    // Property 闁?AtomicReference 濮掓稒顭堥缁樼▔?null闁挎稑鏈晶宥夊嫉婢跺寒鍤㈤柛娆愮墬閺岀喖妫侀埀顒勬嚄閽樺妲遍柣鐐叉濠€顓㈠礆濠靛棭娼楅柛鏍ㄧ墱婵悂骞€娴ｇ鍋?
    public final static Property<Boolean> switchProp = new Property<>(false);
    public static volatile XC_LoadPackage.LoadPackageParam loadPackageParam;

    // 闁告瑥鐭佸鐑樼鐎ｂ晜顐界紒顖濆吹缁?闁?EventBus 濞?Property 妤犵偞鍎奸、鎴炴交閹邦垼鏀介柨娑樼焸閳ь剚鍔栭鐐存交娴ｇ洅鈺呮儎閹存繃鍎旈柡?
    private static final EventBus sEventBus = EventBus.getDefault();

    private static volatile State state = State.UNKNOWN;
    private static final EditorOrchestrator sEditorOrchestrator = new EditorOrchestrator(switchProp);

    /** 濞撴碍绋戦悺娆戠磼閸曨亝顐介柤鎯у槻瑜?EditorOrchestrator 閻庡湱鍋樼欢?*/
    public static EditorOrchestrator getEditorOrchestrator() { return sEditorOrchestrator; }

    private enum State { UNKNOWN, ALLOWED, BLOCKED }

    // =========================================================================
    // 婵☆垪鈧櫕鍋ラ悹褍瀚花?闁?闁?initZygote 濞戞搩鍘兼慨鐐存姜閺傘倗绀夋繛澶堝妼閸欏棝宕氶幍顔界獥闁哄秴娲ょ花鏌ユ偨閵娧勭暠 AssetManager闁挎稑鐗嗛～娆撳箥?ModuleResources闁?
    // =========================================================================

    @Override
    public void initZygote(StartupParam startupParam) {
        ModuleResources.init(startupParam.modulePath,
                XModuleResources.createInstance(startupParam.modulePath, null));
    }

    // =========================================================================
    // 闁告梻濮惧ù鍥礌?闁?婵絽绻嬮柌婊冾啅閹绘帒顫ｉ弶鐐舵缁ㄦ煡鎮介妸褎鐣遍柛蹇嬪劚瑜?
    // =========================================================================

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (R.string.res_inject_success >>> 24 == 0x7f) {
            XposedBridge.log("[GodModePro] package id must NOT be 0x7f, reject loading...");
            return;
        }
        if (!lpp.isFirstApplication) return;

        GodModeInjector.loadPackageParam = lpp;
        final String packageName = lpp.packageName;

        if ("android".equals(packageName)) {
            bootstrapSystemService();
        } else {
            injectIntoTargetApp(lpp, packageName);
        }
    }

    /** 闁?system_server 闁告劕鎳橀崕瀵镐焊?GodModeManagerService 婵炲鍔岄崬鑺ョ▔閾忓綊鍏囩紓浣哄枑濠€鍥礉?*/
    private void bootstrapSystemService() {
        Logger.i(TAG, "[GodMode] inject GodModeManagerService as system service.");
        XServiceManager.initForSystemServer();
        XServiceManager.registerService("godmode",
                (XServiceManager.ServiceFetcher<Binder>) GodModeManagerService::new);
    }

    /** 闁告碍鍨瑰ú浼村冀閸パ呭畨闁活潿鍔嶉弫鐐哄礂?Hook闁挎稒顑媍tivity 闁汇垻鍠庨幊锟犲川閵婏附鍩傞柕鍡曟祰琚濋柟鑺ユ偠閳ь兛鐒︾€垫粓鏌ㄩ鑽ょ殤濞寸姾缈伴埀顑挎诞PC 閻熸瑥鍊搁惂鍌炴嚀?*/
    private void injectIntoTargetApp(XC_LoadPackage.LoadPackageParam lpp, String packageName) {
        Logger.i(TAG, "[GodMode] inject into app: " + packageName);
        hookActivityOnResume();
        hookActivityOnCreate();
        registerHooks();
        registerObserver(packageName);
        Logger.d(TAG, "[GodMode] injection complete for: " + packageName);
    }

    /** Hook Activity.onResume 濞ｅ洦绻冪€?mCurrentActivity 闁圭娲ら幃婊嗐亹閹惧啿顤呴柛娆樺灥椤?Activity */
    private static void hookActivityOnResume() {
        XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                sEditorOrchestrator.setActivity((Activity) param.thisObject);
            }
        });
    }

    /** Hook Activity.onCreate闁挎稒纰嶉弫鐐哄礂閵壩翠線宕稿Δ鍕偒婵犙勫姧缁辨繄绱撻弽顒傚竼婵☆垪鈧磭纭€鐎瑰憡褰冪槐鎴﹀触椤栨稒顦х€点倖鍎肩换婊堝及閸撗佷粵闂傚牄鍨哄?*/
    private static void hookActivityOnCreate() {
        XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Activity activity = (Activity) param.thisObject;
                ModuleResources.injectInto(activity.getResources());
                if (switchProp.get()) {
                    // post 闁?DecorView 缁绢収鍠曠换?setContentView 鐎瑰憡褰冮悾顒勫箣閹扳斁鍋撴担绛嬫綊闁搞儳鍋撻悥鑼偓鐟版湰閺嗭綁宕ユ惔鈥虫櫃闁哄嫬澧介妵姘舵閵忊剝绶?
                    activity.getWindow().getDecorView().post(() -> sEditorOrchestrator.setDisplay(true));
                }
                super.afterHookedMethod(param);
            }
        });
    }

    /** 閺夆晝鍋炵敮?Hook闁挎稒姘ㄩ弫鎾诲川閽樺鍣柡鍫㈠枂閳ь兛娴囪闁硅姤鎮堕埀顑跨劍鐎垫粓鏌ㄩ琛″亾娴ｇ晫娈堕悹鍥ㄦ礀缁旈浠﹂埀?*/
    private void registerHooks() {
        Logger.d(TAG, "[GodMode] registering hooks...");
        // Activity 闁汇垻鍠庨幊锟犲川閵婏附鍩?Hook 闁?闁?Activity 闁诡厹鍨归ˇ?闂佸簱鍋撴慨锝勭劍濡炲倹鎯旈弮鍌涙殢/闁逛勘鍊濋弨銏㈡喆閸曨偄鐏熼柨娑樼墔缁?EventBus 閻犱警鍨扮欢鐐烘晬?
        LifecycleObserver lifecycleObserver = new LifecycleObserver();
        sEventBus.register(lifecycleObserver);                          // EventBus 閻犱警鍨扮欢?
        XposedHelpers.findAndHookMethod(Activity.class, "onPostResume", lifecycleObserver);
        XposedHelpers.findAndHookMethod(Activity.class, "onDestroy", lifecycleObserver);

        // 閻犲鍟抽惁顖滄暜閸愩劎婀?Hook 闁?缂傚倹鐗炵欢顐⑽熼垾宕囩婵犵鍋撴繛鑼帛濡炲倿寮伴崜褋浠涢悷娆忔濞存ɑ娼忛崷顓熸珪
        DebugLayoutHook.install(switchProp);

        // 閻熸瑱闄勯幊婊勭鐎ｂ晜顐?Hook 闁?闁瑰嚖闄勯崺鍛存倷閻熸澘姣?闁归攱鐗楃€氭寧绂掗妷銊х閻炴稑鐬间簺闂傚嫨鍊曢幏鐗堢┍椤旇姤鏆柟鍨С缂?
        TouchHook touchHook = new TouchHook(sEditorOrchestrator);
        switchProp.addOnPropertyChangeListener(sEditorOrchestrator);
        XposedHelpers.findAndHookMethod(View.class, "dispatchTouchEvent",
                MotionEvent.class, touchHook);

        // 闁圭顦甸弫顓熺鐎ｂ晜顐?Hook 闁?闂傚﹥濞婇崳娲煥椤旂厧鐎奸柟骞垮灱婵☆參鎮欒ぐ鎺嗗亾婢跺顏ラ柛锝冨姂濞间即寮?
        ActivityKeyHook keyHook = new ActivityKeyHook(sEditorOrchestrator);
        XposedHelpers.findAndHookMethod(Activity.class, "dispatchKeyEvent",
                KeyEvent.class, keyHook);
    }

    /** 婵炲鍔岄崬?IPC 閻熸瑥鍊搁惂鍌炴嚀閸滃啰绀夊ù锝嗗濠€鍥礉閿涘嫷浼傞柣銊ュ椤宕氬▎鎰函闁哄倹濯介崗姗€宕氶幏灞惧涧閹煎瓨姊婚弫銈夊礃?*/
    private void registerObserver(String packageName) {
        GodModeManager gmManager = GodModeManager.getDefault();
        Logger.d(TAG, "[GodMode] registering observer for: " + packageName);
        // addObserver 缂佹柨顑呭畵鍡涙焻濮樺磭绠?IPC 闁搞儳鍋犻惃鐔煎箳閵娾斁鍋撴担鍝ョЪ闁告挸绉舵慨鎼佸箑娓氬﹦绀刼nEditModeChanged + onViewRuleChanged闁挎稑顧€缁?
        // 闁哄啰濞€濞撳爼宕樺鍡楊杹闁告柣鍔忛鏇犵磾?switchProp / actRuleProp闁靛棗鍊挎导鈺呭礂瀹ュ懏韬?BLOCKED 閹煎瓨姊婚弫銈嗙▔椤撶偛姣夐柣婊勫閻擃參寮抽崒婊勭暠闂佹寧鐟ㄩ銈呪攽閳ь剙煤閼姐倗宕堕柛娆欑祷閳?
        gmManager.addObserver(packageName, new ManagerObserver());
    }

    // =========================================================================
    // 闁稿浚鍓欑槐鎴︽焻濮樿京鍙€闁哄倽顫夌涵?闁?闁?ManagerObserver 闁革负鍔忛～澶愬礆?缂傚倹鐗炵欢顐⑽熼垾宕囩闁告瑦蓱濞插潡寮幆鎵闁?
    // =========================================================================

    public static void notifyEditModeChanged(boolean enable) {
        if (loadPackageParam == null) {
            Logger.w(TAG, "[GodMode] edit mode change ignored: loadPackageParam not ready");
            return;
        }
        if (state == State.UNKNOWN) {
            state = BlockListChecker.isBlocked(loadPackageParam.packageName)
                    ? State.BLOCKED : State.ALLOWED;
        }
        Logger.i(TAG, "[GodMode] edit mode " + enable + " state=" + state
                + " pkg=" + loadPackageParam.packageName);
        if (state == State.ALLOWED) {
            switchProp.set(enable);                        // 闁哄唲鍡欑唴鐎?
            sEventBus.post(new EditModeEvent(enable));     // 闁哄倹濯介惌鎯ь嚗?
        }
        sEditorOrchestrator.setDisplay(enable);
    }

    public static void notifyViewRulesChanged(ActRules actRules) {
        if (actRules == null) return;
        sEventBus.post(new RulesChangedEvent(
                loadPackageParam != null ? loadPackageParam.packageName : "", actRules));
    }

    // 閻犙冨缁喖鈻旈妸銉ュ汲濠殿喗姊规晶顓犵磼?ModuleResources 闁?閻?injectIntoTargetApp 濞戞搩鍘惧▓?ModuleResources.injectInto() 閻犲鍟伴弫?
}
