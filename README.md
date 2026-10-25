# MiBrowserClean · 小米浏览器 / 应用商店净化模块

一个 LSPosed 模块，用于去除小米浏览器（`com.android.browser`）与小米应用商店（`com.xiaomi.market`）中的广告与推广内容。

基于**传统 XposedBridge API** 实现，兼容 LSPosed 2.x。

---

## 功能

| 位置 | 处理内容 |
|---|---|
| **搜索联想** | 搜索框输入后下方弹出的「应用商店 / 版本号 / 安装」推广卡片 |
| **应用商店下载弹窗** | 「小米应用商店审核版本」「原安装包下载」提示 |
| **应用商店下载弹窗** | 推荐应用卡片（图标、标题、版本号、升级按钮） |
| **浏览器下载弹窗** | 应用商店推广区、「审核版本」提示、跳转应用市场 |
| **浏览器首页** | 推广位与切换引导 |
| **浏览器开屏** | MSA 联盟开屏广告 |
| **浏览器设置** | 宿主广告总开关 |
| **浏览器下载** | APK 链接跳转应用商店（改用浏览器自带下载器）|
| **浏览器首页** | 「添加全网热榜小部件」弹窗 |

---

## 环境要求

| 项 | 要求 |
|---|---|
| 系统 | Android 8.0+（minSdk 26） |
| 框架 | LSPosed 2.x（传统 XposedBridge API） |
| 宿主 | 小米浏览器、小米应用商店（MIUI / HyperOS 内置） |

### 作用域

模块已通过 `scope.list` 声明推荐作用域，LSPosed 会自动推荐勾选：

- `com.android.browser`（浏览器）
- `com.xiaomi.market`（应用商店）

---

## 安装

1. 下载 `MiBrowserClean.apk` 并安装
2. 在 LSPosed 中启用模块
3. 确认作用域勾选了**浏览器**与**应用商店**
4. **强制停止**小米浏览器与小米应用商店
5. 重新打开应用

---

## 构建

需要 JDK 17 与 Android SDK（build-tools 34、platforms/android-34）：

```bash
export ANDROID_HOME=/path/to/android-sdk
./scripts/build.sh
```

产物位于 `build/MiBrowserClean.apk`。

首次构建会自动生成测试签名 `build.keystore`。

> 脚本在 aarch64 主机上会自动检测并通过 `qemu-x86_64-static` 运行 x86_64 版本的 `aapt2` / `zipalign`。

### 自动构建

推送到 `master` / `main` 分支，或手动触发 workflow，GitHub Actions 会自动构建并上传 APK 产物。

打 `v*` 标签（如 `v1.0.0`）会自动创建 Release 并附带 APK。

### 签名

构建使用仓库内的固定密钥 `app/signing.keystore`，保证每次构建签名一致，用户可覆盖安装。

如需更换密钥，通过环境变量指定：

```bash
KEYSTORE=/path/to/key.jks KS_PASS=xxx KEY_ALIAS=xxx KEY_PASS=xxx ./scripts/build.sh
```

---

## 实现说明

### 搜索联想广告

搜索联想页由 `SearchSugManager` 创建 `NestedScrollWebView` 加载，页面内容由服务端下发，**元素没有 className**，因此无法用 CSS 选择器定位。

实现方式：向 `NestedScrollWebView` 注入 JS，按文本特征定位：

- 同时包含「应用商店」与「版本号」的元素
- 取最内层节点，向上查找宽度接近屏幕宽度的祖先作为卡片容器
- 隐藏该容器，并清理残留的「查看更多」按钮

注入时机有两处：`hyper.webkit.WebView.loadUrl` 之前，以及周期扫描兜底。

### 应用商店下载弹窗

弹窗是 `AppDetailCardView` 内的 `DetailWithSourceFileView`，推荐卡片与「保存安装包文件」标题**共用容器**。

实测层级（宽度 × 高度）：

```
VersionNameTextView        "版本：8.0.79"
  └ ConstraintLayout       504 × 45
    └ LinearLayout         504 × 167
      └ LinearLayout       960 × 204   ← 推荐卡片
        └ LinearLayout    1104 × 552   ← 含标题，不能动
          └ DetailWithSourceFileView
```

实现方式：从「版本：」文本向上查找宽 900~1050、高 150~300 且非弹窗根的祖先，隐藏它并归零高度与上下 margin。布局系统随后会自行重算，标题位置与窗口尺寸恢复正常。

隐藏后需恢复 `binding.tvSaveApkTips` 可见。

---

## 更新日志

### v1.2

- 新增：拦截 APK 下载跳转应用商店，改用浏览器自带下载器
- 新增：屏蔽「添加全网热榜小部件」弹窗

### v1.0

首个版本。

## 已知限制

**搜索联想首次加载会闪一帧。**

广告卡片在页面 DOM 解析完成后才能按文本识别，而首次绘制早于此时。后续搜索因脚本常驻（`MutationObserver` + 定时轮询）不再闪烁。

彻底消除需在 HTML 响应层（`shouldInterceptRequest`）删除节点，但依赖服务端格式，维护成本较高，故未采用。

**应用商店弹窗位置在屏幕底部。**

这是应用商店的原始设计（底部卡片式）。窗口位置由主题控制，运行时修改 `Window` 属性无效。

---

## 许可

仅供学习与个人使用。
