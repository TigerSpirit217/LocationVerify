# LocationVerify 位置真伪检测

中文 | [English](README.en.md)

Android 位置检测工具，依次运行 **百度 → 高德 → 腾讯定位 SDK**，展示定位结果与模拟定位信号。至少配置一个 Key；未配置的 SDK 自动跳过。每家 SDK 的应用层等待上限为 **15 秒**，三家都运行时约需最多 45 秒。

风险等级仅描述 SDK 的返回信号。“低风险”不能证明位置真实；定位失败、超时和字段未知也不能证明位置真实或虚假。

## 使用与语言

1. 首次启动阅读功能与隐私说明，勾选同意。
2. 在「配置密钥」填写至少一个 Key。页面展示当前安装包的包名与签名 SHA-1，可选中复制。
3. 授予定位权限。建议使用精确位置；应用接受大致位置权限，但 SDK 的结果和可用字段可能受限。
4. 开启系统定位，确保网络能够访问各厂商服务，点击「开始检测」。

语言直接跟随系统：中文系统使用中文资源，其他未支持的系统语言回退到英文。应用不提供语言设置，也不注册系统的独立应用语言选项；升级后会清除旧版的应用语言覆盖。

双语覆盖页面、弹窗、提示、报告字段、风险等级及新生成的应用日志。SDK 原始地址、错误详情和状态描述保留厂商返回文本；已有日志不会自动翻译。切换系统语言或明暗主题会重建页面，并取消被重建主页面持有的检测，请重新开始检测。

## 明暗主题

界面通过 DayNight 主题跟随系统明暗模式，不提供应用内主题设置。页面、卡片、输入框、底栏、链接、风险文字、弹窗和系统栏均适配暗色；系统栏图标随背景切换明暗。

## 定位流程与风险映射

三家按顺序执行，每家完成、失败、超时或跳过后进入下一家，结果分别显示在三个区域。

| SDK | 当前配置 | 重点字段 |
| --- | --- | --- |
| 百度 | `setEnableSimulateGnss(true)`、`setScanSpan(0)`，调用 `LocationClient.start()` 启动首次定位 | `getMockGnssProbability()`、`getMockGnssStrategy()`、`getDisToRealLocation()`、`getReallLocation()` |
| 高德 | `setOnceLocation(true)`、`setMockEnable(true)`，关闭定位缓存 | `isMock`、错误码 15、`trustedLevel`、`locationType` |
| 腾讯 | `setMockEnable(true)`、`setEnableAntiMock(true)`，禁用缓存，使用 `requestSingleFreshLocation`；请求坐标、不等待地址，SDK 首包超时 10 秒，应用兜底 15 秒 | `isMockGps()`、`getFakeProbability()`、`getFakeReason()`、`sourceProvider` |

腾讯 `setInterval(0)` 表示有新结果时才回调，**不表示单次定位**。当前使用 SDK 的单次定位接口，完成、取消或超时后仍调用 `removeUpdates()` 清理；请求返回非 0 时立即报告失败，不再等待 15 秒超时。

以下是应用自己的映射规则，不是统一的厂商真实性保证：

| 等级 | 百度 | 高德 | 腾讯 |
| --- | --- | --- | --- |
| 高风险 | 定位成功且概率 HIGH，或虚假点与真实点距离 > 0 | `isMock=true`，或错误码 15 | `isMockGps=1`、来源含 FAKE，或概率在 0.6～1 |
| 中风险 | 定位成功且概率 MIDDLE | — | 概率 > 0 且 < 0.6 |
| 低风险 | 定位成功且概率 LOW / ZERO | 定位成功且 `isMock=false` | `isMockGps=0` 且没有更高风险信号 |
| 无法判断 | 定位失败或概率未知 | 其他定位失败 | 定位失败，或无法判断 Mock GPS 且没有明确反作弊信号 |

高德错误码 15 表示模拟位置被识别并拒绝，界面显示“检测完成 + 高风险”，不代表获得了可用位置。腾讯 `isMockGps=-1`、概率为 0 或概率无效本身不作为低风险证据。可信度、定位类型用于提供上下文，不能独立证明真伪。

## 本次日志如何解读

- **百度 `requestLocation()` 返回 1**：原代码未启动客户端就请求定位。已改为调用 `start()`，启动服务并自动发起首次定位，避免这个确定的调用顺序错误。参见[百度 LocationClient 接口说明](https://mapopen-pub-androidsdk.cdn.bcebos.com/location/doc/v8.4.4/com/baidu/location/LocationClient.html)。
- **高德 `errorCode=0`、`type=5`、`isMock=false`**：成功获得 Wi-Fi 定位，SDK 没有标记模拟位置。这是正常网络定位结果，不能据此断言其他厂商的服务可达。参见[高德定位类型](https://lbs.amap.com/api/android-location-sdk/guide/utilities/location-type)。
- **新日志百度 `locType=161`、`mockProb=-1`**：已获得网络定位，但没有可判定的 Mock GNSS 概率，因此“无法判断”是预期结果。
- **腾讯请求返回 0，GPS 从 unavailable 变为 available，仍超时**：设备状态通知不等于定位成功回调，不能据此判断已经获得有效坐标。SDK 7.6.1.9 默认首包超时为 15 秒，与原应用 watchdog 同时到期，可能抢先掩盖 SDK 的错误码；现改为 SDK 10 秒、应用 15 秒，并使用单次坐标请求。日志补充请求参数、权限、系统定位、网络验证状态及超时前最后 SDK 状态，实际定位效果仍需真机复测。网络验证状态不代表腾讯服务一定可达。参见[腾讯监听器说明](https://tencentlocation.github.io/doc/com/tencent/map/geolocation/TencentLocationListener.html)。
- **百度启动时又出现一次 `App.onCreate`**：Manifest 为百度服务配置了 `:remote` 进程，各进程都会创建自己的 Application，这本身不表示主进程重启。启动日志现包含 PID 与进程名，便于区分。

## 密钥与签名

应用包名为 `com.tiger.locationverify`。

| 厂商 | 申请入口 | 配置要点 |
| --- | --- | --- |
| 百度 | [百度控制台](https://lbsyun.baidu.com/apiconsole/key) | 创建 Android SDK 应用，填写包名和实际安装包的签名 SHA-1 |
| 高德 | [高德控制台](https://console.amap.com/dev/key/app) | 添加 Android 平台 Key，填写包名和正确的签名 SHA-1 |
| 腾讯 | [腾讯控制台](https://lbs.qq.com/dev/console/application/mine) | 创建应用，按控制台当前鉴权要求配置 |

调试与发布签名可能不同，以安装后配置页展示的值为准。自行签名时也可以查询 keystore：

```text
keytool -list -v -keystore <你的 keystore 路径>
```

密钥保存在本地 `SharedPreferences`。检测器在创建客户端前通过 `LocationClient.setKey`、`AMapLocationClient.setApiKey`、`TencentLocationManagerOptions.setKey` 设置密钥。腾讯使用进程单例，**修改腾讯 Key 后请重启应用**，确保新值生效。

Manifest 中的密钥占位符默认为空。检测器先检查应用内保存的 Key，因此只修改 Manifest 占位符不会使该 SDK 通过“已配置”检查。日志中的 Key 会遮挡中间部分，但日志仍包含位置和 SDK 详情。

## 隐私与日志

用户同意决定在本地持久保存，并在创建定位客户端前同步给三家 SDK；未同意时进入隐私页。应用生成的报告与日志保存在本机；SDK 按各自政策向官方服务传输所需信息，应用没有额外上传服务。

日志位于 `filesDir/logs/log_yyyyMMdd.txt`。「查看日志」展示当前文件的实际路径和最后 300 行；「清空日志」删除该日志目录中的所有日志文件，不需要存储权限。

隐私政策：[百度](https://lbsyun.baidu.com/index.php?title=openprivacy)、[高德](https://lbs.amap.com/pages/privacy/)、[腾讯](https://privacy.qq.com/document/preview/dbd484ce652c486cb6d7e43ef12cefb0)。

## 项目结构与开发

- `MainActivity.kt`：三区结果、权限、顺序检测。
- `location/`：三家检测器、报告与风险等级。
- `consent/`、`keys/`：授权说明、密钥与签名页面。
- `data/`、`util/`：本地配置、日志、系统栏。
- `res/values/strings.xml`：默认英文资源；`res/values-zh/strings.xml`：中文资源。
- `res/values-night/`：暗色配色与系统栏图标配置。
- `AndroidManifest.xml`：权限与 SDK 服务。

当前声明的依赖：百度 **9.7.0**、高德 **11.3.000**、腾讯 **7.6.1.9**。工程配置为 compile/target SDK **37**、Build Tools **37.0.0**、Java **21**、AGP **9.4.1**；完整配置以 Gradle 文件为准。仓库包含 Gradle wrapper 脚本与 `gradle/wrapper/gradle-wrapper.jar`。

本次按要求未执行构建或真机验证；静态检查不能验证 SDK 实际返回、厂商服务连通性或运行时界面表现。
