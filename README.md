OmniOcular - Unofficial Edition
==========
***新增特性***

1. 支持强制使用客户端脚本：客户端将主动拒绝服务端发来的脚本
2. 服务端可以选择不向客户端发送脚本
3. 支持开关各项显示内容：可以在设置中将不必要的显示关掉
4. 新增黑名单功能：将NBT数据大的方块拉黑，这样就不会卡了
5. 新增使用Java编写的脚本，复杂脚本使用Java编写就可以提升性能
6. 添加激进的缓存机制：优化FPS

## 使用方法

1. 脚本安装：将xml脚本放置在`config/OmniOcular/`目录下
2. 配置文件：`config/Omni Ocular Unofficial.cfg`，也可以在游戏内进行修改
3. Java编译脚本安装：暂无法安装外部脚本。内部脚本在配置文件中添加。`scriptClassName`中添加脚本的类全名

## 服务端安装

OmniOcular 是双端模组：**服务端装了它，才能把方块/实体的 NBT（`writeToNBT` + 实体注册名 `id`）
通过 WAILA 发给客户端**。服务端不装时，多数规则在多人游戏下匹配不上。

同一次构建会产出两个可用的 jar：

| 文件 | 装在哪 | 说明 |
| --- | --- | --- |
| `OmniOcularUnofficial-<版本>.jar` | 客户端 + 服务端 | 完整功能，双端通用 |
| `OmniOcularUnofficial-<版本>-server.jar` | **只装服务端** | 不含客户端 GUI / 脚本引擎 / mixin，体积约为完整版的六成 |
| `OmniOcularUnofficial-<版本>-dev.jar`、`-server-dev.jar`、`-sources.jar` | 都不要装 | 开发调试与源码包 |

注意事项：

1. 两个 jar 的 modid 与 version **完全相同**（FML 握手在未声明 `acceptableRemoteVersions` 时
   要求版本串一致），所以「服务端 `-server` + 客户端完整版」可以互相进入游戏。
2. **不要同时装两个**：modid 重复，FML 会报 duplicate mod。
3. **不要把 `-server.jar` 装进客户端**：它没有客户端类，装上去等于关掉显示功能。
4. 服务端前提：`Waila` 与 `NotEnoughItems`（`@Mod` 里声明了 `required-after`）。
5. 服务端 `config/OmniOcular/` 为空时**不会**把空配置下发给客户端，
   客户端的本地 XML 规则不会被清空。想强制忽略服务端下发的配置，
   打开客户端配置项 `forceUseClientXml`。

> **`1.5.4-server` tag 已废弃。** 那是一份物理删除客户端源码的独立提交，需要手工与主线同步，
> 且缺少主线后来修好的实体 `id` 写入，导致 `<entity id="...">` 类规则全部失效。
> 请使用本版及以后由同一次构建产出的 `-server.jar`。
