# OmniOcular 配置更新流程

对应代码：`UpstreamConfigHandler`（下载/落盘）、`XMLConfigHandler`（合并/解析）、
`XMLConfigMessageHandler`（服务端下发）、`Config`（配置项）。

## 触发点

| 触发 | 入口 | `force` | 受 `gtnhConfigAutoUpdate` / GTNH 检测约束 |
| --- | --- | --- | --- |
| 模组 init（客户端与服务端都跑） | `CommonProxy#init` | `false` | 是 |
| `/oo update`（客户端命令） | `CommandLookFor#processCommand` | `true` | 否（开发环境也能手动跑） |
| `/oor update`（服务端命令） | `CommandReloadConfig#processCommand` | `true` | 否（同上；完成后把新配置推给在线玩家） |

## 一、更新主流程

```mermaid
flowchart TD
    A1["CommonProxy.init（客户端与服务端）<br/>UpstreamConfigHandler.update(false)"]
    A2["客户端 /oo update<br/>UpstreamConfigHandler.update(true)"]
    A3["服务端 /oor update<br/>UpstreamConfigHandler.update(true, 完成回调)"]
    L{"单飞锁 running<br/>已有更新在跑？"}
    F1{"force == false ?"}
    F2{"Config.gtnhConfigAutoUpdate ?"}
    F3{"Loader.isModLoaded('dreamcraft')<br/>（GTNH 核心模组）?"}
    D{"config/OmniOcular/ 目录可用？"}

    A1 --> L
    A2 --> L
    A3 --> L
    L -- 是 --> L1["日志：忽略本次请求"] --> Z([结束])
    L -- 否 --> TH["起守护线程 OmniOcular-Config-Updater<br/>updateNow(force)，不阻塞游戏加载"]
    TH --> F1
    F1 -- 是 --> F2
    F2 -- false --> E1["日志：自动更新已关，不下载"] --> Z
    F2 -- true --> F3
    F3 -- 否 --> E2["日志：非 GTNH 整合包，不下载"] --> Z
    F3 -- 是 --> D
    F1 -- 否（强制） --> D
    D -- 否 --> E3["错误日志，不下载"] --> Z

    subgraph SRC["确定下载源与文件清单"]
        S1["sources = normalize(gtnhConfigRepo)<br/>+ gtnhConfigRepoMirrors<br/>（跳过空项、去重，顺序即重试顺序）"]
        S2{"gtnhConfigListingUrl 非空？"}
        S3["用配置里的清单接口"]
        S4["按源自动推断：<br/>raw.githubusercontent.com/OWNER/REPO/REF/<br/>cdn.jsdelivr.net/gh/OWNER/REPO@REF/<br/>→ data.jsdelivr.com/v1/packages/gh/…?structure=flat"]
        S5["请求并解析清单<br/>（jsdelivr flat 或 Gitee API v5 contents）"]
        S6["退回内置清单 FALLBACK_FILE_NAMES"]
        S1 --> S2
        S2 -- 是 --> S3
        S3 --> S5
        S2 -- 否 --> S4
        S4 --> S5
        S5 -- "解析出 .xml" --> S7["repoNames：本次要同步的文件名"]
        S5 -- "推不出 / 请求失败 / 结果为空" --> S6
        S6 --> S7
    end
    D -- 是 --> S1

    subgraph DL["逐文件下载（按 repoNames 顺序）"]
        DS["对每个文件：依次尝试 sources 里的每个源"]
        DS1{"下载成功 ∧ isConfigXml 通过？<br/>非空、≤2MiB、BOM/空白后以 &lt; 开头且含 &lt;oo"}
        DS2["收下该文件内容"]
        DS3{"还有备用源？"}
        DFAIL["放弃整次更新<br/>日志 + 聊天栏 UpdateFailed<br/>本地配置原样不动（不部分覆盖）"]
        DS --> DS1
        DS1 -- 是 --> DS2
        DS1 -- 否 --> DS3
        DS3 -- 是 --> DS
        DS3 -- 否 --> DFAIL
    end
    S7 --> DS
    DFAIL --> Z

    SY["syncConfigs(dir, contents, bundledNames)"]
    SY1["逐文件比内容：与本地不同的记入 changed"]
    SY2["toDisable =（本地已存在 ∩ 模组自带）− 上游清单<br/>再排除已经是占位文件的，避免每次启动都算变更"]
    SY3{"changed 或 toDisable 非空？"}
    UP["日志 + 聊天栏：已是最新"]
    B1["备份：删掉旧的 oo-backup/ 重建，复制目录下所有 *.xml<br/>（只保留本次改动前的一份）"]
    B2{"备份成功？"}
    BFAIL["聊天栏 UpdateFailed<br/>本地配置原样不动"]
    W1["覆盖写 changed 文件<br/>给 toDisable 写空 &lt;oo/&gt; 占位文件"]
    W2{"写盘成功？"}
    R1["XMLConfigHandler.mergeConfig()<br/>重读全部 *.xml → 拼成 &lt;root&gt;…&lt;/root&gt; → 转义"]
    R2{"当前是客户端？"}
    R3["parseConfigFiles()：重建 JSEngine、<br/>重填 entity/tileentity/tooltip pattern 与 setting"]
    R4["跳过解析：服务端没有 JSEngine 管理器<br/>（只在 ClientProxy.preInit 里创建）"]
    R5["日志 + 聊天栏 UpdateResult<br/>（写入 N 个、停用 M 个）"]

    DS2 --> SY
    SY --> SY1
    SY1 --> SY2
    SY2 --> SY3
    SY3 -- 否 --> UP --> Z
    SY3 -- 是 --> B1
    B1 --> B2
    B2 -- 否 --> BFAIL --> Z
    B2 -- 是 --> W1
    W1 --> W2
    W2 -- 否 --> BFAIL
    W2 -- 是 --> R1
    R1 --> R2
    R2 -- 是 --> R3
    R2 -- "否（专用服务端）" --> R4
    R3 --> R5
    R4 --> R5
    R5 --> CB{"由 /oor update 触发？"}
    CB -- 否 --> Z
    CB -- 是 --> CB1["完成后回调：XMLConfigEventHandler.requestPush()<br/>（后台线程只置位，不发包）"]
    CB1 --> CB2["服务端 tick：pushMergedConfigToAllPlayers()<br/>mergeConfig() → 分片下发 mergedConfig 给所有在线玩家"]
    CB2 --> Z
```

## 二、占位文件为什么是空 `<oo/>` 而不是删文件

`releasePreConfigFiles` 只在**文件不存在**时释放 jar 内置配置，删掉的话下次启动会被塞回来；
而内置 `GregTech5U.xml` 与仓库 `GregTech5U-GTNH.xml` 都定义了 `id="BaseMetaTileEntity"`，
`JSEngine` 会对所有匹配 pattern 都执行（没有 break），两者共存会让 GT 机器每行 tooltip 显示两遍。
写占位文件让"文件存在"成立 → 内置版本不再释放，空 `<oo/>` 又不会贡献任何规则。

## 三、更新结果如何到达每一端

```mermaid
flowchart TD
    subgraph CLI["客户端启动"]
        C0["preInit：initConfigFiles()<br/>建 config/OmniOcular/"] --> C1["init：super.init → update(false)"]
        C1 --> C2["init：releasePreConfigFiles()<br/>jar 内置配置 → 仅当文件不存在时释放"]
        C2 --> C3["init：mergeConfig()"]
        C3 --> C4["postInit：parseConfigFiles()<br/>+ Config.preprocess()"]
    end

    subgraph SRV["服务端启动"]
        V0["preInit：initConfigFiles()"] --> V1["init：super.init → update(false)"]
        V1 --> V2["init：mergeConfig()<br/>（配置目录为空时得到空 &lt;root&gt;&lt;/root&gt;）"]
        V3["跳过解析：服务端没有 JSEngine 管理器"]
        V2 -. "跳过解析" .-> V3
    end

    V2 --> P["PlayerLoggedInEvent"]
    P --> P1{"专用服务端 ∧ sendToClientXML ∧ hasRules() ?"}
    P1 -- 否 --> P2["不下发"]
    P1 -- 是 --> P3["按 10KB 分片发送 mergedConfig<br/>__START__ … __END__"]
    P3 --> Q["客户端 recvConfigString()"]
    Q --> Q1{"forceUseClientXml ?"}
    Q1 -- 是 --> Q2["忽略服务端配置"]
    Q1 -- 否 --> Q3{"分片完整 ∧ hasRules() ?"}
    Q3 -- 否 --> Q4["丢弃：空配置等于清空本地规则"]
    Q3 -- 是 --> Q5["mergedConfig = 收到的内容<br/>parseConfigFiles()"]
```

## 四、其它相关命令

| 命令 | 端 | 行为 |
| --- | --- | --- |
| `/oo update` | 客户端 | 强制跑一次上游更新（忽略开关与 GTNH 检测），已在跑则聊天栏提示 |
| `/oo reload` | 客户端 | 只跑 `Config.preprocess()`（重算黑名单方块），不重新合并/下载 |
| `/oor` | 服务端（权限 3） | `mergeConfig()` 后把新配置下发给所有在线玩家；空配置同样拦下不发 |
| `/oor update` | 服务端（权限 3） | 强制跑一次上游更新；完成后由服务端 tick 重新 merge 并推给在线玩家。更新失败时内容未变，等于空刷一遍配置 |

`/oor update` 解决的是"服务器配置更新了、在线玩家手里还是旧规则"：更新在后台线程跑
（不能阻塞服务端），推送必须回到服务端线程（`playerEntityList` 不线程安全），
所以用 `requestPush()` 置位、tick 里做。

客户端的 `forceUseClientXml` 打开后完全不用服务端下发的配置；`sendToClientXML` 是服务端侧的开关。

## 五、配置示例：指向 Gitee 镜像

国内直连 GitHub 困难时，可以把更新源指向 Gitee 镜像（`config/OmniOcular.cfg`）：

```properties
gtnhConfigRepo=https://gitee.com/luomolhx/GTNH_OmniOcular/raw/master/
gtnhConfigListingUrl=https://gitee.com/api/v5/repos/luomolhx/GTNH_OmniOcular/contents/?ref=master
gtnhConfigRepoMirrors=   # 留空；或填默认仓库的 jsdelivr 地址作为回退
```

- 仓库必须**公开**：私有仓库的 raw 与 API 都要 token，本模组不带认证。
- raw 地址会 302 到 `raw.giteeusercontent.com`，`HttpURLConnection` 同协议自动跟随（已实测 200）。
- Gitee 的 WAF **按 UA 拦截**：旧版 IE UA 直接 403，`HttpUtil` 已改用现代浏览器 UA。
- Gitee API 匿名限流 60 次/小时/IP，每次更新只调一次；拿不到清单时退回内置清单
  （当前镜像的 24 个文件与内置清单完全一致，只是以后新增文件要等发版）。
- `parseFileNames` 同时认 Gitee contents 的数组响应与 jsdelivr 的 flat 响应。
