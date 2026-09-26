package me.exz.omniocular.handler;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.script.ScriptException;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import me.exz.omniocular.OmniOcular;
import me.exz.omniocular.reference.Reference;
import me.exz.omniocular.scripts.Utility;
import me.exz.omniocular.util.LogHelper;
import me.exz.omniocular.util.Stats;
import me.exz.omniocular.waila.JSEngine;

@SuppressWarnings("CanBeFinal")
public class XMLConfigHandler {

    public static String mergedConfig = "";
    public static StringBuilder stringBuilder;
    public static Map<Pattern, Node> entityPattern = new HashMap<>();
    public static Map<Pattern, Node> tileEntityPattern = new HashMap<>();
    public static Map<Pattern, Node> tooltipPattern = new HashMap<>();
    public static Map<String, String> settingList = new HashMap<>();
    private static File configDir;

    /** 方块/实体行的显示模板 setting id。 */
    public static final String DISPLAYNAME_SETTING_ID = "displaynameTileentity";

    /**
     * 该 setting 缺失时的内置兜底模板，等价于 OmniOcular.xml 默认值
     * （{@code "DISPLAYNAME" + TAB + ALIGNRIGHT + WHITE + "RETURN"} 求值后的结果）。
     */
    private static final String DEFAULT_DISPLAYNAME_SETTING = "DISPLAYNAME" + Utility.TAB
        + Utility.ALIGNRIGHT
        + Utility.WHITE
        + "RETURN";

    /**
     * 匹配需要转义的标签正文。setting 也要在其中：它的正文同样是 JS 文本，
     * 漏掉时在其中写 {@code a < b} 或 {@code &&} 会破坏 XML 结构并使整个配置解析失败。
     */
    private static final Pattern TAG_BODY_REGEX = Pattern.compile("(?<=<(init|line|setting)[^>]*>).*?(?=</\\1>)");

    /**
     * 需要转义的字符对。顺序不能改：先转义 {@code &}，否则后续替换引入的 {@code &} 会被二次转义。
     * 双引号与单引号不转——正文是 JS 文本而非属性值，转义反而会改变脚本看到的内容。
     */
    private static final String[][] QUOTE_CHARS = { { "&", "&amp;" }, { "<", "&lt;" }, { ">", "&gt;" } };

    /**
     * 取方块/实体行的显示模板。
     *
     * <p>
     * 保证不返回 null——调用方（{@code JSEngine.getBody}）会直接对它调 {@code .replace()}，
     * 原先在 setting 缺失时 NPE 被内层 catch 吞掉，导致所有方块/实体行静默消失。
     */
    public static String getDisplaynameTemplate() {
        String template = settingList.get(DISPLAYNAME_SETTING_ID);
        return template != null ? template : DEFAULT_DISPLAYNAME_SETTING;
    }

    public static void initConfigFiles(FMLPreInitializationEvent event) {

        configDir = new File(event.getModConfigurationDirectory(), Reference.OLD_MOD_ID);
        if (!configDir.exists()) {
            if (!configDir.mkdir()) {
                LogHelper.fatal("Can't create config folder");
            } else {
                LogHelper.info("Config folder created");
            }
        }

    }

    /**
     * 模组自带（jar 内 / 开发环境的资源目录里）的配置名，不含 {@code .xml} 后缀。
     *
     * <p>
     * 抽出来供配置更新器计算"需要停用的自带文件"——同样要区分开发环境与打包后的 jar，
     * 没必要写第二份。
     */
    static Set<String> listBundledConfigNames() throws IOException, URISyntaxException {
        final String assetConfigPath = "assets/omniocular/config/";
        final String xmlExt = ".xml";
        Set<String> configList = new HashSet<>();

        File codeSource = resolveCodeSource();
        if (codeSource == null) {
            return configList;
        }

        if (codeSource.isDirectory()) {
            // 开发环境：资源目录可能不在类的目录里（Gradle 把 classes 与 resources 分开），用 classloader 找
            URL resource = OmniOcular.class.getClassLoader()
                .getResource(assetConfigPath);
            if (resource == null) {
                return configList;
            }
            String[] xmlFiles = new File(resource.toURI()).list();
            if (xmlFiles != null) {
                for (String xmlFilename : xmlFiles) {
                    configList.add(StringUtils.removeEnd(xmlFilename, xmlExt));
                }
            }
        } else {
            // 打包后（含 RFG 的 -dev.jar）：读 jar 条目
            try (JarFile jarFile = new JarFile(codeSource)) {
                final Enumeration<JarEntry> entries = jarFile.entries(); // gives ALL entries in jar
                while (entries.hasMoreElements()) {
                    final String name = entries.nextElement()
                        .getName();
                    if (name.startsWith(assetConfigPath) && name.endsWith(xmlExt)) { // filter according to the path
                        configList.add(StringUtils.removeStart(StringUtils.removeEnd(name, xmlExt), assetConfigPath));
                    }
                }
            }
        }
        return configList;
    }

    /**
     * 自身所在的目录或 jar。
     *
     * <p>
     * 两种常见写法都不能用：
     * <ul>
     * <li>{@code URLDecoder.decode(location.getFile())}——GTNH 的版本号带 '+'（形如
     * {@code OmniOcularUnofficial-1.6.0-master+181ff848f5.jar}），URLDecoder 把 '+' 解码成空格，
     * 于是 {@code new JarFile(...)} 抛 FileNotFoundException，自带配置一个都释放不出来；
     * <li>{@code new File(location.toURI())}——FML/RFG 在开发环境把类报成 {@code jar:file:...!/}，
     * 这种 URL 的 URI 是不透明的，直接 IllegalArgumentException: URI is not hierarchical。
     * </ul>
     * {@code URL.getPath()} 只解码百分号转义，正好绕开这两点。
     */
    private static File resolveCodeSource() throws IOException {
        URL location = OmniOcular.class.getProtectionDomain()
            .getCodeSource()
            .getLocation();
        String external = location.toExternalForm();
        if (external.startsWith("jar:")) {
            String inner = external.substring(4);
            int bang = inner.indexOf("!/");
            location = new URL(bang >= 0 ? inner.substring(0, bang) : inner);
        }
        String path = location.getPath();
        return path == null || path.isEmpty() ? null : new File(path);
    }

    /** 玩家的配置目录：{@code config/OmniOcular/}。 */
    static File getConfigDir() {
        return configDir;
    }

    public static void releasePreConfigFiles() throws IOException, URISyntaxException {
        final String assetConfigPath = "assets/omniocular/config/";
        final String xmlExt = ".xml";
        Set<String> configList = listBundledConfigNames();
        Pattern p = Pattern.compile("[\\\\/:*?\"<>|]");
        Set<String> modList = Loader.instance()
            .getIndexedModList()
            .keySet();

        for (String configName : configList) {
            for (String modID : modList) {
                Matcher m = p.matcher(modID);
                if (configName.equals(m.replaceAll("")) || configName.equals("minecraft")) {
                    File targetFile = new File(configDir, configName + xmlExt);
                    if (!targetFile.exists()) {
                        InputStream resource = OmniOcular.class.getClassLoader()
                            .getResourceAsStream(assetConfigPath + configName + xmlExt);
                        FileUtils.copyInputStreamToFile(resource, targetFile);
                        LogHelper.info("Release pre-config file : " + configName);
                    }
                }
            }
        }
    }

    public static void mergeConfig() {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("<root>");
        File[] configFiles = configDir.listFiles();
        if (configFiles != null) {
            for (File configFile : configFiles) {
                if (configFile.isFile()) {
                    try {
                        List<String> lines = Files.readAllLines(configFile.toPath(), StandardCharsets.UTF_8);
                        for (String line : lines) {
                            stringBuilder.append(line);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }
        stringBuilder.append("</root>");

        mergedConfig = escapeTagBodies(stringBuilder.toString());
    }

    /**
     * 转义 &lt;init&gt; / &lt;line&gt; / &lt;setting&gt; 正文中的 XML 特殊字符，使其可作为 XML 文本被解析。
     *
     * <p>
     * {@code DocumentBuilder} 解析后 {@code getTextContent()} 会把实体还原，所以 JS 侧看到的
     * 仍是原文本——这正是本方法的意义：让脚本里能自由书写 {@code <} {@code >} {@code &}。
     *
     * <p>
     * 手动拼接而非用 {@code Matcher.appendReplacement}/{@code appendTail}：那两个方法把替换串里的
     * {@code $} 当组引用、{@code \} 当转义符，脚本中出现 {@code $}（如 {@code '$'+x}）会抛
     * IllegalArgumentException 并在启动期中断 mergeConfig。手动拼接则正文原样输出，无需转义。
     *
     * <p>
     * 注释区内的字符不转义：把注释里的 {@code -->} 转成 {@code --&gt;} 会让注释不再闭合，
     * 之后整份合并配置都解析失败、所有规则失效。自带 {@code OmniOcular.xml} 里就有被注释掉的
     * {@code <setting id="displaynameTileentity">} 块，而这个正则只看标签、不管它是不是在注释里，
     * 于是那个块的正文成了"标签正文"。
     *
     * <p>
     * 区分两类匹配：起点落在注释区里的，是"注释里的假标签"，只转义它的注释外部分；
     * 其余是真实正文，整体转义——正文里的 {@code <!--} 是 JS 文本而非注释，
     * 当成注释起始会让正文漏转义，反而破坏 XML。
     *
     * <p>
     * 可见性为 package-private 以便单元测试直接覆盖——这是本模组最容易出错的一段纯逻辑。
     */
    static String escapeTagBodies(String source) {
        Matcher matcher = TAG_BODY_REGEX.matcher(source);
        List<int[]> bodies = new ArrayList<>();
        while (matcher.find()) {
            bodies.add(new int[] { matcher.start(), matcher.end() });
        }
        List<int[]> comments = findCommentSpans(source);

        StringBuilder out = new StringBuilder(source.length() + 64);
        int lastEnd = 0;
        for (int[] body : bodies) {
            out.append(source, lastEnd, body[0]);
            if (insideAny(comments, body[0])) {
                appendEscaped(source, body[0], body[1], comments, out);
            } else {
                appendEscapedRange(source, body[0], body[1], out);
            }
            lastEnd = body[1];
        }
        out.append(source, lastEnd, source.length());
        return out.toString();
    }

    /**
     * 扫出注释区间 {@code [start, end)}。
     *
     * <p>
     * 只认有闭合的 {@code <!--...-->}：正文里出现的、后面没有 {@code -->} 的 {@code <!--}
     * （JS 文本）不是注释；把它算成注释会让该正文漏转义，解析直接失败。
     */
    private static List<int[]> findCommentSpans(String source) {
        List<int[]> spans = new ArrayList<>();
        int index = 0;
        while (index < source.length()) {
            int start = source.indexOf("<!--", index);
            if (start < 0) {
                break;
            }
            int end = source.indexOf("-->", start + 4);
            if (end < 0) {
                index = start + 4;
                continue;
            }
            spans.add(new int[] { start, end + 3 });
            index = end + 3;
        }
        return spans;
    }

    /** 转义 {@code [from, to)} 中不在注释里的部分，注释内容原样输出。 */
    private static void appendEscaped(String source, int from, int to, List<int[]> comments, StringBuilder out) {
        int pos = from;
        for (int[] comment : comments) {
            if (comment[1] <= pos) {
                continue;
            }
            if (comment[0] >= to) {
                break;
            }
            int commentStart = Math.max(comment[0], pos);
            appendEscapedRange(source, pos, commentStart, out);
            int commentEnd = Math.min(comment[1], to);
            out.append(source, commentStart, commentEnd);
            pos = commentEnd;
        }
        appendEscapedRange(source, pos, to, out);
    }

    private static void appendEscapedRange(String source, int from, int to, StringBuilder out) {
        if (from >= to) {
            return;
        }
        String body = source.substring(from, to);
        for (String[] quoteCharPair : QUOTE_CHARS) {
            body = body.replace(quoteCharPair[0], quoteCharPair[1]);
        }
        out.append(body);
    }

    private static boolean insideAny(List<int[]> ranges, int position) {
        for (int[] range : ranges) {
            if (position >= range[0] && position < range[1]) {
                return true;
            }
            if (range[0] > position) {
                break; // ranges 按起点递增
            }
        }
        return false;
    }

    public static void parseConfigFiles() {
        // 全程持锁：保证渲染线程不会在 initEngine 换引擎之后、<init> 里的辅助函数
        // 尚未定义之前就执行规则（那会让依赖 helper 的行短暂消失）。
        // 本方法由主线程（postInit）与网络线程（服务端下发配置）调用。
        synchronized (JSEngine.ENGINE_LOCK) {

            JSEngine.initEngine();
            entityPattern.clear();
            tileEntityPattern.clear();
            tooltipPattern.clear();
            settingList.clear();

            try {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                DocumentBuilder builder = factory.newDocumentBuilder();
                Document doc = builder.parse(new InputSource(new StringReader(mergedConfig)));
                doc.getDocumentElement()
                    .normalize();
                Element root = doc.getDocumentElement();
                NodeList ooList = root.getElementsByTagName("oo");
                for (int i = 0; i < ooList.getLength(); i++) {
                    NodeList entityList = ((Element) ooList.item(i)).getElementsByTagName("entity");
                    for (int j = 0; j < entityList.getLength(); j++) {
                        Node node = entityList.item(j);
                        entityPattern.put(
                            Pattern.compile(
                                node.getAttributes()
                                    .getNamedItem("id")
                                    .getTextContent()),
                            node);
                    }
                    NodeList tileEntityList = ((Element) ooList.item(i)).getElementsByTagName("tileentity");
                    for (int j = 0; j < tileEntityList.getLength(); j++) {
                        Node node = tileEntityList.item(j);
                        tileEntityPattern.put(
                            Pattern.compile(
                                node.getAttributes()
                                    .getNamedItem("id")
                                    .getTextContent()),
                            node);
                    }
                    NodeList tooltipList = ((Element) ooList.item(i)).getElementsByTagName("tooltip");
                    for (int j = 0; j < tooltipList.getLength(); j++) {
                        Node node = tooltipList.item(j);
                        tooltipPattern.put(
                            Pattern.compile(
                                node.getAttributes()
                                    .getNamedItem("id")
                                    .getTextContent()),
                            node);
                    }
                    NodeList initList = ((Element) ooList.item(i)).getElementsByTagName("init");
                    for (int j = 0; j < initList.getLength(); j++) {
                        Node node = initList.item(j);
                        long t = Stats.begin();
                        JSEngine.engine.eval(node.getTextContent());
                        Stats.endEval(t);
                    }
                    NodeList configList = ((Element) ooList.item(i)).getElementsByTagName("setting");
                    for (int j = 0; j < configList.getLength(); j++) {
                        Node node = configList.item(j);
                        String settingText = node.getTextContent();
                        String settingId = node.getAttributes()
                            .getNamedItem("id")
                            .getTextContent();
                        try {
                            long t = Stats.begin();
                            String settingResult = JSEngine.engine.eval(settingText.trim())
                                .toString();
                            Stats.endEval(t);
                            settingList.put(settingId, settingResult);
                        } catch (Exception e) {
                            // 报出具体是哪个 setting 失败。原先是 printStackTrace，
                            // 用户只会看到"某些行不见了"而不知道该去改哪里。
                            LogHelper.error("Failed to evaluate <setting id=\"" + settingId + "\">: " + e);
                        }
                    }
                }

                // 显式校验显示模板。缺失时 JSEngine 原先会对 null 调 .replace()，
                // NPE 被内层 catch 吞掉 → 所有方块/实体行静默消失且无任何日志。
                if (!settingList.containsKey(DISPLAYNAME_SETTING_ID)) {
                    LogHelper.error(
                        "Missing <setting id=\"" + DISPLAYNAME_SETTING_ID
                            + "\"> — using the built-in default. Block/entity lines would otherwise disappear silently.");
                    settingList.put(DISPLAYNAME_SETTING_ID, DEFAULT_DISPLAYNAME_SETTING);
                }
            } catch (ScriptException | ParserConfigurationException | IOException | SAXException e) {
                LogHelper.error("Failed to parse the merged OmniOcular config — NO rules will be active.");
                e.printStackTrace();
            }

        }
    }
}
