# OmniOcular-Unofficial 代码分析报告

| 项 | 值 |
| --- | --- |
| 分析对象 | `E:\OmniOcular-Unofficial` |
| 基线提交 | `181ff848f5d5597da3b708c095b865f0ed308af9` — "Remove unused repositories"（2025-07-25） |
| 分析范围 | `src/` 全部 91 个受版本控制文件，其中 Java 28 个 / 约 2850 行，XML 脚本 36 个 / 约 3700 行 |
| 分析日期 | 2026-09-26 |
| 分析方式 | 静态通读，未编译、未运行 |

---

## 1. 项目定位

**OmniOcular-Unofficial** 是 Minecraft 1.7.10 / Forge 的 **WAILA 扩展模组**。

它自身不实现 HUD 渲染，而是挂接进 WAILA（方块/实体）与 NEI（物品 tooltip）的现有渲染管线，用**外部脚本**描述"这个方块/实体/物品要显示哪些信息行"。

核心卖点（据 `README.md`）：

1. 强制客户端脚本 / 服务端可选不下发脚本
2. 各项显示内容可开关
3. **黑名单**：把 NBT 数据量大的方块拉黑以避免卡顿
4. **Java 编写的脚本**：复杂逻辑用 Java 写以提升性能
5. **激进缓存**：优化 FPS

### 1.1 与上游的关系

- 目录名与 `mcmod.info` 的 `url` 指向 `wohaopa/OmniOcular-Unofficial`
- 包名仍为原始作者的 `me.exz.omniocular`，`modId` 为 `OmniOcularUnofficial`，但 `Reference.OLD_MOD_ID` 保留 `OmniOcular` 用于配置文件目录兼容
- `mcmod.info` 的 `authorList` 只写了 `Epix`，未反映 GTNH 分支的改动
- 缺少 `LICENSE` 归属说明与上游差异说明（`LICENSE` 存在但未在 README 中引用）

---

## 2. 技术栈与构建

| 项 | 值 |
| --- | --- |
| Minecraft | 1.7.10 |
| Forge | 10.13.4.1614 |
| MCP | channel `stable`，mappings `12` |
| 构建系统 | RetroFuturaGradle，`com.gtnewhorizons.gtnhconvention`（`build.gradle.kts` 仅 3 行） |
| Settings 插件 | `com.gtnewhorizons.gtnhsettingsconvention` `1.0.38` |
| Spotless | 启用，`gtnh.settings.blowdryerTag = 0.2.2` |
| Java | Jabel 允许编译期使用 Java 17 语法（`enableModernJavaSyntax = true`），目标 JVM 8 |
| Mixins | 启用，使用 **GTNHMixins 的 Late Mixin**（`LateMixinPlugin`），包名 `mixins` |
| Access Transformer | `META-INF/OmniOcular_at.cfg`（4 条） |
| CI | GitHub Actions 复用 `GTNewHorizons/GTNH-Actions-Workflows`；`.travis.yml` 为遗留文件 |
| 测试 | **无** `src/test` 目录，CI 仅执行 GTNH 通用构建流程 |

### 2.1 依赖（`dependencies.gradle`）

```groovy
implementation("com.github.GTNewHorizons:NotEnoughItems:2.6.26-GTNH:dev")
implementation("com.github.GTNewHorizons:waila:1.8.1:dev")
runtimeOnly('com.github.GTNewHorizons:GT5-Unofficial:5.09.49.16:dev')
runtimeOnly('com.github.GTNewHorizons:ForgeMultipart:1.5.0:dev')
```

`@Mod(dependencies = "required-after:Waila;required-after:NotEnoughItems")` 将 Waila 与 NEI 声明为**硬依赖**，因此**服务端也必须装 NEI**（NEI 在 1.7.10 有服务端）。两者在代码中均为 `implementation`，但 WAILA 的 API（`mcp.mobius.waila.api.*`）出现在 `TileEntityHandler` / `EntityHandler` 的 public 签名上，严格来说是 `api` 语义。

GT5-Unofficial 与 ForgeMultipart 是 `runtimeOnly`，只在开发环境用于测试内置脚本。

---

## 3. 架构

### 3.1 分层

```
me.exz.omniocular
├── OmniOcular.java            模组入口 @Mod
├── IScript.java               Java 脚本接口（4 种类型 + 匹配 Pattern）
├── LateMixinPlugin.java       GTNHMixins Late Mixin 加载器
├── reference/Reference.java   常量（MOD_ID / MOD_NAME / VERSION 由 Gradle token 注入）
├── config/Config.java         Forge Configuration 封装 + 黑名单预处理
├── proxy/                     三态代理：Common / Client / Server
├── handler/
│   ├── XMLConfigHandler.java      XML 释放 / 合并 / 解析（3 张 Pattern→Node 表）
│   ├── ScriptEngineHandler.java   Nashorn 引擎探测与自举（含下载）
│   └── XMLConfigEventHandler.java 玩家登录时下发配置
├── network/                    SimpleNetworkWrapper 分片传输
├── waila/
│   ├── JSEngine.java          JS 执行核心 + 渲染缓存 + JS 侧 API
│   ├── PluginEngine.java      Java IScript 注册与分发
│   ├── TileEntityHandler.java WAILA 方块 provider
│   ├── EntityHandler.java     WAILA 实体 provider
│   ├── FMPHandler.java        ForgeMultipart 桥接
│   └── TooltipHandler.java    NEI 物品 tooltip
├── mixins/MixinHUDHandlerFMP.java  注入 WAILA 的 FMP handler
├── client/command/CommandLookFor.java  /oo 命令（客户端）
├── command/CommandReloadConfig.java    /oor 命令（服务端）
├── client/gui/                 Forge GuiConfig 接入
├── scripts/                    GTNHScript（示例）+ Utility（颜色常量）
└── util/                       NBTHelper / NBTSerializer / LogHelper
```

### 3.2 启动数据流

```
FMLPreInitialization
  CommonProxy.preInit
    ├─ Config.initConfig()                 → config/Omni Ocular Unofficial.cfg
    ├─ XMLConfigHandler.initConfigFiles()  → 建目录 config/OmniOcular/
    └─ XMLConfigMessageHandler.network     注册网络通道（仅有 Side.CLIENT 处理器）
  ClientProxy.preInit
    └─ ScriptEngineHandler.initScriptEngineManager()   ← 见 §6.1、§6.2

FMLInitialization
  CommonProxy.init
    ├─ FMLInterModComms → Waila 注册 EntityHandler / TileEntityHandler
    └─ 注册 XMLConfigEventHandler 到 FML bus
  ClientProxy.init
    ├─ 注册 /oo 客户端命令
    ├─ XMLConfigHandler.releasePreConfigFiles()   从 jar 释放内置 XML
    ├─ XMLConfigHandler.mergeConfig()             拼接 + 转义 → mergedConfig
    └─ (ServerProxy 同名调用 mergeConfig())
FMLPostInitialization
  ClientProxy.postInit
    ├─ GuiContainerManager.addTooltipHandler(TooltipHandler)
    ├─ XMLConfigHandler.parseConfigFiles()        ← 建表 + eval <init>/<setting>
    ├─ PluginEngine.init()                        反射实例化 Config.scriptClassName
    └─ Config.preprocess()                        黑名单 → int 集合

FMLServerStarting（双端）
  CommandReloadConfig 注册 /oor（子命令 update：拉上游 + 下发）
```

**关键点：服务端从不调用 `parseConfigFiles()`。** 服务端只做 `mergeConfig()`（拼接原始文本）并通过网络发出；所有脚本求值与显示逻辑都在客户端。这是有意的设计，也让服务端可以零成本地为一个不需要显示任何东西的服务器提供配置。

### 3.3 运行时渲染数据流

```
                          玩家准星指向
                               │
        ┌──────────────────────┼──────────────────────┐
        │                      │                      │
   方块（TE）              实体                 物品（背包内）
        │                      │                      │
 TileEntityHandler      EntityHandler         TooltipHandler
 (Block.class 全局注册) (Entity.class 全局注册) (NEI GuiContainerManager)
        │                      │                      │
        │  accessor.getNBTData()  ← 服务端 writeToNBT 同步
        └──────────────────────┴──────────────────────┘
                               │
                        FMP: MixinHUDHandlerFMP
                        → FMPHandler
                               │
                  ┌────────────┴────────────┐
                  │                         │
        PluginEngine.getWailaBody()   JSEngine.getBody()
        （Java IScript，find 匹配）    （XML+JS，matches 匹配）
                  │                         │
                  └────────────┬────────────┘
                               │
                    currenttip.addAll(...)
```

### 3.4 网络协议

`XMLConfigMessageHandler` 使用 `SimpleNetworkWrapper`，通道名 = `Reference.MOD_ID`，消息 id `0`。

协议是一个**极简的分片流**：

```
进程 1:  "__START__"
进程 2:  "..."          (10240 字符/片)
  ...
进程 N:  "..."          (尾片，非空才发)
进程 N+1:"__END__"
```

- 发送方：`sendConfigString(String, EntityPlayerMP)`
- 接收方：`recvConfigString(String)` 以 `switch` 分发，`default` 分支 append 到 `XMLConfigHandler.stringBuilder`
- `__END__` 时把 `stringBuilder` 赋给 `mergedConfig` 并立即 `parseConfigFiles()`

**该协议没有任何完整性校验、长度上限或序号**。详见 §6.4。

---

## 4. 三种脚本源

这是项目的核心抽象：同一个"显示什么"的需求，有三条互不相同的实现路径。

| 来源 | 存放位置 | 匹配语义 | 求值时机 | 性能 |
| --- | --- | --- | --- | --- |
| **XML + JavaScript** | `config/OmniOcular/*.xml` | `Matcher.matches()` **全串匹配** | 首次遇到该 NBT 时 | 慢（Nashorn） |
| **Java `IScript`** | `Config.scriptClassName` 指定的全类名 | `Matcher.find()` **子串匹配** | 每次缓存未命中 | 快（JIT） |
| **NEI Tooltip** | 同一 XML 的 `<tooltip id="物品注册名">` | 全串匹配 | 每次打开容器 tooltip | 慢 |

### 4.1 XML 脚本格式

```xml
<oo>
    <!-- 全局 JS 函数定义，parseConfigFiles 时 eval 一次 -->
    <init>
        function tick2second(n){return parseInt(n/20)}
    </init>

    <!-- 显示模板。RETURN 被替换为 <line> 的求值结果 -->
    <setting id="displaynameTileentity">
        "DISPLAYNAME" + TAB + ALIGNRIGHT + WHITE + "RETURN"
    </setting>

    <!-- 方块 / TileEntity 规则。id 会经过 Pattern.compile 并全串匹配 -->
    <tileentity id="Furnace">
        <line displayname="Burn Time">
            return tick2second(nbt['BurnTime'])
        </line>
    </tileentity>

    <!-- 实体规则 -->
    <entity id="Sheep">
        <line displayname="Until next love">
            tick2second(nbt['InLove'])
        </line>
    </entity>

    <!-- 物品 tooltip 规则，id 为物品注册名 -->
    <tooltip id="minecraft:skull">
        <line displayname="OwnerName">
            return nbt['SkullOwner']
        </line>
    </tooltip>
</oo>
```

`<line>` 的文本会被包装成 `function S<md5>(){ <body> }` 并缓存到 `JSEngine.scriptSet`。若 body 中不含 `"return"` 字样，会在前面自动补 `return `（`JSEngine.java:108`）——这个启发式规则对 `if(...){return x}` 形式有效，但若脚本中出现 `return` 之外的误判场景（如变量名 `returnValue`）会失效。

### 4.2 JS 侧可用 API

`JSEngine.initEngine()` 注入的全局符号：

**颜色常量**（`setSpecialChar()`）：`BLACK DBLUE DGREEN DAQUA DRED DPURPLE GOLD GRAY DGRAY BLUE GREEN AQUA RED LPURPLE YELLOW WHITE OBF BOLD STRIKE UNDER ITALIC RESET`

**WAILA 特殊符号**：`TAB ALIGNRIGHT ALIGNCENTER HEART HHEART EHEART`

**函数**：

| 函数 | Java 实现 | 说明 |
| --- | --- | --- |
| `translate(t)` | `StatCollector.translateToLocal` | 本地化 |
| `translateFormatted(t, obj)` | `StatCollector.translateToLocalFormatted` | 带参数本地化 |
| `name(nbt)` | `getDisplayName(n.hashCode)` | 由 NBT 反查 ItemStack 显示名 |
| `fluidName(s)` | `getFluidName(s)` | 流体名，小写查找，带缓存 |
| `holding()` | `playerHolding()` | 玩家手持物品注册名 |
| `armor(i)` | `playerArmor(i)` | 护甲槽位 0–3 |
| `isInHotbar(n)` | `haveItemInHotbar` | 快捷栏 0–8 |
| `isInInv(n)` | `haveItemInInventory` | 背包 |

⚠️ 后四个函数读取**静态字段** `JSEngine.entityPlayer`（`JSEngine.java:62`），该字段在每次 `getBody` 入口被覆盖。它们与 §6.3 的缓存组合会产生长期僵死的结果。

`name()` 的实现依赖 `NBTSerializer` 在序列化 `NBTTagCompound` 时**额外注入一个 `hashCode` 字段**（`NBTSerializer.java:75-77`，值为该 Java 对象的 `identityHashCode`），JS 侧 `n.hashCode` 读的正是它。这是一个隐式契约：任何改动 `NBTSerializer` 输出结构的行为都会静默破坏 `name()`。

### 4.3 内置 XML 规则覆盖范围（36 个文件）

| 规模 | 文件 |
| --- | --- |
| >200 行 | `IC2.xml`(553)、`Mekanism.xml`(307)、`LogisticsPipes.xml`(226)、`MineFactoryReloaded.xml`(210) |
| 100–200 行 | `TechReborn`、`GregTech5U`、`GalacticraftCore`、`Railcraft`、`Thaumcraft`、`BuildCraftCore`、`EMT` 等 |
| 小文件 | `OmniOcular.xml`（仅定义 `displaynameTileentity` 模板） |

共 **330 条唯一 id 规则**。

---

## 5. 配置项参考

配置文件：`config/Omni Ocular Unofficial.cfg`（`config/Config.java:46`，`Reference.MOD_NAME + ".cfg"`）

| 键 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `enableEntityInfo` | bool | `true` | 实体信息总开关 |
| `enableFMPInfo` | bool | `true` | ForgeMultipart 信息总开关 |
| `enableTileEntityInfo` | bool | `true` | 方块/TE 信息总开关 |
| `enableTooltipInfo` | bool | `true` | NEI 物品 tooltip 总开关 |
| `sendToClientXML` | bool | `true` | **服务端**是否向客户端下发 XML |
| `forceUseClientXml` | bool | `false` | **客户端**是否拒绝服务端下发的 XML |
| `blackTileEntityNames` | string[] | `[]` | 形如 `minecraft:furnace@0`，`@meta` 可省略 |
| `blackEntityNames` | string[] | `["net.minecraft.entity.player.EntityPlayer"]` | 实体类全名 |
| `scriptClassName` | string[] | `[]` | Java 脚本类全名，如 `me.exz.omniocular.scripts.GTNHScript` |

黑名单在 `Config.preprocess()`（`Config.java:105`）中被编码为 `blockId << 16 | meta`，与 `TileEntityHandler.java:66` 的查询式一致。

---

## 6. 问题清单

### 🔴 6.1 客户端可被服务端远程执行任意代码

**位置**：`handler/XMLConfigHandler.java:210`、`:217` ← `network/XMLConfigMessageHandler.java:48`

`parseConfigFiles()` 对 `<init>` 块和 `<setting>` 块直接调用 `JSEngine.engine.eval(...)`。而 `parseConfigFiles()` 会在**收到服务端数据包**时被触发（`XMLConfigMessageHandler.java:51`）。XML 内容因此成为代码。

Nashorn 默认暴露 `Java.type('java.lang.Runtime')`、`java.lang.ProcessBuilder` 等，等价于：

```
连上恶意服务器 → 客户端 JVM 内任意命令执行
```

唯一防线是客户端配置 `forceUseClientXml`，检查点在 `XMLConfigMessageHandler.java:44`：

```java
private static void recvConfigString(String string) {
    if (Config.forceUseClientXml) return;
    ...
}
```

**问题在于**：该开关默认 `false`（放行），且是纯客户端自愿的选择。README 中"客户端将主动拒绝服务端发来的脚本"描述准确，但没有告知用户**默认状态是接受**。

**建议**：
- 至少在首次连接陌生服务器时向用户显式提示
- 或将默认值改为 `true`，把"接受服务端脚本"变为显式选择加入（opt-in）
- 长期看可考虑在求值前对 `Java.type` / `JavaImporter` / `load` 等符号做沙箱屏蔽

这是模组的设计意图（脚本化本身就需要求值），因此**不是需要"修掉"的 bug，而是需要明确暴露的安全边界**。

---

### 🔴 6.2 明文 HTTP 下载 jar 并注入系统类加载器

**位置**：`handler/ScriptEngineHandler.java:27-56`（下载）、`:58-60`（仓库列表）、`:108-125`（类加载器注入）

当 JVM 为 Java 11+ 且类路径上没有 `org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory` 时，模组会**从网络下载** `nashorn-core-15.4.jar` 到 `mods/oo/`。

三个独立问题叠加：

1. **明文传输**：`repos` 列表中混有 `http://maven.aliyun.com`、`http://maven.netease.com/repository/public/`，连接可被中间人篡改。
2. **零校验**：没有 SHA-256、没有签名验证，落盘内容即被信任。
3. **注入 AppClassLoader**：`ScriptEngineHandler.java:112-124` 通过反射调用 `ClassLoader.appendToClassPathForInstrumentation`，把下载的 jar 塞进**系统类加载器**（最高优先级）。被投毒的 jar 将影响整个游戏进程，而不是被限制在模组的类加载器内。

此外 `downLoadFromUrl` 本身也有缺陷：

- `:29` 设置了 `setConnectTimeout` 但**没有 `setReadTimeout`**，对端不响应时会挂起
- `:35` 直接取 `getInputStream()`，未检查 HTTP 响应码；返回 404/403 页面时会**把错误页面当作 jar 写盘**
- `:52-55` 异常路径上 `FileOutputStream` / `InputStream` 不会关闭（无 try-with-resources）
- 下载失败时 `:102` 调用 `FMLCommonHandler.exitJava(-1, false)` **直接强杀游戏**，而非降级为"无 JS 支持"

**建议**：
- 移除 http 镜像，只保留 https
- 内置固定的 SHA-256 并在写入前校验
- 优先考虑把 nashorn 作为构建期依赖打包（`shadowImplementation`），彻底消除运行时下载
- 至少给下载加 readTimeout、响应码检查与 try-with-resources

---

### 🟠 6.3 缓存层缺陷（最值得优先修）

项目的"激进缓存"由两级 `LoadingCache` 构成，两者都存在失效问题。

#### 6.3.1 配置热重载后缓存不失效

**位置**：`waila/JSEngine.java:50-58`、`:149-178`；`handler/XMLConfigHandler.java:160-167`

`JSEngine.cache` 是静态 `LoadingCache`。而配置重载路径**从不清空它**：

- `JSEngine.initEngine()` 只做 `scriptSet.clear()` + 新建 engine
- `XMLConfigHandler.parseConfigFiles()` 清了 `entityPattern` / `tileEntityPattern` / `tooltipPattern` / `settingList`，未触及 `JSEngine.cache`
- `NBTHelper.NBTCache` 同样从未清空

**后果**：执行 `/oor`（服务端重载）或玩家登录时收到服务端新配置后，同一个 NBT 仍会返回**按旧配置算出的行**。

**修复**：在 `initEngine()` 中加一行 `cache.invalidateAll();`（并在必要时 `NBTHelper.NBTCache.invalidateAll();`）。

#### 6.3.2 缓存无过期策略，玩家状态相关行会长期僵死

**位置**：`waila/JSEngine.java:50-58`

```java
static LoadingCache<Integer, List<String>> cache = CacheBuilder.newBuilder()
    .maximumSize(200)
    .build(new CacheLoader<>() { ... });
```

只有 `maximumSize(200)`，**没有 `expireAfterWrite` / `expireAfterAccess`**。

而 JS 侧暴露了 `holding()` / `armor(i)` / `isInHotbar(n)` / `isInInv(n)`（`JSEngine.java:170-173`），这些函数的结果取决于**玩家的当前状态**。一旦某条 `<line>` 用了它们：

- 首次求值结果被永久写入缓存
- 只有当 200 个其它不同 NBT 把它挤出去时才会重算
- `TileEntityHandler.java:74` 的 10 tick 节流挡不住这一点——节流后仍然落进这个永不过期的缓存

**后果**：切换手持物品后，脚本里 `holding()` 驱动的显示行可能长时间不更新。

**修复**：加 `expireAfterWrite(100ms, TimeUnit.MILLISECONDS)` 或按帧/按 tick 失效；或把玩家状态相关函数的结果从缓存键中排除。

#### 6.3.3 缓存键未包含上下文

**位置**：`waila/JSEngine.java:66-68`

```java
int hashCode = n.toString().hashCode();
List<String> list = cache.getUnchecked(hashCode);
```

缓存键只有 NBT 字符串的 hash，**不含 `patternMap`（tileentity / entity / tooltip）也不含 `id`**。

- 对 TE / Entity 路径：`id` 来自 `n.getString("id")`，即 id 本身是 NBT 的一部分，键尚可认为充分
- 对 **Tooltip 路径**：`id` 来自 `Item.itemRegistry.getNameForObject(...)`（`TooltipHandler.java:43`），**不在 NBT 内**。两个不同物品若拥有完全相同的 NBT（例如都只有 `{RepairCost:0}`），会命中彼此的缓存
- 跨类型：`FMPHandler.java:20` 与 `TileEntityHandler.java:82` 共用同一个 cache，且都传 `tileEntityPattern`；若同一 NBT 先后作为 tooltip 和 tileentity 求值，会互相污染

**修复**：把缓存键改为 `patternMap 身份 + id + nbt hash` 的组合（例如 `Objects.hash(System.identityHashCode(patternMap), id, n.toString())`）。

---

### 🟠 6.4 网络协议缺乏健壮性处理

**位置**：`network/XMLConfigMessageHandler.java:43-54`

| 问题 | 说明 |
| --- | --- |
| `__START__` 未到达即 NPE | `default -> XMLConfigHandler.stringBuilder.append(string)`；若 `stringBuilder` 为 `null`（未收到 START，或上次 `__END__` 后又被追加）则 NPE |
| 无长度上限 | 接收侧对分片流无任何字节数限制，恶意服务端可持续发送导致客户端 OOM |
| 无完整性校验 | 分片丢失/乱序**不会被检测**，会产生半截 XML → `parseConfigFiles` 抛 SAXException |
| 失败静默 | 上述 SAXException 被 `XMLConfigHandler.java:229-232` 捕获后只 `printStackTrace`，游戏内表现为"所有 HUD 都不显示"，无任何用户可见提示 |

**修复**：`__START__` 时初始化、非 START 状态下丢弃数据；加累计长度上限（如 8 MB）；解析失败时 `LogHelper.error` 并 `player.addChatComponentMessage` 提示。

---

### 🟠 6.5 缺 `<setting id="displaynameTileentity">` 时静默丢弃全部方块行

**位置**：`waila/JSEngine.java:126-136`

```java
} else {
    tip = XMLConfigHandler.settingList.get("displaynameTileentity")
        .replace("DISPLAYNAME", displayname)
        .replace("RETURN", result);
}
} catch (Exception e) {
    continue;
}
```

若用户删除了 `OmniOcular.xml` 中的该 setting（或该文件解析失败），`settingList.get(...)` 返回 `null` → NPE → 被下方 `catch` 吞掉 → `continue`。

**后果**：所有 `tileentity` / `entity` 类型的行**全部静默消失**，无日志、无报错、无提示。

**修复**：在 `parseConfigFiles()` 解析完 setting 后校验该键存在，缺失时 `LogHelper.error` 并注入一个内置默认值（例如 `"DISPLAYNAME" + TAB + ALIGNRIGHT + "RETURN"`）。

---

### 🟠 6.6 `mergeConfig()` 的 XML 转义存在两个坑

**位置**：`handler/XMLConfigHandler.java:141-157`

```java
final String[][] quoteChars = { { "&", "&amp;" }, { "<", "&lt;" }, { ">", "&gt;" } };
...
Pattern tagSelectorRegex = Pattern.compile("(?<=<(init|line)[^>]*>).*?(?=</\\1>)");
Matcher tagSelectorMatcher = tagSelectorRegex.matcher(stringBuilder.toString());
while (tagSelectorMatcher.find()) {
    String quotedString = tagSelectorMatcher.group();
    for (String[] quoteCharPair : quoteChars) { ... }
    tagSelectorMatcher.appendReplacement(quotedBuffer, quotedString);
}
```

#### (a) `appendReplacement` 的 `$` 陷阱

`Matcher.appendReplacement(buf, replacement)` 中，替换串里的 `$` 是**组引用元字符**。按 JDK 实现，`$` 后跟非数字且非 `{` 会抛出 `IllegalArgumentException: Illegal group reference`。

`mergedConfig` 在 `ClientProxy.init()` 中被无条件调用，异常会直接冒到 FML 事件处理器，**启动期崩溃**。

**现状核实**：已扫描全部 36 个 XML 文件，**当前 `$` 出现次数为 0**，因此是**潜伏**问题而非现存 bug。但只要将来任何一条脚本行写了 `$`（例如 `'$' + nbt['x']`），即触发。

**修复**：`quotedString = quotedString.replace("$", "\\$").replace("\\", "\\\\")`，或改用 `matcher.appendTail` + 手工拼接。

#### (b) `<setting>` 内容不参与转义

正则只匹配 `<init>` 和 `<line>`。`<setting>` 的正文若含 `&`、`<`、`>`（例如 `if(a<b)` 或 `a && b`），会直接破坏 XML 结构。

**后果**：`parseConfigFiles()` 抛 SAXException，整个配置静默失效（见 §6.4 的"失败静默"）。

**修复**：把正则改为 `(?<=<(init|line|setting)[^>]*>).*?(?=</\1>)`。

#### (c) 顺带一提

转义在**拼接之后**整体执行，因此用户若自行写了 `&amp;` 会被二次转义成 `&amp;amp;`。且正则用 `.*?` + `[^>]*>`，对含 `</line>` 字面量的字符串无法正确处理（非贪婪匹配会在第一个 `</line>` 处停止）。

---

### 🟠 6.7 黑名单移除后不生效

**位置**：`config/Config.java:98`、`:105-120`

```java
blackEntity.addAll(Arrays.asList(blackEntityNames));   // :98  — 只加不清
```

```java
public static void preprocess() {                       // :105
    for (String blackTileEntityName : blackTileEntityNames) {
        ...
        blackTileEntity.add(...);                       // 只加不清
    }
}
```

`blackEntity` 与 `blackTileEntity` 均为静态 `Set`，在 `loadConfig()` / `preprocess()` 中**只 `add` 从不 `clear`**。

**后果**：通过配置 GUI（触发 `ConfigChangedEvent` → `loadConfig()`）或 `/oo reload`（触发 `preprocess()`）删除一条黑名单，**不会生效**，必须重启游戏。

**修复**：在两个方法开头分别加 `blackEntity.clear();` / `blackTileEntity.clear();`。

---

### 🟡 6.8 匹配语义在两条脚本路径上不一致

| 路径 | 代码 | 语义 |
| --- | --- | --- |
| XML / JS | `JSEngine.java:85` `matcher.matches()` | **全串匹配** |
| Java IScript | `PluginEngine.java:62` `matcher.find()` | **子串匹配** |

**后果**：同一条 `id="Chargepad"` 规则，写成 XML 时只匹配 id 恰为 `Chargepad` 的对象；写成 Java `IScript` 时能匹配 `Chargepad BatBox`、`MyChargepad` 等。实测配置中两种写法并存（`Chargepad` 与 `Chargepad.*` 同时存在），说明作者是**隐式依赖正则语义**绕开的。

**建议**：在 README 或配置文件注释中明确说明两条路径的差异；或统一为 `find()`（对现有 `.*` 结尾的规则无影响，但有放大误匹配的风险，需回归测试）。

---

### 🟡 6.9 潜在 NPE 与资源泄漏

| 位置 | 问题 |
| --- | --- |
| `client/command/CommandLookFor.java:49-51` | `minecraft.objectMouseOver` 可为 `null`，直接读 `.typeOfHit` 会 NPE；仅 `MISS` 分支被处理 |
| `client/command/CommandLookFor.java:85` | `/oo nbt` 下 `DataAccessorCommon.instance.getTileEntity()` 可能为 `null` |
| `handler/XMLConfigEventHandler.java:14-15` | `MinecraftServer.getServer()` 在纯客户端（连他人服务器）时为 `null`。该 handler 在双端都注册，`PlayerLoggedInEvent` 客户端也会触发，且 null 解引用在 `isDedicatedServer()` 判断**之前** |
| `waila/TileEntityHandler.java:66` | `itemStack.getItemDamage()` 未判 `itemStack == null` |
| `handler/XMLConfigHandler.java:79,111` | 用 `jarPath.endsWith(".class")` 判断运行环境 + `getResource()` 可能返回 `null`；在含空格或非标准启动器的路径下脆弱 |
| `handler/ScriptEngineHandler.java:52-55` | `FileOutputStream` / `InputStream` 无 try-with-resources，异常路径泄漏 |
| `waila/JSEngine.java:172-173` | `isInHotbar` / `isInInv` 对未注册物品名调用 `Item.itemRegistry.getObject(n)` 返回 `null` 后强转，可能 NPE（会被 `getBody` 的 catch 吞掉） |

---

### 🔵 6.10 清理项

1. **未使用的 Access Transformer**
   `META-INF/OmniOcular_at.cfg` 中 `public net.minecraft.tileentity.TileEntity field_145855_i #nameToClassMap` 已无引用——唯一使用处 `waila/TileEntityHandler.java:29` 已被注释掉。其余 3 条（`InventoryPlayer.func_146029_c`、`NBTTagCompound.field_74784_a`、`NBTTagList.field_74747_a`）均在使用。

2. **被遮蔽的静态字段**
   `handler/XMLConfigHandler.java:47` 的 `public static StringBuilder stringBuilder` 被 `mergeConfig()` 内的同名局部变量遮蔽（`:122`），容易误用。该字段实际只服务于网络接收路径，建议改名（如 `receivedConfigBuilder`）或降为私有。

3. **无任何单元测试**
   项目中最容易出错的两块纯逻辑——`NBTSerializer` 的类型分派与 `mergeConfig()` 的转义——恰好都无测试覆盖。这两处都有明确的输入输出，用 JUnit 即可覆盖，且不依赖 Minecraft 运行时。

4. **遗留文件**
   `.travis.yml` 已被 GitHub Actions 取代；`jwtpack.yml` 与 `repositories.gradle` 内容为单行。

5. **`releasePreConfigFiles()` 只在目标不存在时释放**
   `handler/XMLConfigHandler.java:110` 的 `if (!targetFile.exists())` 意味着**升级模组不会更新内置 XML**。这是有意的（避免覆盖用户修改），但会让用户拿不到新版 GTNH 规则，建议在 README 中说明，或提供"重置为内置配置"的选项。

6. **`ScriptEngineHandler.java:63` 的版本判断偏粗**
   `Double.parseDouble(System.getProperty("java.class.version")) >= 55.0` 只判断 Java 11+。而 Java 11–14 的 JDK **自带** `jdk.nashorn`（非 `org.openjdk.nashorn`），代码却会因此走下载分支，在 11–14 上做了一次不必要的网络下载。

---

## 7. 修复优先级建议

| 优先级 | 项 | 改动量 | 收益 |
| --- | --- | --- | --- |
| **P0** | §6.3.1 缓存重载不失效 | 1 行 | 消除热重载后显示错误 |
| **P0** | §6.3.2 缓存无过期 | 1–2 行 | 消除玩家状态行僵死 |
| **P1** | §6.5 缺 setting 静默全丢 | ~5 行 | 可诊断性大幅提升 |
| **P1** | §6.6(a) `$` 转义 | 1–2 行 | 消除启动期崩溃隐患 |
| **P1** | §6.7 黑名单不清理 | 2 行 | 消除"改了没反应"的困惑 |
| **P2** | §6.6(b) setting 转义 | 1 行（改正则） | 消除整配置静默失效 |
| **P2** | §6.4 网络健壮性 | ~15 行 | 防 OOM 与半截配置 |
| **P2** | §6.8 匹配语义统一 / 文档化 | 文档 or 回归测试 | 降低脚本编写门槛 |
| **P3** | §6.9 NPE 防御 | ~10 行 | 减少崩溃报告 |
| **P3** | §6.2 下载链路加固 | ~20 行 / 或改构建期打包 | 供应链安全 |
| **—** | §6.1 服务端 RCE | 需产品决策 | 安全边界，非单纯代码修改 |

**§6.1 和 §6.2 需要产品层面的决策**（是否接受服务端脚本、nashorn 是否改为构建期打包），不适合作为单纯的重构任务推进。

---

## 附录 A：文件清单（28 个 Java 文件）

| 行数 | 文件 |
| --- | --- |
| 894 | `scripts/GTNHScript.java` |
| 277 | `waila/JSEngine.java` |
| 235 | `handler/XMLConfigHandler.java` |
| 152 | `handler/ScriptEngineHandler.java` |
| 121 | `config/Config.java` |
| 117 | `client/command/CommandLookFor.java` |
| 102 | `waila/TileEntityHandler.java` |
| 97 | `util/NBTSerializer.java` |
| 84 | `waila/EntityHandler.java` |
| 68 | `waila/PluginEngine.java` |
| 67 | `util/NBTHelper.java` |
| 64 | `waila/TooltipHandler.java` |
| 56 | `network/XMLConfigMessageHandler.java` |
| 49 | `proxy/ClientProxy.java` |
| 49 | `command/CommandReloadConfig.java` |
| 46 | `util/LogHelper.java` |
| 45 | `OmniOcular.java` |
| 44 | `proxy/CommonProxy.java` |
| 39 | `IScript.java` |
| 38 | `scripts/Utility.java` |
| 32 | `mixins/MixinHUDHandlerFMP.java` |
| 31 | `client/gui/GuiFactory.java` |
| 27 | `network/XMLConfigMessage.java` |
| 23 | `waila/FMPHandler.java` |
| 23 | `client/gui/OOConfigGui.java` |
| 22 | `LateMixinPlugin.java` |
| 21 | `handler/XMLConfigEventHandler.java` |
| 14 | `proxy/ServerProxy.java` |
| 12 | `reference/Reference.java` |

## 附录 B：命令一览

| 命令 | 侧 | 权限 | 作用 |
| --- | --- | --- | --- |
| `/oo` | 客户端 | 玩家 | 在聊天栏输出准星目标的 id / metadata / 类名 / ItemStack |
| `/oo nbt` | 客户端 | 玩家 | 额外输出目标 NBT 的格式化 JSON |
| `/oo reload` | 客户端 | 玩家 | 仅重跑 `Config.preprocess()`，**重新加载黑名单，不重解析 XML** |
| `/oor` | 服务端 | 等级 3 或单人 | `mergeConfig()` 后向全部在线玩家重新下发配置 |
| `/oor update` | 服务端 | 等级 3 或单人 | 强制从上游仓库拉取配置并覆盖本地，完成后重新 merge 并下发给在线玩家（见 CONFIG_UPDATE_FLOW.md） |

注意 `/oo reload` 只刷新黑名单，不重新读取 XML 文件——XML 的重新加载只能靠 `/oor`（服务端下发）、`/oor update`（服务端拉取后下发）或重启客户端。
