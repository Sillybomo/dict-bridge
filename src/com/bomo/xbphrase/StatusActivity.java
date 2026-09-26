package com.bomo.xbphrase;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.LinkedHashSet;

/**
 * 词库桥控制面板：选择本机词库文件（每行一词，或 "拼音\t词" 两列），
 * 经导入前校验（二进制嗅探/编码识别/行长与控制字符过滤）与预览确认后，
 * 通过 root 通道投递进微信输入法命令桥批量导入，页面实时显示进度。
 * 脚本触发：am start -n com.bomo.xbphrase/.StatusActivity --es dict_path <设备内绝对路径>
 *   （intent 通道同样校验，但跳过确认弹窗直接导入）。
 * 边界：需 root（su）与微信输入法引擎进程存活（:hld，弹一次键盘即激活）；
 *       导入语义=引擎学习（拼音引擎自推），已在库/基础库覆盖的词返回 false 属正常。
 * @author bomo
 */
public class StatusActivity extends Activity {

    private static final int REQ_PICK = 101;
    /** 微信输入法外部文件目录（命令桥落点）。 */
    private static final String WT_DIR = "/sdcard/Android/data/com.tencent.wetype/files";
    /** 词表文件大小上限（32MB 足够十万级词条纯文本）。 */
    private static final long MAX_FILE = 32L * 1024 * 1024;
    /** 单词最大码点数：超过视为误传文本块而非词条。 */
    private static final int MAX_WORD_CP = 16;

    private TextView statusView;
    private volatile boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(48, 96, 48, 48);

        Button pick = new Button(this);
        pick.setText("选择词库文件并导入微信输入法");
        pick.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) { openPicker(); }
        });

        statusView = new TextView(this);
        statusView.setText("root 检查中…");
        ScrollView sv = new ScrollView(this);
        sv.addView(statusView);

        box.addView(pick);
        box.addView(sv);
        setContentView(box);

        new Thread(new Runnable() {
            @Override public void run() {
                final String id = su("id");
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        statusView.setText(id.contains("uid=0")
                                ? "就绪：root 可用。请选择词库文件。"
                                : "未拿到 root（su 失败/未授权）：\n" + id);
                    }
                });
            }
        }).start();

        String path = getIntent() != null ? getIntent().getStringExtra("dict_path") : null;
        if (path != null && !path.isEmpty()) prepare(path, false);
    }

    /** 复用实例再入时（singleTop/脚本二次触发）也要吃 dict_path。 */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String path = intent != null ? intent.getStringExtra("dict_path") : null;
        if (path != null && !path.isEmpty()) prepare(path, false);
    }

    /**
     * 打开系统文件选择器。MIME 放开为通配是刻意的：各家文件管理器对 .txt
     * 上报的 MIME 不可靠，真正的格式把关在 prepare() 的内容校验里。
     */
    private void openPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK && res == RESULT_OK && data != null && data.getData() != null) {
            final File tmp = new File(getCacheDir(), "dict_src.bin");
            final Uri uri = data.getData();
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        copyStream(getContentResolver().openInputStream(uri), tmp);
                        prepare(tmp.getAbsolutePath(), true);
                    } catch (Exception e) {
                        show("读取所选文件失败: " + e);
                    }
                }
            }).start();
        }
    }

    /** 拉取源文件 → 校验规整 → （confirm 时弹窗预览）→ 导入。后台线程。 */
    private synchronized void prepare(String srcPath, final boolean confirm) {
        if (busy) { show("已有导入在跑，请等结束。"); return; }
        busy = true;
        final String src = srcPath;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    show("① 读取并校验词库文件…");
                    File mine = new File(getExternalFilesDir(null), "dict_in.bin");
                    su("cp '" + src + "' '" + mine.getAbsolutePath()
                            + "' && chmod 644 '" + mine.getAbsolutePath() + "'");
                    final Result r = validate(mine);
                    if (r.error != null) { busy = false; show(r.error); return; }
                    if (r.words.isEmpty()) {
                        busy = false;
                        show("没有识别出任何有效词条。\n支持格式：UTF-8/GBK 纯文本，每行一词"
                                + "（或 拼音<Tab>词 两列，自动取词列）。\n跳过无效行 "
                                + r.skipped + " 行。");
                        return;
                    }
                    if (confirm) {
                        busy = false;
                        confirmDialog(r);
                        return;
                    }
                    runImport(r);
                } catch (Exception e) {
                    busy = false;
                    show("失败: " + e);
                }
            }
        }).start();
    }

    /** 预览确认弹窗：数量/编码/跳过行/前 5 词，确认后才真正导入。 */
    private void confirmDialog(final Result r) {
        StringBuilder prev = new StringBuilder();
        int i = 0;
        for (String w : r.words) {
            if (i++ >= 5) break;
            prev.append(w).append("、");
        }
        if (prev.length() > 0) prev.setLength(prev.length() - 1);
        String msg = "识别 " + r.words.size() + " 词（" + r.charset + "）"
                + (r.skipped > 0 ? "，跳过无效行 " + r.skipped : "")
                + "。\n预览：" + prev + "\n\n开始导入微信输入法？";
        runOnUiThread(new Runnable() {
            @Override public void run() {
                new AlertDialog.Builder(StatusActivity.this)
                        .setTitle("词表预览")
                        .setMessage(msg)
                        .setPositiveButton("导入", new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int w) {
                                if (busy) { show("已有导入在跑"); return; }
                                busy = true;
                                new Thread(new Runnable() {
                                    @Override public void run() { runImport(r); }
                                }).start();
                            }
                        })
                        .setNegativeButton("取消", new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int w) {
                                show("已取消。");
                            }
                        })
                        .show();
            }
        });
    }

    /** 校验结果：words=有效词集(保序去重)，skipped=被过滤行数，error=拒绝原因。 */
    private static class Result {
        LinkedHashSet<String> words = new LinkedHashSet<String>();
        int skipped;
        String charset;
        String error;
    }

    /**
     * 导入前校验：大小上限 → 二进制/魔数嗅探（zip/docx 等直接拒）→
     * UTF-8 严格解码失败则 GBK 兜底 → 逐行过滤（空行/超长/控制字符）取词列。
     */
    private Result validate(File f) throws Exception {
        Result r = new Result();
        if (!f.exists() || f.length() == 0) { r.error = "文件不存在或为空。"; return r; }
        if (f.length() > MAX_FILE) {
            r.error = "文件超过 32MB（" + f.length() / 1024 / 1024 + "MB），"
                    + "个人词库纯文本不该这么大——请确认传的是导出的词表而非原始数据包。";
            return r;
        }
        byte[] raw = new byte[(int) f.length()];
        java.io.FileInputStream fis = new java.io.FileInputStream(f);
        int off = 0;
        while (off < raw.length) {
            int n = fis.read(raw, off, raw.length - off);
            if (n <= 0) break;
            off += n;
        }
        fis.close();
        // 魔数嗅探：zip（docx/xlsx 均是 zip 容器）直接拒
        if (raw.length > 2 && raw[0] == 'P' && raw[1] == 'K') {
            r.error = "这是 zip 类二进制（docx/xlsx/apk 等）。请先导出/另存为【纯文本 .txt】再导入。";
            return r;
        }
        int probe = Math.min(raw.length, 8192);
        int nul = 0;
        for (int i2 = 0; i2 < probe; i2++) if (raw[i2] == 0) nul++;
        if (nul > probe / 32) {
            r.error = "检测到二进制内容（疑似 Office/可执行/数据库文件），只接受纯文本词表。";
            return r;
        }
        // 编码：UTF-8 严格解码，失败回退 GBK
        String text = tryDecode(raw, "UTF-8");
        if (text == null) {
            text = tryDecode(raw, "GBK");
            if (text == null) {
                r.error = "无法按 UTF-8/GBK 解码：请另存为 UTF-8 或 GBK 纯文本。";
                return r;
            }
            r.charset = "GBK";
        } else {
            r.charset = "UTF-8";
        }
        String[] lines = text.split("\n");
        for (String line : lines) {
            line = line.replace("\r", "").replace("\uFEFF", "").trim();
            if (line.isEmpty() || line.startsWith("#")) continue;  // # 开头为注释行
            String[] p = line.split("\t");
            String w = (p.length >= 2 ? p[p.length - 1] : line).trim();
            if (w.isEmpty() || w.codePointCount(0, w.length()) > MAX_WORD_CP || hasCtrl(w)) {
                r.skipped++;
                continue;
            }
            r.words.add(w);
        }
        return r;
    }

    private static boolean hasCtrl(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || (c >= 0x7f && c < 0xa1)) return true;
        }
        return false;
    }

    private static String tryDecode(byte[] raw, String cs) {
        try {
            CharsetDecoder dec = Charset.forName(cs).newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            CharBuffer cb = dec.decode(ByteBuffer.wrap(raw));
            return cb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 真正执行：写词表→su 投递命令→轮询进度。后台线程。 */
    private void runImport(Result r) {
        try {
            File tsv = new File(getExternalFilesDir(null), "wt_hist.tsv");
            writeLines(tsv, r.words);
            show("② 投递 " + r.words.size() + " 词到微信输入法…");
            su("cp '" + tsv.getAbsolutePath() + "' '" + WT_DIR + "/wt_hist.tsv'"
                    + " && chmod 666 '" + WT_DIR + "/wt_hist.tsv'"
                    + " && rm -f '" + WT_DIR + "/done_wthist.txt'"
                    + " && echo x > '" + WT_DIR + "/cmd_wthist'"
                    + " && chmod 666 '" + WT_DIR + "/cmd_wthist'");
            int waited = 0;
            while (true) {
                try { Thread.sleep(2000); } catch (InterruptedException ignored) { break; }
                waited += 2;
                String done = su("cat '" + WT_DIR + "/done_wthist.txt' 2>/dev/null");
                if (done != null && done.startsWith("total=")) {
                    show("✅ 完成（" + r.words.size() + " 词）\n" + done
                            + "\n\nfalse≈已在库/基础库覆盖，属正常。"
                            + "打开输入法打你的私人词验证。");
                    break;
                }
                if (done != null && done.startsWith("progress=")) {
                    show("导入中… " + done.substring(9));
                } else if (waited > 12
                        && su("ls '" + WT_DIR + "/cmd_wthist' 2>/dev/null").trim().isEmpty()) {
                    show("❌ 命令文件被清理但无结果：引擎进程可能重启过，重开键盘再试一次。");
                    break;
                } else if (waited > 12) {
                    show("等待引擎处理中（" + waited + "s）…\n提示：微信输入法需存活——"
                            + "随便点个输入框把键盘弹出来一次。");
                }
            }
        } catch (Exception e) {
            show("失败: " + e);
        } finally {
            busy = false;
        }
    }

    private void writeLines(File f, LinkedHashSet<String> words) throws Exception {
        FileOutputStream o = new FileOutputStream(f);
        for (String w : words) { o.write((w + "\n").getBytes("UTF-8")); }
        o.close();
    }

    private void copyStream(java.io.InputStream in, File dst) throws Exception {
        FileOutputStream o = new FileOutputStream(dst);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        o.close(); in.close();
    }

    /** 执行 su 命令，返回 stdout+stderr（合并）；失败返回异常文本。 */
    private String su(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append('\n');
            BufferedReader e = new BufferedReader(new InputStreamReader(p.getErrorStream()));
            while ((l = e.readLine()) != null) sb.append(l).append('\n');
            p.waitFor();
            return sb.toString();
        } catch (Exception ex) {
            return "SU_ERR " + ex;
        }
    }

    private void show(final String s) {
        runOnUiThread(new Runnable() {
            @Override public void run() { statusView.setText(s); }
        });
    }
}
