# 构建说明

无 Gradle 依赖，纯 SDK 工具链（Windows Git Bash 实测）：

```
javac -encoding UTF-8 -source 8 -target 8 -bootclasspath <android.jar> \
      -cp src/stubs -d obj $(find src/stubs src/com -name '*.java')
d8 --min-api 26 --lib <android.jar> --output dexout $(find obj -name '*.class')
aapt2 compile --dir res -o res.zip
aapt2 link -I <android.jar> --manifest AndroidManifest.xml -R res.zip \
      --java-manifest-out -o lnk.apk   # 之后 zipalign + apksigner（自备 keystore）
```

- 签名：用你自己的 keystore 重签（仓库不含密钥）。
- 本源码树即 Release v0.4.1 的发布形态：manifest label/描述/作用域均为微信输入法专用。
- `Module.java` 内含**休眠**的小布输入法桥接代码（历史功能）：作用域数组不含
  `com.oplus.keyboard` 时永不加载，可放心。
- 目标接口：`com.tencent.wxhld.WxhldApi.add_user_history(String)`（微信输入法引擎
  自带静态 native 方法，反射调用；命令文件轮询协议见 Module.java 注释）。
