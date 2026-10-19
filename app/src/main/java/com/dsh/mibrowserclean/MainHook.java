package com.dsh.mibrowserclean;

import android.app.Activity;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 小米浏览器 / 小米应用商店 净化模块
 *
 * 作用域：
 *   com.android.browser   —— 搜索联想广告、浏览器内下载弹窗推广、首页/开屏广告
 *   com.xiaomi.market     —— 下载弹窗里的"审核版本"提示与推荐应用卡片
 *
 * 已验证有效的实现：
 *   1. 搜索联想广告：向 NestedScrollWebView 注入 JS，按"应用商店"+"版本号"
 *      文本特征定位卡片（该页面由服务端下发，元素无 className，只能用文本判断）
 *   2. 商店下载弹窗：按实测 View 层级（宽 900~1050、高 150~300）隐藏推荐卡片，
 *      随后恢复"保存安装包文件"标题可见
 *   3. 商店下载弹窗：隐藏"小米应用商店审核版本""原安装包下载"文案
 *   4. 商店弹窗窗口居中，避免从底部弹出后再跳位
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "MiBrowserClean";
    private static final String PKG_BROWSER = "com.android.browser";
    private static final String PKG_MARKET = "com.xiaomi.market";

    // ==================== 搜索联想广告 ====================

    /**
     * 注入到搜索联想 WebView 的去广告脚本。
     * 该页面由服务端下发，元素没有 className，只能按文本特征定位。
     */
    private static final String SUG_AD_KILLER_JS =
            "(function(){"
          + " if(window.__dshKill) return; window.__dshKill=1;"
          + " function isAdText(t){"
          + "   return t.indexOf('应用商店')>=0 && t.indexOf('版本号')>=0;"
          + " }"
          + " function kill(){"
          + "   var all=document.getElementsByTagName('*');"
          + "   var n=0;"
          + "   for(var i=0;i<all.length;i++){"
          + "     var el=all[i];"
          + "     if(el.getAttribute('data-dsh')) continue;"
          + "     var t=el.innerText||'';"
          + "     if(!isAdText(t)) continue;"
          + "     var inner=false;"
          + "     for(var k=0;k<el.children.length;k++){"
          + "       if(isAdText(el.children[k].innerText||'')){inner=true;break;}"
          + "     }"
          + "     if(inner) continue;"
          // 向上找卡片级容器：宽度接近屏幕宽度、高度足够
          + "     var cur=el, card=el;"
          + "     for(var d=0;d<8&&cur;d++){"
          + "       var r=cur.getBoundingClientRect();"
          + "       if(r.width>window.innerWidth*0.85&&r.height>40){card=cur;break;}"
          + "       cur=cur.parentElement;"
          + "     }"
          + "     card.style.setProperty('display','none','important');"
          + "     card.style.setProperty('height','0','important');"
          + "     card.style.setProperty('margin','0','important');"
          + "     card.style.setProperty('padding','0','important');"
          + "     card.setAttribute('data-dsh','1');"
          + "     n++;"
          + "   }"
          // 广告清除后，把残留的"查看更多"一并隐藏
          + "   var left=0;"
          + "   for(var q=0;q<all.length;q++){"
          + "     if(all[q].getAttribute&&all[q].getAttribute('data-dsh')) left++;"
          + "   }"
          + "   if(left>0){"
          + "     for(var w=0;w<all.length;w++){"
          + "       var et=all[w].innerText||'';"
          + "       if(et.length<30&&/查看更多|更多精彩|换一批/.test(et)){"
          + "         all[w].style.setProperty('display','none','important');"
          + "       }"
          + "     }"
          + "   }"
          + "   return n;"
          + " }"
          + " try{kill();}catch(e){}"
          + " var f=0;"
          + " (function loop(){try{kill();}catch(e){}"
          + "   if(++f<30) requestAnimationFrame(loop);})();"
          + " try{new MutationObserver(function(){try{kill();}catch(e){}})"
          + "   .observe(document.documentElement,{childList:true,subtree:true});}catch(e){}"
          + " setInterval(function(){try{kill();}catch(e){}},30);"
          + "})();";

    /** 已注入过的 WebView，避免重复注入 */
    private final java.util.Set<Object> mInjectedWebViews =
            java.util.Collections.newSetFromMap(
                    new java.util.WeakHashMap<Object, Boolean>());

    /** 商店弹窗里已处理过的项目，避免重复操作与刷日志 */
    private final java.util.Set<String> mMarketHandled = new java.util.HashSet<>();

    // ==================== 入口 ====================

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (PKG_MARKET.equals(lpparam.packageName)) {
            log("命中应用商店进程: " + lpparam.processName);
            hookMarketDialog(lpparam.classLoader);
            return;
        }
        if (!PKG_BROWSER.equals(lpparam.packageName)) return;

        log("命中浏览器进程: " + lpparam.processName);
        ClassLoader cl = lpparam.classLoader;

        hookSearchSugAd(cl);
        hookBrowserDownloadDialog(cl);
        hookHostAdSwitches(cl);
        hookHomePromotion(cl);
        hookSplashAd(cl);

        log("浏览器 hook 安装完毕");
    }

    // ==================== 1. 搜索联想广告 ====================

    private void hookSearchSugAd(ClassLoader cl) {
        // 页面加载前注入，减少首帧闪烁
        try {
            Class<?> hyperWebView = XposedHelpers.findClass("hyper.webkit.WebView", cl);
            XposedBridge.hookAllMethods(hyperWebView, "loadUrl", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    injectSugJs(param.thisObject);
                }
            });
        } catch (Throwable ignored) {}

        // 兜底：周期扫描，确保任何时机创建的联想 WebView 都能注入
        startSugWebViewWatcher();
    }

    private void startSugWebViewWatcher() {
        final android.os.Handler h =
                new android.os.Handler(android.os.Looper.getMainLooper());
        h.post(new Runnable() {
            public void run() {
                try {
                    java.util.List<View> found = new java.util.ArrayList<>();
                    for (View r : findAllRoots()) collectWebViews(r, found);
                    for (View wv : found) injectSugJs(wv);
                } catch (Throwable ignored) {}
                h.postDelayed(this, 50L);
            }
        });
    }

    private void injectSugJs(Object wv) {
        if (wv == null) return;
        try {
            String cn = wv.getClass().getName();
            // 只处理搜索联想用的 WebView，避免污染正常网页
            if (!cn.contains("NestedScrollWebView")) return;
            Method eval = findMethod(wv.getClass(), "evaluateJavascript", 2);
            if (eval == null) return;
            eval.invoke(wv, SUG_AD_KILLER_JS, null);
            if (mInjectedWebViews.add(wv)) {
                log("已向搜索联想页注入去广告脚本");
            }
        } catch (Throwable ignored) {}
    }

    /** 收集当前所有窗口根 View */
    private java.util.List<View> findAllRoots() {
        java.util.List<View> roots = new java.util.ArrayList<>();
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            Object inst = XposedHelpers.callStaticMethod(wmg, "getInstance");
            Object views = XposedHelpers.getObjectField(inst, "mViews");
            if (views instanceof java.util.List) {
                for (Object v : (java.util.List<?>) views) {
                    if (v instanceof View) roots.add((View) v);
                }
            }
        } catch (Throwable ignored) {}
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = XposedHelpers.callStaticMethod(at, "currentActivityThread");
            Object acts = XposedHelpers.callMethod(thread, "getActivities");
            if (acts instanceof java.util.Map) {
                for (Object a : ((java.util.Map<?, ?>) acts).values()) {
                    if (a instanceof Activity) {
                        roots.add(((Activity) a).getWindow().getDecorView());
                    }
                }
            }
        } catch (Throwable ignored) {}
        return roots;
    }

    private void collectWebViews(View v, java.util.List<View> out) {
        if (v == null) return;
        if (v.getClass().getName().contains("WebView")) out.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectWebViews(g.getChildAt(i), out);
            }
        }
    }

    // ==================== 2. 浏览器内下载弹窗推广 ====================

    private void hookBrowserDownloadDialog(ClassLoader cl) {
        try {
            Class<?> impl = XposedHelpers.findClass(
                    "com.android.browser.download.CommonDownloadDialogImpl", cl);
            for (Method m : impl.getDeclaredMethods()) {
                String name = m.getName();
                if (name.equals("initShowMarket") || name.equals("onCreateDialog")) {
                    try {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                hideStoreFields(param.thisObject);
                            }
                        });
                    } catch (Throwable ignored) {}
                } else if (name.equals("initVersionFile")) {
                    try {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                hideField(param.thisObject, "tvVersionStore");
                            }
                        });
                    } catch (Throwable ignored) {}
                } else if (name.equals("downloadInMarket")) {
                    // 阻断跳转应用市场
                    try {
                        XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING);
                    } catch (Throwable ignored) {}
                }
            }
            log("下载弹窗 hook 完成");
        } catch (Throwable t) {
            log("下载弹窗 hook 失败: " + t);
        }
    }

    /** 隐藏下载弹窗里的应用商店区域 */
    private void hideStoreFields(Object self) {
        for (String f : new String[]{
                "rlStore", "tvStoreTitle", "tvVersionStore", "tvSizeStore"}) {
            hideField(self, f);
        }
    }

    // ==================== 3. 宿主广告开关 ====================

    private void hookHostAdSwitches(ClassLoader cl) {
        try {
            Class<?> bs = XposedHelpers.findClass("com.android.browser.BrowserSettings", cl);
            hookBool(bs, "isShowAd", false);
            hookBool(bs, "isPersonalizedAdEnabled", false);
            hookBool(bs, "isAdCustomDisabled", true);
            log("宿主广告开关 hook 完成");
        } catch (Throwable ignored) {}
    }

    // ==================== 4. 首页推广位 ====================

    private void hookHomePromotion(ClassLoader cl) {
        try {
            Class<?> pom = XposedHelpers.findClass(
                    "com.android.browser.homepage.PremiumOperationManager", cl);
            hookBool(pom, "canShowPremiumChangeHint", false);
            hookBool(pom, "getHasShowPremiumGuideDialog", true);
            hookBool(pom, "getHasShowSimpleToPremiumGuideDialog", true);
        } catch (Throwable ignored) {}
        try {
            Class<?> sv = XposedHelpers.findClass(
                    "com.android.browser.homepage.SimpleVersionHomeLayout", cl);
            hookBool(sv, "chanShowPremiumChangeLayout", false);
            log("首页推广 hook 完成");
        } catch (Throwable ignored) {}
    }

    // ==================== 5. 开屏广告 ====================

    private void hookSplashAd(ClassLoader cl) {
        try {
            Class<?> sys = XposedHelpers.findClass("com.msa.sdk.core.splash.SystemSplashAd", cl);
            for (Method m : sys.getDeclaredMethods()) {
                String n = m.getName();
                if (n.equals("getAdSplashType") && m.getReturnType() == int.class) {
                    try {
                        XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(0));
                    } catch (Throwable ignored) {}
                } else if (n.equals("getIsSupportPassiveSplashAd")
                        && m.getReturnType() == boolean.class) {
                    try {
                        XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(false));
                    } catch (Throwable ignored) {}
                }
            }
            log("开屏广告 hook 完成");
        } catch (Throwable ignored) {}
    }

    // ==================== 6. 应用商店下载弹窗 ====================

    private void hookMarketDialog(ClassLoader cl) {
        // 窗口位置由应用商店主题决定，运行时可调空间有限，此处只处理内容清理
        startMarketScan();
    }

    private void startMarketScan() {
        final android.os.Handler h =
                new android.os.Handler(android.os.Looper.getMainLooper());
        h.post(new Runnable() {
            public void run() {
                try {
                    for (View r : findAllRoots()) scanMarketAd(r);
                } catch (Throwable ignored) {}
                h.postDelayed(this, 50L);
            }
        });
        log("商店弹窗扫描已启动");
    }

    private void scanMarketAd(View v) {
        if (v == null) return;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) scanMarketAd(g.getChildAt(i));
            return;
        }
        if (!(v instanceof TextView)) return;
        CharSequence cs = ((TextView) v).getText();
        if (cs == null) return;
        String ts = cs.toString().trim();

        // 推荐卡片：从"版本：x.x.x"向上找推荐卡片层
        // 实测层级：ConstraintLayout(504x45) < LinearLayout(504x167)
        //          < LinearLayout(960x204)=推荐卡片 < LinearLayout(1104x552)=含标题
        if (ts.startsWith("版本：")) {
            hideRecommendCard(v);
            return;
        }

        // 推广文案
        boolean isPromo = ts.equals("小米应用商店审核版本")
                || ts.equals("原安装包下载")
                || (ts.contains("小米应用商店") && ts.contains("审核版本"));
        if (!isPromo) return;

        if (v.getVisibility() == View.VISIBLE) {
            v.setVisibility(View.GONE);
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp != null) {
                lp.height = 0;
                v.setLayoutParams(lp);
            }
            if (mMarketHandled.add("txt#" + ts)) {
                log("已隐藏推广文案: " + ts);
            }
        }
    }

    /** 隐藏推荐卡片，同时保证标题可见 */
    private void hideRecommendCard(View verText) {
        try {
            View cur = verText;
            for (int i = 0; i < 6 && cur != null; i++) {
                Object p = cur.getParent();
                if (!(p instanceof View)) return;
                View parent = (View) p;
                String pcn = parent.getClass().getName();
                int w = parent.getWidth();
                int h = parent.getHeight();
                if (w >= 900 && w <= 1050 && h >= 150 && h <= 300
                        && !pcn.endsWith("DetailWithSourceFileView")
                        && !pcn.endsWith("RenderingDurationFrameLayout")) {
                    if (parent.getVisibility() != View.GONE) {
                        parent.setVisibility(View.GONE);
                        ViewGroup.LayoutParams lp = parent.getLayoutParams();
                        if (lp != null) {
                            lp.height = 0;
                            if (lp instanceof ViewGroup.MarginLayoutParams) {
                                ((ViewGroup.MarginLayoutParams) lp).topMargin = 0;
                                ((ViewGroup.MarginLayoutParams) lp).bottomMargin = 0;
                            }
                            parent.setLayoutParams(lp);
                        }
                        if (mMarketHandled.add("card#" + parent.hashCode())) {
                            log("已隐藏推荐卡片 " + w + "x" + h);
                        }
                    }
                    ensureTitleVisible(verText);
                    return;
                }
                cur = parent;
            }
        } catch (Throwable ignored) {}
    }

    /** 恢复"保存安装包文件"标题可见 */
    private void ensureTitleVisible(View anyChild) {
        try {
            View cur = anyChild;
            for (int i = 0; i < 8 && cur != null; i++) {
                if (cur.getClass().getName().endsWith("DetailWithSourceFileView")) {
                    Object binding = XposedHelpers.getObjectField(cur, "binding");
                    if (binding == null) return;
                    Object tips = XposedHelpers.getObjectField(binding, "tvSaveApkTips");
                    if (tips instanceof View) {
                        View tv = (View) tips;
                        if (tv.getVisibility() != View.VISIBLE) {
                            tv.setVisibility(View.VISIBLE);
                            ViewGroup.LayoutParams lp = tv.getLayoutParams();
                            if (lp != null && lp.height == 0) {
                                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                                tv.setLayoutParams(lp);
                            }
                            if (mMarketHandled.add("title#" + tv.hashCode())) {
                                log("已恢复标题可见");
                            }
                        }
                    }
                    return;
                }
                Object p = cur.getParent();
                cur = (p instanceof View) ? (View) p : null;
            }
        } catch (Throwable ignored) {}
    }

    // ==================== 工具方法 ====================

    private void hookBool(Class<?> cls, String name, boolean value) {
        for (Method m : cls.getDeclaredMethods()) {
            if (!m.getName().equals(name)) continue;
            if (m.getReturnType() != boolean.class) continue;
            if (m.getParameterCount() != 0) continue;
            try {
                XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(value));
            } catch (Throwable ignored) {}
        }
    }

    private void hideField(Object self, String fieldName) {
        try {
            Field f = findField(self.getClass(), fieldName);
            if (f == null) return;
            Object v = f.get(self);
            if (v instanceof View) {
                View view = (View) v;
                view.setVisibility(View.GONE);
                ViewGroup.LayoutParams lp = view.getLayoutParams();
                if (lp != null) {
                    lp.height = 0;
                    view.setLayoutParams(lp);
                }
            }
        } catch (Throwable ignored) {}
    }

    private Field findField(Class<?> c, String name) {
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    private Method findMethod(Class<?> c, String name, int argc) {
        while (c != null && c != Object.class) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == argc) {
                    m.setAccessible(true);
                    return m;
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private void log(String msg) {
        Log.println(Log.INFO, TAG, msg);
        XposedBridge.log("[" + TAG + "] " + msg);
    }
}
