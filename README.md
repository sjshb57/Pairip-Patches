<div align="center">

# 🧩 Pairip Patches

**一套用于还原被加固 / 抽离应用的 [Morphe](https://morphe.software) 补丁集合**

[![Release](https://img.shields.io/github/v/release/sjshb57/pairip-patches?style=for-the-badge&logo=github&color=8B5CF6)](https://github.com/sjshb57/pairip-patches/releases/latest)
[![Morphe](https://img.shields.io/badge/Morphe-Patches-8B5CF6?style=for-the-badge)](https://morphe.software)
[![Stars](https://img.shields.io/github/stars/sjshb57/pairip-patches?style=for-the-badge&color=8B5CF6)](https://github.com/sjshb57/pairip-patches/stargazers)

</div>

---

## ✨ 简介

这是一套基于 **Morphe Patcher** 的纯字节码补丁,专注于**还原被加固或代码抽离的 Android 应用**。

所有补丁在打包阶段直接操作 DEX 字节码,**无需 root、无需 Termux、无需运行任何脚本**——只要把补丁源加到 Morphe Manager,选好 APK 一键打包即可。

> [!NOTE]
> 这些补丁基于对开源还原工具逻辑的移植,目标是在 Morphe 生态里复刻"应用内自足"的还原流程。

---

## 🚀 一键添加到 Morphe

<div align="center">

### 👉 [点此添加到 Morphe Manager](https://morphe.software/add-source?github=sjshb57/pairip-patches) 👈

</div>

> 在手机浏览器中打开上面的链接,会自动唤起 Morphe Manager 并添加本补丁源。

**或者手动添加**:打开 Morphe Manager → 左下角文件夹图标 → `Patch Sources` 旁的 **+** → 选择 **Remote** 标签 → 粘贴下面的地址:

```
https://github.com/sjshb57/pairip-patches
```

---

## 🧩 包含的补丁

<!-- PATCHES_START EXPANDED -->
> **[v1.6.0](https://github.com/sjshb57/Pairip-Patches/releases/tag/v1.6.0)**&nbsp;&nbsp;•&nbsp;&nbsp;`main`&nbsp;&nbsp;•&nbsp;&nbsp;3 patches total
<details open>
<summary>🌐 Universal&nbsp;&nbsp;•&nbsp;&nbsp;3 patches</summary>
<br>

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Remove pairip protection](#remove-pairip-protection) | Restores obfuscated strings and removes pairip bytecode protection. |  |
| [Restore extracted methods](#restore-extracted-methods) | Inlines methods hidden in $c<number> helper classes back into the host class, then removes those helper and reflection method-holder classes. |  |
| [Strip debug info](#strip-debug-info) | Removes .line (line numbers) and .source (source file name) debug data from every class. Optional, disabled by default. |  |

</details>

<!-- PATCHES_END -->

### 📌 补丁原理详解

<details>
<summary><b>🔓 Remove pairip protection</b> — 还原 pairip 字符串混淆与 VMRunner 保护</summary>

<br>

针对使用 **pairip** 加固的应用,完成以下还原工作:

| 步骤 | 作用 |
| :--- | :--- |
| **字符串还原** | 从 `Application` 类(或 `appkiller` / `ObjectLogger` 风格)提取被混淆的字符串映射,把使用方的 `sget-object` 还原成 `const-string`,并清除配套的垃圾 `const/4` 指令 |
| **VMRunner 清空** | 把所有调用 `VMRunner` 的方法体替换为按返回类型的最小返回 |
| **残留清理** | 删除引用 `Lcom/pairip/` 的 invoke / 字段访问指令,删除被清空后只剩 `return-void` 的空 `<clinit>` |
| **类清除** | 真正删除 `com/pairip/` 类与字符串占位类 |

</details>

<details>
<summary><b>🧬 Restore extracted methods</b> — 还原被抽离到辅助类的方法</summary>

<br>

针对把方法体抽离到 `<主类>$c<数字>` 辅助类、主类仅保留反射桩的加固方式:

- **三重特征识别**抽离类:类名形如 `$c<数字>` + 含 `static` 方法 + 该方法首个参数为主类类型(充当 `this`),避免误伤 `$c0` 这类正常的 Kotlin 内部类
- 将抽离方法体**原样搬回**主类对应方法,清除原本的 `Method.invoke` 反射桩
- 保留原方法的注解(如 `@Nullable`)
- 还原完成后**删除已处理的辅助类**

</details>

---

## 📖 使用方法

1. 按上方 [一键添加](#-一键添加到-morphe) 把补丁源加入 Morphe Manager
2. 在 Manager 中选择你要修补的 **APK** 文件
3. 在补丁列表中勾选需要的补丁
4. 点击 **Patch** 开始打包,完成后安装即可

---

## ⚠️ 注意事项

> [!WARNING]
> - 这些补丁会强制启用 **FULL 编译模式**(完整重写 DEX),以确保被还原的类能被真正删除。代价是**打包更慢、更耗内存**,低内存设备打包大型应用时可能失败。
> - FULL 模式存在已知边界情况(desugared `j$` 类),个别应用可能打包失败。若遇到此类问题,请提 Issue。
> - 还原属于逆向性质的操作,**仅供学习研究**,请在合法合规的前提下使用。

---

## 🛠️ 自行构建

本仓库基于 [morphe-patches-template](https://github.com/MorpheApp/morphe-patches-template),使用 `semantic-release` 自动发版。

提交信息遵循 [Conventional Commits](https://www.conventionalcommits.org/):

| 前缀 | 含义 | 版本变化 |
| :--- | :--- | :--- |
| `feat:` | 新功能 | minor |
| `fix:` | 修复 | patch |

推送到 `main` 后,GitHub Actions 会自动构建 `.mpp` 并发布到 [Releases](https://github.com/sjshb57/pairip-patches/releases)。

---

## 🙏 致谢

- [Morphe](https://morphe.software) — 补丁引擎与 Manager
- [ReVanced](https://revanced.app) — Morphe 的前身与基础

---

<div align="center">

**仅供学习与研究使用 · Made with ❤️ by sjshb57**

</div>
