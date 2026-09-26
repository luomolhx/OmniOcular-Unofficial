package me.exz.omniocular.handler;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.io.FileUtils;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import me.exz.omniocular.OmniOcular;
import me.exz.omniocular.config.Config;
import me.exz.omniocular.util.HttpUtil;
import me.exz.omniocular.util.LogHelper;

/**
 * 从上游仓库（{@code luomolhx/GTNH_OmniOcular}）拉取 XML 配置并覆盖本地配置目录。
 *
 * <p>
 * 触发点：模组 init（客户端与服务端都跑，见 {@code CommonProxy#init}），检测到 GTNH 整合包
 * （{@code dreamcraft}，GTNH 核心模组，别的整合包不会有）时自动执行；{@code /oo update} 可强制重跑。
 * 下载、写盘都在后台线程，不阻塞游戏加载。
 *
 * <p>
 * <b>为什么"停用"要用空 &lt;oo/&gt; 占位而不是删文件</b>：{@link XMLConfigHandler#releasePreConfigFiles}
 * 只在文件不存在时才释放 jar 内置配置，删掉的话下次启动会被塞回来；而内置的 {@code GregTech5U.xml}
 * 与仓库的 {@code GregTech5U-GTNH.xml} 都定义了 {@code id="BaseMetaTileEntity"}，{@code JSEngine.getBody}
 * 又会对所有匹配的 pattern 都执行（没有 break）——两者共存会让 GT 机器的每行 tooltip 显示两遍。
 * 占位文件让"文件存在"这一条件成立，内置版本不再被释放，也不参与合并。
 */
public class UpstreamConfigHandler {

    /** 默认上游仓库（{@code luomolhx/GTNH_OmniOcular} 的 master 分支）。 */
    public static final String DEFAULT_REPO = "https://raw.githubusercontent.com/luomolhx/GTNH_OmniOcular/master/";

    /** 默认仓库的镜像：raw.githubusercontent 在国内常不可达，jsdelivr 一般可以。 */
    private static final String[] DEFAULT_MIRRORS = { "https://cdn.jsdelivr.net/gh/luomolhx/GTNH_OmniOcular@master/" };

    /** 列出仓库文件的 API。用 jsdelivr 而不是 GitHub API：后者匿名访问按 IP 限流（60 次/小时）。 */
    private static final String LISTING_URL = "https://data.jsdelivr.com/v1/packages/gh/luomolhx/GTNH_OmniOcular@master?structure=flat";

    /**
     * 列表 API 不可达时的内置清单（对应仓库 2026-09 的文件集）。
     * 仓库<b>新增</b>文件名时才需要更新这里；已有文件的内容更新不受影响。
     */
    private static final String[] FALLBACK_FILE_NAMES = { "AWWayofTime", "AdvancedSolarPanel", "Avaritia", "Botania",
        "BuildCraftCore", "CarpentersBlocks", "EMT", "ElectriCraft", "EnderIO", "Forestry", "GalacticraftCore",
        "GregTech5U-GTNH", "IC2", "LogisticsPipes", "OmniOcular", "ProjectRedstone", "Railcraft", "ReactorCraft",
        "RotaryCraft", "TConstruct", "Thaumcraft", "appliedenergistics2", "minecraft", "witchery" };

    /** 单个配置文件的大小上限，超过就不认（所有真实配置都在 100KB 以内）。 */
    private static final int MAX_FILE_BYTES = 2 * 1024 * 1024;

    private static final String XML_EXT = ".xml";

    /** 备份目录名。位于配置目录内，但 {@code mergeConfig} 只读文件，不会把它合并进去。 */
    private static final String BACKUP_DIR_NAME = "oo-backup";

    /** 单飞标志：更新在后台线程跑，重复触发（启动 + 命令）只留一个。 */
    private static final AtomicBoolean running = new AtomicBoolean();

    public static boolean isUpdating() {
        return running.get();
    }

    /** 起后台线程执行更新；本方法立即返回。{@code force} 为真时忽略开关与 GTNH 检测。 */
    public static void update(final boolean force) {
        if (!running.compareAndSet(false, true)) {
            LogHelper.info("An upstream config update is already running; request ignored.");
            return;
        }
        Thread thread = new Thread(new Runnable() {

            @Override
            public void run() {
                try {
                    updateNow(force);
                } catch (Throwable e) {
                    // 兜底：更新失败绝不能把游戏带崩
                    LogHelper.error("Upstream config update failed: " + e);
                    e.printStackTrace();
                } finally {
                    running.set(false);
                }
            }
        }, "OmniOcular-Config-Updater");
        thread.setDaemon(true);
        thread.start();
    }

    private static void updateNow(boolean force) {
        if (!force) {
            if (!Config.gtnhConfigAutoUpdate) {
                LogHelper.info("Auto config update is disabled (gtnhConfigAutoUpdate=false).");
                return;
            }
            if (!Loader.isModLoaded("dreamcraft")) {
                LogHelper.info("GTNH modpack not detected; skipping the upstream config update.");
                return;
            }
        }

        File dir = XMLConfigHandler.getConfigDir();
        if (dir == null || !dir.isDirectory()) {
            LogHelper.error("Config directory is not available; skipping the upstream config update.");
            return;
        }

        String base = normalizeBaseUrl(Config.gtnhConfigRepo);
        List<String> sources = new ArrayList<>();
        sources.add(base);
        if (base.equals(DEFAULT_REPO)) {
            sources.addAll(Arrays.asList(DEFAULT_MIRRORS));
        }
        LogHelper.info("Checking the upstream config repo: " + base);

        Set<String> repoNames = fetchFileNames(base);
        Map<String, byte[]> contents = new LinkedHashMap<>();
        for (String name : repoNames) {
            byte[] data = fetchFile(sources, name);
            if (data == null) {
                // 整体放弃而不是部分覆盖：半套配置会让部分方块直接没有提示，比保留旧配置更糟
                LogHelper.error("Download failed for " + name + XML_EXT + "; the local configs are left untouched.");
                notifyChat("omniocular.info.UpdateFailed", name + XML_EXT);
                return;
            }
            contents.put(name, data);
        }

        SyncResult result = syncConfigs(dir, contents, bundledConfigNames());
        if (result == null) {
            // 备份或写盘失败：本地配置保持原样
            notifyChat("omniocular.info.UpdateFailed", BACKUP_DIR_NAME);
            return;
        }
        if (!result.changed()) {
            LogHelper.info("OmniOcular configs are already up to date (" + repoNames.size() + " upstream files).");
            notifyChat("omniocular.info.UpdateUpToDate", repoNames.size());
            return;
        }

        // 合并后的配置是渲染用的，两端都需要；解析（跑 <init>/<setting> 的 JS）只有客户端能做：
        // 服务端的 ScriptEngineHandler.manager 没有初始化（ClientProxy.preInit 才建）。
        XMLConfigHandler.mergeConfig();
        if (FMLCommonHandler.instance()
            .getSide()
            .isClient()) {
            XMLConfigHandler.parseConfigFiles();
        }

        String summary = "updated " + result.written.size()
            + " file(s), disabled "
            + result.disabled.size()
            + " bundled file(s)";
        LogHelper.info("OmniOcular configs " + summary + ".");
        notifyChat("omniocular.info.UpdateResult", summary);
    }

    private static void notifyChat(String translationKey, Object... args) {
        OmniOcular.getProxy()
            .notifyChat(translationKey, args);
    }

    /**
     * 把下载好的内容落到配置目录：先备份，再写入变更文件、给"仓库没有的自带文件"写占位。
     *
     * <p>
     * 与网络和 Minecraft 都无关（只依赖传入的目录与内容），便于单测覆盖——
     * 这是最容易把用户配置写坏的一段。
     *
     * @return 本次改动的摘要；备份或写盘失败时返回 null，此时目录内容保持原样
     */
    static SyncResult syncConfigs(File dir, Map<String, byte[]> contents, Set<String> bundledNames) {
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : contents.entrySet()) {
            if (!contentEquals(new File(dir, entry.getKey() + XML_EXT), entry.getValue())) {
                changed.add(entry.getKey());
            }
        }
        Set<String> toDisable = new LinkedHashSet<>();
        for (String name : filesToDisable(listLocalNames(dir), bundledNames, contents.keySet())) {
            // 已经写过占位的不要每次都算成变更，否则每次启动都会备份 + 重载一遍
            if (!contentEquals(new File(dir, name + XML_EXT), stubBytes(name))) {
                toDisable.add(name);
            }
        }
        SyncResult result = new SyncResult(changed, toDisable);
        if (!result.changed()) {
            return result;
        }

        if (!backup(dir)) {
            // 备份失败就不动配置：宁可继续用旧配置，也不要一次不可回退的覆盖
            LogHelper.error("Backup failed; the local configs are left untouched.");
            return null;
        }

        try {
            for (String name : changed) {
                FileUtils.writeByteArrayToFile(new File(dir, name + XML_EXT), contents.get(name));
            }
            for (String name : toDisable) {
                FileUtils.writeByteArrayToFile(new File(dir, name + XML_EXT), stubBytes(name));
            }
        } catch (IOException e) {
            LogHelper.error("Cannot write the config files: " + e);
            e.printStackTrace();
            return null;
        }
        return result;
    }

    /** {@link #syncConfigs} 的结果：本次写入与停用的文件名（不含 {@code .xml} 后缀）。 */
    static final class SyncResult {

        final List<String> written;
        final Set<String> disabled;

        SyncResult(List<String> written, Set<String> disabled) {
            this.written = written;
            this.disabled = disabled;
        }

        boolean changed() {
            return !written.isEmpty() || !disabled.isEmpty();
        }
    }

    /**
     * 滚动备份配置目录下的所有 {@code *.xml} 到 {@code oo-backup/}。
     * 只留最近一次改动前的状态：配置目录本身是仓库的副本，历史版本在 git 里。
     */
    private static boolean backup(File dir) {
        File backupDir = new File(dir, BACKUP_DIR_NAME);
        try {
            if (backupDir.exists()) {
                FileUtils.deleteDirectory(backupDir);
            }
            if (!backupDir.mkdirs()) {
                LogHelper.error("Cannot create the backup directory " + backupDir);
                return false;
            }
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile() && file.getName()
                        .endsWith(XML_EXT)) {
                        FileUtils.copyFileToDirectory(file, backupDir);
                    }
                }
            }
            return true;
        } catch (IOException e) {
            LogHelper.error("Cannot back up the configs: " + e);
            return false;
        }
    }

    /** 依次尝试各下载源；全部失败或内容不像 XML 时返回 null。 */
    private static byte[] fetchFile(List<String> sources, String name) {
        for (String base : sources) {
            String url = base + name + XML_EXT;
            try {
                byte[] data = HttpUtil.downloadBytes(url);
                if (!isConfigXml(data)) {
                    LogHelper.warn("Ignoring " + url + ": the response does not look like an XML config.");
                    continue;
                }
                return data;
            } catch (IOException e) {
                LogHelper.warn("Cannot download " + url + ": " + e.getMessage());
            }
        }
        return null;
    }

    /**
     * 仓库文件清单。默认仓库优先走列表 API（能自动跟进仓库新增的文件），
     * 自定义仓库或列表请求失败时退回内置清单。
     */
    static Set<String> fetchFileNames(String base) {
        if (base.equals(DEFAULT_REPO)) {
            try {
                String json = new String(HttpUtil.downloadBytes(LISTING_URL), StandardCharsets.UTF_8);
                Set<String> names = parseFileNames(json);
                if (!names.isEmpty()) {
                    return names;
                }
                LogHelper.warn("The upstream listing contains no .xml file; using the built-in file list.");
            } catch (IOException | RuntimeException e) {
                LogHelper.warn("Cannot list the upstream repo (" + e + "); using the built-in file list.");
            }
        }
        return new LinkedHashSet<>(Arrays.asList(FALLBACK_FILE_NAMES));
    }

    /** 解析 jsdelivr flat 列表 JSON，取仓库根目录下的 {@code .xml} 文件名（不含后缀）。 */
    static Set<String> parseFileNames(String json) {
        Set<String> names = new LinkedHashSet<>();
        JsonElement root = new JsonParser().parse(json);
        if (root == null || !root.isJsonObject()) {
            return names;
        }
        JsonElement files = root.getAsJsonObject()
            .get("files");
        if (files == null || !files.isJsonArray()) {
            return names;
        }
        for (JsonElement file : files.getAsJsonArray()) {
            if (!file.isJsonObject()) {
                continue;
            }
            JsonElement nameElement = file.getAsJsonObject()
                .get("name");
            if (nameElement == null) {
                continue;
            }
            String name = nameElement.getAsString();
            if (name.startsWith("/")) {
                name = name.substring(1);
            }
            // 只管仓库根目录的 xml：子目录里的东西不是配置
            if (name.endsWith(XML_EXT) && !name.contains("/") && name.length() > XML_EXT.length()) {
                names.add(name.substring(0, name.length() - XML_EXT.length()));
            }
        }
        return names;
    }

    /**
     * 内容校验：非空、不超过上限、去掉 BOM 与首部空白后以 {@code <} 开头，并且含 {@code <oo} 根标签。
     *
     * <p>
     * 挡的是错误页与空响应——它们能"下载成功"，但写进配置目录只会让整份配置解析失败
     * （{@code parseConfigFiles} 抛 SAXException 后所有规则都不生效）。
     * 只判断"以 &lt; 开头"是不够的：CDN 与代理的错误页同样是 HTML。仓库里的配置都带
     * {@code <oo}，拿它当指纹。
     */
    static boolean isConfigXml(byte[] data) {
        if (data == null || data.length == 0 || data.length > MAX_FILE_BYTES) {
            return false;
        }
        int i = skipBomAndLeadingSpaces(data);
        if (i >= data.length || data[i] != '<') {
            return false;
        }
        return new String(data, i, data.length - i, StandardCharsets.UTF_8).contains("<oo");
    }

    private static int skipBomAndLeadingSpaces(byte[] data) {
        int i = 0;
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            i = 3;
        }
        while (i < data.length) {
            byte b = data[i];
            if (b != ' ' && b != '\t' && b != '\r' && b != '\n') {
                break;
            }
            i++;
        }
        return i;
    }

    /**
     * 需要停用的自带配置：本地已存在 ∧ 属于模组自带 ∧ 不在仓库清单里。
     *
     * <p>
     * 只挑"本地已存在"的：服务端不会跑 {@code releasePreConfigFiles}，配置目录里本来就没这些文件，
     * 凭空造一堆占位文件没有意义。
     */
    static Set<String> filesToDisable(Set<String> localNames, Set<String> bundledNames, Set<String> repoNames) {
        Set<String> toDisable = new LinkedHashSet<>(localNames);
        toDisable.retainAll(bundledNames);
        toDisable.removeAll(repoNames);
        return toDisable;
    }

    /** 配置目录下现有 {@code *.xml} 的名字（不含后缀）。 */
    private static Set<String> listLocalNames(File dir) {
        Set<String> names = new LinkedHashSet<>();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                String name = file.getName();
                if (file.isFile() && name.endsWith(XML_EXT)) {
                    names.add(name.substring(0, name.length() - XML_EXT.length()));
                }
            }
        }
        return names;
    }

    private static Set<String> bundledConfigNames() {
        try {
            return XMLConfigHandler.listBundledConfigNames();
        } catch (Exception e) {
            // 拿不到内置清单时按"没有自带文件"处理：结果是少停用几个文件，不会误删用户文件
            LogHelper.warn("Cannot list the bundled configs: " + e);
            return new LinkedHashSet<>();
        }
    }

    /** 停用内置配置用的占位内容：文件存在（内置版本不再被释放）但不参与合并。 */
    private static byte[] stubBytes(String name) {
        String stub = "<!-- Disabled by the OmniOcular GTNH config updater: " + name
            + XML_EXT
            + " is not part of the upstream config repo.\n"
            + "     The original file is kept in "
            + BACKUP_DIR_NAME
            + "/. Delete this file to let the bundled version come back. -->\n"
            + "<oo></oo>\n";
        return stub.getBytes(StandardCharsets.UTF_8);
    }

    /** 统一成以 {@code /} 结尾，便于直接拼接文件名；空值退回默认仓库。 */
    static String normalizeBaseUrl(String url) {
        String trimmed = url == null ? "" : url.trim();
        if (trimmed.isEmpty()) {
            return DEFAULT_REPO;
        }
        return trimmed.endsWith("/") ? trimmed : trimmed + "/";
    }

    private static boolean contentEquals(File file, byte[] data) {
        try {
            return file.isFile() && Arrays.equals(FileUtils.readFileToByteArray(file), data);
        } catch (IOException e) {
            return false;
        }
    }
}
