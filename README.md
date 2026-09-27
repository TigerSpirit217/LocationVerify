# LocationVerify 位置真伪检测

基于**百度地图定位 SDK** 与**高德地图定位 SDK** 的 Android 位置检测工具。
依次运行两个 SDK 的单次定位，重点展示两家 SDK 对 **虚拟位置 / 模拟定位（Mock Location）** 的判定结果，用于定位数据真伪核查与 SDK 风险字段研究。

> 注意：本项目为检测/研究用途。检测结论由两家 SDK 的能力决定，**仅供参考**，不能作为绝对可靠的防作弊依据。

## 1. 功能与流程

```
首次打开 ──► 功能介绍 + 授权提示（隐私合规，请阅读后勾选同意）
                │ 同意
                ▼
        填写密钥（百度 AK + 高德 Key，至少其一，与包名/SHA1 绑定）
                │
                ▼
        主界面：百度 SDK 结果 与 高德 SDK 结果
                │ 点击「开始检测」
                ▼
        ① 先运行百度 SDK 单次定位（最长 15s，超时自动结束）
                │ 完成（成功/失败/超时）
                ▼
        ② 再运行高德 SDK 单次定位（最长 15s）
                │
                ▼
        两区分别展示返回值与虚拟位置风险结论
```

- 首次打开弹出「功能介绍与授权说明」：列明两个 SDK 采集的信息与用途，附双方隐私政策链接；未同意则退出应用。
- 允许只填一个 Key：仅当两个 Key 都未填写时，「开始检测」才会跳转到密钥配置页；只填其一时，未填写 Key 的 SDK 自动跳过（对应区域显示「已跳过」），不会初始化该 SDK。
- 检测日志自动写入应用内部存储（`/data/data/com.tiger.locationverify/files/logs/`，无需存储权限），主界面可查看、清空。

## 2. 虚拟位置检测原理（核心）

### 百度（BaiduMapSDK_Location_All）

| 返回字段 | 含义 |
| --- | --- |
| `BDLocation.getMockGnssProbability()` | 当前定位点的**作弊概率**：`MOCK_GNSS_PROBABILITY_HIGH` 高 / `MIDDLE` 中 / `LOW` 低 / `UNKNOW` 未知 / `ZERO` 零 |
| `BDLocation.getMockGnssStrategy()` | 命中的**防作弊策略**编号（非 0 即命中） |
| `BDLocation.getDisToRealLocation()` | **虚假位置与真实位置的距离**（米，仅检出虚拟点时 > 0） |
| `BDLocation.getReallLocation()` | SDK 推算的**真实位置**（虚拟定位时非空） |
| `BDLocation.getLocType()` / `getLocTypeDescription()` | 定位类型（61 GPS / 161 网络 / 65 离线 / 62、63、167 等失败类型） |

> 关键配置：`LocationClientOption.setEnableSimulateGnss(true)`。
> 该开关默认 `false`，SDK 会**过滤掉模拟 GNSS 结果**（无法检出）；检测工具必须开启为 `true`，让模拟结果返回并在上述字段中标记。

### 高德（com.amap.api:location）

| 返回字段 | 含义 |
| --- | --- |
| `AMapLocation.isMock()` | 本次结果**是否来自模拟定位**（需 `setMockEnable(true)` 开启后有效） |
| `AMapLocation.getErrorCode() == 15` | SDK 6.5.0+：当不允许模拟（`setMockEnable(false)`）时，**模拟定位直接失败**并返回错误码 15 |
| `AMapLocation.getTrustedLevel()` | 结果可信度：`TRUSTED_LEVEL_HIGH` 高（15 秒内实时 GPS）/ `NORMAL` 中（缓存/离线/最后位置）/ `LOW` 低（2~10 分钟）/ `BAD` 非常低（>10 分钟；**模拟定位结果也为该等级**） |
| `AMapLocation.getLocationType()` | 定位来源（官方「定位类型对照表」见下） |

#### 高德定位类型（locationType）对照表（官方，2025-04-18）

| 值 | 含义 | 说明 |
| --- | --- | --- |
| 0 | 定位失败 | 用 `getErrorCode()` 排查 |
| 1 | GPS 定位结果 | 卫星定位，精度约 10~100 米 |
| 2 | 前次定位结果 | 两次定位间隔 <1 秒或位移很小时返回 |
| 4 | 缓存定位结果 | 短时间内同位置的网络定位缓存 |
| 5 | **Wifi 定位结果** | 网络定位，精度约 5~200 米 |
| 6 | 基站定位结果 | 网络定位，精度约 500~5000 米 |
| 8 | 离线定位结果 | 基于离线定位库 |
| 9 | 最后位置缓存 | 上次定位缓存 |
| 11 | 模糊定位结果 | 用户仅授予「大致位置」权限 |
| 12 | 系统网络定位 | 高德网络定位失败时的兜底（6.4.5+） |

> `locationType` 由 SDK 根据**当前可用的定位依据**自动选择，不同环境下会变化（室外 GPS 可用→1；室内 Wi-Fi→5；仅基站→6；请求过快→2；命中缓存→4/9；大致位置权限→11 等），属正常现象。日志中 `type=5` 即「Wifi 定位结果」，与 `isMock=false` 组合表示：本次结果来自 Wi-Fi 网络定位、未检出模拟。

> 关键配置：`AMapLocationClientOption.setMockEnable(true)`。
> 本工具开启允许模拟，以获得带 `isMock` 标记的结果；同时兼容处理错误码 15（模拟被拦截）的情况。

### 风险结论映射

| 等级 | 百度 | 高德 |
| --- | --- | --- |
| 高风险 | 作弊概率 HIGH，或虚假点与真实点距离 > 0 | `isMock = true`，或错误码 15 |
| 中风险 | 作弊概率 MIDDLE | — |
| 低风险 | 作弊概率 LOW / ZERO | `isMock = false` 且定位成功 |
| 无法判断 | 概率未知或定位失败 | 定位失败（除 15 外） |

## 3. Key 申请（使用前必须）

两个 Key 都绑定 **包名 `com.tiger.locationverify` + 签名 SHA1**（至少填写一个即可使用，未填写的 SDK 自动跳过）。调试与发布用的签名不同，请分别配置。

获取签名 SHA1（Android 签名）：

```
若您自行构建了包体，请使用
keytool -v -list -keystore 您的keystore文件路径
```

```
若您是从release中下载的安装包，请在该release的发布文本中获取
```

### 百度 AK

1. 打开 [百度地图开放平台控制台](https://lbsyun.baidu.com/apiconsole/key)，创建应用；
2. 应用类型选 **Android SDK**，填写发布版 SHA1 与开发版 SHA1（建议都填上调试 SHA1，便于真机调试）+ 包名 `com.tiger.locationverify`；
3. 生成后复制 AK。

### 高德 Key

1. 打开 [高德开放平台控制台](https://console.amap.com/dev/key/app)，创建应用；
2. 添加 **Android 平台** Key：填写包名 `com.tiger.locationverify` 与 SHA1；
3. 生成后复制 Key。

### 在应用内填写

打开应用 → 右上角「配置密钥」→ 粘贴（至少其一）→ 保存。
保存后下次点击「开始检测」即生效（检测器在每次启动时通过代码注入 Key，见下）。

### Key 是如何生效的？

两家 SDK 官方都支持**运行时设置 Key**，本应用即采用该方式（无需改代码重新打包）：

- 百度：`LocationClient.setKey(ak)`（每次检测前调用，优先于 Manifest meta-data）；
- 高德：`AMapLocationClient.setApiKey(key)`（必须在实例化 `AMapLocationClient` 之前调用，每次检测前调用）。

`AndroidManifest.xml` 中的两个 `meta-data` 以 `${BAIDU_AK}` / `${AMAP_API_KEY}` 占位（默认空串），也可在 `app/build.gradle.kts` 的 `manifestPlaceholders` 里改为硬编码。

## 4. 软件使用

安装后：首次打开 → 勾选同意授权 → 填写 Key（至少其一）→ 授予定位权限 → 「开始检测」。

真机注意事项：
- 需要联网，建议开启 Wi-Fi（网络定位依据）与 GNSS（如需要卫星定位结果）；
- 授予**精确位置**权限（Android 12+ 注意不要选「大致位置」，否则部分字段不可用）；
- 如需验证“虚拟定位检测”，可用开发者选项中的「模拟位置信息应用」配合模拟定位软件对比两个 SDK 的返回差异。

## 5. 工程结构

```
LocationVerify/
├── build.gradle.kts / settings.gradle.kts / gradle.properties   # Gradle 配置（含百度官方 Maven 仓库）
└── app/
    ├── build.gradle.kts            # 依赖：百度定位 9.7.0、高德定位 11.3.000
    ├── proguard-rules.pro          # 两个 SDK 的混淆 keep 规则
    └── src/main/
        ├── AndroidManifest.xml     # 权限 + 两个 SDK 必需的 service + Key 占位 meta-data
        ├── java/com/tiger/locationverify/
        │   ├── App.kt                          # 隐私合规开关初始化
        │   ├── MainActivity.kt                 # 双区结果主界面、顺序检测编排、权限
        │   ├── consent/ConsentActivity.kt      # 首次打开：功能介绍 + 授权
        │   ├── keys/KeyConfigActivity.kt       # 用户自行填写 Key
        │   ├── data/Prefs.kt                   # SharedPreferences 持久化
        │   ├── util/LogSaver.kt                # 本地日志（应用内部存储）
        │   └── location/
        │       ├── CheckReport.kt              # 报告模型 + 风险等级
        │       ├── BaiduLocationChecker.kt     # 百度 SDK 检测器
        │       └── AmapLocationChecker.kt      # 高德 SDK 检测器
        └── res/                                 # 布局、字符串、颜色、主题、图标
```

## 6. 隐私合规

- 两个 SDK 均要求在使用前按用户同意结果设置合规开关：
  - 百度：`LocationClient.setAgreePrivacy(boolean)`（实例化客户端之前）；
  - 高德：`AMapLocationClient.updatePrivacyShow(context, true, true)` + `updatePrivacyAgree(context, hasAgree, true)`（调用 SDK 任何接口之前）。
- 首次启动引导页收集用户的同意决定并持久化，`App.onCreate` 与同意动作发生时会即时同步给两个 SDK；未同意时无法进入主界面、也不会创建任何定位客户端。
- 隐私政策：
  - 百度：https://lbsyun.baidu.com/index.php?title=openprivacy
  - 高德：https://lbs.amap.com/pages/privacy/

## 7. 常见问题

| 现象 | 排查                                                                                   |
| --- |--------------------------------------------------------------------------------------|
| 百度返回类型 167 / 鉴权失败 | AK 错误或未绑定正确的包名 + SHA1；确认应用内填写的 AK 与控制台一致                                             |
| 高德错误码 7 | Key 校验失败：检查 Key 与包名/SHA1 绑定                                                          |
| 高德错误码 10 | Manifest 缺少 `<service android:name="com.amap.api.location.APSService"/>`（若您修改了包体请检查） |
| 高德错误码 12 | 未授予定位权限                                                                              |
| 高德错误码 15 | SDK 判定结果为模拟位置（若未开启 setMockEnable 就会走此路径，属“检出”信号）                                     |
| 高德日志 type=5 | Wifi 定位结果（网络定位），正常现象；type 随环境（GPS/Wi-Fi/基站/权限）自动变化，见「定位类型对照表」                        |
| 两个 SDK 都超时 | 检查网络、定位开关、WI-Fi 是否可用；室内且无网络信号时可能无法定位                                                 |
| 百度升级 SDK 后无法定位 | 新版可能变更 service 组件名（本工程使用官方要求的 `com.baidu.location.f`），以新版官方文档为准                      |

## 8. 局限性说明

- 检测能力完全取决于 SDK 版本与厂商策略：本工程基于百度 9.7.0 / 高德 11.3.000 的公开接口编写，升级 SDK 时接口可能变化（如百度 mock 相关字段为 9.x 新增）。
- 部分模拟定位方案（如系统级虚拟化、深度 Hook）可能绕过以上字段，检测结果不代表绝对可靠。
- 百度在网络定位（非 GNSS）场景下不提供作弊概率字段，此时提示“无法判断”属正常。
- 本仓库未内置 `gradle/wrapper/gradle-wrapper.jar`，请用 Android Studio 打开（会自动补全）或自行生成 wrapper。