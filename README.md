# JavaInterview Android App

一个手机本地优先的 Java 面试训练 Android App，面向 Java 后端求职准备。

## 功能概览

- 面试题库：按项目面试、八股文、收藏、大类、小类筛选题目。
- 刷题训练：随机出题、查看答案、记录练习历史。
- 本地题库：内置结合个人简历项目生成的题目。
- JavaGuide 同步：从 `Snailclimb/JavaGuide` 拉取 Java/数据库/分布式/系统设计/AI 面试题 Markdown，并解析写入本地 Room 数据库。
- AI 配置：支持配置兼容 OpenAI 风格接口的模型服务，用于生成题目或评价回答。

## 本地构建

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

首次克隆后需要本机安装 Android SDK，并通过 `ANDROID_HOME` 或本地 `local.properties` 指向 SDK 路径。

Release 签名文件放在 `secrets/` 下，不进入 Git。没有签名文件时 debug 构建和单测可正常执行，只有真正构建 release 时会失败。
