# GPS（com.gpstesttools）· 源码地图

> 用途：改代码前先在本文件定位目标文件，避免全库搜索。
> 生成时间：2026-09-22　覆盖：`GPS/app/src/main/java/**` 19 个 .kt + 构建配置 + 资源。
> 拆分判定：项目内**没有**源码文件超过 75KB / 1800 行（最大 `ui/pages/NavigationPage.kt` 55KB / 1349 行）→ 本轮不含拆分任务。
> 相关：协议/字段规则见 `.workbuddy/memory/MEMORY.md` 的「GPS」段；任务与需求记录见 `TASKS_记录保存与历史.md`。

---
## GPS 工具箱（com.gpstesttools）源码文件作用索引

> 索引基于逐文件通读生成，覆盖 Kotlin 源码（19 个）、构建/配置文件、清单与资源、`.workbuddy` 脚本与任务文档。
> 硬规则核对见各文件「备注」：速度记录文件 = `getExternalFilesDir(null)/<开始时间>.csv`；图表缓存 = `externalCacheDir/chart/`；管线 `EMA(0.20)→480 桶→3 点滑动平均→km/h`；统计值走原始数据而非缓存。

---

#### 1. 应用入口与生命周期

| 文件 | 行数 | 作用 | 关键成员 | 备注 |
|---|---|---|---|---|
| `MainActivity.kt` | 695 | App 根：权限声明/请求、三页 Pager、引擎启停、前后台策略、护屏与退出 | `GpsToolboxApp` / `saveAndExitApp` / `onTrimMemory` | 记录中收关机/清理/最近任务划掉均 `RecordStore.finish`+退出（幂等）。后台 1min 停保活、记录中 10min 硬退超时。tab 索引持久化到 `sp` 防冷启动回 0 |
| `LocationKeepAliveService.kt` | 221 | 后台定位保活前台服务（location 型 FGS）+ 通知 + 唤醒锁 | `onStartCommand` / `startForegroundWith` (三参 location) | **Android14+ 必须传 `FOREGROUND_SERVICE_TYPE_LOCATION` 三参 startForeground**，否则 FGS 被撤权、GPS 丢失。`onTaskRemoved` 保存退出 |

#### 2. 定位 / 传感器引擎

| 文件 | 行数 | 作用 | 关键成员 | 备注 |
|---|---|---|---|---|
| `gps/GpsEngine.kt` | 332 | GNSS + 定位数据源：GnssStatus 回调出卫星/速度/定位状态，StateFlow 暴露 | `start/stop/backgroundPause/foregroundResume` / `SatInfo` | 用 `GnssStatus.Callback` 非旧 `GpsStatus`；卫星去重防 Compose key 冲突崩；回前台检测 GNSS 停滞强刷 AGPS |
| `gps/CompassEngine.kt` | 131 | 指南针：ROTATION_VECTOR（降级 ACCEL+MAG）→ 地理北方位角 | `start/stop/updateDeclination` / `heading` | 用反射兼容 `GeomagneticField`（SDK37 迁移到 hardware 包）；磁偏角修正叠加 |

#### 3. 记录存储 / 备份 / 图表缓存

| 文件 | 行数 | 作用 | 关键成员 | 备注 |
|---|---|---|---|---|
| `gps/RecordStore.kt` | 207 | 行驶 CSV 单例：begin/append/finish/list/loadSeries + 4KB 缓冲 | `begin/append/finish/delete/list` / `MAX_RECORDING_MS=12h` | 文件 = `getExternalFilesDir(null)/<开始时间>.csv`；首行开始、末行停止、中间每 1s `序号,速度(m/s)`。**历史与备份扫此目录 `*.csv`，新增文件勿放此目录/勿用 .csv 结尾** |
| `gps/RecordBackup.kt` | 110 | 备份/还原：全部 csv 打包 zip 到 `/sdcard/GPS速度测试记录备份文件.zip` | `backup/restore` / `suppressKeepAliveOnce` | 11+ 需 MANAGE_EXTERNAL_STORAGE（跳设置）；STORED 条目预填 crc/size；还原前 `check(!isRecording)` |
| `gps/ChartCache.kt` | 172 | 绘图专用缓存：EMA(α=0.20)→480桶→3点滑动平均→km/h | `load/delete` / `EMA_ALPHA` `POINTS` | 缓存目录 `externalCacheDir/chart/`，与记录目录分离∴不被历史/备份扫到。**统计仍走 RecordStore 原始数据（平滑削峰）**；源字节数/mtime 变自动重建 |

#### 4. UI 框架与页面

| 文件 | 行数 | 作用 | 关键成员 | 备注 |
|---|---|---|---|---|
| `ui/ScreenScale.kt` | 58 | 屏幕等比缩放：覆盖全局 Density 按宽度比例缩放（基准 393dp） | `ScreenScaling` / `DESIGN_W_DP` | 仅按宽度、截断 0.75~1.30；改基准机只改 `DESIGN_W_DP` |
| `ui/pages/CompassPage.kt` | 202 | 页一指南针：表盘+指针+方向+精度/磁偏/卫星卡+坐标 | `CompassPage` / `InfoCard` | 表盘 `compass_dial.png` 固定、指针 `compass_needle.png` 反向旋转 `-heading` |
| `ui/pages/HistogramPage.kt` | 400 | 页二信号强度：顶栏+表头+卫星行（SNR 条/国家 emoji/PRN/方位/仰角）+底栏坐标 | `HistogramPage` / `snrBarColor` | 旗帜列用 emoji 替代国旗 drawable；图标字形来自 `gps_test.ttf`；2s 节流快照仅显 SNR>0 |
| `ui/pages/NavigationPage.kt` | 1348 | 页三速度/导航：半圆仪表+记录卡片+历史/详情/备份弹窗+曲线绘制 | `NavigationPage` / `RecordState` / `SpeedGauge` / `DetailDialog` | 记录会话状态由 MainActivity 提升保存；12h 上限自动退出；指针/护屏漂移自驱 `withFrameNanos` 动画（避开系统 ANIMATOR_DURATION_SCALE=0 瞬移坑） |

#### 5. UI 组件

| 文件 | 行数 | 作用 | 关键成员 | 备注 |
|---|---|---|---|---|
| `ui/components/SpeedScreenSaver.kt` | 298 | 记录中护屏层：全屏沉浸、速度表+大字+时间整体 30s 随机漂移（防烧屏） | `SpeedScreenSaver` / `IDLE_ENTER_MS=30s` | 吃掉触摸交由 MainActivity 全局时间戳统一判定退出；边距用**物理像素** ≥20px（非 dp） |
| `ui/components/BackupRestoreDialog.kt` | 152 | 备份/还原弹窗：IO 放 Dispatchers.IO，未授权跳所有文件访问设置页 | `BackupRestoreDialog` / `runOp` | 跳设置前置 `RecordBackup.suppressKeepAliveOnce=true` 防 ON_PAUSE 起 FGS 闪退 |

#### 6. 主题（Compose）

| 文件 | 行数 | 作用 | 关键成员 | 备注 |
|---|---|---|---|---|
| `ui/theme/Color.kt` | 10 | 默认 Material 配色（紫调，基本未用） | `Purple80` 等 | 实际 UI 用 GpsColors |
| `ui/theme/Type.kt` | 27 | Material Typography 默认定义 | `Typography` | — |
| `ui/theme/Theme.kt` | 57 | 应用主题入口（含动态配色） | `GPS工具箱Theme` | 实际界面多为纯黑背景硬编码 |
| `ui/theme/GpsColors.kt` | 27 | 原版 gps_test 配色集中定义 | `GpsOn/GpsOff/Dati/NavigationColor` 等 | 改全局色值/文案色优先看这里 |

#### 7. 测试

| 文件 | 行数 | 作用 | 关键成员 | 备注 |
|---|---|---|---|---|
| `app/src/test/java/.../ExampleUnitTest.kt` | 16 | 占位本地单测（2+2） | `addition_isCorrect` | 无业务测试，改完靠 `./gradlew :app:assembleDebug` 验证 |
| `app/src/androidTest/java/.../ExampleInstrumentedTest.kt` | 23 | 占位插桩测试（校验包名） | `useAppContext` | — |

#### 8. 构建与配置

| 文件 | 行数 | 作用 | 关键成员 | 备注 |
|---|---|---|---|---|
| `build.gradle.kts`（根） | 4 | 顶级插件声明（apply false） | `android.application` / `kotlin.compose` | — |
| `app/build.gradle.kts` | 81 | 模块构建：签名、compileSdk=37、minSdk=28、依赖、release 优化 | `projectKey` 签名 / `resourceConfigurations` | 签名读 `../signing/keystore.properties`；仅留 zh/en 资源缩包；release 开 R8 优化+自定义 keep |
| `settings.gradle.kts` | 26 | 仓库与模块包含 | `rootProject.name="GPS 工具箱"` / `include(":app")` | 配置缓存/并行策略在 gradle.properties |
| `gradle.properties` | 20 | 全局 Gradle 设置 | `org.gradle.jvmargs` / `configuration-cache=true` | JDK 指向 Android Studio JBR；本机 JDK25 |
| `gradle/libs.versions.toml` | 32 | 依赖版本目录 | `agp=9.3.0` / `kotlin=2.2.10` / `composeBom` | 升级依赖改这里 |
| `gradle/wrapper/gradle-wrapper.properties` | 9 | Gradle 包装版本（9.5.0） | `distributionUrl` | — |
| `gradle/gradle-daemon-jvm.properties` | 12 | Daemon JVM 工具链（25） | `toolchainVersion=25` | — |
| `local.properties` | 9 | 本地 SDK 路径（自动生成，勿提交） | `sdk.dir` | 已被 .gitignore 忽略 |
| `app/.gitignore` | 0 | 空 | — | — |
| `.gitignore` | 15 | 仓库忽略规则（iml/.gradle/build/local.properties 等） | — | — |
| `gradlew` / `gradlew.bat` | 251/94 | Gradle 包装启动脚本（POSIX / Windows） | — | 构建入口：`./gradlew :app:assembleDebug` |

#### 9. 清单与资源

| 文件 | 行数 | 作用 | 关键成员 | 备注 |
|---|---|---|---|---|
| `app/src/main/AndroidManifest.xml` | 62 | 权限声明 + Activity + 保活服务注册 | `FOREGROUND_SERVICE_LOCATION` / `LocationKeepAliveService` | 声明定位/网络/前台服务/通知/唤醒锁/MANAGE_EXTERNAL_STORAGE；服务 `foregroundServiceType="location"`；`allowBackup=true` |
| `app/src/main/keepRules/rules.keep` | 11 | R8 自定义 keep（当前仅注释模板） | — | 改 release 混淆后若崩，追加规则于此 |
| `res/values/strings.xml` | 3 | 应用名 `GPS 工具箱` | `app_name` | — |
| `res/values/themes.xml` | 4 | 应用主题（Material Light NoActionBar） | `Theme.GPS工具箱` | — |
| `res/values/colors.xml` | 9 | 默认色（基本未用，UI 走 GpsColors） | — | — |
| `res/xml/backup_rules.xml` | 13 | 云备份规则（全注释） | — | — |
| `res/xml/data_extraction_rules.xml` | 19 | 设备传输/云备份提取规则（空） | — | — |
| `res/drawable/*` | — | 关键图：`compass_dial.png`(表盘)、`compass_needle.png`(指针)、`radar.png`、`sky_sat.png`、`satellite_cn/jp/ru/us/xx.png`(国旗，现多被 emoji 替代)、`flag_zh.png`、`logo.png`；另有 `ic_launcher_*`(矢量前景/背景)、`ic_account_box/favorite/home.xml`(自适应导航图标) | `compass_dial/needle` 被 CompassPage 用 | 新增图标注意 densities |
| `res/mipmap-*/` | — | 启动图标：各密度 `ic_launcher.webp`/`ic_launcher_fg.png`/`ic_launcher_round.webp` + `mipmap-anydpi` 自适应 xml | — | 由 `.workbuddy/make_icon.py` 生成 |
| `res/font/gps_test.ttf` 与 `assets/fonts/gps_test.ttf` | — | 自定义图标字体（顶栏/表头/底栏字形 Z O Q R l S G t T W L D K H 等），两处内容相同 | `IconFont` 引用 | 改"字形图标"需改字体文件本身 |

#### 10. 脚本与工具（.workbuddy）

| 文件 | 行数 | 作用 | 备注 |
|---|---|---|---|
| `.workbuddy/make_icon.py` | 84 | 生成应用图标：源图抠底→透明 glyph→拼长图裁 1024→各密度 mipmap 前景/旧版 webp | 产物写入 `res/mipmap-*`（非临时） |
| `.workbuddy/bbox.py` | 84 | 纯 Python 解码 PNG 求非黑像素包围盒与四边距（无 Pillow 时用） | 验证 UI 截图用 |
| `.workbuddy/cache/parse_ui.py` | 18 | 解析 adb uiautomator 导出的 `ui*.xml`，打印带 text/bounds 的节点 | **`.workbuddy/cache/*.png` 与 `ui*.xml` 为临时验证产物**，非源码，勿改 |

#### 11. 文档

| 文件 | 行数 | 作用 | 备注 |
|---|---|---|---|
| `TASKS_记录保存与历史.md` | 114 | 需求/任务记录：CSV 格式规范、历史列表格式、T1~T9 任务清单、真机验证清单 | 项目需求基线，改"保存/历史"相关逻辑应先对照此文 |

---

### GPS 项目速查（改 X 去哪个文件）

1. **改速度单位换算 / 仪表盘分段配色 / 上限档位（骑行60…飞机1500）** → `ui/pages/NavigationPage.kt`（`speedFrac`/`needleColorAt`/`SPEED_MODES`/`shiftSpeedMode`）
2. **改行驶记录 CSV 写入格式、删除、历史列表统计口径** → `gps/RecordStore.kt`（首行/末行/缓冲/解析）
3. **改图表曲线平滑算法或缓存目录/失效策略** → `gps/ChartCache.kt`（`EMA_ALPHA`/`POINTS`/`build`）
4. **改后台保活 / 1min 停服务 / 10min 退出 / 关机保存** → `MainActivity.kt` + `LocationKeepAliveService.kt`
5. **改卫星信号表布局、SNR 条颜色、国家 emoji、PRN 编码** → `ui/pages/HistogramPage.kt` + `gps/GpsEngine.kt`（`constellationEmoji`/`legacyPrn`）
6. **改指南针表盘/指针、磁偏角** → `ui/pages/CompassPage.kt` + `gps/CompassEngine.kt`
7. **改备份恢复路径/权限/zip 打包** → `gps/RecordBackup.kt` + `ui/components/BackupRestoreDialog.kt`
8. **改全局配色或字号缩放基准** → `ui/theme/GpsColors.kt` / `ui/ScreenScale.kt`（`DESIGN_W_DP`）

