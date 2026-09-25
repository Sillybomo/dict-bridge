package de.robv.android.xposed;

/* 编译桩：运行时由 LSPosed 框架提供真实类，勿打进 dex */
public abstract class XC_MethodReplacement extends XC_MethodHook {
    protected abstract Object replaceHookedMethod(MethodHookParam param) throws Throwable;
}
