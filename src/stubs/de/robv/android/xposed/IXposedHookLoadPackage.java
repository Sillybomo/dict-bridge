package de.robv.android.xposed;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

/* 编译桩：运行时由 LSPosed 框架提供真实类，勿打进 dex */
public interface IXposedHookLoadPackage {
    void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable;
}
