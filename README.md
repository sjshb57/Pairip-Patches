<div align="center">

# 🧩 Pairip Patches

**一套用于还原被加固 / 抽离应用的 [Morphe](https://morphe.software) 补丁集合**

[![Release](https://img.shields.io/github/v/release/sjshb57/Pairip-Patches?style=for-the-badge&logo=github&color=8B5CF6)](https://github.com/sjshb57/Pairip-Patches/releases/latest)
[![Morphe](https://img.shields.io/badge/Morphe-Patches-8B5CF6?style=for-the-badge)](https://morphe.software)
[![Stars](https://img.shields.io/github/stars/sjshb57/Pairip-Patches?style=for-the-badge&color=8B5CF6)](https://github.com/sjshb57/Pairip-Patches/stargazers)

</div>

---

## ✨ 简介

这是一套基于 **Morphe Patcher** 的纯字节码补丁,专注于**还原被 pairip 加固或代码抽离的 Android 应用**。

所有补丁在打包阶段直接操作 DEX 字节码,**无需 root、无需 Termux、无需运行任何脚本**——只要把补丁源加到 Morphe Manager,选好 APK 一键打包即可。

> [!NOTE]
> 这些补丁基于对开源还原工具逻辑的移植,目标是在 Morphe 生态里复刻“应用内自足”的还原流程。

---

## 🚀 一键添加到 Morphe

<div align="center">

### 👉 [点此添加到 Morphe Manager](https://morphe.software/add-source?github=sjshb57/Pairip-Patches) 👈

</div>

> 在手机浏览器中打开上面的链接,会自动唤起 Morphe Manager 并添加本补丁源。

**或者手动添加**:打开 Morphe Manager → 左下角文件夹图标 → `Patch Sources` 旁的 **+** → 选择 **Remote** 标签 → 粘贴下面的地址:

```
https://github.com/sjshb57/Pairip-Patches
```

---

## 🧩 包含的补丁

<!-- PATCHES_START EXPANDED -->
> **[v1.10.1](https://github.com/sjshb57/Pairip-Patches/releases/tag/v1.10.1)**&nbsp;&nbsp;•&nbsp;&nbsp;`main`&nbsp;&nbsp;•&nbsp;&nbsp;4 patches total
<details open>
<summary>🌐 Universal&nbsp;&nbsp;•&nbsp;&nbsp;4 patches</summary>
<br>

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Inline pairip call wrappers](#inline-pairip-call-wrappers) | Inlines pairip's static call-wrapper stubs ($<number>) back into their call sites and removes the stubs. |  |
| [Remove pairip protection](#remove-pairip-protection) | Restores obfuscated strings and removes pairip bytecode protection. |  |
| [Restore extracted methods](#restore-extracted-methods) | Inlines methods hidden in $c<number> helper classes back into the host class, then removes those helper and reflection method-holder classes. |  |
| [Strip debug info](#strip-debug-info) | Removes .line (line numbers) and .source (source file name) debug data from every class. Optional, disabled by default. |  |

</details>

<!-- PATCHES_END -->

> [!TIP]
> 默认情况下,**前三个还原补丁已勾选**(开箱即用),`Strip debug info` 为可选项需手动勾选。

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
| **常量类删除** | 删除 pairip 注入的整数常量容器类(`super` 为 `Object`、无方法、无实例字段、仅 `static final int` 字段且全 app 零引用) |

</details>

<details>
<summary><b>🧬 Restore extracted methods</b> — 还原被抽离到辅助类的方法</summary>

<br>

针对把方法体抽离到 `<主类>$c<数字>` 辅助类、主类仅保留反射桩的加固方式:

- **识别**:类名形如 `<主类>$c<数字>`,且主类存在对应的反射桩(方法体含 `Method.invoke`)才还原——类名负责“不漏”,桩验证负责“不误伤”,正常的 Kotlin 内部类没有桩会自动跳过
- 将抽离方法体**原样搬回**主类对应方法,替换掉原本的 `Method.invoke` 反射桩,并保留原方法注解(如 `@Nullable`)
- 还原完成后**删除已处理的 `$c` 辅助类**
- **删除反射 `Method` 占位类**(带残留引用检查:仍被 `sget-object` 引用的保留,避免悬空)

</details>

<details>
<summary><b>🔗 Inline pairip call wrappers</b> — 内联调用转发桩</summary>

<br>

针对 pairip 把普通调用(`invoke-virtual` / `invoke-static` 等)包装成 `static synthetic` 转发桩、调用点改成 `invoke-static` 调桩的保护方式:

- **识别**:从已还原的方法体出发,扫调用点的 `invoke-static` 目标——名字带 `$<数字>` 后缀、定义在本 app、且桩体是纯转发结构(唯一一条真实 `invoke` + 可选 `move-result` + `return`)三者皆满足才认定为桩
- **内联**:把调用点的 `invoke-static {寄存器}, 桩` 还原成桩里那条真实调用 `invoke-XXX {同一寄存器}, 真实目标`,**寄存器完全不动**
- 全部调用点替换完后**删除桩方法**
- **限定范围**:仅处理上一补丁还原过的主类(依赖 `Restore extracted methods`),正常类一律不碰

</details>

<details>
<summary><b>🧹 Strip debug info</b> — 清除调试信息(可选)</summary>

<br>

清除所有类的调试元数据,默认**不启用**:

- **`.source`**:清除每个类的源文件名(`setSourceFile(null)`)
- **`.line`**:就地清除每条指令所在位置的 `debugItems`(行号、局部变量等),不重建指令,不影响跳转 / switch / try
- 适合在还原完成后进一步精简产物体积、去除调试痕迹

> [!NOTE]
> 该补丁会遍历并修改几乎所有类,**打包耗时会明显增加**;若仅需还原功能,保持默认不勾选即可。

</details>

---

## 📖 使用方法

1. 按上方 [一键添加](#-一键添加到-morphe) 把补丁源加入 Morphe Manager
2. 在 Manager 中选择你要修补的 **APK** 文件
3. 在补丁列表中勾选需要的补丁(三个还原补丁默认已勾选)
4. 点击 **Patch** 开始打包,完成后安装即可

---

## ⚠️ 注意事项

> [!WARNING]
> - 还原补丁(`Remove pairip protection` / `Restore extracted methods` / `Inline pairip call wrappers`)需要真正删除类,会强制启用 **FULL 编译模式**(完整重写 DEX),代价是**打包更慢、更耗内存**,低内存设备打包大型应用时可能失败。
> - FULL 模式按线程分段编译,产物可能是**多个 DEX**,属正常现象,不影响运行(Android 原生支持 multidex)。
> - 还原属于逆向性质的操作,**仅供学习研究**,请在合法合规的前提下使用。

---

## 🛠️ 自行构建

本仓库基于 [morphe-patches-template](https://github.com/MorpheApp/morphe-patches-template),使用 `semantic-release` 自动发版。

提交信息遵循 [Conventional Commits](https://www.conventionalcommits.org/),版本变化规则如下:

| 前缀 | 含义 | 版本变化 |
| :--- | :--- | :--- |
| `release:` | 常规发布 | patch&nbsp;(+0.0.1) |
| `feat:` | 较大新功能 | minor&nbsp;(+0.1.0) |
| `fix:` / `perf:` / `bump:` | 修复 / 优化 / 更新支持 | patch&nbsp;(+0.0.1) |

本地构建:

```bash
./gradlew :patches:buildAndroid
```

产物 `.mpp` 位于 `patches/build/libs/`。推送到 `main` 后,GitHub Actions 会自动构建并发布到 [Releases](https://github.com/sjshb57/Pairip-Patches/releases)。

---

## 🙏 致谢

- [Morphe](https://morphe.software) — 补丁引擎与 Manager
- [ReVanced](https://revanced.app) — Morphe 的前身与基础

---

<div align="center">

**仅供学习与研究使用 · Made with ❤️ by sjshb57**

</div>
