# 编译教程：从零部署环境并自行编译「隐藏任务 / HideRecent」

本文档面向想自己动手编译本模块的人。按顺序做完 **第 1~3 章** 就能出包，
**第 4 章** 是签名，**第 5 章** 是常见报错。

- 项目仓库：https://github.com/GTian5418/HiddenQuest
- 包名：`com.oshin.hiderecent`
- 构建工具：Gradle + AGP 8.7.2 + Kotlin 2.0.21
- 最低/目标 SDK：`minSdk 29` / `targetSdk 34`（`compileSdk 35`）

---

## 0. 一句话总览

需要三样东西：**JDK 17** + **Android SDK** + **Gradle（8.7 以上，推荐 8.9）**。
三者装好、配好环境变量，再补一个签名配置，执行 `gradle assembleRelease` 即可。

---

## 1. 安装 JDK 17

AGP 8.7 要求 **JDK 17 或更高**，用 8 / 11 会直接报错。

### 1.1 下载安装
- 推荐 **Microsoft Build of OpenJDK 17** 或 **Temurin (Adoptium) JDK 17**
- 安装到无空格、无中文的路径，例如：
  ```
  D:\DevTools\Java\jdk-17
  ```

### 1.2 配置环境变量（Windows）
`此电脑 → 属性 → 高级系统设置 → 环境变量`，新建：

| 变量名 | 值（示例，改成你自己的路径） |
| --- | --- |
| `JAVA_HOME` | `D:\DevTools\Java\jdk-17` |
| `Path` | 追加 `%JAVA_HOME%\bin` |

### 1.3 验证
打开 **新的** PowerShell / CMD 窗口：
```powershell
java -version
```
应输出 `openjdk version "17.x.x"`。若提示找不到命令，说明 `Path` 没生效，重开终端或重新登录。

---

## 2. 安装 Android SDK

本模块只需要 SDK，**不强制装 Android Studio**（装了更省事）。

### 方案 A：只装命令行工具（体积小）
1. 下载 **Android SDK Command-line Tools**：
   https://developer.android.com/studio#command-tools
2. 解压到例如 `D:\DevTools\Android\Sdk\cmdline-tools\latest\`
3. 用 `sdkmanager` 装必需组件：
   ```powershell
   $env:ANDROID_HOME = "D:\DevTools\Android\Sdk"
   & "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" `
       "platform-tools" `
       "platforms;android-35" `
       "build-tools;35.0.0"
   ```
4. 接受许可：
   ```powershell
   & "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" --licenses
   ```
   全部输入 `y`。

### 方案 B：装 Android Studio
安装后打开一次 `SDK Manager`，勾选：
- **Android SDK Platform 35**
- **Android SDK Build-Tools 35.0.0**（34.0.0 亦可）
- **Android SDK Platform-Tools**

SDK 默认在 `C:\Users\<你>\AppData\Local\Android\Sdk`。

### 2.1 配置环境变量

| 变量名 | 值 |
| --- | --- |
| `ANDROID_HOME` | `D:\DevTools\Android\Sdk` |
| `ANDROID_SDK_ROOT` | 同上（老工具兼容用，可留空） |
| `Path` | 追加 `%ANDROID_HOME%\platform-tools` |

验证：
```powershell
adb version
```

### 2.2 告诉项目 SDK 在哪（关键）

Gradle 不认 `ANDROID_HOME` 就靠 `local.properties`。在**项目根目录**新建
`local.properties`（该文件已在 `.gitignore` 里，不会入库）：

```properties
sdk.dir=D:\\DevTools\\Android\\Sdk
```

> ⚠️ Windows 路径的反斜杠要写成双写 `\\`。
> 注意：这是 `local.properties`，**不要**和签名用的 `keystore.properties` 搞混。

---

## 3. 安装 Gradle 并编译

本项目 **没有自带 `gradlew` 包装器**，所以有两种做法。

### 方案 A：用本机已装的 Gradle（推荐，最简单）
1. 下载 Gradle **8.9**（AGP 8.7.2 兼容 8.7~8.9）：
   https://gradle.org/releases/
2. 解压到 `D:\DevTools\Gradle\gradle-8.9`
3. 把 `D:\DevTools\Gradle\gradle-8.9\bin` 加入 `Path`，验证：
   ```powershell
   gradle -v
   ```

### 方案 B：为项目生成包装器（之后别人 clone 直接用 `gradlew`）
在项目根目录执行一次（需已装好任意 Gradle 8.x）：
```powershell
gradle wrapper --gradle-version 8.9
```
会生成 `gradlew`、`gradlew.bat`、`gradle/wrapper/`，之后统一用 `.\gradlew.bat` 即可。

### 3.1 开始编译

先切到项目根目录（含 `settings.gradle.kts` 的那层），然后：

```powershell
# 建议显式指定 JDK，避免机器上多版本 JDK 打架
$env:JAVA_HOME = "D:\DevTools\Java\jdk-17"

# 方案 A（本机 Gradle）
gradle assembleRelease --no-daemon

# 方案 B（有包装器时）
.\gradlew.bat assembleRelease
```

- 首次编译会联网下载 AGP / Kotlin / AndroidX 依赖，**较慢属正常**。
- 项目已配置腾讯云 Maven 镜像，国内网络一般不用挂代理。

### 3.2 产物位置

```
app/build/outputs/apk/release/app-release.apk
```

### 3.3 只想快速验证能否编译
```powershell
gradle assembleDebug      # 调试包，不签名、不混淆，最快
```

### 3.4 清理重建（编译缓存出错时）
```powershell
gradle clean
Remove-Item -Recurse -Force .gradle, app\build -ErrorAction SilentlyContinue
```

---

## 4. 配置签名（出正式 release 包必做）

`app/build.gradle.kts` 会读取根目录的 `keystore.properties`。
**没有这个文件时，`assembleRelease` 打出的包是未签名的**，装不上或无法覆盖安装。

### 4.1 生成自己的 keystore
```powershell
& "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -v `
  -keystore keystore\my-release.jks `
  -alias mykey `
  -keyalg RSA -keysize 2048 -validity 10000 `
  -storetype PKCS12
```
按提示设置两次密码、填写 CN 等信息。

> 🔑 **务必记住**：keystore 文件、alias、两个密码。
> 以后升级 App 必须用**同一个** keystore，否则无法覆盖安装，只能卸载重装。

### 4.2 创建 `keystore.properties`
在**项目根目录**新建（已在 `.gitignore` 中，不会入库）：

```properties
storeFile=keystore/my-release.jks
storePassword=你的store密码
keyAlias=mykey
keyPassword=你的key密码
```

- `storeFile` 用**相对项目根目录**的路径。
- `keyPassword` 与 `storePassword` 相同即可（PKCS12 通常一致）。

### 4.3 重新编译
```powershell
gradle assembleRelease
```

### 4.4 验证签名
```powershell
& "$env:ANDROID_HOME\build-tools\35.0.0\apksigner.bat" verify --print-certs `
  app\build\outputs\apk\release\app-release.apk
```
能打印出 `Signer #1 certificate ...` 就说明签名成功。

查看包信息：
```powershell
& "$env:ANDROID_HOME\build-tools\35.0.0\aapt2.exe" dump badging `
  app\build\outputs\apk\release\app-release.apk
```

---

## 5. 安装到设备

```powershell
adb devices                                   # 确认设备已连接（需开 USB 调试）
adb install -r app\build\outputs\apk\release\app-release.apk
```
`-r` 表示覆盖安装（签名一致时才能成功）。

装完后在 **LSPosed / 现代 Xposed 管理器**里启用模块，并勾选作用域
（系统框架 + 你的桌面），然后重启设备。详见 `README.md`。

---

## 6. 常见报错速查

| 报错 | 原因 / 解决 |
| --- | --- |
| `'gradlew' 不是内部或外部命令` | 项目没带包装器。用本机 `gradle`，或先跑 `gradle wrapper`（见 3.A/B）。 |
| `SDK location not found` | 缺 `local.properties` 或 `sdk.dir` 路径错。见 2.2。 |
| `Unsupported class file major version` / `requires JDK 17` | `JAVA_HOME` 指向了 JDK 8/11。改成 JDK 17 并重开终端。 |
| `Plugin [id: 'com.android.application', version: '8.7.2'] was not found` | 网络问题或镜像不可达。检查网络，或临时在 `settings.gradle.kts` 里保留 `google()`。 |
| `Found item String/loading more than one time` | `strings.xml` 里有重复的字符串名，删掉重复项。 |
| `Keystore file not found` | `keystore.properties` 里的 `storeFile` 路径不对（应为相对项目根目录）。 |
| 编译卡在下载依赖 | 首次编译正常。可加 `--info` 看进度；确认腾讯云镜像可访问。 |
| 改了隐藏列表不生效 | 部分 ROM 缓存了任务列表，`adb shell am force-stop com.oplus.quickstep` 后重试。 |

---

## 7. 环境自检清单

编译前逐条确认：

- [ ] `java -version` → 17.x
- [ ] `adb version` → 能输出版本号
- [ ] `gradle -v` → 8.7 ~ 8.9（或项目里有 `gradlew.bat`）
- [ ] 项目根目录存在 `local.properties`，且 `sdk.dir` 指向真实 SDK
- [ ] `$env:ANDROID_HOME\platforms\android-35` 目录存在
- [ ] `$env:ANDROID_HOME\build-tools\35.0.0` 目录存在
- [ ] 要出正式包：根目录存在 `keystore.properties` 且指向有效 keystore

全绿即可执行：
```powershell
gradle assembleRelease
```
