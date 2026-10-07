# 秦PLUS DM-i 车测排障交接文档（2026-10-04）

> 本文件供新会话接力使用。完整背景另见项目记忆：
> `/Users/lixp7/.qoder/projects/-Users-lixp7-workspace-javaWorkspace-DiPlay/memory/user-car-qinplus-stall.md`

## 一、现场与取证方式

- **车机**：比亚迪秦 PLUS DM-i，DiLink 3.0，**Android 9**（证据：三指下滑设置页没有 "Wi-Fi Direct (5 GHz)" 选项，该选项仅 Android 10+ 渲染；且 DiPlayActivity 点 Wi-Fi Direct 会弹"已保存"但立即回退——`AirPlayPersistence.loadWirelessHotspotMode` 在 SDK<Q 时把 WIFI_P2P 强制改回 MANUAL）。
- **车机限制**：开不了 ADB；~~"设置→诊断→保存诊断报告"保存失败~~ **已修复并二轮迭代**——一轮（16:53 包）写 `Android/data/.../files/DiPlay/` 但比亚迪文件管理器进不去该目录；二轮改为：声明 WRITE_EXTERNAL_STORAGE（maxSdk 28），点保存时请求权限，**授权后写公共 `Download/DiPlay/`**（任何文件管理器可见），拒绝才回落应用私有目录。弹窗显示绝对路径。取证路径 = 诊断报告文件 + 屏幕实时日志拍照。
- **hudtest 包**：`mobile-debug.apk`（包名 `com.shihab.diplay.hudtest`，身份资产与官网 0.2.11 包字节一致，来自 `~/DiPlay-runtime-assets/offline-mfi/`）。用户装在车上与官方包并排，配置独立。
- **调试闭环（HITL）**：改代码 → 构建 → `scripts/push-apk.sh` 推到用户 Redmi 手机（`/sdcard/Download/`）→ 用户手动搬到车上覆盖安装 → 车上复现 → 拍照日志发回。每轮都需用户配合。
- **用户偏好**：屏幕日志必须带**中文标注**（`ConnectionLogChineseTags`），用户靠肉眼扫屏定位；已明确要求，后续新增日志默认配中文标签。

## 二、无线流程：已定案，无解

证据链（都已实测）：

| 日志字段 | 值 | 含义 |
| --- | --- | --- |
| `waitingFor` | `WiFi_discovery_or_AirPlay_TCP` | 蓝牙 iAP2 认证通过、start-session 已发，iPhone 零 AirPlay TCP 入站 |
| `tcpAccepted` / `bonjourAdded` | 0 / 0 | 双向 mDNS 发现全无 |
| `apFamily` / `apIface` | IPv6 / **ap0** | 监听和 JmDNS 绑在车热点 ap0 接口的 IPv6 链路本地地址 |
| `apMdns`（双栈被动探针） | `packets=0 v4=0/0 v6=0/0` 持续为 0 | **连 DiPlay 自己 JmDNS 广播都没有回环** |

结论：**比亚迪车热点（ap0）的多播在 Android 用户空间完全不通**（疑固件桥接只透传单播）。iPhone 确实连在车热点"秦lxp"（MANUAL 模式，用户自配 SSID），但两端 mDNS 互盲。与 iOS 27（上游 issue #96 的怀疑）无关。

P2P 替代路线同样堵死：Android 9 上 `WifiP2pGroupManager` 需要 API 29 才能读 P2P 凭据（发给 iPhone 的 0x5703），模式选择会被强制回退 MANUAL。

**→ 无线不要再试。USB 是唯一活路。**

## 三、USB 流程：根因 = 比亚迪守护进程抢接口（已解决，车测确认连通）

### 观察到的失败（不同轮次失败点不同）

1. 10:02 轮：`STEP usb/data` 后 `ERROR Android could not select the NCM data alternate setting`（config=6 control=3/0 data=4/1；status=0x87 是状态中断端点地址不是错误码）。即 USBMUX claim 成功、NCM claim 成功，挂在 `setInterface(data)` 返回 false。
2. 11:14 轮：`ERROR Android could not claim USBMUX interface 1`（比上一轮更早挂）。
3. 11:2x 轮（11:19 版带 5 次重试）：`ERROR Android could not claim USBMUX interface 1 after 5 attempts`——重试整轮全输。

### 根因判断

**比亚迪系统守护进程（疑车机 USB 媒体播放器/亿连手机互联服务）持有 iPhone USB 接口的用户态 fd**。`claimInterface(force=true)`（USBDEVFS_DISCONNECT_CLAIM）只能踢内核驱动、抢不走其他进程的 fd；**先 claim 的用户态进程胜出且持有权不可被抢**。守护进程的占用是间歇性而非永久（10:02 一轮我们赢过），但它一旦占住会握满一整轮（非毫秒级竞争，500ms×5 重试无效）。

### 已实现的代码对策（全部在未提交的工作区里）

| 文件 | 改动 |
| --- | --- |
| `shared/.../transport/NcmUsbBridge.kt` | `claimInterfaceWithRetry`（500ms×5）用于 USBMUX/NCM 两接口 claim；`selectDataAlternateSetting`（失败后 re-claim+300ms×3）用于 NCM setInterface |
| `shared/.../transport/IphoneUsbHost.kt` | **`startEarlyClaim`（11:34 版新增）**：attach 广播到达第一时间就在 executor 上抢 claim（3 次×200ms）并持有连接；`openIap2UsbSession` 通过 `takeEarlyClaim` 复用；抢占日志走 `onDiagnostic` 上屏幕 |
| `shared/.../orchestration/CarPlayController.kt` | `beginReenumeration` 启动 earlyClaimHandle，`closeReceivers` 释放；IphoneUsbHost 构造传 `onDiagnostic = ::debugLog` |
| `common/.../CarPlayHostActivity.kt` + `ConnectionLogChineseTags.kt` | appendLog 统一加中文标注：【USB接口抢占成功/失败/就绪/被占用】【等手机连热点或AirPlay】【检测到外来mDNS流量】等 |
| `shared/.../network/ApMdnsTrafficProbe.kt` | 双栈（v4+v6）mDNS 被动收包计数，快照行 `apMdns windowMs=.. packets=.. foreign=.. v4=x/y v6=x/y`；快照行数 take(4)→take(5)；快照新增 `apFamily=` `apIface=` |
| `scripts/push-apk.sh` | 一键推包（默认 debug 包到 /sdcard/Download/，`-i` 安装） |
| `common/.../res/xml/usb_device_filter.xml`（15:09 版新增） | 加入 `vendor-id="1452"`（Apple 0x05ac）→ DiPlay 出现在插线"打开方式"chooser 中，用户选"始终"后 attach 即自动拉起 CarPlayHostActivity 并自动授予 USB 权限（CarPlayHostActivity 已处理 attach intent，会切有线模式） |
| `IphoneUsbHost.attemptEarlyClaim`（15:09 版） | 四处静默 return 全部补 onDiagnostic：permission 未授予 / 无 CarPlay 配置 / 无 USBMUX 接口 / openDevice=null；中文标签【USB接口抢占待授权】【USB接口抢占跳过】 |
| `CarPlayController.startIphone`（15:09 版） | earlyClaimHandle 注册点从 beginReenumeration 提前到 startIphone——此前 iPhone 已处 CarPlay 配置的重试轮（onIphonePermission 直接走 openDataPaths）early claim 从不注册，是 1:45 轮 fallback claim 孤军奋战的结构性原因 |

测试全绿（`NcmAlternateSettingRetryTest`、`ApMdnsTrafficProbeTest`、`ConnectionLogChineseTagsTest` 等），lint 通过。

### 当前状态与待办

**已解决**：15:09 包已推送装车，用户回报"通过 usb 可以连接成功了"——chooser 选 DiPlay + early-claim 的组合奏效。当前瓶颈已转移为**性能**（见第四节）。

**等待用户回报的零成本实验（可能直接绕开竞争）**：
1. ~~插 iPhone 的瞬间车机屏幕弹出什么界面~~ **已回报（1:45 轮后）：插线瞬间弹出"夸克网盘"的"打开方式"选择框** → 证明车机上存在注册了 Apple/USB device-filter 的第三方应用。chooser 能弹出说明当前没有默认处理应用，夸克未必是占用元凶（更可能是不弹框的系统服务）；用户已能在 chooser 中选 DiPlay；
2. 车机设置里关闭"USB 自动播放音乐 / 媒体扫描 / 手机互联自动连接"后再连，claim 能否赢（连通后此项优先级降低）。

### 备选思路（若 early-claim 仍输）

- 确认守护进程身份后，引导用户停用对应车机服务（治本，超出 app 能力范围）；
- 观察 early-claim 与守护进程的相对时序（屏幕日志已有时间戳），判断是拼启动速度还是有内核驱动绑定（若是内核驱动且不支持 unbind，任何用户态重试都无效）；
- 上游同类问题参考：GitHub issue #96（shihabal3amri/DiPlay，Qin Plus/iOS 27.0.1 stall，open，无诊断结论）——我们是第一个拿到真实日志的，最终结论值得回帖。

## 四、性能优化：批次 A（22:19 包，待车测）

USB 连通后用户回报"性能调到最低导航还是卡卡的"。按收益排序的分析结论：最大头是**每帧纯 Java ChaCha20-Poly1305 解密**（BouncyCastle），其次是镜像画面各自重复一份完整解码器、TextureView 主屏合成、日志面板主线程全量重绘+同步磁盘写、队列 O(n) 扫描、热线程无优先级。批次 A 先做不改架构的三项：

| 项 | 文件 | 改动 |
| --- | --- | --- |
| A1 解密换原生 | `shared/.../airplay/AirPlayCrypto.kt` | ChaCha20-Poly1305 默认走 JCA/Conscrypt（BoringSSL native，API 28+），检测不到才回落 BouncyCastle；Cipher 按 key 缓存复用（ThreadLocal，Conscrypt 禁止同 cipher+key 复用 nonce，生产上 nonce 本来单调递增）。视频每帧、音频每包都走这条路 |
| A2 日志面板减负 | `common/.../CarPlayHostActivity.kt` | 磁盘写移到单线程 executor（主线程零阻塞）；日志 View 200ms 合并刷新；内存行数上限 150；SimpleDateFormat 改 ThreadLocal 复用 |
| A3 队列与线程优先级 | `shared/.../media/VideoDecodeQueue.kt`、`AndroidMediaSink.kt`、`ScreenStream.kt`、`AudioStream.kt`、`Ipv6NcmBridge.kt` | 队列字节统计改增量计数（去掉每 offer 的 O(n) 求和）；视频解码/收流线程 URGENT_DISPLAY，音频解码/收流线程 URGENT_AUDIO，NCM IPv6 桥 DISPLAY |

零成本设置建议（已转告用户）：关闭仪表盘地图/中控卡片/启动器共享（每路镜像 = 一份完整 MediaCodec 解码器）、fps=30、缩放 0.3。

**22:19 包已构建（49033730 字节），手机未连 adb 未推送；连上后 `scripts/push-apk.sh`**。车测看点：
- 导航滑动/缩放是否还卡（重点对比同设置下的旧包）；
- 屏幕日志确认无新增报错（批次 A 未加新日志行；原生解密在 API 28+ 必然生效，回落 BouncyCastle 只会出现在引擎加载失败的设备上）；
- 批次 B（镜像解码抽帧/隐藏镜像不喂帧）、批次 C（主屏 TextureView→SurfaceView、丢迟帧代替清队列）待批次 A 效果回报后定。

测试全绿（新增 `AirPlayCryptoTest` 5 项含 RFC 8439 向量与跨引擎互操作；`VideoDecodeQueueTest` 补字节预算特性化测试；`CarPlayHostThemeDiagnosticsTest` 适配异步日志写），lint 通过。

### 诊断报告导出修复（二轮：公共 Downloads，与批次 A 同包待车测）

| 文件 | 改动 |
| --- | --- |
| `common/.../DiagnosticExportStore.kt` | `saveToAppStorage`（应用私有目录兜底）+ `saveToPublicDownloads`（公共 Download/DiPlay，需权限），共用落盘助手；`DiagnosticExportStoreTest` sdk=28 四项（两条路径的 UTF-8 落盘 + 失败传播不碰挡路文件） |
| `common/.../DiPlayActivity.kt` | pre-Q 保存按钮：有存储权限→直接导出公共 Downloads；无→请求权限，授权/拒绝都继续导出（拒绝走私有目录）；成功弹窗显示绝对路径；file:// 不渲染分享按钮（targetSdk 34 下 file Uri 分享会崩） |
| `common/.../AndroidManifest.xml` | 声明 WRITE_EXTERNAL_STORAGE maxSdkVersion=28 |

背景：一轮方案（应用私有目录）实测失败——比亚迪文件管理器无法浏览 `Android/data`，故改公共目录。应用显示名同步从 "DiPlay HUD Test" 改为 "DiPlay Beta"。

## 五、常用命令

```bash
# 全量测试 + lint
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintDebug

# 构建 standalone 车测包（必须带身份资产环境变量）
DIPLAY_AUTH_ASSETS_DIR="$HOME/DiPlay-runtime-assets" ./gradlew :mobile:assembleStandaloneDebug
# 产物：mobile/build/outputs/apk/debug/mobile-debug.apk

# 推送到用户 Redmi 手机（需先 adb 授权；MIUI 静默安装常被拦，让用户手动装或开"通过 USB 安装"）
scripts/push-apk.sh [路径] [-i]

# 复制 APK 到 U 盘（文件名自动带包时间戳，如 diplay-hudtest-20261004-2219.apk；缺省目标 /Volumes/HIKSEMI）
scripts/copy-apk-to-usb.sh
# U 盘只读挂载（FAT 脏标记，热插拔后常见）已由 LaunchAgent com.shilapi.usb-remount 自动重挂载（2026-10-07 起）；
# 手动兜底：diskutil unmount /Volumes/HIKSEMI && diskutil mount disk4s1
# 排查服务：launchctl list | grep usb-remount；日志：/tmp/usb-remount.log

# adb（已加入 ~/.zshrc PATH；设备序列号 6hx8xsofr4jzu4vo，adb 授权容易掉线）
adb devices
```

## 六、USB 存储误判与 ADB 解锁路线（2026-10-04 晚新增）

- **用户曾尝试电脑直接插车机当"U盘"**：走不通——车机 USB 口是 host 口，只认 U盘/移动硬盘类存储设备，电脑（同为 host）不会被枚举。已实测：电脑 USB 总线无任何外接设备、`adb devices` 空。上午 adb 识别到的是 Redmi 手机（6hx8xsofr4jzu4vo），不是车机。
- **可行投递路线**（按优先级）：
  1. **解锁 ADB（优先尝试）**：车机 设置→DiLink→版本管理→连续点击「恢复出厂设置」10 次（别点真正重置）→开发者选项→开 USB/无线调试；电脑连车热点"秦lxp"后 `adb connect <车IP>:5555`（网关一般是 192.168.43.1）。通了则 `adb install`/`adb pull` 直连车机，彻底告别手机中转。受限固件可能需 U盘 装 ADB 激活工具。
  2. **U盘 FAT32**：APK 拷 U盘 插数据口，车机文件管理器点装（反向可拷回日志）。
  3. **车热点+HTTP**：电脑连"秦lxp"，`python3 -m http.server 8000`，车机浏览器下 APK。
- 待用户回报 ADB 是否解锁成功；成功则更新本文档取证路径（"车机无 ADB"假设作废）。

## 七、其他要点

- **git 状态**：已全部提交并推送到 fork（origin=tenderne13/DiPlay）。main = `b47d783`（批次 A `21fb2c6` + 比亚迪支持 `b47d783`）；**给上游提 PR 用 `perf/low-end-head-units` 分支（仅含批次 A，未推送）**。
- 仓库 remote 是 fork `tenderne13/DiPlay`，上游 `shihabal3amri/DiPlay`；`gh` CLI 未安装，查上游 issue 用 `https://api.github.com/...`（WebFetch 可用）。
- 用户手机（Redmi，adb 调试）与车机（无 ADB）是两台设备，勿混淆。
- 用户在国内网络环境，构建下载类命令注意重试；细节见用户记忆 `user-dev-environment.md`。
