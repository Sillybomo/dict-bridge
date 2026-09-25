package de.robv.android.xposed.callbacks;

/* 编译桩：运行时由 LSPosed 框架提供真实类，勿打进 dex */
public class XC_LoadPackage {
    public static class LoadPackageParam {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
    }
}
