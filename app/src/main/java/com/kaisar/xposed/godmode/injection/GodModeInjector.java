package com.kaisar.xposed.godmode.injection;

import static com.kaisar.xposed.godmode.GodModeApplication.TAG;

import android.app.Activity;
import android.content.res.XModuleResources;
import android.os.Binder;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import com.kaisar.xposed.godmode.R;
import com.kaisar.xposed.godmode.engine.Property;
import com.kaisar.xposed.godmode.engine.event.EventBus;
import com.kaisar.xposed.godmode.engine.event.RulesChangedEvent;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.injection.bridge.GodModeManager;
import com.kaisar.xposed.godmode.injection.bridge.ManagerObserver;
import com.kaisar.xposed.godmode.injection.editor.EditorOrchestrator;
import com.kaisar.xposed.godmode.injection.entry.ActivityKeyHook;
import com.kaisar.xposed.godmode.injection.entry.DebugLayoutHook;
import com.kaisar.xposed.godmode.injection.entry.TouchHook;
import com.kaisar.xposed.godmode.injection.util.BlockListChecker;
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
 * GodMode 闂?Xposed 闂佺绻堥崕杈亹濞戙垹违?
 * <p>
 * 闂?{@link IXposedHookZygoteInit} 闂傚倸鍟抽崺鏍敊瀹€鍕闁绘鍎ょ粊鐗堜繆椤栨せ鍋撻搹顐淮闂佺厧顨庢禍锝夋閳哄啯灏庨柛鏇ㄥ墰閻栧崬霉閻橆喖鍔欑紒宀冩硾閳绘棃濡搁妷銉ユ辈闂佸憡甯楁竟鍡椕烘导鏉戝唨闁搞儜鍛暔闂佹椿娼块崝濠囧焵?
 * 闂?{@link IXposedHookLoadPackage} 闂傚倸鍟抽崺鏍敊瀹€鍕櫖?
 * <ul>
 *   <li>闁诲海鏁婚埀顒佺〒閼?{@code "android"}闂佹寧绋戝鍎乻tem_server闂佹寧绋戦¨鈧紒鍙樺嵆閺屽懎顫濆畷鍥╃暫闂佸憡鎼╂禍锝夊礆濞戙垹绾ч柛顭戝枛琚ラ梺褰掓櫜缁€渚€鎯?
 *       {@link GodModeManagerService} 濠电偛顦崝宀勫船閼恒儳鈻旈柧蹇撶秺閸忓洨绱撴担鍝勬瀾婵犫偓閸ヮ剙绀夐幒鎶藉焵?/li>
 *   <li>闁诲海鏁婚埀顒佺〒閼归箖鏌ｉ埡濠傛灍闁绘牭绲鹃幆鏃堝籍閸屾稒娈㈤梺鎸庣⊕椤戞潣ok Activity 闂佹眹鍨婚崰搴ㄥ箠閿熺姴宸濋柕濠忛檮閸╁倿鏌曢崱鏇熺グ鐞氭繈鏌熸禒瀣珳缂併劊鍔嶇粋鎺楃叕濞村浜惧ù锝囨嚀閻﹀姊哄▎鎯ф灆缂併劊鍔嶇粋鎺旀媼閸︻厾顦?
 *       濡ょ姷鍋犻崺鏍极閻愬搫绀?IPC 闁荤喐鐟ラ崐鎼佹儌閸岀偞鍤€闁告侗鍏涚花浼存煙閹帒鍔滈柡浣告贡閹叉挳宕熼銏户闂佸憡鐟﹁摫婵炴彃娼℃俊?/li>
 * </ul>
 */
public final class GodModeInjector implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    // =========================================================================
    // 闂佸憡鐟崹鐢革綖閸モ斁鍋撻悽闈涙灁濠殿喗鎮傞獮鈧?闂?闁诲繐绻愬Λ娑氭椤忓懏缍囬柟瀵镐迹娴ｅ壊鍤曢煫鍥ㄦ尰鐎氭煡鎮峰▎蹇擃仼闁割煈鍨跺畷锝呂熺紒妯煎嚱婵炵鍋愭慨鐢稿箰闁秴绀嗛柡澶嬪閸婃鈽?Hook
    // =========================================================================

    // 闂佸憡甯楃换鍌烇綖閹版澘绀岄柡宥忕畳缁€瀣倵閻熼偊妲搁柛娆忕箲椤偓婵☆垱顑欓崥鍥煕婵犲嫨鈧妲愬┑瀣挀闁告瑥顦介崜楣冩煕閿斿搫濮€妞ゎ偄娲ㄩ埀顒傛暩閸犳洟鍩€椤掆偓閹冲骸煤閺嶎偅瀚柛鎰╁妼閻撳倿寮堕崼鐔峰姢濠⒀呭█瀹曟瑩鎼归悷鎵杸 null 闂佺懓鍢插Λ娑㈩敊?NPE闂?
    // Property 闂?AtomicReference 婵帗绋掗…鍫ヮ敇缂佹鈻?null闂佹寧绋戦張顒佹櫠瀹ュ瀚夊璺哄瘨閸ゃ垽鏌涘▎鎰闁哄瞼鍠栧Λ渚€鍩€椤掑嫭鍤勯柦妯侯樈濡查亶鏌ｉ悙鍙夘棡婵犫偓椤撱垹绀嗘繝闈涙－濞兼鏌涢弽銊у⒈濠殿喗鎮傞獮鈧ù锝囶暯閸?
    public final static Property<Boolean> switchProp = new Property<>(false);
    public static volatile XC_LoadPackage.LoadPackageParam loadPackageParam;

    // EventBus 鈥?浠呯敤浜庤鍒欏彉鏇撮€氱煡锛圧ulesChangedEvent锛夛紝缂栬緫妯″紡閫氳繃 Property 鍒嗗彂
    private static final EventBus sEventBus = EventBus.getDefault();

    private static volatile State state = State.UNKNOWN;
    private static final EditorOrchestrator sEditorOrchestrator = new EditorOrchestrator(switchProp);

    /** 婵炴挻纰嶇粙鎴︽偤濞嗘垹纾奸柛鏇ㄤ簼椤愪粙鏌ら幆褍妲荤憸?EditorOrchestrator 闁诲骸婀遍崑妯兼?*/
    public static EditorOrchestrator getEditorOrchestrator() { return sEditorOrchestrator; }

    private enum State { UNKNOWN, ALLOWED, BLOCKED }

    // =========================================================================
    // 濠碘槅鍨埀顒冩珪閸嬨儵鎮硅鐎氼厾鑺?闂?闂?initZygote 婵炴垶鎼╅崢鍏兼叏閻愬瓨濮滈柡鍌樺€楃粈澶嬬箾婢跺牆濡奸柛娆忔瀹曟岸骞嶉鐣岀崶闂佸搫绉村ú銈囪姳閺屻儲鍋ㄩ柕濞у嫮鏆?AssetManager闂佹寧绋戦悧鍡涳綖濞嗘挸绠?ModuleResources闂?
    // =========================================================================

    @Override
    public void initZygote(StartupParam startupParam) {
        ModuleResources.init(startupParam.modulePath,
                XModuleResources.createInstance(startupParam.modulePath, null));
    }

    // =========================================================================
    // 闂佸憡姊绘慨鎯归崶顒€绀?闂?濠殿噯绲界换瀣煂濠婂喚鍟呴柟缁樺笒椤綁寮堕悙鑸殿棄缂併劍鐓￠幃浠嬪Ω瑜庨悾閬嶆煕韫囧鍔氱憸?
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

    /** 闂?system_server 闂佸憡鍔曢幊姗€宕曠€甸晲鐒?GodModeManagerService 濠电偛顦崝宀勫船閼恒儳鈻旈柧蹇撶秺閸忓洨绱撴担鍝勬瀾婵犫偓閸ヮ剙绀?*/
    private void bootstrapSystemService() {
        Logger.i(TAG, "[GodMode] inject GodModeManagerService as system service.");
        XServiceManager.initForSystemServer();
        XServiceManager.registerService("godmode",
                (XServiceManager.ServiceFetcher<Binder>) GodModeManagerService::new);
    }

    /** 闂佸憡纰嶉崹鐟懊烘导鏉戝唨闁搞儜鍛暔闂佹椿娼块崝宥夊极閻愬搫绀?Hook闂佹寧绋掗濯峵ivity 闂佹眹鍨婚崰搴ㄥ箠閿熺姴宸濋柕濠忛檮閸╁倿鏌曢崱鏇熺グ鐞氭繈鏌熼懞銉﹀仩闁逞屽厸閻掞妇鈧灚绮撻弻銊╊敊閼姐倗娈ゆ繛瀵稿Ь缂堜即鍩€椤戞寧璇濸C 闁荤喐鐟ラ崐鎼佹儌閸岀偞鍤€?*/
    private void injectIntoTargetApp(XC_LoadPackage.LoadPackageParam lpp, String packageName) {
        Logger.i(TAG, "[GodMode] inject into app: " + packageName);
        hookActivityOnResume();
        hookActivityOnCreate();
        registerHooks();
        registerObserver(packageName);
        Logger.d(TAG, "[GodMode] injection complete for: " + packageName);
    }

    /** Hook Activity.onResume 婵烇絽娲︾换鍐偓?mCurrentActivity 闂佸湱顭堝ú銈夊箖濠婂棎浜归柟鎯у暱椤ゅ懘鏌涘▎妯虹仴妞?Activity */
    private static void hookActivityOnResume() {
        XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                sEditorOrchestrator.setActivity((Activity) param.thisObject);
            }
        });
    }

    /** Hook Activity.onCreate闂佹寧绋掔喊宥夊极閻愬搫绀傞柕澹╃繝绶氬畷绋课旈崟顓滃亽濠电姍鍕Ё缂佽鲸绻勭槐鎾诲冀椤掑倸绔煎┑鈽嗗灙閳ь剙纾涵鈧悗鐟版啞瑜板啰妲愰幋锕€瑙︽い鏍ㄧ⊕椤ρ呪偓鐐瑰€栭崕鑲╂崲濠婂牆鍙婇柛鎾椾椒绮甸梻鍌氱墑閸ㄥ搫顭?*/
    private static void hookActivityOnCreate() {
        XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Activity activity = (Activity) param.thisObject;
                ModuleResources.injectInto(activity.getResources());
                if (switchProp.get()) {
                    // post 闂?DecorView 缂佺虎鍙庨崰鏇犳崲?setContentView 閻庣懓鎲¤ぐ鍐偩椤掑嫬绠ｉ柟鎵虫杹閸嬫挻鎷呯粵瀣秺闂佹悶鍎抽崑鎾绘偉閼碱兘鍋撻悷鐗堟拱闁哄棴缍佸畷銉︽償閳ヨ櫕娅冮梺鍝勫婢т粙濡靛鑸殿棃闁靛繆鍓濈欢?
                    activity.getWindow().getDecorView().post(() -> sEditorOrchestrator.setDisplay(true));
                }
                super.afterHookedMethod(param);
            }
        });
    }

    /** 闁哄鏅濋崑鐐垫暜?Hook闂佹寧绋掑銊╁极閹捐宸濋柦妯侯槹閸ｎ垶鏌￠崼銏犳瀭闁逞屽厸濞村洩顤傞梺纭呭Г閹爼鍩€椤戣法鍔嶉悗鍨矒閺屻劑顢欑悰鈥充壕濞达絿鏅▓鍫曟偣閸ャ劍绀€缂佹棃顥撴禒锕傚焵?*/
    private void registerHooks() {
        Logger.d(TAG, "[GodMode] registering hooks...");
        // Activity 闂佹眹鍨婚崰搴ㄥ箠閿熺姴宸濋柕濠忛檮閸?Hook 闂?闂?Activity 闂佽鍘归崹褰捤?闂備礁绨遍崑鎾存叏閿濆嫮鍔嶆俊鐐插€归幆鏃堝籍閸屾稒娈?闂侀€涘嫎閸婃繈寮ㄩ姀銏″枂闁告洦鍋勯悘鐔兼煥濞戞澧旂紒?EventBus 闁荤姳璀﹂崹鎵閻愮儤鏅?
        LifecycleObserver lifecycleObserver = new LifecycleObserver();
        sEventBus.register(lifecycleObserver);                          // EventBus 闁荤姳璀﹂崹鎵?
        XposedHelpers.findAndHookMethod(Activity.class, "onPostResume", lifecycleObserver);
        XposedHelpers.findAndHookMethod(Activity.class, "onDestroy", lifecycleObserver);

        // 闁荤姴顑呴崯鎶芥儊椤栨粍鏆滈柛鎰╁妿濠€?Hook 闂?缂傚倸鍊归悧鐐垫椤愨懡鐔煎灳瀹曞洨顢呭┑鐘殿暯閸嬫挻绻涢懠顒傚笡婵＄偛鍊垮浼村礈瑜嬫禒娑㈡偡濞嗗繑顥滄繛瀛樕戝蹇涘捶椤撶喐鐝?
        DebugLayoutHook.install(switchProp);

        // 闁荤喐鐟遍梽鍕箠濠婂嫮顩查悗锝傛櫆椤?Hook 闂?闂佺懓鍤栭梽鍕春閸涘瓨鍊烽柣鐔告緲濮?闂佸綊鏀遍悧妤冣偓姘缁傛帡濡烽妸褏顔掗柣鐐寸☉閻棿绨洪梻鍌氬閸婃洟骞忛悧鍫⑩攳妞ゆ棁濮ら弳顓㈡煙閸喚小缂?
        TouchHook touchHook = new TouchHook(sEditorOrchestrator);
        switchProp.addOnPropertyChangeListener(sEditorOrchestrator);
        XposedHelpers.findAndHookMethod(View.class, "dispatchTouchEvent",
                MotionEvent.class, touchHook);

        // 闂佸湱顭堥ˇ鐢稿极椤撶喓顩查悗锝傛櫆椤?Hook 闂?闂傚倸锕ユ繛濠囧闯濞差亝鐓ユい鏃傚帶閻庡ジ鏌熼獮鍨伇濠碘槅鍙冮幃娆掋亹閹哄棗浜惧璺侯儏椤忋儵鏌涢敐鍐ㄥ婵為棿鍗冲?
        ActivityKeyHook keyHook = new ActivityKeyHook(sEditorOrchestrator);
        XposedHelpers.findAndHookMethod(Activity.class, "dispatchKeyEvent",
                KeyEvent.class, keyHook);
    }

    /** 濠电偛顦崝宀勫船?IPC 闁荤喐鐟ラ崐鎼佹儌閸岀偞鍤€闁告粌鍟扮粈澶娒归敐鍡楊嚋婵犫偓閸ヮ剙绀夐柨娑樺娴煎倿鏌ｉ妸銉ヮ伂妞ゎ偄顦靛畷姘枎閹邦厾鍑介梺鍝勫€规刊浠嬪礂濮椻偓瀹曟岸骞忕仦鎯ф锭闁圭厧鐡ㄥ濠氬极閵堝绀?*/
    private void registerObserver(String packageName) {
        GodModeManager gmManager = GodModeManager.getDefault();
        Logger.d(TAG, "[GodMode] registering observer for: " + packageName);
        // addObserver 缂備焦鏌ㄩ鍛暤閸℃稒鐒绘慨妯虹－缁?IPC 闂佹悶鍎抽崑鐘绘儍閻旂厧绠抽柕濞炬杹閸嬫挻鎷呴崫銉梺鍛婃尭缁夎埖鎱ㄩ幖浣哥畱濞撴艾锕︾粈鍒糿EditModeChanged + onViewRuleChanged闂佹寧绋戦¨鈧紒?
        // 闂佸搫鍟版繛鈧繛鎾崇埣瀹曟ê顓奸崱妤婃澒闂佸憡鏌ｉ崝蹇涱敊閺囩姷纾?switchProp / actRuleProp闂侀潧妫楅崐鎸庡閳哄懎绀傜€广儱鎳忛煬?BLOCKED 闁圭厧鐡ㄥ濠氬极閵堝棛鈻旀い鎾跺仜濮ｅ鏌ｅ鍕棆闁绘搩鍙冨鎶藉磼濠婂嫮鏆犻梻浣瑰閻熴劑顢氶妶鍛斀闁逞屽墮鐓ら柤濮愬€楀畷鍫曟煕濞嗘瑧绁烽柍?
        gmManager.addObserver(packageName, new ManagerObserver());
    }

    // =========================================================================
    // 闂佺娴氶崜娆戞閹达附鐒绘慨妯夸含閸欌偓闂佸搫鍊介～澶屾兜?闂?闂?ManagerObserver 闂侀潻璐熼崝蹇涳綖婢舵劕绀?缂傚倸鍊归悧鐐垫椤愨懡鐔煎灳瀹曞洨顢呴梺鍛婄懄钃辨繛鎻掓健瀵噣骞嗛幍顔筋啀闂?
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
            switchProp.set(enable);                        // 缂佸灝缂庣拠鐭惧鍕剁幢闁俺绻?Property 闁氨鐓￠幍鈧張澶屾磧閸氼剝鈧?
        }
        sEditorOrchestrator.setDisplay(enable);
    }

    public static void notifyViewRulesChanged(ActRules actRules) {
        if (actRules == null) return;
        sEventBus.post(new RulesChangedEvent(
                loadPackageParam != null ? loadPackageParam.packageName : "", actRules));
    }

    // 闁荤姍鍐仾缂侇煈鍠栭埢鏃堝Ω閵夈儱姹叉繝娈垮枟濮婅鏅堕鐘电＜?ModuleResources 闂?闁?injectIntoTargetApp 婵炴垶鎼╅崢鎯р枔?ModuleResources.injectInto() 闁荤姴顑呴崯浼村极?
}
