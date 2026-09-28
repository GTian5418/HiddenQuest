# 最近任务隐藏 / HideRecent

LSPosed 模块（Modern API 102），自定义最近任务界面中哪些应用显示/隐藏。  
ColorOS 桌面侧 + AOSP system\_server 侧双 hook。

-   仓库：[https://github.com/GTian5418/HideRecent](https://github.com/GTian5418/HideRecent)
-   包名：`top.gtian.hiderecent`
-   minSdk 29 / targetSdk 34 / compileSdk 35
-   AGP 8.7.2 + Kotlin 2.0.21 + JDK 17

参考：hideRecent / RocGwei/hideTask、suqi8/OShin。

## 结构

```
app/src/main/java/top/gtian/hiderecent/
├─ Main.kt                  # 模块入口，分发 system / launcher 两条 hook
├─ SystemRecentHook.kt      # system_server：hook RecentTasks.isVisibleRecentTask
├─ LauncherRecentHook.kt    # 桌面：hook OplusRecentTasksFilter.filterTask（ColorOS 真正生效点）
├─ PrefsBridge.kt           # 多通道读取隐藏名单（ContentProvider / remote prefs / 缓存）
├─ HiddenPackagesSnapshot.kt# 进程内名单快照（热路径只读内存）
├─ HookDecision.kt          # hook 判定逻辑
├─ PrefsProvider.kt         # ContentProvider（仅模块进程内部）
└─ ui/                      # 模块界面（应用列表 / 勾选 / 竖点菜单）
app/src/main/resources/META-INF/xposed/
├─ java_init.list           # 入口类全名
├─ module.prop              # minApiVersion=102
└─ scope.list               # 注入目标包（android + system）

```

## 编译

需要 **JDK 17 + Android SDK + Gradle 8.9**。仓库不带 gradlew，用本机 Gradle：

```powershell
$env:JAVA_HOME = "D:\DevTools\Java\jdk-17"
gradle assembleRelease

```

产物 `app/build/outputs/apk/release/app-release.apk`。

> Gradle 在 minSdk 29 下只输出 v3 签名。要 v1+v2+v3 全签名，用 zipalign + apksigner 一步签名（见 `BUILD.md` 第 4 章）。

完整环境搭建、签名、CI/CD 详见 **[BUILD.md](BUILD.md)**。

## 安装与启用

1.  安装 APK。
2.  LSPosed 管理器 → 启用模块。
3.  作用域勾选：`系统框架 (system)` + 你的桌面 `com.oplus.quickstep`（或 `com.android.launcher`）。
4.  重启设备。

## 使用

-   模块 UI 里勾选应用即时生效，无需重启。
-   竖点菜单「隐藏自身」：勾上后本模块也不出现在最近任务里。
-   **杀掉模块进程后隐藏仍生效**：名单在桌面进程里有内存快照，模块 UI 每次改动会广播完整名单给桌面进程原子替换。即使模块进程被划掉（ColorOS 上等于 force-stop），`filterTask` 热路径仍读快照，不会退化成空集。
-   想更稳：给本模块开「允许自启动」、关电池优化、在最近任务里上锁。

## 注意

-   排查隐藏不生效：`adb logcat -s HideRecentTiles`，正常会看到 `hidden(n)=...`。
-   部分 ROM 缓存任务列表，改完名单若旧卡片还在：`adb shell am force-stop com.oplus.quickstep`。
-   开机循环崩溃：先在 LSPosed 禁用本模块或取消作用域，重启后更新版本。