package com.bomo.xbphrase;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * XBPhrase Bridge —— 小布输入法短语(自定义词库)导出/导入命令桥。
 * 用途：在 com.oplus.keyboard 进程内监听 externalFilesDir 下的命令文件，
 *       反射调用 Kernel.phraseSyncExport / phraseSyncImport 操作引擎词库。
 * 命令协议（文件放在 /sdcard/Android/data/com.oplus.keyboard/files/）：
 *   cmd_export  -> 引擎导出到同目录 export.json，结果写 done_export.txt
 *   cmd_import  -> 引擎导入同目录 import.json，结果写 done_import.txt（含 phraseGetCount）
 * 执行成功后删除命令文件。
 * @author bomo
 */
public class Module implements IXposedHookLoadPackage {

    private static final String PKG = "com.oplus.keyboard";
    /** 宿主 App 的 ClassLoader，反射 Kernel 必须走它 */
    private volatile ClassLoader appCl;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (lpp == null) return;
        if (PKG.equals(lpp.packageName)) {
            appCl = lpp.classLoader;
            XposedBridge.log("xbphrase: loaded into " + PKG);
            if (Build.MANUFACTURER.equalsIgnoreCase("vivo")) {
                installHapticsFix(lpp.classLoader);
            }
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    loop();
                }
            }, "xbphrase-cmd");
            t.setDaemon(true);
            t.start();
            return;
        }
        if ("com.tencent.wetype".equals(lpp.packageName)) {
            // 震动钩子仅 OnePlus/OPPO（vivo 原生震动正常）；导入循环全设备可用
            if (Build.MANUFACTURER.equalsIgnoreCase("OnePlus")
                    || Build.MANUFACTURER.equalsIgnoreCase("Oppo")) {
                installWetypeXiaobuHaptic(lpp.classLoader);
            }
            wtCl = lpp.classLoader;
            installWtBackupHooks(lpp.classLoader);
            // 引擎(native 词典/会话)只活在 :hld 子进程；其他进程抢命令会 SIGSEGV
            if (!lpp.processName.endsWith(":hld")) {
                XposedBridge.log("xbphrase: wetype non-engine process " + lpp.processName + ", skip wtLoop");
                return;
            }
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    wtLoop();
                }
            }, "xbphrase-wt");
            t.setDaemon(true);
            t.start();
        }
    }

    /** wetype 进程 ClassLoader 与命令轮询（cmd_wtimport → wt_import.tsv → done_wtimport.txt）。 */
    private volatile ClassLoader wtCl;

    /**
     * 钩住换机助手官方通道 WxhldApi.export_user_data / import_user_data：
     * 记录 data_path + encrypt_key 到 wt_backup_log.txt，并把当时的词库包文件
     * 复制到外部 files 目录（export 在返回后复制产物，import 在调用前复制传入包），
     * 用于解剖官方包格式与密钥来源。
     * @author bomo
     */
    private void installWtBackupHooks(ClassLoader cl) {
        try {
            Class<?> api = Class.forName("com.tencent.wxhld.WxhldApi", false, cl);
            Class<?> paramCls = Class.forName("com.tencent.wxhld.info.ImportAndExportUserDataParam", false, cl);
            XposedHelpers.findAndHookMethod(api, "export_user_data", paramCls, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    wtBackupLog("export.call", p.args[0]);
                }
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    wtBackupLog("export.ret=" + p.getResult(), p.args[0]);
                    wtCopyPkg(p.args[0], "wt_pkg_export.bin");
                }
            });
            XposedHelpers.findAndHookMethod(api, "import_user_data", paramCls, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    wtBackupLog("import.call", p.args[0]);
                    wtCopyPkg(p.args[0], "wt_pkg_import.bin");
                }
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    wtBackupLog("import.ret=" + p.getResult(), p.args[0]);
                }
            });
            XposedBridge.log("xbphrase: wetype backup-hooks installed");
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private void wtBackupLog(String tag, Object param) {
        try {
            Context ctx = currentAppOrNull();
            if (ctx == null) return;
            String path = (String) param.getClass().getField("data_path").get(param);
            String key = (String) param.getClass().getField("encrypt_key").get(param);
            java.io.File f = new java.io.File(ctx.getExternalFilesDir(null), "wt_backup_log.txt");
            java.io.FileWriter w = new java.io.FileWriter(f, true);
            w.write(tag + " data_path=" + path + " encrypt_key=" + key + "\n");
            w.close();
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private void wtCopyPkg(Object param, String destName) {
        try {
            Context ctx = currentAppOrNull();
            if (ctx == null) return;
            String path = (String) param.getClass().getField("data_path").get(param);
            java.io.File src = new java.io.File(path);
            if (!src.exists()) return;
            java.io.File dst = new java.io.File(ctx.getExternalFilesDir(null), destName);
            java.io.FileInputStream in = new java.io.FileInputStream(src);
            java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            out.close();
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private void wtLoop() {
        while (true) {
            try {
                Thread.sleep(2000);
                Context ctx = currentAppOrNull();
                if (ctx == null) continue;
                File dir = ctx.getExternalFilesDir(null);
                if (dir == null) continue;
                File cmd = new File(dir, "cmd_wtimport");
                File stt = new File(dir, "cmd_wtstat");
                File his = new File(dir, "cmd_wthist");
                File exp = new File(dir, "cmd_wtexport");
                File imp2 = new File(dir, "cmd_wtimport2");
                File lrn = new File(dir, "cmd_wtlearn");
                File rst = new File(dir, "cmd_wtreset");
                if (!cmd.exists() && !stt.exists() && !his.exists()
                        && !exp.exists() && !imp2.exists() && !lrn.exists()
                        && !rst.exists()) continue;
                if (rst.exists()) {
                    doWtReset(new File(dir, "done_wtreset.txt"));
                    rst.delete();
                }
                if (lrn.exists()) {
                    doWtLearn(new File(dir, "wt_learn.tsv"), new File(dir, "done_wtlearn.txt"));
                    lrn.delete();
                }
                if (cmd.exists()) {
                    doWtImport(new File(dir, "wt_import.tsv"), new File(dir, "done_wtimport.txt"));
                    cmd.delete();
                }
                if (exp.exists()) {
                    doWtUserData(dir, exp, new File(dir, "done_wtexport.txt"), false);
                    exp.delete();
                }
                if (imp2.exists()) {
                    doWtUserData(dir, imp2, new File(dir, "done_wtimport2.txt"), true);
                    imp2.delete();
                }
                if (his.exists()) {
                    doWtHist(new File(dir, "wt_hist.tsv"), new File(dir, "done_wthist.txt"));
                    his.delete();
                }
                if (stt.exists()) {
                    doWtStat(new File(dir, "done_wtstat.txt"));
                    stt.delete();
                }
            } catch (Throwable e) {
                XposedBridge.log(e);
            }
        }
    }

    /**
     * 批量导入用户短语到微信输入法引擎：wt_import.tsv 每行 "拼音码\t词"，
     * 反射构造 UserHotWordInfo{key,words,flag=0} 调 WxhldApi.set_user_hot_word。
     */
    private void doWtImport(File tsv, File done) {
        long ok = 0, fail = 0, total = 0;
        String firstErr = "";
        try {
            Class<?> api = Class.forName("com.tencent.wxhld.WxhldApi", false, wtCl);
            final Class<?> infoCls = Class.forName("com.tencent.wxhld.info.UserHotWordInfo", false, wtCl);
            Method set = api.getMethod("set_user_hot_word", infoCls);
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(tsv), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                String[] p = line.split("\t");
                if (p.length < 2) continue;
                total++;
                try {
                    Object info = infoCls.getConstructor().newInstance();
                    infoCls.getField("key").set(info, p[0]);
                    infoCls.getField("words").set(info, p[1]);
                    infoCls.getField("flag").setInt(info, 0);
                    Object r = set.invoke(null, info);
                    if (Boolean.TRUE.equals(r)) ok++; else fail++;
                } catch (Throwable t) {
                    fail++;
                    if (firstErr.isEmpty()) {
                        Throwable c = t.getCause() != null ? t.getCause() : t;
                        firstErr = String.valueOf(c);
                    }
                }
            }
            br.close();
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            firstErr = "FATAL " + c;
        }
        write(done, "total=" + total + " ok=" + ok + " fail=" + fail + " firstErr=" + firstErr);
    }

    /** 一加专属：把微信输入法按键震动替换为小布同款 Oplus 线性马达波形。 */
    private void installWetypeXiaobuHaptic(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.tencent.wetype.plugin.hld.vibrate.i", cl, "a",
                    "com.tencent.wetype.plugin.hld.vibrate.k",
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) {
                            int level = 5;
                            try {
                                java.lang.reflect.Field vf =
                                        param.args[0].getClass().getDeclaredField("value");
                                vf.setAccessible(true);
                                level = ((Integer) vf.get(param.args[0])).intValue();
                            } catch (Throwable ignored) {
                            }
                            xiaobuVibrate(level);
                            return null;
                        }
                    });
            XposedBridge.log("xbphrase: wetype xiaobu-haptic installed");
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    /**
     * 小布 V.d(level) 的一加原生复刻：LinearmotorVibrator("linearmotor") +
     * WaveformEffect{strength={0,1200,2000,2000,2000,2400}, type={-1,0,0,1,69,69}}，
     * 失败退标准 Vibrator。结果带缓存，只解析一次。
     */
    private volatile Object lmVibrator;
    private volatile boolean lmResolved;

    private void xiaobuVibrate(int level) {
        if (level < 1 || level > 5) level = 5;
        int[] strength = {0, 1200, 2000, 2000, 2000, 2400};
        int[] type = {-1, 0, 0, 1, 69, 69};
        try {
            Context ctx = currentAppOrNull();
            if (ctx == null) return;
            if (!lmResolved) {
                synchronized (this) {
                    if (!lmResolved) {
                        try {
                            Class<?> lmv = Class.forName("com.oplus.os.LinearmotorVibrator");
                            lmVibrator = ctx.getSystemService("linearmotor");
                            if (lmv != null && lmVibrator != null
                                    && !lmv.isInstance(lmVibrator)) {
                                lmVibrator = null;
                            }
                        } catch (Throwable t) {
                            lmVibrator = null;
                        }
                        lmResolved = true;
                    }
                }
            }
            if (lmVibrator != null) {
                Class<?> builderCls = Class.forName("com.oplus.os.WaveformEffect$Builder");
                Object builder = builderCls.getConstructor().newInstance();
                try {
                    builderCls.getMethod("setStrengthSettingEnabled", boolean.class)
                            .invoke(builder, Boolean.FALSE);
                } catch (NoSuchMethodException ignored) {
                }
                builderCls.getMethod("setEffectStrength", int.class)
                        .invoke(builder, Integer.valueOf(strength[level]));
                builderCls.getMethod("setEffectType", int.class)
                        .invoke(builder, Integer.valueOf(type[level]));
                builderCls.getMethod("setAsynchronous", boolean.class)
                        .invoke(builder, Boolean.TRUE);
                Object effect = builderCls.getMethod("build").invoke(builder);
                lmVibrator.getClass().getMethod("vibrate", effect.getClass())
                        .invoke(lmVibrator, effect);
                return;
            }
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
        // 退路：标准震动 100ms
        doStandardVibrate(5);
    }

    /** 主轮询循环：2s 一次检查命令文件。异常只记录不退出。 */
    private void loop() {
        while (true) {
            try {
                Thread.sleep(2000);
                Context ctx = currentApp();
                if (ctx == null) continue;
                File dir = ctx.getExternalFilesDir(null);
                if (dir == null) continue;
                File exp = new File(dir, "cmd_export");
                File imp = new File(dir, "cmd_import");
                File ins = new File(dir, "cmd_insert");
                File qry = new File(dir, "cmd_query");
                File ude = new File(dir, "cmd_udexport");
                File udi = new File(dir, "cmd_udimport");
                File cand = new File(dir, "cmd_cand");
                File cand9 = new File(dir, "cmd_cand9");
                if (!exp.exists() && !imp.exists() && !ins.exists() && !qry.exists()
                        && !ude.exists() && !udi.exists() && !cand.exists() && !cand9.exists()
                        && !new File(dir, "cmd_train9").exists()
                        && !new File(dir, "cmd_uword").exists()
                        && !new File(dir, "cmd_delcand").exists()) continue;
                Object kernel = kernelInstance();
                if (kernel == null) { XposedBridge.log("xbphrase: kernel null"); continue; }
                if (exp.exists()) {
                    File out = new File(dir, "export.json");
                    Object r = invokeString(kernel, "phraseSyncExport", out.getAbsolutePath());
                    write(new File(dir, "done_export.txt"),
                            "result=" + r + " exists=" + out.exists() + " size=" + out.length());
                    exp.delete();
                }
                if (imp.exists()) {
                    File in = new File(dir, "import.json");
                    try {
                        Object r = invokeString(kernel, "phraseSyncImport", in.getAbsolutePath());
                        Object n = kernel.getClass().getMethod("phraseGetCount").invoke(kernel);
                        write(new File(dir, "done_import.txt"),
                                "result=" + r + " count=" + n + " src=" + in.length());
                    } catch (Throwable t) {
                        write(new File(dir, "done_import.txt"), "crash=" + t);
                    }
                    imp.delete();
                }
                if (ins.exists()) {
                    doInsert(kernel, new File(dir, "insert.tsv"), new File(dir, "done_insert.txt"));
                    ins.delete();
                }
                if (qry.exists()) {
                    doQuery(kernel, new File(dir, "query.txt"), new File(dir, "done_query.txt"));
                    qry.delete();
                }
                if (ude.exists()) {
                    File out = new File(dir, "ud_export.json");
                    try {
                        Object r = invokeString(kernel, "userDictSyncExport", out.getAbsolutePath());
                        write(new File(dir, "done_udexport.txt"),
                                "result=" + r + " exists=" + out.exists() + " size=" + out.length());
                    } catch (Throwable t) {
                        Throwable c = t.getCause() != null ? t.getCause() : t;
                        write(new File(dir, "done_udexport.txt"), "crash=" + c);
                    }
                    ude.delete();
                }
                if (udi.exists()) {
                    File in = new File(dir, "ud_import.json");
                    try {
                        Object r = invokeString(kernel, "userDictSyncImport", in.getAbsolutePath());
                        write(new File(dir, "done_udimport.txt"), "result=" + r + " src=" + in.length());
                    } catch (Throwable t) {
                        Throwable c = t.getCause() != null ? t.getCause() : t;
                        write(new File(dir, "done_udimport.txt"), "crash=" + c);
                    }
                    udi.delete();
                }
                if (cand.exists()) {
                    doCand(kernel, new File(dir, "cand.txt"), new File(dir, "done_cand.txt"));
                    cand.delete();
                }
                if (cand9.exists()) {
                    doCand9(kernel, new File(dir, "cand9.txt"), new File(dir, "done_cand9.txt"));
                    cand9.delete();
                }
                File tr9 = new File(dir, "cmd_train9");
                if (tr9.exists()) {
                    doTrain9(kernel, new File(dir, "train9.txt"), new File(dir, "done_train9.txt"));
                    tr9.delete();
                }
                File uw = new File(dir, "cmd_uword");
                if (uw.exists()) {
                    doUword(new File(dir, "uword.txt"), new File(dir, "done_uword.txt"));
                    uw.delete();
                }
                File dc = new File(dir, "cmd_delcand");
                if (dc.exists()) {
                    doDelCand(kernel, new File(dir, "delcand.txt"), new File(dir, "done_delcand.txt"));
                    dc.delete();
                }
            } catch (Throwable e) {
                XposedBridge.log(e);
            }
        }
    }

    /**
     * 批量单条插入：读 insert.tsv（每行 input_code\tcontent[\tlabel]），
     * 逐行调 Kernel.phraseInsert，统计成功数与最终 phraseGetCount。
     * 单行异常不中断整批。
     */
    private void doInsert(Object kernel, File tsv, File done) {
        long ok = 0, fail = 0, total = 0;
        String err = "";
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(tsv), "UTF-8"));
            java.lang.reflect.Method m = kernel.getClass().getMethod(
                    "phraseInsert",
                    Class.forName("com.oplus.keyboard.kernel.Phrase", false, appCl));            String line;
            while ((line = br.readLine()) != null) {
                total++;
                String[] parts = line.split("\t", -1);
                if (parts.length < 2) { fail++; continue; }
                try {
                    Object ph = newPhrase(parts[0], parts[1], parts.length > 2 ? parts[2] : "");
                    int r = ((Integer) m.invoke(kernel, ph)).intValue();
                    if (r >= 0) ok++; else fail++;
                } catch (Throwable t) {
                    fail++;
                    if (err.isEmpty()) {
                        Throwable c = (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null)
                                ? t.getCause() : t;
                        java.io.StringWriter sw = new java.io.StringWriter();
                        c.printStackTrace(new java.io.PrintWriter(sw));
                        err = sw.toString();
                    }
                }
            }
            br.close();
        } catch (Throwable t) {
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            err = sw.toString();
        }
        String cnt = "?";
        try {
            cnt = String.valueOf(kernel.getClass().getMethod("phraseGetCount").invoke(kernel));
        } catch (Throwable ignored) {
        }
        write(done, "total=" + total + " ok=" + ok + " fail=" + fail + " count=" + cnt + " firstErr=" + err);
    }

    /** 反射构造 Phrase：7 参构造 (input_code, content, label, primary_key, isFold, isSideslip, update_time)。 */
    private Object newPhrase(String code, String content, String label) throws Exception {
        Class<?> pc = Class.forName("com.oplus.keyboard.kernel.Phrase", false, appCl);
        java.lang.reflect.Constructor<?> c = pc.getDeclaredConstructor(
                String.class, String.class, String.class, String.class,
                boolean.class, boolean.class, long.class);
        return c.newInstance(code, content, label, "", false, false, 0L);
    }

    /**
     * 查询验证：query.txt 每行一个 input_code，
     * 调 Kernel.phraseQuery(code,"","",50) 把命中的 (code,content) 写入 done_query.txt。
     */
    private void doQuery(Object kernel, File qf, File done) {
        StringBuilder sb = new StringBuilder();
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(qf), "UTF-8"));
            java.lang.reflect.Method m = kernel.getClass().getMethod(
                    "phraseQuery", String.class, String.class, String.class, int.class);
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty()) continue;
                sb.append("Q[").append(line).append("] ");
                try {
                    Object list = m.invoke(kernel, line, "", "", 50);
                    java.util.List<?> l = (java.util.List<?>) list;
                    sb.append("hits=").append(l.size()).append(" ");
                    for (Object p : l) {
                        String c = (String) p.getClass().getMethod("getInput_code").invoke(p);
                        String w = (String) p.getClass().getMethod("getContent").invoke(p);
                        sb.append("(").append(c).append(",").append(w).append(")");
                    }
                } catch (Throwable t) {
                    Throwable c = t.getCause() != null ? t.getCause() : t;
                    sb.append("ERR=").append(c);
                }
                sb.append("\n");
            }
            br.close();
        } catch (Throwable t) {
            sb.append("FATAL ").append(t);
        }
        write(done, sb.toString());
    }

    /**
     * 候选顺序探测：cand.txt 每行一个拼音码（连写），
     * 引擎 clear→逐字符 processKey→读前 8 个候选（text|source|quality）写入 done_cand.txt。
     * 直接反射 Engine.INSTANCE，绕开 Kernel 的 UI 回调链。
     */
    private void doCand(Object kernel, File cf, File done) {
        StringBuilder sb = new StringBuilder();
        try {
            Class<?> ec = Class.forName("com.oplus.keyboard.kernel.Engine", false, appCl);
            Object eng = ec.getField("INSTANCE").get(null);
            java.lang.reflect.Method mDp = kernel.getClass().getDeclaredMethod("getEngineDataPath");
            java.lang.reflect.Method mLp = kernel.getClass().getDeclaredMethod("getEngineDataLogPath");
            mDp.setAccessible(true);
            mLp.setAccessible(true);
            String dataPath = (String) mDp.invoke(kernel);
            String logPath = (String) mLp.invoke(kernel);
            java.lang.reflect.Method mUp = ec.getMethod("startUp", String.class, String.class);
            java.lang.reflect.Method mSet = ec.getMethod("setInput", String.class);
            java.lang.reflect.Method mRaw = ec.getMethod("getRawInput");
            java.lang.reflect.Method mCount = ec.getMethod("getCandidateCount");
            java.lang.reflect.Method mClear = ec.getMethod("clear");
            java.lang.reflect.Method mCands = ec.getMethod("getCandidates");
            Object up = mUp.invoke(eng, dataPath, logPath);
            sb.append("startUp=").append(up).append(" path=").append(dataPath).append("\n");
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(cf), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                sb.append("== ").append(line).append("\n");
                try {
                    mClear.invoke(eng);
                    mSet.invoke(eng, line);
                    Object cnt = mCount.invoke(eng);
                    sb.append("raw=").append(mRaw.invoke(eng)).append(" count=").append(cnt).append("\n");
                    java.util.List<?> l = (java.util.List<?>) mCands.invoke(eng);
                    int n = Math.min(l.size(), 8);
                    for (int i = 0; i < n; i++) {
                        Object c = l.get(i);
                        Class<?> cc = c.getClass();
                        Object text = cc.getMethod("getText").invoke(c);
                        Object src = cc.getMethod("getSource").invoke(c);
                        Object q = cc.getMethod("getQuality").invoke(c);
                        sb.append(i).append(" ").append(text)
                          .append(" | ").append(src).append(" | ").append(q).append("\n");
                    }
                    mClear.invoke(eng);
                } catch (Throwable t) {
                    Throwable c = t.getCause() != null ? t.getCause() : t;
                    sb.append("ERR ").append(c).append("\n");
                }
            }
            br.close();
        } catch (Throwable t) {
            sb.append("FATAL ").append(t);
        }
        write(done, sb.toString());
    }

    /**
     * 九宫格(T9)候选探测：cand9.txt 每行一个数字键序列（如 2666），
     * startUp→selectInputMode("t9")→逐数字 processKey→读前 8 候选；结束后恢复 rime_frost。
     */
    private void doCand9(Object kernel, File cf, File done) {
        StringBuilder sb = new StringBuilder();
        try {
            Class<?> ec = Class.forName("com.oplus.keyboard.kernel.Engine", false, appCl);
            Object eng = ec.getField("INSTANCE").get(null);
            java.lang.reflect.Method mDp = kernel.getClass().getDeclaredMethod("getEngineDataPath");
            java.lang.reflect.Method mLp = kernel.getClass().getDeclaredMethod("getEngineDataLogPath");
            mDp.setAccessible(true);
            mLp.setAccessible(true);
            java.lang.reflect.Method mUp = ec.getMethod("startUp", String.class, String.class);
            java.lang.reflect.Method mMode = ec.getMethod("selectInputMode", String.class);
            java.lang.reflect.Method mKey = ec.getMethod("keyName2Keycode", String.class);
            java.lang.reflect.Method mProc = ec.getMethod("processKey", int.class, int.class);
            java.lang.reflect.Method mRaw = ec.getMethod("getRawInput");
            java.lang.reflect.Method mClear = ec.getMethod("clear");
            java.lang.reflect.Method mCands = ec.getMethod("getCandidates");
            sb.append("startUp=").append(mUp.invoke(eng, mDp.invoke(kernel), mLp.invoke(kernel))).append("\n");
            sb.append("mode9=").append(mMode.invoke(eng, "t9")).append("\n");
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(cf), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                sb.append("== ").append(line).append("\n");
                try {
                    mClear.invoke(eng);
                    for (int i = 0; i < line.length(); i++) {
                        int kc = ((Integer) mKey.invoke(eng, String.valueOf(line.charAt(i)))).intValue();
                        if (i == 0) sb.append("kc(").append(line.charAt(0)).append(")=").append(kc).append("\n");
                        mProc.invoke(eng, kc, 0);
                    }
                    sb.append("raw=").append(mRaw.invoke(eng)).append("\n");
                    java.util.List<?> l = (java.util.List<?>) mCands.invoke(eng);
                    int n = Math.min(l.size(), 8);
                    for (int i = 0; i < n; i++) {
                        Object c = l.get(i);
                        Class<?> cc = c.getClass();
                        sb.append(i).append(" ").append(cc.getMethod("getText").invoke(c))
                          .append(" | ").append(cc.getMethod("getSource").invoke(c)).append("\n");
                    }
                    mClear.invoke(eng);
                } catch (Throwable t) {
                    Throwable c = t.getCause() != null ? t.getCause() : t;
                    sb.append("ERR ").append(c).append("\n");
                }
            }
            br.close();
            mMode.invoke(eng, "rime_frost");
        } catch (Throwable t) {
            sb.append("FATAL ").append(t);
        }
        write(done, sb.toString());
    }

    /**
     * T9 选词训练：train9.txt 每行 "数字键|目标词|次数"。
     * 每轮 clear→processKey 序列→getCandidates 定位目标→selectCandidate→Enter 上屏，
     * 用于验证引擎的读音自适应学习。结果写 done_train9.txt。
     */
    private void doTrain9(Object kernel, File tf, File done) {
        StringBuilder sb = new StringBuilder();
        try {
            Class<?> ec = Class.forName("com.oplus.keyboard.kernel.Engine", false, appCl);
            Object eng = ec.getField("INSTANCE").get(null);
            java.lang.reflect.Method mDp = kernel.getClass().getDeclaredMethod("getEngineDataPath");
            java.lang.reflect.Method mLp = kernel.getClass().getDeclaredMethod("getEngineDataLogPath");
            mDp.setAccessible(true);
            mLp.setAccessible(true);
            java.lang.reflect.Method mUp = ec.getMethod("startUp", String.class, String.class);
            java.lang.reflect.Method mMode = ec.getMethod("selectInputMode", String.class);
            java.lang.reflect.Method mKey = ec.getMethod("keyName2Keycode", String.class);
            java.lang.reflect.Method mProc = ec.getMethod("processKey", int.class, int.class);
            java.lang.reflect.Method mClear = ec.getMethod("clear");
            java.lang.reflect.Method mCands = ec.getMethod("getCandidates");
            java.lang.reflect.Method mSel = ec.getMethod("selectCandidate", int.class);
            java.lang.reflect.Method mCommit = ec.getMethod("getCommit");
            sb.append("startUp=").append(mUp.invoke(eng, mDp.invoke(kernel), mLp.invoke(kernel))).append("\n");
            sb.append("mode9=").append(mMode.invoke(eng, "t9")).append("\n");
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(tf), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] p = line.split("\\|");
                if (p.length < 3) continue;
                int times;
                try { times = Integer.parseInt(p[2]); } catch (Exception e) { continue; }
                int enter = ((Integer) mKey.invoke(eng, "Return")).intValue();
                int hits = 0, misses = 0;
                for (int r = 0; r < times; r++) {
                    mClear.invoke(eng);
                    for (int i = 0; i < p[0].length(); i++) {
                        int kc = ((Integer) mKey.invoke(eng, String.valueOf(p[0].charAt(i)))).intValue();
                        mProc.invoke(eng, kc, 0);
                    }
                    java.util.List<?> l = (java.util.List<?>) mCands.invoke(eng);
                    int idx = -1;
                    for (int i = 0; i < l.size(); i++) {
                        Object t = l.get(i).getClass().getMethod("getText").invoke(l.get(i));
                        if (p[1].equals(t)) { idx = i; break; }
                    }
                    if (idx < 0) { misses++; break; }
                    mSel.invoke(eng, idx);
                    mProc.invoke(eng, enter, 0);
                    Object cm = mCommit.invoke(eng);
                    hits++;
                    if (r == 0) sb.append(p[1]).append(" idx=").append(idx).append(" commit=").append(cm).append("\n");
                }
                sb.append("train ").append(p[1]).append(" hits=").append(hits).append(" misses=").append(misses).append("\n");
                mClear.invoke(eng);
            }
            br.close();
            mMode.invoke(eng, "rime_frost");
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            sb.append("FATAL ").append(c);
        }
        write(done, sb.toString());
    }

    /**
     * 官方学习通道：uword.txt 每行 "词|拼音(空格分隔)|次数"，
     * 调 Engine.updateUserWord(word, pinyin) 次数遍，结果写 done_uword.txt。
     */
    private void doUword(File tf, File done) {
        StringBuilder sb = new StringBuilder();
        try {
            Class<?> ec = Class.forName("com.oplus.keyboard.kernel.Engine", false, appCl);
            Object eng = ec.getField("INSTANCE").get(null);
            java.lang.reflect.Method mUw = ec.getMethod("updateUserWord", String.class, String.class);
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(tf), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] p = line.split("\\|");
                if (p.length < 2) continue;
                int times = 1;
                if (p.length > 2) { try { times = Integer.parseInt(p[2]); } catch (Exception ignored) {} }
                String last = "";
                for (int i = 0; i < times; i++) {
                    last = String.valueOf(mUw.invoke(eng, p[0], p[1]));
                }
                sb.append(p[0]).append(" x").append(times).append(" last=").append(last).append("\n");
            }
            br.close();
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            sb.append("FATAL ").append(c);
        }
        write(done, sb.toString());
    }

    /**
     * T9 删词：delcand.txt 每行 "数字键|要删的词"。
     * 输入键序列→定位词→查 deletable 标志→deleteCandidate，结果写 done_delcand.txt。
     */
    private void doDelCand(Object kernel, File tf, File done) {
        StringBuilder sb = new StringBuilder();
        try {
            Class<?> ec = Class.forName("com.oplus.keyboard.kernel.Engine", false, appCl);
            Object eng = ec.getField("INSTANCE").get(null);
            java.lang.reflect.Method mDp = kernel.getClass().getDeclaredMethod("getEngineDataPath");
            java.lang.reflect.Method mLp = kernel.getClass().getDeclaredMethod("getEngineDataLogPath");
            mDp.setAccessible(true);
            mLp.setAccessible(true);
            java.lang.reflect.Method mUp = ec.getMethod("startUp", String.class, String.class);
            java.lang.reflect.Method mMode = ec.getMethod("selectInputMode", String.class);
            java.lang.reflect.Method mKey = ec.getMethod("keyName2Keycode", String.class);
            java.lang.reflect.Method mProc = ec.getMethod("processKey", int.class, int.class);
            java.lang.reflect.Method mClear = ec.getMethod("clear");
            java.lang.reflect.Method mCands = ec.getMethod("getCandidates");
            java.lang.reflect.Method mFlag = ec.getMethod("getCandidateDeletableFlag", int.class);
            java.lang.reflect.Method mDel = ec.getMethod("deleteCandidate", int.class);
            mUp.invoke(eng, mDp.invoke(kernel), mLp.invoke(kernel));
            mMode.invoke(eng, "t9");
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(tf), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] p = line.split("\\|");
                if (p.length < 2) continue;
                mClear.invoke(eng);
                for (int i = 0; i < p[0].length(); i++) {
                    int kc = ((Integer) mKey.invoke(eng, String.valueOf(p[0].charAt(i)))).intValue();
                    mProc.invoke(eng, kc, 0);
                }
                java.util.List<?> l = (java.util.List<?>) mCands.invoke(eng);
                int idx = -1;
                for (int i = 0; i < l.size(); i++) {
                    Object t = l.get(i).getClass().getMethod("getText").invoke(l.get(i));
                    if (p[1].equals(t)) { idx = i; break; }
                }
                if (idx < 0) { sb.append(p[1]).append(" NOT FOUND\n"); continue; }
                Object flag = mFlag.invoke(eng, idx);
                Object r = mDel.invoke(eng, idx);
                sb.append(p[1]).append(" idx=").append(idx).append(" flag=").append(flag).append(" del=").append(r).append("\n");
                mClear.invoke(eng);
            }
            br.close();
            mMode.invoke(eng, "rime_frost");
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            sb.append("FATAL ").append(c);
        }
        write(done, sb.toString());
    }

    /**
     * vivo 移植震动修复（仅 vivo 安装）：
     * 1) l.d0() 原读 Settings.System input_method_key_vibration（ColorOS 私有键，vivo 上恒 0），
     *    改读 App 自身开关 Key_setVibration_Enabled；
     * 2) V.d(int) 先过 OplusFeatureConfigManager（vivo 上类初始化失败抛 Error，原代码只 catch
     *    Exception 接不住），改为直接用标准 Vibrator API 震动。
     */
    private void installHapticsFix(final ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("com.oplus.keyboard.base.data.l", cl, "d0",
                new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        Context ctx = currentAppOrNull();
                        if (ctx == null) return Boolean.FALSE;
                        return Boolean.valueOf(ctx.getSharedPreferences(
                                "com.oplus.keyboard.restore.preference", 3)
                                .getBoolean("Key_setVibration_Enabled", false));
                    }
                });
            XposedHelpers.findAndHookMethod("com.oplus.keyboard.base.util.V", cl, "d", int.class,
                new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        doStandardVibrate(((Integer) param.args[0]).intValue());
                        return null;
                    }
                });
            XposedBridge.log("xbphrase: haptics fix installed (vivo)");
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    /** 标准震动：level 1..5 对应小布原数组 {0,20,40,60,80,100}ms / {0,50,100,150,200,255}amplitude。 */
    private void doStandardVibrate(int level) {
        long[] dur = {0, 20, 40, 60, 80, 100};
        int[] amp = {0, 50, 100, 150, 200, 255};
        if (level < 1 || level > 5) return;
        try {
            Context ctx = currentAppOrNull();
            if (ctx == null) return;
            Vibrator v;
            try {
                v = ((VibratorManager) ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE))
                        .getDefaultVibrator();
            } catch (Throwable t2) {
                v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (v == null || !v.hasVibrator()) return;
            if (v.hasAmplitudeControl()) {
                v.vibrate(VibrationEffect.createOneShot(dur[level], amp[level]));
            } else {
                v.vibrate(VibrationEffect.createOneShot(dur[level],
                        VibrationEffect.DEFAULT_AMPLITUDE));
            }
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    /** 引擎用户短语现状：dump get_user_hot_word() 前 60 条 + stats，验证容量上限假设。 */
    private void doWtStat(File done) {
        StringBuilder sb = new StringBuilder();
        try {
            Class<?> api = Class.forName("com.tencent.wxhld.WxhldApi", false, wtCl);
            Class<?> infoCls = Class.forName("com.tencent.wxhld.info.UserHotWordInfo", false, wtCl);
            Object arr = api.getMethod("get_user_hot_word").invoke(null);
            int len = java.lang.reflect.Array.getLength(arr);
            sb.append("user_hot_word_count=").append(len).append("\n");
            for (int i = 0; i < Math.min(len, 60); i++) {
                Object o = java.lang.reflect.Array.get(arr, i);
                sb.append(i).append(" ").append(infoCls.getField("key").get(o))
                  .append(" | ").append(infoCls.getField("words").get(o))
                  .append(" | kind=").append(infoCls.getField("kind").get(o))
                  .append(" flag=").append(infoCls.getField("flag").get(o)).append("\n");
            }
            Object st = api.getMethod("get_user_hot_word_stats", boolean.class)
                    .invoke(null, Boolean.FALSE);
            if (st != null) {
                java.lang.reflect.Field[] fs = st.getClass().getFields();
                sb.append("stats:");
                for (java.lang.reflect.Field f : fs) {
                    sb.append(" ").append(f.getName()).append("=").append(f.get(st));
                }
                sb.append("\n");
            }
            Object sz = api.getMethod("get_user_dict_size").invoke(null);
            sb.append("user_dict_size=").append(sz).append("\n");
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            sb.append("ERR ").append(c);
        }
        write(done, sb.toString());
    }

    /**
     * 学习词库通道试水：wt_hist.tsv 每行一个词，调 WxhldApi.add_user_history(word)，
     * 前后对比 get_user_dict_size，结果写 done_wthist.txt。
     */
    private void doWtHist(File tsv, File done) {
        long ok = 0, fail = 0, total = 0;
        String firstErr = "";
        StringBuilder failed = new StringBuilder();
        try {
            Class<?> api = Class.forName("com.tencent.wxhld.WxhldApi", false, wtCl);
            Method add = api.getMethod("add_user_history", String.class);
            Method sz = api.getMethod("get_user_dict_size");
            Object sizeBefore = sz.invoke(null);
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(tsv), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                total++;
                if (total % 500 == 0) {
                    // 进度回写：供 StatusActivity UI 轮询展示（2s 粒度足够）
                    write(done, "progress=" + total + " ok=" + ok + " fail=" + fail);
                }
                try {
                    Object r = add.invoke(null, line);
                    if (Boolean.TRUE.equals(r)) ok++;
                    else { fail++; failed.append(line).append('\n'); }
                } catch (Throwable t) {
                    fail++;
                    failed.append(line).append('\n');
                    if (firstErr.isEmpty()) {
                        Throwable c = t.getCause() != null ? t.getCause() : t;
                        firstErr = String.valueOf(c);
                    }
                }
            }
            br.close();
            Object sizeAfter = sz.invoke(null);
            write(done, "total=" + total + " ok=" + ok + " fail=" + fail
                    + " size_before=" + sizeBefore + " size_after=" + sizeAfter
                    + " firstErr=" + firstErr);
            if (failed.length() > 0) {
                write(new File(tsv.getParentFile(), "wt_hist_fail.txt"), failed.toString());
            }
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            write(done, "FATAL " + c);
        }
    }

    /**
     * 清空引擎用户词库：反射调 WxhldApi.reset_user_dict()（void），
     * 前后打 get_user_dict_size 快照，用于"真拒绝词"甄别实验（重灌对照）。
     * @author bomo
     */
    private void doWtReset(File done) {
        try {
            Class<?> api = Class.forName("com.tencent.wxhld.WxhldApi", false, wtCl);
            Object before = api.getMethod("get_user_dict_size").invoke(null);
            api.getMethod("reset_user_dict").invoke(null);
            Thread.sleep(1000);
            Object after = api.getMethod("get_user_dict_size").invoke(null);
            write(done, "reset done size_before=" + before + " size_after=" + after);
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            write(done, "FATAL " + c);
        }
    }

    /**
     * PoC：会话学习通道打通验证。wt_learn.tsv 每行 "拼音(逗号分隔)\t词"，
     * 对每行反射：create_session(new SessionConfig()) 拿 sid →
     * process_input(sid,pinyin,空aux) → add_hard_fixed_position_user_word(sid,词,词UTF8,true,true)。
     * 逐步记录 sid/ret/dict_size 到 done，用于判断 headless 学习是否可行。
     * @author bomo
     */
    private void doWtLearn(File tsv, File done) {
        StringBuilder sb = new StringBuilder();
        try {
            Class<?> api = Class.forName("com.tencent.wxhld.WxhldApi", false, wtCl);
            Class<?> cfgCls = Class.forName("com.tencent.wxhld.info.SessionConfig", false, wtCl);
            Method createS = api.getMethod("create_session", cfgCls);
            Method procIn = api.getMethod("process_input", long.class, String.class, byte[].class);
            Method addHard = api.getMethod("add_hard_fixed_position_user_word",
                    long.class, String.class, byte[].class, boolean.class, boolean.class);
            Method dictSz = api.getMethod("get_user_dict_size");
            sb.append("size0=").append(dictSz.invoke(null));
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(tsv), "UTF-8"));
            String line; int n = 0;
            while ((line = br.readLine()) != null && n < 5) {
                String[] p = line.split("\t");
                if (p.length < 2) continue;
                n++;
                String py = p[0], word = p[1];
                try {
                    Object cfg = cfgCls.getConstructor().newInstance();
                    long sid = ((Long) createS.invoke(null, cfg)).longValue();
                    procIn.invoke(null, sid, py, new byte[0]);
                    Object r = addHard.invoke(null, sid, word, word.getBytes("UTF-8"),
                            Boolean.TRUE, Boolean.TRUE);
                    sb.append(" | [").append(py).append('/').append(word)
                      .append("] sid=").append(sid).append(" addHard=").append(r);
                } catch (Throwable t) {
                    Throwable c = t.getCause() != null ? t.getCause() : t;
                    sb.append(" | [").append(py).append('/').append(word).append("] ERR ").append(c);
                }
            }
            br.close();
            Thread.sleep(1500);
            sb.append(" size1=").append(dictSz.invoke(null));
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            sb.append(" FATAL ").append(c);
        }
        write(done, sb.toString());
    }

    /**
     * 调 WxhldApi.export_user_data / import_user_data（换机备份通道）。
     * 命令文件内容：第1行 dataPath（默认 <externalFilesDir>/wt_userdata.bin），
     * 第2行 encryptKey（默认 bomo-kt-key-0001）。ImportAndExportUserDataParam
     * 仅 data_path/encrypt_key 两字段，native 静态方法直接反射调用。
     * @author bomo
     */
    private void doWtUserData(File dir, File cmdFile, File done, boolean isImport) {
        String dataPath = new File(dir, "wt_userdata.bin").getAbsolutePath();
        String key = "bomo-kt-key-0001";
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(cmdFile), "UTF-8"));
            String l1 = br.readLine(); String l2 = br.readLine(); br.close();
            if (l1 != null && !l1.trim().isEmpty()) dataPath = l1.trim();
            if (l2 != null && !l2.trim().isEmpty()) key = l2.trim();
        } catch (Throwable ignored) {
        }
        try {
            Class<?> api = Class.forName("com.tencent.wxhld.WxhldApi", false, wtCl);
            Class<?> paramCls = Class.forName("com.tencent.wxhld.info.ImportAndExportUserDataParam", false, wtCl);
            Object param = paramCls.getConstructor().newInstance();
            paramCls.getField("data_path").set(param, dataPath);
            paramCls.getField("encrypt_key").set(param, key);
            // 诊断：登录态 + 用户词库量，帮助判断 export=false 的原因
            String diag = "";
            try {
                Object uid = api.getMethod("get_user_id").invoke(null);
                Object dsz = api.getMethod("get_user_dict_size").invoke(null);
                diag = " user_id=" + uid + " dict_size=" + dsz;
            } catch (Throwable ignored) {
            }
            // 若是 export，确保目标文件不存在（native 可能拒绝覆盖已存在文件；
            // 此前预建空文件导致 ret=false 且 size=0 无法区分成败）
            if (!isImport) {
                try {
                    File pf = new File(dataPath);
                    if (pf.getParentFile() != null) pf.getParentFile().mkdirs();
                    if (pf.exists()) pf.delete();
                } catch (Throwable ignored) {
                }
            }
            Method m = api.getMethod(isImport ? "import_user_data" : "export_user_data", paramCls);
            Object r = m.invoke(null, param);
            File f = new File(dataPath);
            write(done, "op=" + (isImport ? "import" : "export")
                    + " ret=" + r + " dataPath=" + dataPath + diag
                    + " exists=" + f.exists() + " size=" + f.length());
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            write(done, "FATAL " + c);
        }
    }

    private static Context currentAppOrNull() {
        try {
            return currentApp();
        } catch (Throwable t) {
            return null;
        }
    }

    private static Context currentApp() throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        return (Context) at.getMethod("currentApplication").invoke(null);
    }

    /** 反射拿 Kernel 单例（Kotlin object INSTANCE），必须用宿主 ClassLoader。 */
    private Object kernelInstance() {
        try {
            Class<?> kc = Class.forName("com.oplus.keyboard.kernel.Kernel", false, appCl);
            return kc.getField("INSTANCE").get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object invokeString(Object target, String method, String arg) throws Exception {
        Method m = target.getClass().getMethod(method, String.class);
        return m.invoke(target, arg);
    }

    private static void write(File f, String text) {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(text.getBytes("UTF-8"));
        } catch (Exception ignored) {
        }
    }
}
