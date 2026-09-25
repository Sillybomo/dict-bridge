package de.robv.android.xposed;

/* 编译桩：运行时由 LSPosed 框架提供真实类，勿打进 dex */
public class XposedHelpers {
    public static Class<?> findClass(String className, ClassLoader classLoader) { return null; }
    public static XC_MethodHook.Unhook findAndHookMethod(String className, ClassLoader classLoader,
            String methodName, Object... parameterTypesAndCallback) { return null; }
    public static XC_MethodHook.Unhook findAndHookMethod(Class<?> clazz, String methodName,
            Object... parameterTypesAndCallback) { return null; }
}
