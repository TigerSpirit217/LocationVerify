# LocationVerify

[中文](README.md) | English

An Android tool that runs **Baidu → Amap → Tencent Location SDKs** and displays location results and mock location signals. Configure at least one key; SDKs without keys are skipped. Each SDK has a 15-second application timeout, so a full sequence can take approximately 45 seconds.

Risk labels describe SDK signals. A low-risk result does not prove authenticity. Failed requests, timeouts and missing signals cannot establish whether a location is real or fake.

## Usage and languages

1. Read the privacy notice and give consent on first launch.
2. Configure at least one SDK key. The configuration page shows the installed APK’s package name and signing certificate SHA-1, which can be selected and copied.
3. Grant location access. Precise access is recommended; approximate access is accepted but may limit SDK results.
4. Enable system location, use a network that can reach provider services, and tap **Start check**.

Language follows the system directly. Chinese system locales use Chinese resources; unsupported system languages fall back to English. There is no language setting in the app and no independent app language option registered in system settings. Upgrading clears an earlier app language override.

Pages, dialogs, prompts, report fields, risk labels and newly generated app log messages are localized. SDK-provided addresses, error details and status descriptions retain their original text; existing logs are not translated. Changing the system language or light/dark theme recreates activities and cancels checks owned by the recreated main page; start a new check afterward.

## Light and dark themes

The DayNight theme follows system appearance with no in-app setting. Pages, cards, inputs, bottom controls, links, risk text, dialogs and system bars adapt to dark mode. System bar icons switch with the background.

## SDK behavior and risk mapping

Each SDK runs after the previous one completes, fails, times out or is skipped. Results appear in three separate sections.

| SDK | Configuration | Main signals |
| --- | --- | --- |
| Baidu | `setEnableSimulateGnss(true)`, `setScanSpan(0)`; `LocationClient.start()` triggers the initial request | `getMockGnssProbability()`, `getMockGnssStrategy()`, `getDisToRealLocation()`, `getReallLocation()` |
| Amap | `setOnceLocation(true)`, `setMockEnable(true)`, cache disabled | `isMock`, error code 15, `trustedLevel`, `locationType` |
| Tencent | `setMockEnable(true)`, `setEnableAntiMock(true)`, cache disabled; `requestSingleFreshLocation` requests coordinates without waiting for an address; SDK first-result timeout 10 seconds, app watchdog 15 seconds | `isMockGps()`, `getFakeProbability()`, `getFakeReason()`, `sourceProvider` |

Tencent `setInterval(0)` means callbacks only when a new result is available; it does **not** mean a single request. The app now uses the SDK single-fresh-location API and still removes the listener on completion, cancellation or timeout. Nonzero request return codes are reported immediately instead of waiting for the application timeout.

The following mapping belongs to this app, not a universal provider authenticity guarantee:

| Risk | Baidu | Amap | Tencent |
| --- | --- | --- | --- |
| High | Successful location with HIGH probability or positive fake-to-real distance | `isMock=true`, or error 15 | `isMockGps=1`, FAKE source, or probability in 0.6–1 |
| Medium | Successful location with MIDDLE probability | — | Probability greater than 0 and less than 0.6 |
| Low | Successful location with LOW/ZERO probability | Successful location with `isMock=false` | `isMockGps=0` without a stronger risk signal |
| Unknown | Location failure or unknown probability | Other location failures | Failure, or unknown Mock GPS status without a clear anti-mock signal |

Amap error 15 is a completed high-risk check even though a usable location was rejected. Tencent `isMockGps=-1`, zero probability or an invalid probability alone is not evidence of low risk. Trust level and location source provide context, not independent authenticity guarantees.

## Understanding the supplied log

- **Baidu `requestLocation()` returned 1**: the previous code requested a location before starting the client. It now calls `start()`, which starts the service and triggers the initial request. See the [Baidu API reference](https://mapopen-pub-androidsdk.cdn.bcebos.com/location/doc/v8.4.4/com/baidu/location/LocationClient.html).
- **Amap `errorCode=0`, `type=5`, `isMock=false`**: a successful Wi-Fi location without a mock flag. This does not establish connectivity to other providers. See [Amap location types](https://lbs.amap.com/api/android-location-sdk/guide/utilities/location-type).
- **New Baidu log: `locType=161`, `mockProb=-1`**: network positioning succeeded, but Mock GNSS probability is unavailable. An unknown risk is expected.
- **Tencent returned 0, changed from GPS unavailable to available, but still timed out**: device status updates are separate from location-result callbacks and do not establish that valid coordinates were obtained. SDK 7.6.1.9 defaults to a 15-second first-result timeout, competing with the previous app watchdog and potentially masking its error code. The SDK timeout is now 10 seconds, with a 15-second app watchdog and a single coordinate request. Logs include request options, permissions, system location, network validation and final SDK statuses. Device retesting is still required; network validation does not establish Tencent service reachability. See the [Tencent listener reference](https://tencentlocation.github.io/doc/com/tencent/map/geolocation/TencentLocationListener.html).
- **An additional `App.onCreate` when Baidu starts**: its service is configured in a `:remote` process, and each process has its own Application. This alone does not mean the main process restarted. Startup logs now include PID and process name.

## Keys and signing

The package name is `com.tiger.locationverify`.

| Provider | Console | Setup |
| --- | --- | --- |
| Baidu | [Baidu console](https://lbsyun.baidu.com/apiconsole/key) | Create an Android SDK application with the package name and installed APK’s signing SHA-1 |
| Amap | [Amap console](https://console.amap.com/dev/key/app) | Add an Android Key with the package name and correct signing SHA-1 |
| Tencent | [Tencent console](https://lbs.qq.com/dev/console/application/mine) | Create an application and follow its current authorization requirements |

Debug and release certificates can differ; use the signature shown by the installed app. You can also inspect your signing keystore:

```text
keytool -list -v -keystore <path-to-your-keystore>
```

Keys are stored locally in `SharedPreferences`. Before obtaining clients, checkers set them through `LocationClient.setKey`, `AMapLocationClient.setApiKey` and `TencentLocationManagerOptions.setKey`. Tencent uses a process singleton; **restart the app after changing its Key** to ensure the new value takes effect.

Manifest key placeholders default to empty. Checkers first require a key saved on the app’s configuration page, so changing only a Manifest placeholder does not enable that SDK. Logs mask key values but still contain location data and SDK details.

## Privacy and logs

Consent is stored locally and synchronized with all three SDKs before location clients are created. Without consent, the app redirects to the privacy page. Reports and logs stay on the device; SDKs may transmit required information to their official services under their policies. The app has no additional upload service.

Logs are stored at `filesDir/logs/log_yyyyMMdd.txt`. The log dialog shows the current file path and its last 300 lines. Clearing logs deletes all log files in that directory; no storage permission is required.

Privacy policies: [Baidu](https://lbsyun.baidu.com/index.php?title=openprivacy), [Amap](https://lbs.amap.com/pages/privacy/), [Tencent](https://privacy.qq.com/document/preview/dbd484ce652c486cb6d7e43ef12cefb0).

## Project and development

- `MainActivity.kt`: permissions, ordered checks and three result sections.
- `location/`: three SDK checkers, reports and risk mapping.
- `consent/`, `keys/`: privacy and key configuration pages.
- `data/`, `util/`: local settings, logging and system bar insets.
- `res/values/strings.xml`: English fallback; `res/values-zh/strings.xml`: Chinese.
- `res/values-night/`: dark colors and system bar icon configuration.
- `AndroidManifest.xml`: permissions and SDK services.

Declared dependencies: Baidu **9.7.0**, Amap **11.3.000**, Tencent **7.6.1.9**. The project declares compile/target SDK **37**, Build Tools **37.0.0**, Java **21** and AGP **9.4.1**; refer to Gradle files for the full configuration. The repository includes Gradle wrapper scripts and `gradle/wrapper/gradle-wrapper.jar`.

No build or device verification was performed for this change, as requested. Static checks cannot verify SDK responses, provider reachability or runtime UI behavior.
