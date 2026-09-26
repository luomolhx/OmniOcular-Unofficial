package me.exz.omniocular.config;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraftforge.common.config.Configuration;

import cpw.mods.fml.client.event.ConfigChangedEvent;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import me.exz.omniocular.handler.UpstreamConfigHandler;
import me.exz.omniocular.reference.Reference;
import me.exz.omniocular.util.Stats;

public class Config {

    public static Configuration config;
    public static boolean enableEntityInfo = true;
    public static boolean enableFMPInfo = true;
    public static boolean enableTileEntityInfo = true;
    public static boolean enableTooltipInfo = true;
    public static boolean forceUseClientXml = false;
    public static boolean sendToClientXML = true;

    /** 打开性能埋点。关闭时埋点开销可忽略（见 Stats 的说明）。 */
    public static boolean debug = false;
    /** debug 打开时每隔多少秒向日志输出一次汇总（0 = 只输出到 /oo stats）。 */
    public static int debugStatsIntervalSeconds = 0;

    /** 检测到 GTNH 整合包时，是否从上游仓库拉取配置并覆盖本地。 */
    public static boolean gtnhConfigAutoUpdate = true;
    /** 上游 XML 配置仓库的基地址，文件名直接追加在后面。 */
    public static String gtnhConfigRepo = UpstreamConfigHandler.DEFAULT_REPO;

    private static String[] blackTileEntityNames = new String[0];
    public static String[] scriptClassName = new String[0];
    public static Set<Integer> blackTileEntity = new HashSet<>();

    public static Set<String> blackEntity = new HashSet<>();

    @SubscribeEvent
    public void onConfigChangedEvent(ConfigChangedEvent.OnConfigChangedEvent event) {
        if (event.modID.equalsIgnoreCase(Reference.MOD_ID)) {
            loadConfig();
        }
    }

    public static void initConfig(FMLPreInitializationEvent event) {

        FMLCommonHandler.instance()
            .bus()
            .register(new Config());

        config = new Configuration(new File(event.getModConfigurationDirectory(), Reference.MOD_NAME + ".cfg"));
        loadConfig();
    }

    private static void loadConfig() {

        enableEntityInfo = config.getBoolean(
            "enableEntityInfo",
            Configuration.CATEGORY_GENERAL,
            enableEntityInfo,
            "Handle entity information.");
        enableFMPInfo = config
            .getBoolean("enableFMPInfo", Configuration.CATEGORY_GENERAL, enableFMPInfo, "Handle FMP information.");
        enableTileEntityInfo = config.getBoolean(
            "enableTileEntityInfo",
            Configuration.CATEGORY_GENERAL,
            enableTileEntityInfo,
            "Handle TileEntity information.");
        enableTooltipInfo = config.getBoolean(
            "enableTooltipInfo",
            Configuration.CATEGORY_GENERAL,
            enableTooltipInfo,
            "Handle Tooltip information.");
        sendToClientXML = config.getBoolean(
            "sendToClientXML",
            Configuration.CATEGORY_GENERAL,
            sendToClientXML,
            "Use the server-side XML configuration");
        forceUseClientXml = config.getBoolean(
            "forceUseClientXml",
            Configuration.CATEGORY_GENERAL,
            forceUseClientXml,
            "Force client-side XML configuration");

        debug = config.getBoolean(
            "debug",
            Configuration.CATEGORY_GENERAL,
            debug,
            "Enable performance instrumentation. Counters are near-free while false. See /oo stats.");
        debugStatsIntervalSeconds = config.getInt(
            "debugStatsIntervalSeconds",
            Configuration.CATEGORY_GENERAL,
            debugStatsIntervalSeconds,
            0,
            3600,
            "While debug is on, log a stats summary every N seconds (0 = only via /oo stats).");

        // 同步到埋点层。放在 loadConfig 里，使配置 GUI 改动后即时生效，无需重启。
        Stats.enabled = debug;
        Stats.reportIntervalSeconds = debugStatsIntervalSeconds;

        gtnhConfigAutoUpdate = config.getBoolean(
            "gtnhConfigAutoUpdate",
            Configuration.CATEGORY_GENERAL,
            gtnhConfigAutoUpdate,
            "Detected GTNH modpack: download the latest XML configs from the upstream repo and overwrite the local ones."
                + " Disable to keep your local configs. See also /oo update.");
        gtnhConfigRepo = config.getString(
            "gtnhConfigRepo",
            Configuration.CATEGORY_GENERAL,
            gtnhConfigRepo,
            "Base URL of the upstream XML config repo; the file names are appended to it directly.");

        blackTileEntityNames = config.getStringList(
            "blackTileEntityNames",
            Configuration.CATEGORY_GENERAL,
            blackTileEntityNames,
            "Black TileEntity Names list. as: minecraft:furnace@0");

        String[] blackEntityNames = config.getStringList(
            "blackEntityNames",
            Configuration.CATEGORY_GENERAL,
            new String[] { "net.minecraft.entity.player.EntityPlayer" },
            "Black Entity Names list. as: net.minecraft.entity.player.EntityPlayer");

        scriptClassName = config.getStringList(
            "scriptClassName",
            Configuration.CATEGORY_GENERAL,
            new String[] {},
            "The class name of the script written in java. as: me.exz.omniocular.scripts.GTNHScript");

        // 必须先清空：loadConfig 会被配置 GUI 的 ConfigChangedEvent 反复调用，
        // 原实现只 add 不 clear，导致从配置里删掉的条目要重启才生效。
        blackEntity.clear();
        blackEntity.addAll(Arrays.asList(blackEntityNames));

        if (config.hasChanged()) {
            config.save();
        }
    }

    public static void preprocess() {
        // 同上：/oo reload 会重跑本方法，只 add 不 clear 的话删除的条目不会生效
        blackTileEntity.clear();
        for (String blackTileEntityName : blackTileEntityNames) {
            String[] ss = blackTileEntityName.split("@", 2);
            Block block = Block.getBlockFromName(ss[0]);
            if (block != null) {
                if (ss.length == 2) {
                    try {
                        blackTileEntity.add(Block.getIdFromBlock(block) << 16 | Integer.parseInt(ss[1]));
                    } catch (NumberFormatException ignored) {}
                } else {
                    blackTileEntity.add(Block.getIdFromBlock(block) << 16);
                }

            }
        }
    }
}
