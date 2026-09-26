package me.exz.omniocular.handler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.StringReader;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Test;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

/**
 * 覆盖 {@link XMLConfigHandler#escapeTagBodies}。
 *
 * <p>
 * 这是本模组最容易出错的一段纯逻辑：转义不足会让整个配置解析失败（所有 HUD 行消失），
 * 转义过度则会改变脚本的可见文本。历史 bug：曾用
 * {@code Matcher.appendReplacement} 直接传正文，脚本里出现 {@code $} 就抛
 * IllegalArgumentException 并在启动期中断 mergeConfig。
 */
public class XMLConfigHandlerTest {

    @Test
    public void escapesSpecialCharsInLineBody() {
        String in = "<oo><line displayname=\"x\">a < b && c > d</line></oo>";
        assertEquals(
            "<oo><line displayname=\"x\">a &lt; b &amp;&amp; c &gt; d</line></oo>",
            XMLConfigHandler.escapeTagBodies(in));
    }

    @Test
    public void escapesInitBody() {
        String in = "<oo><init>if (a < b) { c = 1 && 2 }</init></oo>";
        assertEquals(
            "<oo><init>if (a &lt; b) { c = 1 &amp;&amp; 2 }</init></oo>",
            XMLConfigHandler.escapeTagBodies(in));
    }

    @Test
    public void escapesSettingBody() {
        // setting 曾被漏掉，导致在其中写 a < b 会破坏 XML 结构
        String in = "<oo><setting id=\"s\">a < b</setting></oo>";
        assertEquals("<oo><setting id=\"s\">a &lt; b</setting></oo>", XMLConfigHandler.escapeTagBodies(in));
    }

    /**
     * 回归测试：{@code $} 必须原样保留。
     *
     * <p>
     * 旧实现走 {@code appendReplacement}，而该方法把替换串里的 {@code $} 当组引用，
     * 遇到 {@code $} 后跟非数字会抛 {@code IllegalArgumentException: Illegal group reference}，
     * 使 mergeConfig 在启动期中断。
     */
    @Test
    public void preservesDollarSigns() {
        String in = "<oo><line>return '$' + nbt['a'] + '$1' + '${x}'</line></oo>";
        assertEquals(in, XMLConfigHandler.escapeTagBodies(in));
    }

    /** {@code \} 同样不能被当成转义符。 */
    @Test
    public void preservesBackslashes() {
        String in = "<oo><line>return 'a\\\\b'</line></oo>";
        assertEquals(in, XMLConfigHandler.escapeTagBodies(in));
    }

    /** 标签外的内容（含属性）不参与转义——属性由写配置的人自己保证合法。 */
    @Test
    public void leavesTextOutsideBodiesUntouched() {
        String in = "<oo><tileentity id=\"BaseMetaTileEntity\"><line>x</line></tileentity></oo>";
        assertEquals(in, XMLConfigHandler.escapeTagBodies(in));
    }

    /** 多个标签正文各自独立转义，不应互相串扰。 */
    @Test
    public void handlesMultipleBodies() {
        String in = "<oo><line>a < b</line><line>c > d</line></oo>";
        assertEquals("<oo><line>a &lt; b</line><line>c &gt; d</line></oo>", XMLConfigHandler.escapeTagBodies(in));
    }

    /**
     * 端到端：转义后的文本必须能被 XML 解析器接受，且 {@code getTextContent()} 还原出原脚本。
     *
     * <p>
     * 这条是真正重要的断言——转义的全部意义就是"给 XML 层看转义后的、给 JS 层看原始的"。
     */
    @Test
    public void escapedTextRoundTripsThroughXmlParser() throws Exception {
        String script = "if (a < b && c > d) { return \"x\" }";
        String xml = "<root><oo><line>" + script + "</line></oo></root>";

        Document doc = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(new InputSource(new StringReader(XMLConfigHandler.escapeTagBodies(xml))));

        String recovered = doc.getElementsByTagName("line")
            .item(0)
            .getTextContent();
        assertEquals(script, recovered);
    }

    /** 空正文与没有可转义标签时不应抛异常。 */
    @Test
    public void toleratesEmptyAndTaglessInput() {
        assertEquals("", XMLConfigHandler.escapeTagBodies(""));
        assertEquals("<root></root>", XMLConfigHandler.escapeTagBodies("<root></root>"));
        assertEquals("<line></line>", XMLConfigHandler.escapeTagBodies("<line></line>"));
    }

    /**
     * setting 缺失时 {@code getDisplaynameTemplate()} 必须返回可用的内置兜底而非 null。
     *
     * <p>
     * 回归测试：原实现在此处返回 null 并被 JSEngine 的 catch 吞掉，表现为所有方块/实体行
     * 静默消失且无任何日志。settingList 此时为空（未解析过配置），正好覆盖该路径。
     */
    @Test
    public void displaynameTemplateFallsBackWhenSettingMissing() {
        String t = XMLConfigHandler.getDisplaynameTemplate();
        assertNotNull(t);
        assertFalse(t, t.isEmpty());
        assertTrue(t, t.contains("DISPLAYNAME"));
        assertTrue(t, t.contains("RETURN"));
    }

    /**
     * 注释区里的内容不转义。
     *
     * <p>
     * 回归测试：转义正则只看标签、不看是否在注释里，被注释掉的 {@code <setting>} 块会被当成
     * 真实标签正文，其中的 {@code -->} 被转成 {@code --&gt;}，注释不再闭合 → 整份合并配置
     * 解析失败、所有规则失效。
     */
    @Test
    public void doesNotEscapeInsideComments() throws Exception {
        String xml = "<root><oo><setting id=\"displaynameTileentity\">\"DISPLAYNAME\" + TAB</setting>"
            + "<!--<setting id=\"displaynameTileentity\">-->"
            + "<!--YELLOW + \"DISPLAYNAME\" + \" : \" + WHITE + \"RETURN\"-->"
            + "<!--</setting>--></oo></root>";

        String escaped = XMLConfigHandler.escapeTagBodies(xml);

        assertFalse(escaped, escaped.contains("--&gt;"));
        assertTrue(escaped, escaped.contains("<!--</setting>-->"));

        Document doc = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(new InputSource(new StringReader(escaped)));
        // 只有真实的那一个 setting 存在；注释里的不算
        assertEquals(
            1,
            doc.getElementsByTagName("setting")
                .getLength());
    }

    /**
     * 标签正文里的 {@code <!--} 是 JS 文本，必须照常转义——把它当注释起始会让正文漏转义，
     * 反而破坏 XML。注释判定必须先排除正文区间。
     */
    @Test
    public void escapesCommentMarkersInsideScriptBodies() {
        String escaped = XMLConfigHandler.escapeTagBodies("<oo><line>if (a <!-- b && c > d) x</line></oo>");
        assertFalse(escaped, escaped.contains("<!--"));
        assertTrue(escaped, escaped.contains("&lt;!--"));
        assertTrue(escaped, escaped.contains("&amp;&amp;"));
    }

    /**
     * 直接用 jar 里那份自带 OmniOcular.xml（含被注释掉的 setting 块）跑一遍真实链路：
     * 逐行拼接（同 {@code mergeConfig}）→ 转义 → 解析。这是上面那条回归在真实文件上的投影，
     * 文件以后被改动、重新引入同类注释时也会立刻失败。
     */
    @Test
    public void bundledOmniOcularXmlSurvivesEscaping() throws Exception {
        String content;
        try (java.io.InputStream in = getClass().getResourceAsStream("/assets/omniocular/config/OmniOcular.xml")) {
            assertNotNull("打包资源缺失", in);
            content = new String(readAll(in), java.nio.charset.StandardCharsets.UTF_8);
        }

        // mergeConfig 是把每个文件的每一行不加分隔地拼起来，这里照做
        StringBuilder merged = new StringBuilder("<root>");
        for (String line : content.split("\n", -1)) {
            merged.append(line.replace("\r", ""));
        }
        merged.append("</root>");

        Document doc = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(new InputSource(new StringReader(XMLConfigHandler.escapeTagBodies(merged.toString()))));
        assertTrue(
            doc.getElementsByTagName("setting")
                .getLength() > 0);
    }

    private static byte[] readAll(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int len;
        while ((len = in.read(buffer)) != -1) {
            bos.write(buffer, 0, len);
        }
        return bos.toByteArray();
    }
}
