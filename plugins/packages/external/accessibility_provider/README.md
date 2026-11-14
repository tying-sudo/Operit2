# 界面自动化服务

安装 Operit 界面自动化所需的配套无障碍服务包。

## 为什么需要单独的包

Android 的无障碍权限按应用授予，而且系统只在应用进程存活时绑定无障碍服务。
负责操作屏幕的应用因此需要一个自己的安装包。本插件携带这个包，
用户在包管理页安装它，再在系统无障碍设置里开启服务即可。

服务包只有约 650 KB，不含界面引擎。

## 组成

- `resources/provider.apk` — 配套无障碍服务包（`com.ai.assistance.operit.provider`）
- `src/ui/installer.ui.ts` — 安装向导页面
- `src/packages/accessibility_provider.ts` — 安装与检查工具

## 契约

服务包与 Operit 通过 AIDL 契约
`com.ai.assistance.operit.provider.IAccessibilityProvider` 通信，
两侧编译同一份接口定义，事务顺序不会漂移。
