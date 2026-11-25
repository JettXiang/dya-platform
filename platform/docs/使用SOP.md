# 采集系统使用 SOP v0.1

适用：42 批实时采集与上传平台 + 抖音采集扩展
更新：2026-11-10

---

## 0. 这套系统由什么组成

| 部件 | 位置 | 作用 |
| --- | --- | --- |
| 平台（前端/后端/数据库） | WSL `Ubuntu-24.04` 里的 Docker：`deploy-web-1` / `deploy-backend-1` / `deploy-mysql-1` | 收数据、看板、查询 |
| 平台入口 | <http://127.0.0.1:19203> | 浏览器打开即用，账号 `INTERN01` / `admin123` |
| 采集扩展 | Chrome 扩展「抖音AI合集作者采集」，源码 `C:\dyaccount-live\dyaccount_v1.3.8-正式版` | 在抖音精选页扫作者/视频并推给平台，同时每秒回传页面画面 |
| WSL 保活 | 启动文件夹 `dya-wsl-keepalive.vbs` → `platform\scripts\wsl-keepalive.cmd` | 防止 WSL 被闲置回收，导致平台容器重建、页面掉线 |

一句话链路：抖音网页 →（扩展扫描）→ `/api/v1/collect/ingest` → MySQL → 今日总览。
画面链路：抖音网页 →（扩展 `captureVisibleTab`）→ `/api/v1/collect/frame` → 平台「抖音采集」页 LIVE FRAME。

---

## 1. 开机后要做的事

### 1.1 正常情况：什么都不用做

登录 Windows 后约 1 分钟内，启动项会自动执行：

1. 拉起 WSL 发行版
2. 等 dockerd 就绪（最多 60 秒）
3. `docker compose up -d`
4. 挂 `sleep infinity` 保活，让发行版不被回收

**自检三条**（PowerShell，逐条粘）：

```powershell
wsl -d Ubuntu-24.04 -- bash -c "docker ps --format '{{.Names}} {{.Status}}'"
```
预期三行：`deploy-mysql-1 Up (healthy)`、`deploy-backend-1 Up`、`deploy-web-1 Up`

```powershell
(Invoke-WebRequest http://127.0.0.1:19203/ -UseBasicParsing -TimeoutSec 8).StatusCode
```
预期 `200`

```powershell
Get-CimInstance Win32_Process -Filter "Name='wsl.exe'" | Measure-Object | Select-Object -ExpandProperty Count
```
预期 ≥ 1（保活进程在）

### 1.2 页面打不开（ERR_CONNECTION_REFUSED / 502）

手动跑一次保活脚本：

```powershell
wscript "$env:APPDATA\Microsoft\Windows\Start Menu\Programs\Startup\dya-wsl-keepalive.vbs"
```

等 30~60 秒后重跑 1.1 的自检。仍不行看后端日志：

```powershell
wsl -d Ubuntu-24.04 -- bash -c "docker logs --tail 80 deploy-backend-1"
```

### 1.3 Chrome 这边

- 打开 `https://www.douyin.com/jingxuan`（扩展只在 `/jingxuan` 或搜索页工作）
- 点扩展图标，确认采集开关是开的
- 如果刚重启过浏览器或重载过扩展，对抖音页按一次 **F5**
- 抖音标签不要最小化：最小化后平台画面会停在最后一帧（数据采集不受影响）

---

## 2. 日常怎么用

1. 打开 <http://127.0.0.1:19203>，登录 `INTERN01` / `admin123`
2. 「今日总览」看 **LIVE INGEST · 扩展直传** 四格：今日入库视频 / 今日去重作者 / 最近入库时间 / 扩展最近心跳
3. 「抖音采集」页看 **LIVE FRAME**，每秒一帧的抖音页面画面
4. 批次、日终、导出等仍按原来的批次流程走；扩展直传不产生排班/批次记录，总览下方两张表显示「暂无批次」是正常的

**判断是否正常**（三个指标同时满足即可）：

- 今日入库视频：几分钟内应该在涨
- 最近入库时间：距今 < 5 分钟
- 扩展最近心跳：< 90 秒（超了会显示「扩展离线」）

### 2.1 采集工作区（遥控自己电脑上的抖音页）

入口：左侧「实时采集区 → **采集工作区**」。

这是一个遥控台：你的电脑上开着抖音页，在平台上点按钮就能让那个页面下滑、搜索、刷新，不用切回浏览器。原理是扩展每 2.5 秒问一次平台「有没有新指令」，指令全部在你自己的 Chrome 里执行——**只遥控自己这台电脑，别人看不到你的页面，也替你操作不了**。

**第一次进来先选设备**：下拉里形如 `dev-xxxxxxxx`，由扩展首次运行时生成并存在本机；重装扩展会变。显示「在线」的那条就是你的。

**页面结构**：

- 左上 LIVE FRAME：每秒一帧的抖音画面
- 中间一排按钮
- 右下「最近执行」：最近 20 条指令与结果，点完 2~3 秒后出现

**按钮对应关系**：

| 按钮 | 效果 | 注意 |
| --- | --- | --- |
| 下滑一屏 | 页面下滚一屏 | 单次 |
| 持续下滑 N 秒 | 每 0.9 秒滚一次，到点自停 | 连续刷时用 |
| 停止下滑 | 立即停 | 只停滚屏，不停止采集 |
| 回顶部 | 滚回页面顶部 | |
| 关键词搜索 | 跳到抖音搜索页 | 会离开当前精选页，中文直接输 |
| 刷新页面 | 原地 F5 | 采集会中断十几秒后自动续上 |
| 采集开关 | 采集 + 自动下滑一起开/关 | 关闭后不再扫描、不再翻页 |
| 清缓存 | 清本地去重集合（已匹配作者、已发布记录） | **慎用**，已采过的账号可能重复入库 |

**页面状态怎么读**：

- `采集已自动停止`（红字）：触发了限流、账号登出或连续无响应，系统自己停的，红字后面就是原因
- 点「采集开关」开启时会顺带清掉这个停机标记，等于人工接管
- 心跳约 8 秒一次，数字基本实时；如果还停在 30 秒以上，是扩展没重载（走第 4 节）

**多标签**：只有当前**可见**的抖音标签会执行指令，后台标签拿不到，所以开多个也不会抢。

**命令行验证**（不想开浏览器时）：

```powershell
$t=(Invoke-RestMethod -Uri http://127.0.0.1:19203/api/v1/auth/login -Method Post -ContentType 'application/json' -Body '{"code":"INTERN01","password":"admin123"}').data.accessToken
Invoke-RestMethod -Uri http://127.0.0.1:19203/api/v1/collect/status -Headers @{Authorization="Bearer $t"} | ConvertTo-Json -Depth 4
```

看 `deviceId / online / ageMs / collecting / autoBrowse / scanned / fatal / lastHint` 几个字段就够了。

> 不要用 PowerShell 直接发中文关键词搜索：Windows PowerShell 5 默认不是 UTF-8，中文会变成 `??`。中文一律在平台页面上点。

### 2.2 汇总上传（把作者推给上游）

入口：左侧「实时采集区 → **汇总上传**」。

这一页解决「哪些作者已经推过、哪些还没推」。数据来自 `videos` 表，按 `author_sec_uid` 汇总，**推成功过的作者记进永久台账，不会再出现在待推清单里**。

**怎么用**：

1. 选日期范围（默认今天）→ 可选「采集人」筛选
2. 表格里是按作者汇总的行：sec_uid、昵称、该账号采集到几条视频、采集人、最近采集时间
3. 勾选要推的（或「全选」）→ 点「推送所选（N）」
4. 弹窗确认后一次打包推给上游 `http://43.165.183.142/api/file`，`uploader_id` 用当前登录账号

**结果怎么读**（推送后提示条）：

| 字段 | 含义 |
| --- | --- |
| 提交 N / 实推 M | M 是去掉台账里已推过之后的真实条数；M 小于 N 说明列表过期 |
| 影响视频 X 条 | 这批作者名下的视频被标记为「已上传」 |
| 上游新增 / 上游重复 | 上游接口返回的 `inserted_count` / `remote_duplicate_count`，重复说明上游本来就有 |
| 失败 | 红字给 HTTP 状态或异常；同一批作者下次还会出现在待推清单，可重试 |

**约束**：

- 单次最多 10000 条，超出会被拒；正常按天推不会碰到
- 上游无鉴权、无批次号，也没有自动重试；推送期间不要关页面（请求 120 秒超时）
- 台账（页面下方「推送台账」）记录：推送人、次数、HTTP 状态、上游新增/重复、失败原因

**和扩展自带上传的区别**：扩展弹窗里的「上传」是浏览器直接传给上游、由个人手动触发；本页是中心端汇总后统一推，且做了作者级去重，适合多人采集后合并推送。

---

## 3. 改了平台代码怎么上线

前提：宿主机没有 mvn / node，构建一律用容器。以下命令中的长路径就是当前项目的真实路径，直接粘。

### 3.1 后端打包（产出 `backend/target/collect-backend-0.1.0.jar`）

```powershell
wsl -d Ubuntu-24.04 -- bash -lc "cd '/mnt/c/Users/dataeye/AppData/Roaming/Trae CN/ModularData/ai-agent/work-mode-projects/6aac99a556e079edbea957d7/platform' && docker run --rm -v '/mnt/c/Users/dataeye/AppData/Roaming/Trae CN/ModularData/ai-agent/work-mode-projects/6aac99a556e079edbea957d7/platform':/src -w /src -v maven-repo:/root/.m2 maven:3.9-eclipse-temurin-17 mvn -B -DskipTests package"
```

### 3.2 前端构建（产出 `web/dist`）

```powershell
wsl -d Ubuntu-24.04 -- bash -lc "cd '/mnt/c/Users/dataeye/AppData/Roaming/Trae CN/ModularData/ai-agent/work-mode-projects/6aac99a556e079edbea957d7/platform/web' && docker run --rm -v '/mnt/c/Users/dataeye/AppData/Roaming/Trae CN/ModularData/ai-agent/work-mode-projects/6aac99a556e079edbea957d7/platform/web':/app -w /app -v dya-npm-cache:/root/.npm node:20-alpine sh -c 'npm ci && npm run build'"
```

### 3.3 重建镜像并重启

```powershell
wsl -d Ubuntu-24.04 -- bash -lc "cd '/mnt/c/Users/dataeye/AppData/Roaming/Trae CN/ModularData/ai-agent/work-mode-projects/6aac99a556e079edbea957d7/platform/deploy' && docker compose build backend web && docker compose up -d"
```

只改了前端就跑 3.2 + 3.3，只改了后端就跑 3.1 + 3.3。

> 镜像的 Dockerfile 只拷贝产物（jar / dist），所以**必须先构建再 build 镜像**，否则镜像里还是旧代码。
> 前端更新后浏览器要 **Ctrl+Shift+R** 硬刷，否则看到的是缓存页面。

---

## 4. 改了扩展代码怎么生效

扩展是「加载已解压的扩展」，改磁盘文件**不会**自动生效：

1. Chrome 打开 `chrome://extensions`
2. 找到「抖音AI合集作者采集」→ 点卡片上的 **刷新图标**（重新加载）
3. 回到抖音标签页按 **F5**

漏掉第 2 步 = 改动没生效，这是最容易踩的坑。扩展出错时点卡片上的「错误」按钮看堆栈。

---

## 5. 常见问题对照表

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 平台打不开、连接被拒 | WSL 被回收 / 容器没起 | 跑 1.2 的 vbs，等 30~60 秒后自检 |
| 总览数字不涨，但扩展显示在线、扫描数在涨 | 推送链路报错但心跳、截图还活着（本次根因：`platformSentTotal` 未声明，`flushPlatform()` 里 `+=` 抛 `ReferenceError`，且抛在 `splice` 之后 → 队列被清空、一条没发出去） | 重载扩展 + F5；以后同类问题先看扩展「错误」按钮 |
| 最近入库时间停住几小时 | 同上，数据推送断了 | 同上 |
| 扩展显示离线 | Chrome 没开 / 扩展被禁用 / 抖音页没 F5 | 检查扩展开关，F5 |
| LIVE FRAME 卡住不动 | 抖音标签不是前台可见（最小化或切到别的标签） | 把 Chrome 窗口还原到前台 |
| 改了前端看不到变化 | 浏览器缓存 | Ctrl+Shift+R |
| 改了后端但接口返回没变 | 没重新 build 镜像 | 走第 3 节全流程 |
| 总览「在岗账号 / 当日批次」全是 0 | 扩展直传不写排班/批次表 | 正常现象，看 LIVE INGEST 那排数字 |
| 「采集工作区」下拉里没有设备 / 一直离线 | 扩展没重载，或抖音页没 F5，心跳没带上 `deviceId` | 走第 4 节重载 + F5，等 10 秒重进页面 |
| 遥控点了没反应，或点了开启采集还是「暂停」 | 指令只由当前可见的抖音标签执行；页面不在前台，或触发了限流/登出/连续无响应自动停机 | 把抖音标签切到前台；看面板红字提示的原因；再点一次采集开关（会清掉停机标记） |
| 遥控按钮 2~3 秒才生效 | 扩展每 2.5 秒轮询一次指令 | 正常，不是卡了 |

---

## 6. 命令速查

| 想做什么 | 命令（PowerShell） |
| --- | --- |
| 看容器状态 | `wsl -d Ubuntu-24.04 -- bash -c "docker ps --format '{{.Names}} {{.Status}}'"` |
| 起/重启平台 | `wscript "$env:APPDATA\Microsoft\Windows\Start Menu\Programs\Startup\dya-wsl-keepalive.vbs"` |
| 后端日志 | `wsl -d Ubuntu-24.04 -- bash -c "docker logs --tail 100 deploy-backend-1"` |
| 平台健康检查 | `(Invoke-WebRequest http://127.0.0.1:19203/ -UseBasicParsing).StatusCode` |
| 进 MySQL | `wsl -d Ubuntu-24.04 -- bash -c "docker exec -it deploy-mysql-1 sh -c 'mysql -u\"\$MYSQL_USER\" -p\"\$MYSQL_PASSWORD\" \"\$MYSQL_DATABASE\"'"` |
| 看抖音页最新帧是否在更新 | 平台「抖音采集」页 LIVE FRAME 卡（含「最近一帧 N 秒前」） |

---

## 7. 关键参数

| 项 | 值 |
| --- | --- |
| 平台地址 | <http://127.0.0.1:19203>（`deploy/.env` 的 `WEB_PORT`） |
| 登录账号 | `INTERN01` / `admin123` |
| 采集密钥 | `dya-collect-2026`（后端 `collect.ingest-key`，扩展里必须一致） |
| 数据库 | 库名 `collect_platform`，容器 `deploy-mysql-1`，数据卷 `deploy_mysql-data` |
| 作者推送台账 | 表 `author_uploads`（`sec_uid` 主键，成功即永久去重） |
| 上游上传接口 | `http://43.165.183.142/api/file`，字段 `uploader_id` + `file`，无鉴权，单批 10000 条 |
| 数据核对接口 | `/api/v1/reports/daily?date=YYYY-MM-DD`（`direct_videos` / `direct_authors` / `direct_last_at`）、`/api/v1/collect/status`、`/api/v1/collect/frame` |
| 构建缓存卷 | `maven-repo`（后端依赖）、`dya-npm-cache`（前端依赖），删了会重新下载 |
| 扩展源码目录 | `C:\dyaccount-live\dyaccount_v1.3.8-正式版` |

---

## 8. 已知限制

- 开机自启挂在「启动」文件夹 → 只有**用户登录后**才生效；机器重启但没人登录时平台不会自启（要覆盖这个场景需管理员权限注册「开机时」计划任务）
- 画面回传依赖 Chrome `captureVisibleTab`：只能截**当前前台可见标签**，窗口最小化会停帧
- WSL 保活是靠一个 `sleep infinity` 进程撑着；该进程被杀（或机器重启）后需重跑 1.2 的命令
- 采集工作区第一版只做本机遥控：平台跑在 `127.0.0.1:19203`，同事的电脑要用自己的平台实例（或把扩展里的 `platformUrl` 指向能访问的地址）
- 遥控指令存在后端内存里，保留 120 秒；后端重启期间发出的指令会丢，重发即可
- 已发布的视频与作者去重集合存在浏览器本地，清缓存/重装扩展后会重建，可能造成少量重复入库
