# 最近任务隐藏（LSPosed Modern API 102 模块）

参考：
- hideRecent / RocGwei/hideTask（system 侧 `RecentTasks.isVisibleRecentTask` 隐藏任务）
- suqi8/OShin（ColorOS 桌面侧 hook 结构）

功能：自定义最近任务界面中哪些应用显示/隐藏。

## 结构
```
app/
├─ src/main/java/com/top/hiderecent/
│  ├─ Main.kt                # 模块入口（XposedModule），分发 system / launcher 两条 hook；UI 配置读取与缓存
│  ├─ SystemRecentHook.kt    # system_server：hook RecentTasks.isVisibleRecentTask
│  ├─ LauncherRecentHook.kt  # 桌面进程：hook OplusRecentTasksFilter.filterTask（ColorOS 真正生效的过滤点）
│  ├─ PrefsProvider.kt       # ContentProvider（仅模块进程内部）
│  └─ ui/                    # 模块自身的界面（应用列表 / 勾选 / 竖点菜单）
├─ src/main/resources/META-INF/xposed/
│  ├─ java_init.list          # 入口类全名
│  ├─ module.prop            # minApiVersion=102
│  └─ scope.list             # 注入目标包
```

## 编译
Android Studio 打开（需 JDK 17+）。AGP 8.7.2 / Kotlin 2.0.21，无其他依赖。

仓库**不带 gradle wrapper**，直接用本机 Gradle 构建（Windows / PowerShell）：

```powershell
$env:JAVA_HOME = "D:\DevTools\Java\jdk-17"
D:\DevTools\Gradle\gradle-8.9\bin\gradle.bat assembleRelease
```

产出 `app/build/outputs/apk/release/app-release.apk`。

## 安装与启用
1. 安装 APK。
2. LSPosed 管理器 → 启用模块。
3. 作用域勾选：`系统框架 (system)` + 你的桌面 `com.oplus.quickstep`（或 `com.android.launcher`）。
4. 重启设备。

## 使用
- 模块 UI：勾选应用即时生效并写入模块 SharedPreferences，无需重启。
- 竖点菜单里还有「隐藏自身」：勾上以后，「最近任务隐藏」自己也不会出现在最近任务里；
  该勾选态与列表里「最近任务隐藏」自身的条目是同一份状态，两边双向同步。
- 写完之后模块会广播一次完整名单给桌面进程，所以**即使模块进程随后被划掉，隐藏依然生效**（见下节）。

## 为什么杀掉模块进程后隐藏仍然生效

这是本模块最容易踩的坑，值得单独说明。

**问题**：隐藏名单存在模块自己的 `SharedPreferences` 里，而桌面进程（`com.oplus.quickstep`）
是另一个 UID。早期实现只靠「每次过滤时查一次 ContentProvider」这一条路读名单——
而那个 provider 就跑在模块自己的进程里。模块进程一旦被最近任务划掉（ColorOS 上往往等于
force-stop），provider 查不到，hook 侧就把「读不到」错当成了「名单是空的」，
于是被隐藏的应用全部重新冒出来。

**现在的做法：hook 热路径只读内存快照 + 非热路径最佳努力刷新。**

读取通道按优先级依次尝试（`Main.readHiddenPackages()`，仅用于非高频刷新）：

| 级别 | 通道 | 依赖模块进程？ | 说明 |
|---|---|---|---|
| 1 | ContentProvider（`com.top.hiderecent.prefs`） | 是 | 仅模块内部；hook 进程不再依赖 |
| 2 | libxposed remote preferences | 否 | 由 LSPosed 框架服务提供，不依赖模块进程 |
| 3 | 直读模块 data 目录的 `shared_prefs/*.xml` | 否 | 跨 UID 通常无权限，仅作兜底 |
| 4 | 本进程落盘缓存（`files/hidden_cache.txt`） | 否 | 上面全部无应答时使用（典型场景：模块进程被划掉） |

关键规则两条：

- **失败绝不覆盖缓存。** 只有某个通道*成功应答*才会被采信（包括合法的空名单——用户真的全部取消勾选）。
  全部失败时沿用上一次的名单，而不是退化成空集。
- **写入即推送。** 模块 UI 每次改动都会广播 `com.top.hiderecent.PREFS_CHANGED`，
  extra `hide_list` 带完整名单。桌面进程里的动态接收器收到后原子替换内存快照，`filterTask` 热路径不再做同步 IPC/文件读写。

- system_server 侧 `isVisibleRecentTask` 同样只读进程内快照；快照刷新在独立后台线程最佳努力执行。刷新失败或 ROM 反射失败时一律回退原方法 `chain.proceed()`（默认显示任务）。

### 想更稳的话（可选）
- 在系统设置里给「最近任务隐藏」开「允许自启动」、关掉电池优化、在最近任务里上锁。
- 这些只是让 provider 通道少断，上面四级 + 推送已经能兜住。

## Hook 点说明

### 桌面侧（ColorOS 上真正生效的）
- 目标：`com.oplus.quickstep.data.OplusRecentTasksFilter.filterTask(GroupTask): boolean`
- 语义：**返回 `true` = 隐藏，`false` = 显示**
- 调用方：`com.android.quickstep.OplusRecentTasksListImpl.loadTasksInBackground()`
  → `if (getFilter().filterTask(map)) { map = null; }`
- 包名从 `GroupTask.task1` 字段 + `Task.getPackageName()` 取。
- 注意：AOSP 那条 `RecentTasks.isVisibleRecentTask` 在 ColorOS 上其实是死代码。

### system 侧（AOSP / 其他 ROM）
- `com.android.server.wm.RecentTasks.isVisibleRecentTask(Task)`（单参，AOSP）
- `com.android.server.wm.RecentTasks.isVisibleRecentTask(Task, boolean)`（双参，Vivo/OPPO 部分 ROM）
  → 返回 `false` 即从最近任务隐藏该任务。
- 兜底取包名：`Task.getBaseIntent()` / 字段 `mBaseIntent`。

### 换 ROM 时怎么找目标类
1. 打开最近任务界面，用 adb 看当前前台是谁：
   ```bat
   adb shell dumpsys window | findstr /i "mCurrentFocus"
   adb shell dumpsys activity top | findstr /i "ACTIVITY"
   adb shell dumpsys activity recents
   ```
   输出形如 `mCurrentFocus=Window{... u0 com.oplus.quickstep/com.oplus.quickstep.recents.RecentsActivity}`。
2. 用 jadx 反编译该 ROM 的 `services.jar` / 桌面 APK，搜 `isVisibleRecentTask`、
   `filterTask`、`loadTasksInBackground` 等，找到真正的过滤点。
3. 替换 `SystemRecentHook` / `LauncherRecentHook` 里的目标方法即可；
   日志 tag 为 `HideRecentTiles`，`LauncherRecentHook` 前 20 次调用会打印 `filterTask#n pkg=...`，
   可用来确认目标方法确实被调到。

## 注意
- 现代 API 不需要 `assets/xposed_init`，只写 `java_init.list`。
- 混淆/开 R8：保留 `app/proguard-rules.pro` 中 XposedModule 条目，勿删。
- 排查隐藏不生效时，先看 logcat：
  ```bat
  adb logcat -s HideRecentTiles
  ```
  正常会看到名单来源与条数（`hidden(n)=...`、`prefs pushed -> hidden(n)`）；
  若一直打印「全部通道失败」，说明模块进程被冻结且推送也没收到，
  按上面「想更稳的话」处理。
- 部分 ROM 会缓存任务列表，改完名单后如果旧的卡片还在，
  杀一次 launcher 进程重试：`adb shell am force-stop com.oplus.quickstep`。
- 如果出现开机循环崩溃/黑屏（ROM hook 点变化），先在 LSPosed 里禁用本模块或临时取消 system/launcher 作用域，再重启后更新版本。
