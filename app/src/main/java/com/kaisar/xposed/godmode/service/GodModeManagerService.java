package com.kaisar.xposed.godmode.service;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.kaisar.xposed.godmode.BuildConfig;
import com.kaisar.xposed.godmode.IGodModeManager;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.rule.ActRules;
import com.kaisar.xposed.godmode.rule.AppRules;
import com.kaisar.xposed.godmode.rule.RuleRecord;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 娑撳﹤绗ｅΟ鈥崇础閺嶇绺剧粻锛勬倞閺堝秴濮?閳?閹碘偓閺堝娉曟潻娑氣柤闁俺顔嗛崸鍥偓姘崇箖濮濄倖婀囬崝掳鈧?
 * <p>
 * 鐠囥儲婀囬崝锟犫偓姘崇箖 XServiceManager 濞夈劌鍙嗛崚?SystemServer 鏉╂稓鈻奸妴?
 * 闁插洨鏁ょ紒鍕値濡€崇础閿涘苯鐨㈢憴鍕灟缂傛挸鐡ㄩ妴浣瑰瘮娑斿懎瀵查妴浣筋潎鐎电喕鈧懐顓搁悶鍡愨偓浣规綀闂勬劙鐛欑拠浣割潤閹垫绮?4 娑擃亙绗撻懕?Manager閵?
 * Handler 濞戝牊浼呴崚鍡楀絺娴ｆ粈璐熺紓鏍ㄥ笓鐏炲偊绱濋崡蹇氱殶閸?Manager 娑斿妫块惃鍕紣娴ｆ粍绁﹂妴?
 * <p>
 * Client 缁旑垶鈧俺绻?{@link com.kaisar.xposed.godmode.injection.bridge.GodModeManager#getDefault()} 娴ｈ法鏁ら幒銉ュ經閵?
 */
public final class GodModeManagerService extends IGodModeManager.Stub {

    // ===== 缂佸嫬鎮庨惃鍕矋娴?=====
    private final PermissionEnforcer mPermissionEnforcer;
    private final RuleCacheManager mCacheManager;
    private final WorkflowOrchestrator mOrchestrator;

    // ===== 閸╄櫣顢呯拋鐐煢 =====
    private final Logger mLogger;
    private final Context mContext;
    private final Gson mGson = new GsonBuilder().setPrettyPrinting().create();

    // ===== 閻樿埖鈧礁鐡у▓?=====
    private volatile boolean mInEditMode;
    private boolean mStarted;

    // ===== 瀹搞儱鍙块弽蹇撲焊婵傛枻绱欑粻鈧崡鏇炵摟濞堢绱濇稉宥夋付閸楁洜瀚?Manager閿?=====
    private String mToolbarHiddenItems = "";

    public GodModeManagerService(Context context) {
        mLogger = Logger.getLogger("GMMService");
        mContext = context;
        mPermissionEnforcer = new PermissionEnforcer(context);
        mCacheManager = new RuleCacheManager(mGson, mLogger);
        mOrchestrator = new WorkflowOrchestrator(mGson, mLogger, mCacheManager,
                items -> mToolbarHiddenItems = items);
        mStarted = true;
        mLogger.i("GMMService started, loading rules from /data/system/godmode");
    }

    // ===================================================================
    // AIDL 閹恒儱褰涚€圭偟骞?閳?婵梹澧紒娆忔倗 Manager
    // ===================================================================

    @Override
    public boolean hasLight() throws RemoteException {
        mPermissionEnforcer.enforcePermission("has light fail permission denied");
        return true;
    }

    // ---- 缂傛牞绶Ο鈥崇础 ----

    @Override
    public void setEditMode(boolean enable) throws RemoteException {
        mPermissionEnforcer.enforcePermission("set edit mode fail permission denied");
        if (!mStarted) return;
        mLogger.i("setEditMode: " + enable);
        mInEditMode = enable;
        mOrchestrator.notifyEditModeChanged(enable);
    }

    @Override
    public boolean isInEditMode() throws RemoteException {
        mPermissionEnforcer.enforcePermission("is in edit mode fail permission denied");
        return mInEditMode;
    }

    // ---- 鐟欏倸鐧傞懓?----

    @Override
    public void addObserver(String packageName, com.kaisar.xposed.godmode.IObserver observer)
            throws RemoteException {
        mPermissionEnforcer.enforcePermission(
                new String[]{packageName, BuildConfig.APPLICATION_ID},
                "register observer fail permission denied");
        if (!mStarted) return;
        ActRules rules = mCacheManager.getRules(packageName);
        mOrchestrator.addObserver(packageName, observer, mInEditMode, rules);
    }

    @Override
    public void removeObserver(String packageName, com.kaisar.xposed.godmode.IObserver observer)
            throws RemoteException {
        mPermissionEnforcer.enforcePermission(
                new String[]{packageName, BuildConfig.APPLICATION_ID},
                "unregister observer fail permission denied");
        if (!mStarted) return;
        mOrchestrator.removeObserver(packageName, observer);
    }

    // ---- 鐟欏嫬鍨弻銉嚄 ----

    @Override
    public AppRules getAllRules() throws RemoteException {
        mPermissionEnforcer.enforcePermission("get all rules fail permission denied");
        if (!mStarted || !mOrchestrator.isDataLoaded()) return new AppRules();
        return mCacheManager.getAllRules();
    }

    @Override
    public ActRules getRules(String packageName) throws RemoteException {
        mPermissionEnforcer.enforcePermission(
                new String[]{packageName, BuildConfig.APPLICATION_ID},
                "get rules fail permission denied");
        if (!mStarted || !mOrchestrator.isDataLoaded()) return new ActRules();
        return mCacheManager.getRules(packageName);
    }

    // ---- 鐟欏嫬鍨崘娆忓弳 ----

    @Override
    public boolean writeRule(String packageName, RuleRecord viewRule, Bitmap snapshot)
            throws RemoteException {
        mPermissionEnforcer.enforcePermission(
                new String[]{packageName, BuildConfig.APPLICATION_ID},
                "write rule fail permission denied");
        if (!mStarted) return false;
        return mOrchestrator.writeRuleAsync(packageName, viewRule, snapshot);
    }

    @Override
    public boolean updateRule(String packageName, RuleRecord viewRule) throws RemoteException {
        mPermissionEnforcer.enforcePermission("update rule fail permission denied");
        if (!mStarted) return false;
        return mOrchestrator.updateRuleAsync(packageName, viewRule);
    }

    // ---- 鐟欏嫬鍨崚鐘绘珟 ----

    @Override
    public boolean deleteRule(String packageName, RuleRecord viewRule) throws RemoteException {
        mPermissionEnforcer.enforcePermission("delete rule fail permission denied");
        if (!mStarted) return false;
        return mOrchestrator.deleteRuleAsync(packageName, viewRule);
    }

    @Override
    public boolean deleteRules(String packageName) throws RemoteException {
        mPermissionEnforcer.enforcePermission("delete rules fail permission denied");
        if (!mStarted) return false;
        return mOrchestrator.deleteRulesAsync(packageName);
    }

    // ---- 閸ュ墽澧栭幙宥勭稊 ----

    @Override
    public String saveImageFile(String packageName, Bitmap bitmap) throws RemoteException {
        mPermissionEnforcer.enforcePermission(
                new String[]{packageName, BuildConfig.APPLICATION_ID},
                "save image fail permission denied");
        if (!mStarted || bitmap == null || bitmap.isRecycled()) return null;
        try {
            return mOrchestrator.saveBitmap(bitmap,
                mOrchestrator.getAppDataDir(packageName));
        } catch (Exception e) {
            throw new RemoteException("Cannot access package data dir: " + e.getMessage());
        }
    }

    @Override
    public ParcelFileDescriptor openImageFileDescriptor(String filePath) throws RemoteException {
        if (!mOrchestrator.isValidImagePath(filePath))
            throw new RemoteException("unauthorized access " + filePath);
        File parentFile = new File(filePath).getParentFile();
        String packageFromPath = parentFile != null ? parentFile.getName() : "";
        mPermissionEnforcer.enforcePermission(
                new String[]{packageFromPath, BuildConfig.APPLICATION_ID},
                "open fd fail permission denied");
        File file = new File(filePath);
        if (!file.exists() || !file.isFile())
            throw new RemoteException("File not found: " + filePath);
        if (file.length() > 5 * 1024 * 1024)
            throw new RemoteException("File too large (>5MB): " + filePath);
        try {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (FileNotFoundException e) {
            RemoteException re = new RemoteException();
            re.initCause(e);
            throw re;
        }
    }

    // ---- 瀹搞儱鍙块弽蹇撲焊婵?----

    @Override
    public String getToolbarHiddenItems() throws RemoteException {
        mPermissionEnforcer.enforcePermission("get toolbar hidden items fail permission denied");
        return mToolbarHiddenItems;
    }

    @Override
    public void setToolbarHiddenItems(String items) throws RemoteException {
        mPermissionEnforcer.enforcePermission("set toolbar prefs fail permission denied");
        mToolbarHiddenItems = items != null ? items : "";
        mOrchestrator.persistToolbarHiddenItems(mToolbarHiddenItems);
    }
}
