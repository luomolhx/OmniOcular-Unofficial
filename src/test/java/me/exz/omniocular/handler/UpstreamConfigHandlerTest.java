package me.exz.omniocular.handler;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * 覆盖 {@link UpstreamConfigHandler} 里与网络无关的纯逻辑。
 *
 * <p>
 * 这几段决定了"会不会把垃圾写进配置目录"和"会不会误停用用户的配置"：
 * 内容校验漏了 HTML 错误页会让整份配置解析失败（所有 HUD 行消失），
 * 停用集合算错则会悄悄让某个模组的提示消失。
 */
public class UpstreamConfigHandlerTest {

    @Rule
    public final TemporaryFolder tempFolder = new TemporaryFolder();

    /** jsdelivr flat 列表的真实返回结构（截取自 2026-09 的响应）。 */
    private static final String LISTING_JSON = "{\n" + "  \"type\": \"gh\",\n"
        + "  \"name\": \"luomolhx/GTNH_OmniOcular\",\n"
        + "  \"version\": \"master\",\n"
        + "  \"files\": [\n"
        + "    {\"name\": \"/AdvancedSolarPanel.xml\", \"hash\": \"x\", \"size\": 1439},\n"
        + "    {\"name\": \"/GregTech5U-GTNH.xml\", \"hash\": \"y\", \"size\": 67776},\n"
        + "    {\"name\": \"/README.md\", \"hash\": \"z\", \"size\": 10},\n"
        + "    {\"name\": \"/sub/nested.xml\", \"hash\": \"w\", \"size\": 10}\n"
        + "  ]\n"
        + "}";

    @Test
    public void parsesXmlFileNamesFromListing() {
        assertEquals(
            new LinkedHashSet<>(Arrays.asList("AdvancedSolarPanel", "GregTech5U-GTNH")),
            UpstreamConfigHandler.parseFileNames(LISTING_JSON));
    }

    @Test
    public void listingWithoutFilesArrayYieldsNothing() {
        assertTrue(
            UpstreamConfigHandler.parseFileNames("{}")
                .isEmpty());
        assertTrue(
            UpstreamConfigHandler.parseFileNames("[\"not\", \"an\", \"object\"]")
                .isEmpty());
    }

    @Test
    public void acceptsConfigXml() {
        assertTrue(UpstreamConfigHandler.isConfigXml("<oo></oo>".getBytes(StandardCharsets.UTF_8)));
        // 仓库里的文件都以注释开头
        assertTrue(UpstreamConfigHandler.isConfigXml("<!--Version: x --><oo/>".getBytes(StandardCharsets.UTF_8)));
        // BOM + 前置空白
        byte[] body = " \n<oo/>".getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[body.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, withBom, 3, body.length);
        assertTrue(UpstreamConfigHandler.isConfigXml(withBom));
    }

    @Test
    public void rejectsNonConfigContent() {
        assertFalse(UpstreamConfigHandler.isConfigXml(null));
        assertFalse(UpstreamConfigHandler.isConfigXml(new byte[0]));
        // 纯文本 404（raw.githubusercontent 的响应体）
        assertFalse(UpstreamConfigHandler.isConfigXml("404: Not Found".getBytes(StandardCharsets.UTF_8)));
        assertFalse(UpstreamConfigHandler.isConfigXml("  \n\t ".getBytes(StandardCharsets.UTF_8)));
        // 以 < 开头的错误页也要挡住：只判首个字符的话它会被当成配置写进目录
        assertFalse(
            UpstreamConfigHandler
                .isConfigXml("<!DOCTYPE html><html><body>404</body></html>".getBytes(StandardCharsets.UTF_8)));
        assertFalse(
            UpstreamConfigHandler
                .isConfigXml("<?xml version=\"1.0\"?><error>Not Found</error>".getBytes(StandardCharsets.UTF_8)));
        // 超限
        byte[] tooBig = new byte[2 * 1024 * 1024 + 1];
        tooBig[0] = '<';
        assertFalse(UpstreamConfigHandler.isConfigXml(tooBig));
    }

    @Test
    public void disablesOnlyBundledFilesMissingUpstream() {
        Set<String> local = new LinkedHashSet<>(Arrays.asList("GregTech5U", "minecraft", "MyOwnConfig"));
        Set<String> bundled = new LinkedHashSet<>(Arrays.asList("GregTech5U", "minecraft", "Mekanism"));
        Set<String> upstream = new LinkedHashSet<>(Arrays.asList("minecraft", "GregTech5U-GTNH"));

        // 仓库有的（minecraft）保住，自带的冲突文件（GregTech5U）停用，
        // 用户自己的（MyOwnConfig）与本地没有的自带文件（Mekanism）都不动
        assertEquals(
            new LinkedHashSet<>(Arrays.asList("GregTech5U")),
            UpstreamConfigHandler.filesToDisable(local, bundled, upstream));
    }

    @Test
    public void normalizesRepoBaseUrl() {
        assertEquals("https://example.com/repo/", UpstreamConfigHandler.normalizeBaseUrl("https://example.com/repo"));
        assertEquals(
            "https://example.com/repo/",
            UpstreamConfigHandler.normalizeBaseUrl("  https://example.com/repo/  "));
        assertEquals(UpstreamConfigHandler.DEFAULT_REPO, UpstreamConfigHandler.normalizeBaseUrl(""));
        assertEquals(UpstreamConfigHandler.DEFAULT_REPO, UpstreamConfigHandler.normalizeBaseUrl(null));
    }

    /**
     * 落盘流程：覆盖仓库文件、备份改动前的状态、把仓库没有的自带文件写成占位、用户的文件不动，
     * 并且第二次执行必须判定为"无变更"（否则每次启动都会重新备份 + 重载）。
     */
    @Test
    public void writesUpstreamFilesBacksUpAndDisablesBundledConflicts() throws Exception {
        File dir = tempFolder.newFolder("OmniOcular");
        byte[] oldMinecraft = "<oo><line>old</line></oo>".getBytes(StandardCharsets.UTF_8);
        byte[] oldGregTech = "<oo><tileentity id=\"BaseMetaTileEntity\"></tileentity></oo>"
            .getBytes(StandardCharsets.UTF_8);
        write(dir, "minecraft.xml", oldMinecraft);
        write(dir, "GregTech5U.xml", oldGregTech);
        write(dir, "MyOwn.xml", "<oo><line>mine</line></oo>".getBytes(StandardCharsets.UTF_8));

        byte[] newMinecraft = "<oo><line>upstream</line></oo>".getBytes(StandardCharsets.UTF_8);
        byte[] newGregTech = "<oo><line>gtnh</line></oo>".getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> contents = new LinkedHashMap<>();
        contents.put("minecraft", newMinecraft);
        contents.put("GregTech5U-GTNH", newGregTech);
        Set<String> bundled = new LinkedHashSet<>(Arrays.asList("GregTech5U", "minecraft", "Mekanism"));

        UpstreamConfigHandler.SyncResult result = UpstreamConfigHandler.syncConfigs(dir, contents, bundled);

        assertNotNull(result);
        assertTrue(result.changed());
        assertEquals(Arrays.asList("minecraft", "GregTech5U-GTNH"), result.written);
        assertEquals(new LinkedHashSet<>(Arrays.asList("GregTech5U")), result.disabled);

        assertArrayEquals(newMinecraft, read(dir, "minecraft.xml"));
        assertArrayEquals(newGregTech, read(dir, "GregTech5U-GTNH.xml"));
        assertTrue(readAsString(dir, "GregTech5U.xml").contains("<oo></oo>"));
        // 用户自己加的配置不能被动
        assertEquals("<oo><line>mine</line></oo>", readAsString(dir, "MyOwn.xml"));

        // 备份留住的必须是"改动前"的内容
        assertArrayEquals(oldMinecraft, read(new File(dir, "oo-backup"), "minecraft.xml"));
        assertArrayEquals(oldGregTech, read(new File(dir, "oo-backup"), "GregTech5U.xml"));

        UpstreamConfigHandler.SyncResult again = UpstreamConfigHandler.syncConfigs(dir, contents, bundled);
        assertNotNull(again);
        assertFalse(again.changed());
    }

    private static void write(File dir, String name, byte[] content) throws Exception {
        FileUtils.writeByteArrayToFile(new File(dir, name), content);
    }

    private static byte[] read(File dir, String name) throws Exception {
        return FileUtils.readFileToByteArray(new File(dir, name));
    }

    private static String readAsString(File dir, String name) throws Exception {
        return new String(read(dir, name), StandardCharsets.UTF_8);
    }
}
